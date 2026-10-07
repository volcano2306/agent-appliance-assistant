import json
from pathlib import Path
from types import SimpleNamespace
import pytest
from app.agent import AgentService
from app.memory_eval_data import validate_dataset
from app.memory_eval_replay import request_for, assert_pure_memory_request, score_text
from app.schemas import MemorySummary, MemorySource


def dataset():
    manifest = json.loads((Path(__file__).parent / "fixtures/session_memory/manifest.json").read_text(encoding="utf-8"))
    return manifest, validate_dataset(manifest)


def test_all_arms_use_independent_fixed_prefix_and_current_question_once():
    manifest, trajectories = dataset(); trajectory = trajectories[0]
    for arm in manifest["arms"]:
        request = request_for(trajectory, arm, list(trajectory.turns[:15]), None, 0, {})
        request.query = "不泄题的当前问题"
        messages = AgentService._build_input(request)
        assert sum(message["content"] == request.query for message in messages) == 1
        assert not any("生成答案不应入后续前缀" in message["content"] for message in messages)
        assert_pure_memory_request(request)
        expected = 6 if arm["mode"] == "messages" else 30
        assert len(messages) == expected + 1


def test_old_summary_coverage_and_recent_correction_are_scored_at_different_times():
    manifest, trajectories = dataset(); trajectory = trajectories[0]
    summary = MemorySummary(device=[dict(text="W-A01", source="u1")], currentIssue=[], attempts=[],
        constraints=[], openQuestions=[], evidenceRefs=[], background=[])
    request = request_for(trajectory, manifest["arms"][2], list(trajectory.turns[:9]), summary, 6,
        {"u1": MemorySource(messageId="u1", turnSeq=1, role="user")})
    assert [turn.turnSeq for turn in request.memory.recentTurns] == [7, 8, 9]
    summary_model = [fact for fact in trajectory.active(6) if fact.key == "型号"]
    answer_model = [fact for fact in trajectory.active(9) if fact.key == "型号"]
    assert score_text("W-A01", summary_model)[0]["screenPass"]
    assert not score_text("W-A01", answer_model)[0]["screenPass"]
    assert score_text("W-A02", answer_model)[0]["screenPass"]


def test_probe_branch_does_not_change_replay_prefix_or_next_group():
    manifest, trajectories = dataset(); trajectory = trajectories[0]; prefix = list(trajectory.turns[:13])
    first = request_for(trajectory, manifest["arms"][2], prefix, None, 0, {})
    first.memory.pendingTurns.clear()
    second = request_for(trajectory, manifest["arms"][2], prefix, None, 0, {})
    assert len(second.memory.pendingTurns) == 10 and len(prefix) == 13
    other = request_for(trajectories[1], manifest["arms"][2], [], None, 0, {})
    assert other.memory.summary is None and other.memory.conversationId != first.memory.conversationId


@pytest.mark.parametrize("field", ["manualId", "manualName", "manualModel", "productId"])
def test_pure_track_rejects_answer_leaks_in_external_fields(field):
    manifest, trajectories = dataset()
    request = request_for(trajectories[0], manifest["arms"][0], [], None, 0, {})
    setattr(request, field, "W-A02")
    with pytest.raises(ValueError, match="external_hint"):
        assert_pure_memory_request(request)


def test_lexical_match_is_not_claimed_as_semantic_correctness():
    fact = SimpleNamespace(id="not-done", key="检查", priority="critical", acceptable=("未执行",), forbidden=("已经检查",))
    result = score_text("虽然说未执行，但是已经检查。", [fact])[0]
    assert not result["screenPass"] and result["requiresHumanReview"]
