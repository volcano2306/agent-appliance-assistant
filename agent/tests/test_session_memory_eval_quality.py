import json
from pathlib import Path
from types import SimpleNamespace
import pytest
from app.memory_eval_quality import aggregate_review, full_comparison, quality_gate_failures, choose_configuration


def gates():
    return json.loads((Path(__file__).parent / "fixtures/session_memory/manifest.json").read_text(encoding="utf-8"))["gates"]


def valid_group(identifier="N5-S600"):
    return dict(id=identifier, criticalRetention=1, criticalAnswerAccuracy=1, generalRetention=1,
        generalAnswerAccuracy=1, correctionAdoption=1, cancellationAdoption=1, recentDetailAccuracy=1,
        committedSchemaRate=1, committedSizeRate=1, summaryOperationSuccess=1, unsupportedFactRate=0,
        crossSessionLeakage=0, falseWriteClaims=0, unauthorizedWrites=0, answerDropVsFull=0,
        actualTotalCost=1, p95Ms=100)


def test_one_missing_critical_cancellation_rejects_even_the_cheapest_configuration():
    group = valid_group(); group.update(criticalRetention=0, actualTotalCost=.01)
    assert "criticalRetention" in quality_gate_failures(group, gates())
    assert choose_configuration([group], gates(), complete_real_matrix=True,
        human_review_complete=True)["recommendedConfig"] is None


def test_degraded_raw_answer_success_does_not_hide_failed_compression():
    group = valid_group(); group["summaryOperationSuccess"] = .8
    assert "summaryOperationSuccess" in quality_gate_failures(group, gates())


def test_summary_and_answer_reviews_use_separate_denominators_and_explicit_claims():
    fact = SimpleNamespace(id="cancel", priority="critical", supersedes=None, key="操作限制")
    review = dict(correct=False, retained=False, corrected=False, recentCorrect=False)
    result = aggregate_review([fact], {"cancel": review}, [dict(supported=True), dict(supported=False)],
                              recent_ids=["cancel"], false_write_claims=1)
    assert result["criticalDenominator"] == 1 and result["criticalAnswerAccuracy"] == 0
    assert result["factPrecision"] == .5 and result["unsupportedFactRate"] == .5
    assert result["cancellationAdoption"] == result["recentDetailAccuracy"] == 0
    assert result["falseWriteClaims"] == 1
    with pytest.raises(ValueError, match="atomic_claim"):
        aggregate_review([fact], {"cancel": review}, [{}])


def test_full_comparison_excludes_na_without_calling_na_success():
    result = full_comparison({"a": True, "b": False, "c": False}, {"a": True, "b": True, "c": None})
    assert result == dict(answerDropVsFull=.5, commonDenominator=2, fullCoverage=2/3)
    assert full_comparison({"a": True}, {"a": None})["answerDropVsFull"] is None


def test_incomplete_matrix_or_review_can_never_select_a_winner():
    for matrix, review in [(False, True), (True, False), (False, False)]:
        assert choose_configuration([valid_group()], gates(), complete_real_matrix=matrix,
                                    human_review_complete=review)["recommendedConfig"] is None


def test_near_equal_cost_uses_p95_but_more_than_five_percent_does_not():
    cheapest = valid_group("cheap"); fast = valid_group("fast"); fast.update(actualTotalCost=1.04, p95Ms=50)
    assert choose_configuration([cheapest, fast], gates(), complete_real_matrix=True,
        human_review_complete=True)["recommendedConfig"] == "fast"
    fast["actualTotalCost"] = 1.06
    assert choose_configuration([cheapest, fast], gates(), complete_real_matrix=True,
        human_review_complete=True)["recommendedConfig"] == "cheap"


def test_unknown_prices_or_na_metric_do_not_become_zero_or_one_hundred_percent():
    group = valid_group(); group["actualTotalCost"] = None
    assert choose_configuration([group], gates(), complete_real_matrix=True,
        human_review_complete=True)["recommendedConfig"] is None
    group["recentDetailAccuracy"] = None
    assert "recentDetailAccuracy" in quality_gate_failures(group, gates())
