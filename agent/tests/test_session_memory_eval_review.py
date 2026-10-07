import json
from pathlib import Path
import pytest
from app.memory_eval_data import validate_dataset
from app.memory_eval_review import review_pack, score_review


def inputs():
    manifest = json.loads((Path(__file__).parent / "fixtures/session_memory/manifest.json").read_text(encoding="utf-8"))
    data = validate_dataset(manifest)
    rows = [dict(runId=f"{data[0].id}/N3-S300/0", kind="probe", turn=13, raw="合成答案", status="ok")]
    return manifest, data, rows


def test_review_template_is_ungraded_and_does_not_mark_keywords_as_passes():
    manifest, data, rows = inputs(); pack = review_pack(rows, data)
    assert not pack["rows"][0]["reviewComplete"]
    assert all(review["correct"] is None for review in pack["rows"][0]["factReviews"].values())
    assert all(fact["valid_from"] <= 13 for fact in pack["rows"][0]["expectedFacts"])


def test_missing_semantic_annotation_is_rejected_not_defaulted_to_success():
    manifest, data, rows = inputs(); pack = review_pack(rows, data)
    with pytest.raises(ValueError, match="human_review_incomplete"):
        score_review(dict(rows=rows, calls=[], track="pure-memory"), data, manifest, pack, split="dev")


def test_edited_gold_in_review_file_cannot_change_metric_denominators():
    manifest, data, rows = inputs(); pack = review_pack(rows, data); row = pack["rows"][0]
    row.update(reviewComplete=True, atomicClaims=[dict(supported=True)], crossSessionLeakage=0,
               falseWriteClaims=0, unauthorizedWrites=0, expectedFacts=[])
    for review in row["factReviews"].values(): review.update(correct=True, retained=True, corrected=True, recentCorrect=True)
    state = dict(rows=rows, calls=[], track="pure-memory", realModelVerified=False, completeRealMatrix=False)
    result = score_review(state, data, manifest, pack, split="dev")
    group = next(group for group in result["groups"] if group["id"] == "N3-S300")
    assert group["probeReview"]["criticalDenominator"] > 0
    assert result["decision"]["recommendedConfig"] is None


def test_duplicate_or_missing_review_rows_are_not_silently_merged():
    manifest, data, rows = inputs(); pack = review_pack(rows, data)
    pack["rows"].append(pack["rows"][0])
    with pytest.raises(ValueError, match="duplicate"):
        score_review(dict(rows=rows, calls=[], track="pure-memory"), data, manifest, pack, split="dev")


def test_density_recent_detail_and_supersession_annotation_are_consistent():
    _, data, _ = inputs()
    for trajectory in data:
        assert len(trajectory.active(len(trajectory.turns))) == trajectory.density
        for probe in trajectory.probes:
            assert any(fact.valid_from > probe - 3 for fact in trajectory.active(probe))
            assert all(probe in fact.required_at for fact in trajectory.active(probe))
        assert "DISTRACTOR-X" in trajectory.turns[7].user.content
        assert not any("DISTRACTOR-X" in fact.text for fact in trajectory.active(13))
