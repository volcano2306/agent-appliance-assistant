"""Semantic review aggregation and frozen gates; no automatic LLM grading."""
from app.memory_eval_metrics import review_fact_scores


def aggregate_review(facts, reviews, claims, *, recent_ids=(), leakage=0,
                     false_write_claims=0, unauthorized_writes=0):
    result = review_fact_scores(facts, reviews)
    if any(type(claim.get("supported")) is not bool for claim in claims):
        raise ValueError("atomic_claim_review_required")
    unsupported = sum(not claim["supported"] for claim in claims)
    result.update(claimDenominator=len(claims), unsupportedClaimCount=unsupported,
        factPrecision=None if not claims else 1 - unsupported / len(claims),
        unsupportedFactRate=None if not claims else unsupported / len(claims),
        crossSessionLeakage=leakage, falseWriteClaims=false_write_claims, unauthorizedWrites=unauthorized_writes)
    fact_ids = {fact.id for fact in facts}
    if not set(recent_ids) <= fact_ids: raise ValueError("unknown_recent_fact")
    cancelled = [fact for fact in facts if fact.key == "操作限制"]
    result["cancellationAdoption"] = None if not cancelled else sum(reviews[f.id]["correct"] for f in cancelled) / len(cancelled)
    result["recentDetailAccuracy"] = None if not recent_ids else sum(reviews[id]["recentCorrect"] for id in recent_ids) / len(recent_ids)
    return result


def full_comparison(candidate, full):
    """Both maps use the SAME atomic probe keys. FULL N/A is represented by None."""
    common = [key for key in candidate if key in full and full[key] is not None and candidate[key] is not None]
    if not common: return dict(answerDropVsFull=None, commonDenominator=0, fullCoverage=0)
    if any(type(candidate[key]) is not bool or type(full[key]) is not bool for key in common):
        raise ValueError("comparison_boolean_required")
    return dict(answerDropVsFull=(sum(full[key] for key in common) - sum(candidate[key] for key in common)) / len(common),
                commonDenominator=len(common), fullCoverage=len(common) / len(candidate))


def quality_gate_failures(metrics, gates):
    lower = ("criticalRetention", "criticalAnswerAccuracy", "generalRetention", "generalAnswerAccuracy",
        "correctionAdoption", "cancellationAdoption", "recentDetailAccuracy", "committedSchemaRate",
        "committedSizeRate", "summaryOperationSuccess")
    upper = {"unsupportedFactRate": "unsupportedFactRateMax", "crossSessionLeakage": "crossSessionLeakageMax",
        "falseWriteClaims": "falseWriteClaimsMax", "answerDropVsFull": "answerDropVsFullMax"}
    failed = [key for key in lower if metrics.get(key) is None or metrics[key] < gates[key]]
    failed += [key for key, gate in upper.items() if metrics.get(key) is None or metrics[key] > gates[gate]]
    if metrics.get("unauthorizedWrites") is None or metrics["unauthorizedWrites"] != 0: failed.append("unauthorizedWrites")
    return failed


def choose_configuration(groups, gates, *, complete_real_matrix, human_review_complete):
    if not complete_real_matrix or not human_review_complete:
        return dict(recommendedConfig=None, reason="incomplete_matrix_or_semantic_review")
    qualified = [group for group in groups if not quality_gate_failures(group, gates)]
    if not qualified: return dict(recommendedConfig=None, reason="no_quality_gate_pass")
    if any(group.get("actualTotalCost") is None or group.get("p95Ms") is None for group in qualified):
        return dict(recommendedConfig=None, reason="unknown_cost_or_latency")
    cheapest = min(group["actualTotalCost"] for group in qualified)
    tied = [group for group in qualified if group["actualTotalCost"] <= cheapest * 1.05]
    selected = min(tied, key=lambda group: (group["p95Ms"], -group["generalAnswerAccuracy"], group["id"]))
    return dict(recommendedConfig=selected["id"], reason="qualified_actual_cost_then_p95")
