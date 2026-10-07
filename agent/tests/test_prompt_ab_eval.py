"""Unit tests only: FakeModel is scripted and never counts as LLM validation."""
import copy
import contextlib
import json
import sys
from types import SimpleNamespace as NS

import pytest

from prompt_ab_eval_support import (AuditGuard, Budget, StopRun, ObservedCompletions, ROOT,
    argument_errors, build_live_client, check_endpoint, compact, digest, execute_checkpoint, forbidden,
    preflight, raw_final_errors, schedule, schema_errors, scrub, size_of, wrapped)
from prompt_ab_eval_report import checked_review, finalize, ratio, review_template, summarize, usage_sum, write_json, write_report


@pytest.fixture(scope="module")
def env():
    try:
        return preflight()
    except ValueError as exc:
        if "Non-prompt production code changed since this baseline" in str(exc):
            pytest.skip("Historical prompt A/B runtime is frozen; use the new boundary tests for current code")
        raise


def payload():
    return json.dumps({"answerText": "测试回答", "structuredAnswer": {"summary": "测试", "steps": [],
        "cautions": [], "basisNote": "测试"}, "insufficientContext": False}, ensure_ascii=False)


def response(content=None, calls=None, usage=True, finish="stop"):
    return NS(model="fake-model", usage=NS(prompt_tokens=100, completion_tokens=20,
        total_tokens=120) if usage else None, choices=[NS(finish_reason=finish,
        message=NS(content=content, tool_calls=calls or [], reasoning_content="private-thinking-never-log"))])


def call(name="manual_search", args=None, i=1):
    return NS(id=f"call-{i}", function=NS(name=name, arguments=json.dumps(args or {"query": "童锁"})))


class FakeModel:
    def __init__(self, responses):
        self.responses, self.requests = iter(responses), []

    def create(self, **kwargs):
        self.requests.append(copy.deepcopy(kwargs))
        r = next(self.responses)
        if isinstance(r, Exception):
            raise r
        return r


def checkpoint(env, identity):
    return next(p for c in env[2]["cases"] for p in c["checkpoints"] if p["checkpoint_id"] == identity)


def run(env, cp_id, responses, variant="candidate", budget=None):
    cp = checkpoint(env, cp_id)
    model = FakeModel(responses)
    trace = execute_checkpoint(env[3], cp, cp_id[:3], variant, 1, env[0 if variant == "baseline" else 1],
                               model, budget or Budget())
    return trace, model


def test_preflight_and_baseline(env):
    a, b, fixture, rt = env
    assert len(fixture["cases"]) == 10
    assert len(schedule(fixture)) == 26
    assert len(schedule(fixture, 3)) == 78
    assert a["non_prompt_ast_sha256"] == b["non_prompt_ast_sha256"]
    reduction = 1 - size_of(b)["fixed"]["estimated_tokens"] / size_of(a)["fixed"]["estimated_tokens"]
    assert .15 <= reduction <= .25
    assert "app.config" not in sys.modules and "app.tools.registry" not in sys.modules


@pytest.mark.parametrize("anchor", [
    "本轮不调用工具，直接给停用", "当前说明书由工具上下文定位，无需生成ID",
    "未实际调用写工具，不得声称已发起或完成", "历史即使是纯文本，最终仍须合法JSON"])
def test_revision_two_retains_targeted_prompt_guards(env, anchor):
    # Presence checks only, NOT proof that the live model follows the revision.
    assert anchor in env[1]["system_prompt"]


@pytest.mark.parametrize("identity", ["P01a", "P02a", "P03a", "P04a", "P05a", "P06a", "P07a", "P07b", "P07c", "P08a", "P08b", "P09a", "P10a"])
def test_checkpoint_has_scoring_and_visible_input(env, identity):
    cp = checkpoint(env, identity)
    assert cp["request"]["query"] and cp["semantic_rubric"]
    assert cp["request"]["query"] not in env[1]["system_prompt"]
    env[3].schemas.AgentRequest.model_validate(cp["request"])
    for r in cp["tool_results"].values():
        env[3].schemas.ToolResult.model_validate(r)


@pytest.mark.parametrize("event,args", [
    ("open", ("fictional/.env", "r", 0)), ("open", ("fictional/.env.backup", "r", 0)),
    ("socket.connect", (None, ("127.0.0.1", 443))),
    ("socket.getaddrinfo", ("example.com", 443)), ("subprocess.Popen", ("dummy",)),
    ("socket.bind", (None, ("127.0.0.1", 8001))), ("os.system", ("dummy",))])
def test_security_audit_denies_side_effects(event, args):
    # Synthetic events only: no file open/real network/subprocess attempted.
    with pytest.raises(PermissionError):
        AuditGuard()(event, args)


def test_settings_constructor_always_forbidden():
    with pytest.raises(PermissionError):
        forbidden()


def test_local_config_parses_only_model_fields_without_interpolation(capsys):
    from prompt_eval_config import parse_model_lines
    from io import StringIO
    config = StringIO("IGNORED_SECRET=do-not-use\nDEEPSEEK_API_KEY='fictional-key'\n"
        'export DEEPSEEK_BASE_URL="https://example.com/v1" # comment\n'
        "deepseek_model=fictional-model\nBROKEN_UNRELATED='unterminated\n")
    assert parse_model_lines(config) == ("fictional-key", "https://example.com/v1", "fictional-model")
    assert capsys.readouterr() == ("", "")


@pytest.mark.parametrize("value", ["", "'not-closed", '"${OTHER_SECRET}"', '"line\\nline"'])
def test_local_config_invalid_values_never_echo_contents(value, capsys):
    from prompt_eval_config import parse_model_lines
    from io import StringIO
    with pytest.raises(ValueError) as err:
        parse_model_lines(StringIO("DEEPSEEK_API_KEY=" + value + "\n"))
    assert not value or value not in str(err.value)
    assert capsys.readouterr() == ("", "")


def test_local_config_requires_all_three_fields():
    from prompt_eval_config import parse_model_lines
    with pytest.raises(ValueError, match="DEEPSEEK_BASE_URL, DEEPSEEK_MODEL"):
        parse_model_lines(["DEEPSEEK_API_KEY=fictional-key\n"])


def test_local_config_guard_exact_read_once_only():
    guard = AuditGuard()
    target = str(ROOT / ".env")
    with guard.local_model_config():
        for path, mode, flags in [(target, "w", 1), (target + ".backup", "r", 0), (str(ROOT / "other/.env"), "r", 0)]:
            with pytest.raises(PermissionError):
                guard("open", (path, mode, flags))
        guard("open", (target, "r", 0))  # synthetic only, does not open a file
        with pytest.raises(PermissionError):
            guard("open", (target, "r", 0))
    with pytest.raises(PermissionError):
        guard("open", (target, "r", 0))
    with pytest.raises(PermissionError):
        with guard.local_model_config():
            pass


def test_local_config_guard_revokes_unused_scope_on_error():
    guard = AuditGuard()
    with pytest.raises(RuntimeError):
        with guard.local_model_config():
            raise RuntimeError("test")
    with pytest.raises(PermissionError):
        guard("open", (str(ROOT / ".env"), "r", 0))


def test_local_config_never_loads_during_network():
    guard = AuditGuard()
    with guard.network(), pytest.raises(PermissionError):
        with guard.local_model_config():
            pass


@pytest.mark.parametrize("url", ["http://example.com/v1/chat/completions", "https://elsewhere.test/v1/chat/completions",
    "https://example.com/v1/models", "https://example.com/v1/chat/completions?secret=x", "https://a:b@example.com/v1/chat/completions"])
def test_http_allowlist_rejects(url):
    with pytest.raises((ValueError, PermissionError)):
        check_endpoint(url, "https://example.com/v1")


def test_http_allowlist_accepts_only_configured_endpoint():
    check_endpoint("https://example.com/v1/chat/completions", "https://example.com/v1/")


def test_budget_exact_call_limit():
    budget = Budget(1, 1000)
    budget.before()
    with pytest.raises(StopRun):
        budget.before()
    assert budget.calls == 1


def test_budget_token_overshoot_stops_next_request():
    budget = Budget(5, 100)
    budget.before()
    budget.usage({"prompt_tokens": 90, "completion_tokens": 30})
    with pytest.raises(StopRun):
        budget.before()
    assert budget.tokens == 120 and budget.calls == 1


@pytest.mark.parametrize("status", [401, 403, 400, 404, 422])
def test_fatal_error_stops(status):
    budget = Budget()
    budget.error(status)
    with pytest.raises(StopRun):
        budget.before()


def test_three_consecutive_errors_stop():
    budget = Budget()
    for _ in range(3):
        budget.error(500)
    assert budget.stop_reason == "consecutive_errors"


def test_early_answer_no_extra_call(env):
    trace, fake = run(env, "P01a", [response(payload())])
    assert len(fake.requests) == 1 and trace["score"]["automatic_pass"]
    assert "private-thinking" not in compact(trace)
    assert trace["model_calls"][0]["usage"]["prompt_tokens"] == 100


def test_all_four_tool_results_reach_fifth_summary(env):
    trace, fake = run(env, "P02a", [*[response(calls=[call(i=i)]) for i in range(4)], response(payload())])
    assert [r["tool_choice"] for r in fake.requests] == ["auto"] * 4 + ["none"]
    assert len(trace["tool_calls"]) == 4
    assert len([m for m in fake.requests[-1]["messages"] if m["role"] == "tool"]) == 4
    assert fake.requests[-1]["messages"][-1]["content"] == env[1]["summary_prompt"]


def test_fifth_tool_request_recorded_but_never_executed(env):
    trace, fake = run(env, "P02a", [response(calls=[call(i=i)]) for i in range(5)])
    assert len(trace["tool_calls"]) == 4
    assert trace["status"] == "application_error" and not trace["score"]["tool_decision"]


def test_baseline_injection_does_not_mutate_production(env):
    trace, fake = run(env, "P01a", [response(payload())], "baseline")
    assert fake.requests[0]["messages"][0]["content"] == env[0]["system_prompt"].strip()
    assert fake.requests[0]["tools"] == wrapped(env[0])
    assert fake.requests[0]["max_tokens"] == 2048


def test_both_variants_differ_only_in_prompt(env):
    _, a = run(env, "P01a", [response(payload())], "baseline")
    _, b = run(env, "P01a", [response(payload())], "candidate")
    ar, br = a.requests[0], b.requests[0]
    ar.pop("tools"); br.pop("tools")
    ar["messages"][0]["content"] = br["messages"][0]["content"] = "normalized"
    assert ar == br


@pytest.mark.parametrize("kind", ["missing_usage", "empty_choices", "truncated", "exception"])
def test_invalid_model_response_not_passed(env, kind):
    r = response(payload(), usage=kind != "missing_usage", finish="length" if kind == "truncated" else "stop")
    if kind == "empty_choices":
        r.choices = []
    if kind == "exception":
        r = RuntimeError("Bearer sensitive-key")
    trace, fake = run(env, "P01a", [r])
    assert trace["status"] == "error" and not trace["score"]["automatic_pass"]
    assert "sensitive-key" not in compact(trace)


def test_raw_format_not_repaired_for_scoring(env):
    trace, _ = run(env, "P01a", [response("```json\n" + payload() + "\n```")])
    assert trace["parsed_response"]["answerText"] == "测试回答"
    assert not trace["score"]["raw_json_valid"]


def test_json_type_checks():
    assert not raw_final_errors(payload())
    bad = json.loads(payload()); bad["insufficientContext"] = "false"
    assert raw_final_errors(json.dumps(bad))
    assert schema_errors(True, {"type": "number"})
    assert schema_errors(None, {"type": "string"})


def test_fault_parameters_and_id_source(env):
    cp = checkpoint(env, "P04a")
    args = {"brand": "", "model": "TEST-W100", "fault_code": "", "symptoms": "排水慢，没有焦味"}
    assert not argument_errors("diagnose_fault", args, cp, env[1]["tool_schemas"])
    args["manual_id"] = "TEST-W100"
    assert "manual_id:unknown_id" in argument_errors("diagnose_fault", args, cp, env[1]["tool_schemas"])


def test_cancel_write_is_visible_violation(env):
    trace, _ = run(env, "P09a", [response(calls=[call("create_repair_order", {"issue_desc": "不排水", "confirmed": True})]), response(payload())])
    assert trace["score"]["critical"]["unauthorized_repair"] == 1
    assert not trace["score"]["automatic_pass"]


def test_write_timeout_is_not_retried_by_runner(env):
    trace, fake = run(env, "P10a", [response(calls=[call("create_repair_order", {"issue_desc": "不排水", "confirmed": True})]), response(payload())])
    assert len(trace["tool_calls"]) == 1
    assert "状态" not in trace["raw_final"]  # fake answer isn't semantically certified
    assert "timeout_after_dispatch" in compact(fake.requests[-1]["messages"])
    review = review_template([trace], env[2])
    assert checked_review(trace, review, env[2])["status"] == "pending"


def test_duplicate_write_failure(env):
    c = call("create_repair_order", {"issue_desc": "不排水", "confirmed": True})
    trace, _ = run(env, "P10a", [response(calls=[c]), response(calls=[c]), response(payload())])
    assert trace["score"]["critical"]["duplicate_write"] == 1


def test_scrub_and_usage_arithmetic():
    assert scrub({"reasoning_content": "secret", "text": "Bearer x key=KEY", "phone": "13800138000"}, ("KEY",)) == {
        "text": "Bearer [REDACTED] key=[REDACTED]", "phone": "[PHONE]"}
    u = {"prompt_tokens": 100, "completion_tokens": 20, "prompt_cache_hit_tokens": 80,
         "completion_tokens_details": {"reasoning_tokens": 10}}
    assert usage_sum([{"model_calls": [{"usage": u}]}])["total_tokens"] == 120
    assert usage_sum([{"model_calls": [{"usage": None}]}])["total_tokens"] is None
    assert ratio(0, 0)["rate"] is None


def report_input(env):
    traces = [run(env, "P01a", [response(payload())], v)[0] for v in ("baseline", "candidate")]
    fixture = {"cases": [env[2]["cases"][0]]}
    manifest = {"fixture_hash": digest(fixture), "snapshot_hashes": {v: digest(env[i]) for i, v in enumerate(("baseline", "candidate"))},
                "planned": [{k: t[k] for k in ("trace_id", "case_id", "checkpoint_id", "variant", "repeat")} for t in traces]}
    return manifest, traces, review_template(traces, fixture), fixture


def test_pending_review_cannot_accept(env):
    manifest, traces, review, fixture = report_input(env)
    m = summarize(manifest, traces, review, fixture)
    assert m["acceptance_status"] == "incomplete"
    assert m["groups"]["candidate"]["semantic_pending"] == 1


def test_review_requires_evidence(env):
    manifest, traces, review, fixture = report_input(env)
    review[traces[0]["trace_id"]]["items"][0]["status"] = "pass"
    with pytest.raises(ValueError, match="evidence"):
        summarize(manifest, traces, review, fixture)


def test_failed_checkpoint_does_not_hide_unexercised_semantic_item(env):
    trace, _ = run(env, "P02a", [response(payload())])
    review = review_template([trace], env[2])
    review[trace["trace_id"]]["items"][0].update(
        status="fail", evidence="scripted missing retrieval", reason="unit test", reviewer="test")
    result = checked_review(trace, review, env[2])
    assert result["status"] == "fail"
    assert result["pending_items"] == 1


def test_complete_review_and_smaller_actual_input_can_accept(env):
    manifest, traces, review, fixture = report_input(env)
    traces[1]["model_calls"][0]["usage"]["prompt_tokens"] = 80
    review = review_template(traces, fixture)
    for entry in review.values():
        for item in entry["items"]:
            item.update(status="pass", evidence="unit-test-only", reason="unit-test-only", reviewer="test")
    assert summarize(manifest, traces, review, fixture)["acceptance_status"] == "passed"
    # Critical semantic failure must override apparent token savings.
    review[traces[1]["trace_id"]]["items"][0]["status"] = "fail"
    assert summarize(manifest, traces, review, fixture)["acceptance_status"] == "failed"


def test_missing_planned_runs_not_dropped(env):
    manifest, traces, review, fixture = report_input(env)
    m = summarize(manifest, traces[:1], review, fixture)
    assert not m["all_planned_completed"]
    assert m["groups"]["candidate"]["execution_completion"] == ratio(0, 1)
    assert m["acceptance_status"] != "passed"


def test_changed_prompt_and_case_hashes_rejected(env):
    manifest, traces, review, fixture = report_input(env)
    traces[0]["prompt_hash"] = "different"
    with pytest.raises(ValueError, match="Prompt"):
        summarize(manifest, traces, review, fixture)


@pytest.mark.parametrize("status", [200, 302, 401, 403, 429, 500])
def test_sdk_transport_no_network_redirect_or_automatic_retry(monkeypatch, status):
    import httpx
    seen, options = [], []

    def handle(request):
        seen.append(request)
        return httpx.Response(status, headers={"Location": "https://unapproved.test/steal"}, json={
            "id": "fake", "object": "chat.completion", "created": 1, "model": "fake-model",
            "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": payload()}}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}})

    def transport(**kwargs):
        options.append(kwargs)
        return httpx.MockTransport(handle)

    monkeypatch.setattr(httpx, "HTTPTransport", transport)
    guard = NS(configure=lambda url: None, network=contextlib.nullcontext)
    client = build_live_client("fake-key-not-a-secret", "https://example.com/v1", 2, guard)
    try:
        if status == 200:
            result = client.chat.completions.create(model="fake-model", messages=[{"role": "user", "content": "test"}])
            assert result.usage.prompt_tokens == 100
        else:
            with pytest.raises(Exception):
                client.chat.completions.create(model="fake-model", messages=[{"role": "user", "content": "test"}])
        assert len(seen) == 1
        assert options == [{"retries": 0, "trust_env": False}]
        assert client.max_retries == 0
    finally:
        client.close()


def test_report_files_and_finalize_do_not_call_model(env, tmp_path):
    manifest, traces, review, fixture = report_input(env)
    manifest.update(run_id="unit-test-not-live", live_status="completed",
                    trace_hashes={t["trace_id"]: digest(t) for t in traces})
    write_json(tmp_path / "manifest.json", manifest)
    write_json(tmp_path / "cases.json", fixture)
    write_json(tmp_path / "review.json", review)
    (tmp_path / "traces.jsonl").write_text("\n".join(compact(t) for t in traces), encoding="utf-8")
    result = finalize(tmp_path)
    assert result["acceptance_status"] == "incomplete"
    assert (tmp_path / "report.md").is_file() and (tmp_path / "metrics.json").is_file()
    assert "测试回答" in (tmp_path / "report.md").read_text(encoding="utf-8")
    traces[0]["raw_final"] = "tampered"
    (tmp_path / "traces.jsonl").write_text("\n".join(compact(t) for t in traces), encoding="utf-8")
    with pytest.raises(ValueError, match="hashes"):
        finalize(tmp_path)


def test_socket_guard_limits_model_addresses():
    guard = AuditGuard()
    guard.host, guard.port, guard.addresses = "example.com", 443, {"192.0.2.1"}
    with guard.network():
        guard("socket.getaddrinfo", ("example.com", 443))
        guard("socket.connect", (None, ("192.0.2.1", 443)))
        with pytest.raises(PermissionError):
            guard("socket.connect", (None, ("192.0.2.2", 443)))
        with pytest.raises(PermissionError):
            guard("socket.getaddrinfo", ("unapproved.test", 443))
    assert not guard.active


def test_infrastructure_failure_is_not_labeled_prompt_regression(env):
    manifest, traces, review, fixture = report_input(env)
    failed, _ = run(env, "P01a", [RuntimeError("network unavailable")])
    traces[1] = failed
    review = review_template(traces, fixture)
    m = summarize(manifest, traces, review, fixture)
    assert m["execution_errors"] == [failed["trace_id"]]
    assert not m["regressions"] and m["acceptance_status"] == "incomplete"


def test_no_actual_saving_fails_cost_gate_after_review(env):
    manifest, traces, review, fixture = report_input(env)
    for entry in review.values():
        for item in entry["items"]:
            item.update(status="pass", evidence="unit-test-only", reason="unit-test-only", reviewer="test")
    m = summarize(manifest, traces, review, fixture)
    assert m["cost_gate_failed"] and m["acceptance_status"] == "failed"
