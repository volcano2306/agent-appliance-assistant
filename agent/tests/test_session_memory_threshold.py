import json
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from app.config import Settings
from app.memory import SummaryService, TokenCounter, compact, history_messages
from app.memory_eval_replay import prepare_prefix, request_for
from app.schemas import CompleteTurn, MemoryPlanRequest, MemorySnapshot, MemorySource, SummaryRequest


def turn(n, text="问"):
    return CompleteTurn(turnSeq=n, requestId=f"r{n}",
        user=dict(messageId=f"u{n}", role="user", content=text),
        assistant=dict(messageId=f"a{n}", role="assistant", content="答"))


ARM = dict(id="N5-S600", mode="summary", recentTurns=5, summaryMaxTokens=600)
TRAJECTORY = SimpleNamespace(id="threshold-fixture")


class Client:
    def __init__(self, fail=False):
        self.calls = []
        self.fail = fail
        self.chat = SimpleNamespace(completions=self)

    def create(self, **kwargs):
        self.calls.append(kwargs)
        data = json.loads(kwargs["messages"][1]["content"])
        summary = dict(device=[dict(text="已确认设备", source=data["turns"][0]["user"]["messageId"])],
            currentIssue=[], attempts=[], constraints=[], openQuestions=[], evidenceRefs=[], background=[])
        return SimpleNamespace(usage=SimpleNamespace(prompt_tokens=40, completion_tokens=20),
            choices=[SimpleNamespace(finish_reason="stop",
                message=SimpleNamespace(tool_calls=[], content="{" if self.fail else compact(summary)))])


def service(fail=False, counter=None):
    client = Client(fail)
    return SummaryService(Settings(_env_file=None, memory_enabled=True,
        memory_model_context_window=40000, memory_summary_retries=0), client,
        counter or TokenCounter(len, "synthetic-char-count", False)), client


def sized_prefix(size, long_recent=False):
    prefix = [turn(n) for n in range(1, 9)]
    snapshot = request_for(TRAJECTORY, ARM, prefix, None, 0, {}, "ok").memory
    overhead = len(compact(history_messages(snapshot)))
    n = 7 if long_recent else 0
    prefix[n] = turn(n + 1, "问" + "x" * (size - overhead))
    return prefix


@pytest.mark.parametrize("size,calls", [(1499, 0), (1500, 1), (1501, 1)])
def test_threshold_boundary_preserves_complete_recent_turns(size, calls):
    summarizer, client = service()
    prefix = sized_prefix(size)
    summary, through, sources, next_trigger, status, events = prepare_prefix(
        TRAJECTORY, ARM, prefix, summarizer, None, 0, {}, 1500)
    assert len(client.calls) == calls
    snapshot = request_for(TRAJECTORY, ARM, prefix, summary, through, sources, status).memory
    assert [t.turnSeq for t in snapshot.recentTurns] == [4, 5, 6, 7, 8]
    assert status == "ok"
    if calls:
        assert through == 3 and not snapshot.pendingTurns
        assert summarizer.counter.count(history_messages(snapshot)) <= 1000
    else:
        assert len(snapshot.pendingTurns) == 3 and through == 0 and summary is None


def test_failure_preserves_history_and_durable_growth_gate_skips_next_attempt():
    summarizer, client = service(fail=True)
    prefix = sized_prefix(1500)
    state = prepare_prefix(TRAJECTORY, ARM, prefix, summarizer, None, 0, {}, 1500)
    summary, through, sources, next_trigger, status, _ = state
    assert summary is None and through == 0 and status == "degraded" and next_trigger == 2000
    restored = prepare_prefix(TRAJECTORY, ARM, prefix + [turn(9)], summarizer,
        summary, through, sources, next_trigger)
    assert len(client.calls) == 1 and restored[1] == 0
    snapshot = request_for(TRAJECTORY, ARM, prefix + [turn(9)], *restored[:3], restored[4]).memory
    assert len(snapshot.pendingTurns) + len(snapshot.recentTurns) == 9


def test_recent_window_too_long_cannot_cause_immediate_repeat_compaction():
    summarizer, client = service()
    prefix = sized_prefix(4000, long_recent=True)
    summary, through, sources, next_trigger, status, events = prepare_prefix(
        TRAJECTORY, ARM, prefix, summarizer, None, 0, {}, 1500)
    after = request_for(TRAJECTORY, ARM, prefix, summary, through, sources, status).memory
    assert after.recentTurns == prefix[-5:]
    assert next_trigger >= summarizer.counter.count(history_messages(after)) + 500
    restored = prepare_prefix(TRAJECTORY, ARM, prefix + [turn(9)], summarizer,
        summary, through, sources, next_trigger)
    assert len(client.calls) == 1 and restored[1] == through


def test_local_tokenizer_counts_chinese_tokens_instead_of_utf8_bytes():
    counter = TokenCounter()
    text = "用户暂不报修，预算1500元，检查结果未知。"
    assert 0 < counter.count(text) < len(text.encode("utf-8"))
    assert counter.name == "deepseek-v4-text-8f9f37ca37fd" and not counter.exact


def plan_request(prefix):
    snapshot = request_for(TRAJECTORY, ARM, prefix, None, 0, {}, "ok").memory
    sources = {m.messageId: MemorySource(messageId=m.messageId, turnSeq=t.turnSeq, role=m.role)
        for t in snapshot.pendingTurns for m in (t.user, t.assistant)}
    summary = SummaryRequest(userId=snapshot.userId, conversationId=snapshot.conversationId,
        requestId=snapshot.requestId, historyRevision=snapshot.historyRevision, summaryVersion=0,
        policyVersion=snapshot.policyVersion, summarizedThroughTurnSeq=0, turns=snapshot.pendingTurns,
        sourceMap=sources, sourceSnapshotHash="0"*64)
    return MemoryPlanRequest(memory=snapshot, summaryRequest=summary, triggerTokens=0)


def test_planner_uses_full_envelope_and_does_not_split_an_oversized_turn():
    summarizer, client = service()
    request = plan_request([turn(n, "随机"*6000 if n == 1 else "问") for n in range(1, 8)])
    result = summarizer.plan(request)
    assert result.reason == "memory_turn_too_large" and result.batchTurnCount == 0
    assert not client.calls and len(request.memory.pendingTurns[0].user.content) == 12000


def test_planner_selects_largest_complete_prefix_within_input_budget():
    summarizer, client = service()
    request = plan_request([turn(n, "不重复内容"*60) for n in range(1, 9)])
    one = request.summaryRequest.model_copy(update={"turns": request.summaryRequest.turns[:1]})
    summarizer.settings.memory_summary_input_budget = summarizer.counter.count(
        dict(messages=summarizer._messages(one), tools=[]))
    result = summarizer.plan(request)
    assert result.batchTurnCount == 1 and not client.calls


def test_plan_rejects_mismatched_owner_and_ranges():
    request = plan_request(sized_prefix(1500)).model_dump()
    request["summaryRequest"]["userId"] = "other"
    with pytest.raises(ValidationError):
        MemoryPlanRequest(**request)


def test_no_older_turns_calls_no_summary_even_over_threshold():
    summarizer, client = service()
    snapshot = MemorySnapshot(userId="u", conversationId="c", requestId="r", historyRevision=1,
        summaryVersion=0, policyVersion="p", summarizedThroughTurnSeq=0, recentTurns=[turn(1, "x"*4000)])
    plan = summarizer.plan(MemoryPlanRequest(memory=snapshot))
    assert plan.reason == "no_older_turns" and not client.calls


def test_plan_endpoint_requires_internal_token_and_never_calls_model(monkeypatch):
    from fastapi.testclient import TestClient
    from app import main
    summarizer, client = service()
    monkeypatch.setattr(main, "get_settings", lambda: Settings(_env_file=None, agent_internal_token="test-token"))
    main.app.dependency_overrides[main.get_summary_service] = lambda: summarizer
    body = plan_request(sized_prefix(1499)).model_dump()
    body["triggerTokens"] = 1500
    try:
        with TestClient(main.app) as http:
            assert http.post("/v1/agent/memory/plan", json=body).status_code == 401
            response = http.post("/v1/agent/memory/plan", json=body, headers={"X-Internal-Token": "test-token"})
            assert response.status_code == 200
            assert response.json()["reason"] == "below_threshold"
            assert response.json()["memoryTokens"] == 1499
        assert not client.calls
    finally:
        main.app.dependency_overrides.clear()
