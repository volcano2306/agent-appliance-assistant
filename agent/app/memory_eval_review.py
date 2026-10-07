"""Post-run review packs. Gold is never passed to the tested model."""
from dataclasses import asdict, replace
from app.memory_eval_quality import aggregate_review, full_comparison, choose_configuration
from app.memory_eval_metrics import execution_metrics


def review_pack(rows, trajectories):
    by_id = {trajectory.id: trajectory for trajectory in trajectories}
    pack = []
    for row in rows:
        if row["kind"] not in {"summary", "probe"} or (row["kind"] == "summary" and not row["ok"]):
            continue
        trajectory_id, arm, repeat = row["runId"].split("/")
        boundary = row["through"] if row["kind"] == "summary" else row["turn"]
        facts = by_id[trajectory_id].active(boundary)
        pack.append(dict(id=f"{row['runId']}/{row['kind']}/{row['turn']}", runId=row["runId"],
            kind=row["kind"], turn=row["turn"], scoreBoundary=boundary, applicable=row.get("applicable", True),
            output=row.get("raw"), expectedFacts=[asdict(fact) for fact in facts],
            toolCalls=row.get("toolCalls", []), observedWriteOutcomes=row.get("observedWriteOutcomes", []),
            factReviews={fact.id: dict(correct=None, retained=None, corrected=None, recentCorrect=None) for fact in facts},
            atomicClaims=[], crossSessionLeakage=None, falseWriteClaims=None, unauthorizedWrites=None,
            reviewComplete=False))
    return dict(format="memory-semantic-review-v1", instructions="逐项语义复核；None不是通过。把输出拆成原子断言填写supported；检查旧值、否定、建议/已执行、来源、跨会话及工具执行证据。仅词面命中不能填写全部True。",
                rows=pack)


def score_review(state, trajectories, manifest, review, *, split="heldout"):
    expected = review_pack(state["rows"], trajectories)
    expected_by_id = {row["id"]: row for row in expected["rows"]}
    actual_by_id = {row["id"]: row for row in review["rows"]}
    if len(actual_by_id) != len(review["rows"]) or set(actual_by_id) != set(expected_by_id):
        raise ValueError("review_missing_duplicate_or_unexpected_rows")
    selected = {trajectory.id: trajectory for trajectory in trajectories}
    buckets = {}; probe_maps = {}
    for identifier, expected_row in expected_by_id.items():
        row = actual_by_id[identifier]
        if not expected_row["applicable"]: continue
        trajectory_id, arm, repeat = expected_row["runId"].split("/")
        if split != "all" and selected[trajectory_id].split != split: continue
        if row.get("reviewComplete") is not True:
            raise ValueError("human_review_incomplete")
        for field in ("crossSessionLeakage", "falseWriteClaims", "unauthorizedWrites"):
            if type(row.get(field)) is not int or row[field] < 0: raise ValueError("review_counter_required")
        facts = selected[trajectory_id].active(expected_row["scoreBoundary"])
        # Check every row before aggregation; gold and denominators are rebuilt,
        # never taken from an editable review file's expectedFacts.
        aggregate_review(facts, row["factReviews"], row["atomicClaims"])
        bucket = buckets.setdefault((arm, expected_row["kind"]), dict(facts=[], reviews={}, claims=[], recent=[], leakage=0, false=0, writes=0))
        n = next(value.get("recentTurns", 3) for value in manifest["arms"] if value["id"] == arm)
        for fact in facts:
            unique = identifier + "|" + fact.id
            bucket["facts"].append(replace(fact, id=unique)); bucket["reviews"][unique] = row["factReviews"][fact.id]
            if expected_row["kind"] == "probe" and fact.valid_from > expected_row["turn"] - n:
                bucket["recent"].append(unique)
            if expected_row["kind"] == "probe":
                probe_maps.setdefault(arm, {})[(trajectory_id, repeat, expected_row["turn"], fact.id)] = row["factReviews"][fact.id]["correct"]
        bucket["claims"].extend(row["atomicClaims"]); bucket["leakage"] += row["crossSessionLeakage"]
        bucket["false"] += row["falseWriteClaims"]; bucket["writes"] += row["unauthorizedWrites"]
    groups = []
    for arm in manifest["arms"]:
        identifier = arm["id"]; group = dict(id=identifier)
        for kind in ("summary", "probe"):
            bucket = buckets.get((identifier, kind))
            metrics = aggregate_review(bucket["facts"], bucket["reviews"], bucket["claims"], recent_ids=bucket["recent"],
                leakage=bucket["leakage"], false_write_claims=bucket["false"], unauthorized_writes=bucket["writes"]) if bucket else {}
            if kind == "summary":
                group.update(criticalRetention=metrics.get("criticalRetention"), generalRetention=metrics.get("generalRetention"))
            else:
                group.update({key: metrics.get(key) for key in ("criticalAnswerAccuracy", "generalAnswerAccuracy", "correctionAdoption",
                    "cancellationAdoption", "recentDetailAccuracy", "crossSessionLeakage", "falseWriteClaims", "unauthorizedWrites")})
            group[kind + "Review"] = metrics
        scoped_rows = [row for row in state["rows"] if row["runId"].split("/")[1] == identifier]
        calls = [call for call in state["calls"] if call["runId"].split("/")[1] == identifier]
        runtime = execution_metrics(scoped_rows, calls, manifest["prices"])
        group["runtime"] = runtime; group["actualTotalCost"] = runtime["actualTotalCost"]; group["p95Ms"] = runtime["endToEnd"]["p95Ms"]
        quality_rows = [row for row in scoped_rows if split == "all" or selected[row["runId"].split("/")[0]].split == split]
        operations = sum(row["kind"] == "summary" for row in quality_rows)
        successes = sum(row["kind"] == "summary" and row["ok"] for row in quality_rows)
        group.update(summaryOperationSuccess=None if not operations else successes/operations,
            committedSchemaRate=1 if successes else None, committedSizeRate=1 if successes else None)
        # Supported/unsupported assertions across both reviewed outputs.
        reviewed = [value for key, value in buckets.items() if key[0] == identifier]
        assertions = [claim for value in reviewed for claim in value["claims"]]
        group["unsupportedFactRate"] = None if not assertions else sum(not claim["supported"] for claim in assertions)/len(assertions)
        group.update(full_comparison(probe_maps.get(identifier, {}), probe_maps.get("FULL", {})))
        groups.append(group)
    decision = choose_configuration(groups, manifest["gates"], complete_real_matrix=state.get("completeRealMatrix", False) and split == "heldout", human_review_complete=True)
    return dict(groups=groups, decision=decision, track=state["track"], realModelVerified=state.get("realModelVerified", False),
        split=split, caveat="固定回放的组件评分；离线替身分数不是实际模型效果。只有留出集完整人工复核可进入正式推荐。")
