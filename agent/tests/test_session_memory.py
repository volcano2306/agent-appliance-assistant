import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.config import Settings
from app.schemas import AgentRequest, MemorySnapshot, MemorySummary, CompleteTurn
from session_memory_guard import audit_guard
from types import SimpleNamespace
from app.memory import SummaryService, TokenCounter, ContextBudgetError, history_messages
from app.schemas import SummaryRequest, SummaryResult

FIXTURE = Path(__file__).parent / "fixtures/session_memory/contract.json"


def snapshot():
    return MemorySnapshot.model_validate_json(FIXTURE.read_text(encoding="utf-8"))


def test_shared_wire_contract():
    memory = snapshot()
    assert json.loads(memory.model_dump_json()) == json.loads(FIXTURE.read_text(encoding="utf-8"))
    request = AgentRequest(query="现在是什么型号？", userId=memory.userId,
        conversationId=memory.conversationId, requestId=memory.requestId, memory=memory)
    assert request.memory.recentTurns[0].assistant.role == "ai"


@pytest.mark.parametrize("field,value", [("userId", "other"), ("conversationId", "other"),
                                        ("requestId", "other"), ("persistHistory", False)])
def test_snapshot_ownership(field, value):
    memory = snapshot()
    values = dict(query="问题", userId=memory.userId, conversationId=memory.conversationId,
                  requestId=memory.requestId, memory=memory)
    values[field] = value
    with pytest.raises(ValidationError):
        AgentRequest(**values)


def test_old_request_still_works():
    request = AgentRequest(query="问题")
    assert request.persistHistory is None and request.memory is None


def test_summary_rejects_invented_metadata():
    value = snapshot().summary.model_dump()
    value["userId"] = "invented"
    with pytest.raises(ValidationError):
        MemorySummary(**value)


@pytest.mark.parametrize("variant", ["overlap", "missing", "unknown_source", "duplicate"])
def test_snapshot_range_validation(variant):
    value = snapshot().model_dump()
    if variant == "overlap":
        value["recentTurns"][0]["turnSeq"] = 1
    elif variant == "missing":
        value["summary"] = None
    elif variant == "unknown_source":
        value["sourceMap"] = {}
    else:
        value["pendingTurns"] = value["recentTurns"]
    with pytest.raises(ValidationError):
        MemorySnapshot(**value)


def test_complete_turn_cannot_use_system_role_or_split_pair():
    value = snapshot().recentTurns[0].model_dump()
    value["assistant"]["role"] = "user"
    with pytest.raises(ValidationError):
        CompleteTurn(**value)


@pytest.mark.parametrize("kwargs", [dict(memory_recent_turns=4), dict(memory_summary_max_tokens=500),
    dict(memory_summary_retries=2), dict(memory_enabled=True),
    dict(memory_model_context_window=1000), dict(memory_main_input_budget=0)])
def test_invalid_config(kwargs):
    with pytest.raises(ValidationError):
        Settings(_env_file=None, **kwargs)


def test_safe_defaults_and_explicit_window():
    assert Settings(_env_file=None).memory_enabled is False
    assert Settings(_env_file=None, memory_enabled=True,
                    memory_model_context_window=32768).memory_recent_turns == 5


def test_compression_rollback_keeps_explicit_execution_guard_configuration():
    with pytest.raises(ValidationError, match="context_window_required"):
        Settings(_env_file=None, memory_execution_enabled=True)
    settings = Settings(_env_file=None, memory_enabled=False, memory_execution_enabled=True,
                        memory_model_context_window=40000)
    client = FakeClient([])
    result = SummaryService(settings, client).summarize(summary_request())
    assert result.error == "memory_disabled" and not client.calls


def test_guard_rejects_virtual_secret_without_opening_it():
    with pytest.raises(PermissionError):
        audit_guard("open", ("synthetic-test/.env",))
    with pytest.raises(PermissionError):
        audit_guard("socket.connect", ())


def test_default_configuration_is_forbidden():
    with pytest.raises(AssertionError):
        Settings()


def summary_request():
    memory = snapshot()
    sources = memory.sourceMap | {
        "u2": dict(messageId="message-u2", turnSeq=2, role="user", evidenceRefs=[]),
        "a2": dict(messageId="message-a2", turnSeq=2, role="assistant", evidenceRefs=[]),
    }
    return SummaryRequest(userId=memory.userId, conversationId=memory.conversationId,
        requestId=memory.requestId, historyRevision=memory.historyRevision,
        summaryVersion=1, policyVersion=memory.policyVersion, summarizedThroughTurnSeq=1,
        oldSummary=memory.summary, turns=memory.recentTurns, sourceMap=sources,
        summaryMaxTokens=300, sourceSnapshotHash="0" * 64)


def response(text, finish="stop", calls=None):
    return SimpleNamespace(choices=[SimpleNamespace(finish_reason=finish,
        message=SimpleNamespace(content=text, tool_calls=calls))],
        usage=SimpleNamespace(prompt_tokens=123, completion_tokens=45, prompt_cache_hit_tokens=12))


class FakeClient:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.calls = []
        self.chat = SimpleNamespace(completions=self)

    def create(self, **kwargs):
        self.calls.append(kwargs)
        value = next(self.responses)
        if isinstance(value, Exception):
            raise value
        return value


def valid_summary():
    value = snapshot().summary.model_dump()
    value["device"] = [dict(text="用户更正型号M100-Pro", source="u2")]
    return json.dumps(value, ensure_ascii=False)


def service(responses, **kwargs):
    settings = Settings(_env_file=None, memory_enabled=True,
        memory_model_context_window=32768, **kwargs)
    client = FakeClient(responses)
    counter = TokenCounter(lambda text: max(1, len(text) // 4), "synthetic-count-v1", False)
    return SummaryService(settings, client, counter), client


def test_incremental_summary_request_and_usage():
    summarizer, client = service([response(valid_summary())])
    result = summarizer.summarize(summary_request())
    assert result.ok and result.summarizedThroughTurnSeq == 2
    assert result.sourceSnapshotHash == "0" * 64
    assert result.counter == "synthetic-count-v1" and not result.exactCount
    call = client.calls[0]
    assert "tools" not in call and call["max_tokens"] == 300
    assert "extra_body" not in call  # Generic gateway: no DeepSeek-only parameters.
    payload = json.loads(call["messages"][1]["content"])
    assert len(payload["turns"]) == 1 and payload["turns"][0]["turnSeq"] == 2
    assert result.usage[0].inputTokens == 123 and result.usage[0].cachedInputTokens == 12


def test_server_receipts_are_summary_data_not_current_authorization():
    from app.schemas import MemorySource, MemoryToolResult, ObservedWriteOutcome
    request = summary_request()
    request.sourceMap["receipt-2"] = MemorySource(messageId="receipt-2", turnSeq=2, role="tool")
    request.toolResults = [MemoryToolResult(source="receipt-2", receipt=ObservedWriteOutcome(
        name="create_repair_order", writeStatus="unknown", target="device", identifier="", requestId="old-r2"))]
    value = json.loads(valid_summary())
    value["attempts"] = [dict(action="报修", result="结果未知", state="tool_observed", source="receipt-2")]
    summarizer, client = service([response(json.dumps(value, ensure_ascii=False))])
    assert summarizer.summarize(request).ok
    payload = json.loads(client.calls[0]["messages"][1]["content"])
    assert payload["toolResults"][0]["receipt"]["writeStatus"] == "unknown"
    assert "tools" not in client.calls[0]
    assert "本轮授权" in client.calls[0]["messages"][0]["content"]


def test_receipt_requires_a_matching_tool_source_in_the_incremental_range():
    value = json.loads(summary_request().model_dump_json())
    value["toolResults"] = [dict(source="u2", receipt=dict(name="create_repair_order",
        writeStatus="unknown", target="device", identifier="", requestId="old"))]
    with pytest.raises(ValidationError, match="summary_tool_source_invalid"):
        SummaryRequest.model_validate_json(json.dumps(value))


def test_a_tool_source_without_a_server_receipt_cannot_support_observed_execution():
    from app.schemas import MemorySource
    request = summary_request()
    request.sourceMap["forged-tool"] = MemorySource(messageId="forged-tool", turnSeq=2, role="tool")
    value = json.loads(valid_summary())
    value["attempts"] = [dict(action="报修", result="成功", state="tool_observed", source="forged-tool")]
    summarizer, _ = service([response(json.dumps(value)), response(json.dumps(value))])
    assert not summarizer.summarize(request).ok


@pytest.mark.parametrize("bad", ["broken", "truncated", "empty", "unknown", "oversized", "tools", "timeout"])
def test_invalid_summary_never_advances_boundary_and_retries_once(bad):
    text = valid_summary()
    if bad == "broken": text = "{"
    if bad == "empty": text = json.dumps({key: [] for key in snapshot().summary.model_dump()})
    if bad == "unknown": text = text.replace("u2", "unknown")
    if bad == "oversized": text = text.replace("用户更正型号M100-Pro", "x" * 2000)
    value = response(text, "length" if bad == "truncated" else "stop", ["tool"] if bad == "tools" else None)
    if bad == "timeout": value = RuntimeError("must-never-print-secret")
    summarizer, client = service([value, value])
    result = summarizer.summarize(summary_request())
    assert not result.ok and result.summary is None
    assert result.summarizedThroughTurnSeq == 1 and len(client.calls) == 2
    assert len(result.usage) == 2 and "secret" not in result.model_dump_json()


def test_retry_can_recover_without_replaying_tools():
    summarizer, client = service([response("{"), response(valid_summary())])
    assert summarizer.summarize(summary_request()).ok
    assert len(client.calls) == 2


def test_suggested_action_cannot_become_tool_observation():
    value = json.loads(valid_summary())
    value["attempts"] = [dict(action="检查", result="未知", state="tool_observed", source="a2")]
    summarizer, _ = service([response(json.dumps(value)), response(json.dumps(value))])
    assert summarizer.summarize(summary_request()).error == "summary_attempt_source_invalid"


def test_source_map_is_bounded_to_summary_and_new_turns():
    request = summary_request()
    request.sourceMap["future"] = request.sourceMap["u2"].model_copy(update={"messageId": "future", "turnSeq": 99})
    summarizer, _ = service([response(valid_summary())])
    assert "future" not in summarizer._messages(request)[1]["content"]


def test_budget_includes_tools_and_json_keys():
    counter = TokenCounter()
    messages = [{"role": "user", "content": "x"}]
    assert counter.count({"messages": messages, "tools": []}) > counter.count("x")
    with pytest.raises(ContextBudgetError):
        counter.check_input(messages, [{"description": "不同内容" * 300}], 256)


def test_summary_budget_rejection_calls_no_model():
    summarizer, client = service([], memory_summary_input_budget=256)
    result = summarizer.summarize(summary_request())
    assert result.error == "memory_turn_too_large" and client.calls == []


def test_memory_input_does_not_upgrade_history_to_system_or_duplicate_current_query():
    from app.agent import AgentService
    memory = snapshot()
    request = AgentRequest(query="现在是什么型号？", userId=memory.userId,
        conversationId=memory.conversationId, requestId=memory.requestId,
        memory=memory, history=[dict(role="user", content="客户端不可信历史")])
    messages = AgentService._build_input(request)
    assert all(item["role"] != "system" for item in messages)
    assert [item["content"] for item in messages].count(request.query) == 1
    assert "客户端不可信历史" not in str(messages)
    assert "M100-Pro" in str(messages)


def test_explicit_single_turn_ignores_client_history():
    from app.agent import AgentService
    request = AgentRequest(query="问题", persistHistory=False,
        history=[dict(role="user", content="旧内容")])
    assert AgentService._build_input(request) == [{"role": "user", "content": "问题"}]


@pytest.mark.parametrize("n", [3, 5])
def test_complete_window_is_not_truncated_again(n):
    from app.agent import AgentService
    value = snapshot().model_dump()
    template = value["recentTurns"][0]
    value["recentTurns"] = []
    for i in range(2, n + 2):
        turn = json.loads(json.dumps(template))
        turn.update(turnSeq=i, requestId=f"request-{i}")
        turn["user"].update(messageId=f"u{i}", content=f"user-{i}")
        turn["assistant"].update(messageId=f"a{i}", content=f"answer-{i}")
        value["recentTurns"].append(turn)
    memory = MemorySnapshot.model_validate(value)
    messages = AgentService._build_input(AgentRequest(query="current", userId=memory.userId,
        conversationId=memory.conversationId, requestId=memory.requestId, memory=memory))
    assert len(messages) == 2 * n + 2
    assert messages[1]["content"] == "user-2" and messages[-1]["content"] == "current"


def memory_agent(responses, **config):
    from test_agent_loop import make_agent
    agent, client = make_agent(responses)
    agent.settings = Settings(_env_file=None, deepseek_api_key="offline",
        memory_enabled=True, memory_model_context_window=40000, **config)
    agent.execution_checker = lambda request: None
    agent.token_counter = TokenCounter(lambda text: len(text) // 4, "synthetic", False)
    memory = snapshot()
    request = AgentRequest(query="问题", userId=memory.userId, conversationId=memory.conversationId,
        requestId=memory.requestId, memory=memory)
    return agent, client, request


def test_main_call_sends_output_reserve_and_records_each_actual_call():
    from test_agent_loop import final
    agent, client, request = memory_agent([final("回答")])
    result = agent.run(request)
    assert client.requests[0]["max_tokens"] == 2000
    assert result.requestId == request.requestId and len(result.usageBreakdown) == 1
    assert result.usageBreakdown[0].inputTokens is None  # Missing usage is not zero.


def test_initial_budget_rejects_before_model_or_tools():
    agent, client, request = memory_agent([], memory_main_input_budget=256)
    result = agent.run(request)
    assert result.error == "context_budget_exceeded"
    assert client.requests == [] and agent.registry.calls == [] and result.usageBreakdown == []


def test_tool_results_are_checked_before_next_call_without_truncation():
    from test_agent_loop import tool_reply, final
    from app.schemas import ToolResult
    agent, client, request = memory_agent([tool_reply(1), final("must not run")])
    agent.registry.execute = lambda *args: ToolResult(data={"text": "x" * 100000})
    result = agent.run(request)
    assert result.error == "context_budget_exceeded" and len(client.requests) == 1
    assert len(result.usageBreakdown) == 1 and len(result.toolCalls) == 1


def test_lost_execution_qualification_prevents_tool_dispatch():
    from test_agent_loop import tool_reply
    agent, client, request = memory_agent([tool_reply(1)])
    checks = []
    def check(_):
        checks.append(True)
        if len(checks) == 2:
            raise PermissionError("lost")
    agent.execution_checker = check
    result = agent.run(request)
    assert result.error == "execution_qualification_lost"
    assert len(client.requests) == 1 and agent.registry.calls == []


def test_verified_write_receipt_survives_budget_failure_and_terminal_publication():
    from test_agent_loop import reply, final
    from app.schemas import ToolResult
    from types import SimpleNamespace
    call = SimpleNamespace(id="w1", function=SimpleNamespace(name="add_manual_to_account", arguments="{}"))
    agent, client, request = memory_agent([reply(calls=[call]), final("must not run")])
    request.query = "收藏当前说明书"; request.manualId = "verified-manual"
    agent.registry.execute = lambda *args: ToolResult(ok=True, write_status="succeeded", write_target="verified-manual",
                                                      data={"oversized": "x" * 100000})
    result = agent.run(request)
    assert len(client.requests) == 1 and result.observedWriteOutcomes[0].writeStatus == "succeeded"
    assert result.observedWriteOutcomes[0].requestId == request.requestId
    assert "已将" in result.answerText  # Application retains the actually observed write.


def test_terminal_publication_is_only_for_lost_final_qualification_and_is_injectable():
    from test_agent_loop import final
    agent, client, request = memory_agent([final("已观察答案")])
    checks, published = [], []
    def check(_):
        checks.append(True)
        if len(checks) > 1: raise PermissionError("lost after model")
    agent.execution_checker = check
    agent.terminal_publisher = lambda req, result: published.append((req.requestId, result.answerText))
    result = agent.run(request)
    assert published == [(request.requestId, result.answerText)] and len(client.requests) == 1


def test_receipt_does_not_upgrade_unverified_success_flag():
    from app.write_outcomes import observed_write_status
    from app.schemas import ToolResult
    request = AgentRequest(query="收藏", manualId="A")
    assert observed_write_status(request, "add_manual_to_account", ToolResult(ok=True)) == "unknown"
    assert observed_write_status(request, "add_manual_to_account",
        ToolResult(ok=True, write_status="succeeded", write_target="B")) == "unknown"


def test_dataset_is_synthetic_annotated_and_gold_is_not_part_of_turn_payload():
    from app.memory_eval_data import validate_dataset
    manifest = json.loads((Path(__file__).parent / "fixtures/session_memory/manifest.json").read_text(encoding="utf-8"))
    data = validate_dataset(manifest)
    assert len(data) == 12 and len(data[-1].turns) == 80
    assert sum(t.split == "heldout" for t in data) == 6
    for trajectory in data:
        raw = json.dumps([turn.model_dump() for turn in trajectory.turns])
        assert "acceptable" not in raw and "forbidden" not in raw and "supersedes" not in raw
        assert any(fact.id == "model-new" for fact in trajectory.active(13))
        assert not any(fact.id == "model-old" for fact in trajectory.active(13))


def test_summary_endpoint_uses_injected_service_and_requires_token(monkeypatch):
    from fastapi.testclient import TestClient
    from app import main
    settings = Settings(_env_file=None, agent_internal_token="synthetic-token")
    monkeypatch.setattr(main, "get_settings", lambda: settings)
    summarizer, model = service([response(valid_summary())])
    main.app.dependency_overrides[main.get_summary_service] = lambda: summarizer
    try:
        with TestClient(main.app) as client:
            payload = summary_request().model_dump(mode="json")
            assert client.post("/v1/agent/summarize", json=payload).status_code == 401
            result = client.post("/v1/agent/summarize", json=payload,
                headers={"X-Internal-Token": "synthetic-token"})
            assert result.status_code == 200 and result.json()["ok"]
            assert len(model.calls) == 1
            payload["turns"] = []
            assert client.post("/v1/agent/summarize", json=payload,
                headers={"X-Internal-Token": "synthetic-token"}).status_code == 422
    finally:
        main.app.dependency_overrides.clear()


def test_chat_accepts_execution_only_snapshot_after_compression_rollback(monkeypatch):
    from fastapi.testclient import TestClient
    from app import main
    from app.schemas import AgentResponse
    settings = Settings(_env_file=None, memory_enabled=False, memory_execution_enabled=True,
        memory_model_context_window=40000, agent_internal_token="synthetic-token")
    memory = snapshot()
    request = AgentRequest(query="问题", userId=memory.userId, conversationId=memory.conversationId,
                           requestId=memory.requestId, memory=memory)
    monkeypatch.setattr(main, "get_settings", lambda: settings)
    agent = SimpleNamespace(run=lambda value: AgentResponse(answerText="合成回答", requestId=value.requestId))
    monkeypatch.setattr(main, "AgentService", lambda: agent)
    with TestClient(main.app) as client:
        result = client.post("/v1/agent/chat", json=request.model_dump(mode="json"),
                             headers={"X-Internal-Token": "synthetic-token"})
        assert result.status_code == 200 and result.json()["status"] == "ok"
