from types import SimpleNamespace
import json
import pytest
from app.memory_eval_metrics import atomic_json, percentile, call_totals, execution_metrics, review_fact_scores


PRICES = dict(inputPerMillion=2, cachedInputPerMillion=.2, outputPerMillion=8, currency="synthetic")


def call(identifier, **values):
    return dict(callId=identifier, inputTokens=100, outputTokens=20, cachedInputTokens=50, phase="answer", **values)


def test_accounting_includes_retries_and_probes_without_double_count():
    calls = [call("a"), call("retry"), {**call("probe"), "phase": "probe"}]
    totals = call_totals(calls, PRICES)
    assert totals["callCount"] == 3 and totals["totalTokens"] == 360
    assert totals["actualTotalCost"] == pytest.approx(3 * (50*2 + 50*.2 + 20*8) / 1_000_000)
    with pytest.raises(ValueError, match="duplicate_call_id"):
        call_totals([calls[0], calls[0]], PRICES)


@pytest.mark.parametrize("missing", ["inputTokens", "outputTokens", "cachedInputTokens"])
def test_missing_usage_is_unknown_not_free(missing):
    totals = call_totals([{**call("a"), missing: None}], PRICES)
    assert totals["actualTotalCost"] is None
    if missing != "cachedInputTokens":
        assert totals["totalTokens"] is None and totals["unknownUsageCalls"] == 1


def test_missing_prices_do_not_produce_zero_cost():
    assert call_totals([call("a")], {})["actualTotalCost"] is None


def test_latency_includes_compression_and_nearest_rank_is_frozen():
    rows = [dict(kind="answer", endToEndMs=value, compressionTriggered=value==1000, memoryStatus="ok")
            for value in [1, 2, 3, 1000]] + [dict(kind="summary", ok=False)]
    metrics = execution_metrics(rows, [call("a")], PRICES)
    assert metrics["endToEnd"] == dict(count=4, p50Ms=2, p95Ms=1000)
    assert metrics["compressionRequests"]["p95Ms"] == 1000
    assert metrics["summaryOperationsSucceeded"] == 0
    assert metrics["recommendedConfig"] is None and not metrics["semanticQualityVerified"]
    assert percentile([], .95) is None


def test_semantic_review_denominator_includes_missing_facts():
    facts = [SimpleNamespace(id="critical", priority="critical", supersedes=None),
             SimpleNamespace(id="corrected", priority="general", supersedes="old")]
    good = dict(correct=True, retained=True, corrected=True, recentCorrect=True)
    bad = dict(correct=False, retained=False, corrected=False, recentCorrect=False)
    scores = review_fact_scores(facts, {"critical": good, "corrected": bad})
    assert scores["criticalRetention"] == 1 and scores["generalRetention"] == 0
    assert scores["correctionDenominator"] == 1 and scores["correctionAdoption"] == 0
    with pytest.raises(ValueError, match="review_incomplete"):
        review_fact_scores(facts, {"critical": good})


def test_atomic_checkpoint_replaces_whole_json(tmp_path):
    path = tmp_path / "checkpoint.json"
    atomic_json(path, {"nextSeq": 2}); atomic_json(path, {"nextSeq": 3})
    assert json.loads(path.read_text(encoding="utf-8")) == {"nextSeq": 3}
    assert not path.with_name("checkpoint.json.pending").exists()
