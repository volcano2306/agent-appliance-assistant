from types import SimpleNamespace
import json
import pytest
from app.config import Settings
from app.memory import SummaryService, compact
from app.memory_eval_replay import prepare_prefix
from app.schemas import CompleteTurn, MemorySummary, MemorySource, SummaryRequest

def summary(source="u7"):
    return MemorySummary(device=[dict(text="当前型号", source=source)], currentIssue=[], attempts=[],
        constraints=[], openQuestions=[], evidenceRefs=["fragment18"], background=[])

def sources():
    return {"u7": MemorySource(messageId="u7",turnSeq=7,role="user"),
        "a5": MemorySource(messageId="a5",turnSeq=5,role="assistant",evidenceRefs=["fragment18"]),
        "a6": MemorySource(messageId="a6",turnSeq=6,role="assistant",evidenceRefs=["fragment18"]),
        "a1": MemorySource(messageId="a1",turnSeq=1,role="assistant",evidenceRefs=["unrelated"])}

def test_retains_one_deterministic_carrier_and_rejects_missing_provenance():
    available = sources(); selected = summary().retained_sources(available)
    assert selected == {"u7", "a5"}
    assert summary().retained_sources({key:available[key] for key in selected}) == selected
    with pytest.raises(ValueError,match="evidence_source_missing"):
        summary().retained_sources({"u7": available["u7"]})

def test_next_envelope_includes_old_carrier_without_unrelated_sources():
    turn = CompleteTurn(turnSeq=8,requestId="r8",user=dict(messageId="u8",role="user",content="新增问题"),
        assistant=dict(messageId="a8",role="assistant",content="新增回答"))
    request = SummaryRequest(userId="u",conversationId="c",requestId="r",historyRevision=8,
        summaryVersion=1,policyVersion="v",summarizedThroughTurnSeq=7,oldSummary=summary(),turns=[turn],
        sourceMap=sources(),sourceSnapshotHash="0"*64)
    service = SummaryService(Settings(_env_file=None))
    data = json.loads(service._messages(request)[1]["content"])
    assert set(data["sourceMap"]) == {"u7", "a5"}
    assert data["allowedEvidenceRefs"] == ["fragment18"]

def test_replay_can_replace_citation_summary_more_than_ten_times():
    captured = []
    def create(**kwargs):
        data = json.loads(kwargs["messages"][1]["content"]); captured.append(data)
        source = data["turns"][-1]["user"]["messageId"]
        candidate = summary(source).model_dump(); candidate["evidenceRefs"]=["fragment18"]
        assert "fragment18" in data["allowedEvidenceRefs"]
        return SimpleNamespace(usage=None, choices=[SimpleNamespace(finish_reason="stop",
            message=SimpleNamespace(tool_calls=[],content=compact(candidate)))])
    client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
    service = SummaryService(Settings(_env_file=None,memory_enabled=True,memory_model_context_window=40000),client)
    turns=[CompleteTurn(turnSeq=n,requestId=f"r{n}",user=dict(messageId=f"u{n}",role="user",content="问"),
        assistant=dict(messageId=f"a{n}",role="assistant",content="[引用:fragment18]" if n==1 else "答")) for n in range(1,20)]
    current=None; through=0; retained={}; trigger=1500
    for length in range(6,20):
        current,through,retained,trigger,status,events=prepare_prefix(SimpleNamespace(id="citation"),
            dict(id="N5-S600",mode="summary",recentTurns=5,summaryMaxTokens=600), turns[:length],
            service,current,through,retained,trigger,strategy="rolling")
        assert status=="ok" and {"a1",f"u{length-5}"} == set(retained)
    assert len(captured)==14 and all("a1" in row["sourceMap"] for row in captured)
