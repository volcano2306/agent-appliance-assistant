"""Deterministic evaluation bookkeeping. No model, credentials or dotenv loading."""
import json
import math
import os
from pathlib import Path


def atomic_json(path: Path, value):
    temporary = path.with_name(path.name + ".pending")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def percentile(values, fraction):
    """Nearest-rank percentile, frozen here so small test sets are reproducible."""
    if not values:
        return None
    if not 0 < fraction <= 1:
        raise ValueError("percentile_fraction_invalid")
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)]


def call_totals(calls, prices):
    # Explicit IDs identify observations, NOT retry attempts; retries need new IDs.
    if len({call["callId"] for call in calls}) != len(calls):
        raise ValueError("duplicate_call_id")
    unknown = [call for call in calls if call.get("inputTokens") is None or call.get("outputTokens") is None]
    priced = all(prices.get(key) is not None for key in (
        "inputPerMillion", "cachedInputPerMillion", "outputPerMillion"))
    cache_unknown = any(call.get("cachedInputTokens") is None for call in calls)
    cost = None
    if priced and not unknown and not cache_unknown:
        cost = 0.0
        for call in calls:
            cached = call["cachedInputTokens"]
            if not 0 <= cached <= call["inputTokens"]:
                raise ValueError("invalid_cached_usage")
            cost += ((call["inputTokens"] - cached) * prices["inputPerMillion"]
                     + cached * prices["cachedInputPerMillion"]
                     + call["outputTokens"] * prices["outputPerMillion"]) / 1_000_000
    return dict(callCount=len(calls), unknownUsageCalls=len(unknown),
        knownInputTokens=sum(call["inputTokens"] for call in calls if call.get("inputTokens") is not None),
        knownOutputTokens=sum(call["outputTokens"] for call in calls if call.get("outputTokens") is not None),
        totalTokens=None if unknown else sum(call["inputTokens"] + call["outputTokens"] for call in calls),
        actualTotalCost=None if prices.get("basis") == "peak-upper-bound" else cost,
        conservativeCostUpperCny=cost if prices.get("basis") == "peak-upper-bound" else None,
        currency=prices.get("currency", "unconfigured"),
        cachedUsageUnknown=cache_unknown)


def execution_metrics(rows, calls, prices):
    answer_rows = [row for row in rows if row["kind"] == "answer"]
    summary_rows = [row for row in rows if row["kind"] == "summary"]
    durations = [row["endToEndMs"] for row in answer_rows]
    def timing(values):
        return dict(count=len(values), p50Ms=percentile(values, .5), p95Ms=percentile(values, .95))
    return dict(**call_totals(calls, prices),
        endToEnd=timing(durations),
        compressionRequests=timing([row["endToEndMs"] for row in answer_rows if row.get("compressionTriggered")]),
        nonCompressionRequests=timing([row["endToEndMs"] for row in answer_rows if not row.get("compressionTriggered")]),
        summaryOperations=len(summary_rows), summaryOperationsSucceeded=sum(row["ok"] for row in summary_rows),
        degradedAnswers=sum(row.get("memoryStatus") == "degraded" for row in answer_rows),
        probeCallCount=sum(call.get("phase") == "probe" for call in calls),
        # Runtime/schema metrics are NOT semantic memory-quality gates.
        semanticQualityVerified=False, recommendedConfig=None)


def review_fact_scores(expected, reviews):
    """Require explicit semantic review for every expected fact and output claim.

    reviews: factId -> {correct: bool, retained: bool, corrected: bool,
                       recentCorrect: bool}; denominators come from gold, not hits.
    Open unsupported claims are counted separately by the caller's human review.
    """
    ids = {fact.id for fact in expected}
    if set(reviews) != ids:
        raise ValueError("review_incomplete_or_unknown_fact")
    for review in reviews.values():
        if any(type(review.get(key)) is not bool for key in (
                "correct", "retained", "corrected", "recentCorrect")):
            raise ValueError("review_boolean_required")
    def rate(group, key):
        return None if not group else sum(reviews[fact.id][key] for fact in group) / len(group)
    critical = [fact for fact in expected if fact.priority == "critical"]
    general = [fact for fact in expected if fact.priority != "critical"]
    corrected = [fact for fact in expected if fact.supersedes]
    return dict(criticalDenominator=len(critical), generalDenominator=len(general),
        criticalRetention=rate(critical, "retained"), criticalAnswerAccuracy=rate(critical, "correct"),
        generalRetention=rate(general, "retained"), generalAnswerAccuracy=rate(general, "correct"),
        correctionDenominator=len(corrected), correctionAdoption=rate(corrected, "corrected"))
