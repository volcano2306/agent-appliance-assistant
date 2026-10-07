from types import SimpleNamespace
import json
import pytest
from app.memory_eval_runtime import BudgetStop, MeteredClient


class Clock:
    def __init__(self): self.now = 0; self.pauses = []
    def clock(self): return self.now
    def sleep(self, seconds): self.pauses.append(seconds); self.now += seconds


class Client:
    def __init__(self): self.chat = SimpleNamespace(completions=self); self.calls = []
    def create(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(usage=SimpleNamespace(prompt_tokens=20, completion_tokens=5, prompt_cache_hit_tokens=0))


def meter(**overrides):
    clock = Clock(); client = Client()
    kwargs = dict(max_calls=10, max_tokens=10000, max_duration=100, min_interval=0,
                  clock=clock.clock, sleep=clock.sleep)
    kwargs.update(overrides)
    return MeteredClient(client, **kwargs), client, clock


def test_call_limit_stops_before_next_dispatch():
    tested, client, _ = meter(max_calls=1)
    tested.create(messages=[], max_tokens=10)
    with pytest.raises(BudgetStop): tested.create(messages=[], max_tokens=10)
    assert len(client.calls) == len(tested.calls) == 1


def test_token_reservation_accounts_for_output_and_tools_before_dispatch():
    tested, client, _ = meter(max_tokens=100)
    with pytest.raises(BudgetStop): tested.create(messages=[], tools=[dict(description="不同内容"*200)], max_tokens=10)
    assert not client.calls and not tested.calls


def test_rate_limit_waits_full_interval_in_short_interruptible_steps():
    tested, client, clock = meter(min_interval=3)
    tested.create(messages=[], max_tokens=10); tested.create(messages=[], max_tokens=10)
    assert sum(clock.pauses) == 3 and max(clock.pauses) <= 1
    assert len(client.calls) == 2


def test_rate_limit_does_not_cross_remaining_time_budget():
    tested, client, _ = meter(min_interval=3, max_duration=2)
    tested.create(messages=[], max_tokens=10)
    with pytest.raises(BudgetStop): tested.create(messages=[], max_tokens=10)
    assert len(client.calls) == 1


def test_timeout_is_clamped_to_remaining_budget():
    tested, client, _ = meter(max_duration=2)
    tested.create(messages=[], max_tokens=10)
    assert client.calls[0]["timeout"] == 2


def test_reservation_is_durable_before_call_and_unknown_usage_not_lost(tmp_path):
    path = tmp_path / "calls.json"
    tested, client, _ = meter(journal=path)
    def crash(**kwargs):
        saved = json.loads(path.read_text(encoding="utf-8"))
        assert saved["calls"][0]["status"] == "issued_outcome_unknown"
        raise RuntimeError("synthetic provider failure")
    client.chat.completions = SimpleNamespace(create=crash)
    with pytest.raises(RuntimeError): tested.create(messages=[], max_tokens=10)
    assert tested.calls[0]["inputTokens"] is None
    saved = json.loads(path.read_text(encoding="utf-8"))
    restored, restored_client, _ = meter(max_calls=1, calls=saved["calls"], prior_elapsed=saved["elapsedSeconds"])
    with pytest.raises(BudgetStop): restored.create(messages=[], max_tokens=10)
    assert not restored_client.calls


def test_resume_keeps_prior_elapsed_duration():
    tested, client, _ = meter(max_duration=2, prior_elapsed=2)
    with pytest.raises(BudgetStop): tested.create(messages=[], max_tokens=10)
    assert not client.calls


def test_budget_stop_cannot_be_swallowed_by_model_error_fallback():
    assert not issubclass(BudgetStop, Exception)


def test_authentication_error_stops_matrix_without_logging_sdk_message():
    tested, client, _ = meter()
    class Forbidden(RuntimeError): status_code = 401
    def fail(**kwargs): raise Forbidden("synthetic sensitive exception text")
    client.chat.completions = SimpleNamespace(create=fail)
    with pytest.raises(BudgetStop, match="configuration_error"):
        tested.create(messages=[], max_tokens=10)
    assert tested.calls[0]["httpStatus"] == 401
    assert "sensitive" not in json.dumps(tested.calls)


def test_model_phase_annotation_does_not_add_or_double_count_calls():
    tested, _, _ = meter()
    tested.create(messages=[], max_tokens=10); tested.create(messages=[], max_tokens=10)
    tested.annotate(0, [SimpleNamespace(phase="answer"), SimpleNamespace(phase="format_repair")])
    assert len(tested.calls) == 2
    assert [call["modelPhase"] for call in tested.calls] == ["answer", "format_repair"]
