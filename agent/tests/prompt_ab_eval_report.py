"""Deterministic reports over actual traces; semantic reviews are never fabricated."""
import json
from pathlib import Path
from statistics import mean, median

from prompt_ab_eval_support import compact, digest, read_json


def ratio(numerator, denominator):
    return {"numerator": numerator, "denominator": denominator,
            "rate": numerator / denominator if denominator else None}


def review_template(traces, fixture):
    checkpoints = {p["checkpoint_id"]: p for c in fixture["cases"] for p in c["checkpoints"]}
    return {t["trace_id"]: {"trace_hash": digest(t), "items": [
        {**rubric, "status": "pending", "evidence": "", "reason": "", "reviewer": ""}
        for rubric in checkpoints[t["checkpoint_id"]]["semantic_rubric"]]} for t in traces}


def checked_review(trace, review, fixture):
    template = review_template([trace], fixture)[trace["trace_id"]]
    actual = review.get(trace["trace_id"])
    if not actual or actual.get("trace_hash") != template["trace_hash"]:
        return {"status": "pending", "critical_failures": 0, "pending_items": len(template["items"]), "error": "missing_review_or_trace_changed"}
    expected = {r["id"]: r for r in template["items"]}
    items = actual.get("items", [])
    if len(items) != len(expected) or {r.get("id") for r in items} != set(expected):
        raise ValueError("Review rubric IDs changed")
    pending, failed, critical = False, False, 0
    for item in items:
        rubric = expected[item["id"]]
        if any(item.get(k) != rubric[k] for k in ("text", "critical")):
            raise ValueError("Review criteria were modified")
        status = item.get("status")
        if status not in {"pending", "pass", "fail"}:
            raise ValueError("Review status must be pending/pass/fail")
        if status != "pending" and not all(str(item.get(k, "")).strip() for k in ("evidence", "reason", "reviewer")):
            raise ValueError("Completed semantic reviews require evidence, reason and reviewer")
        pending |= status == "pending"
        failed |= status == "fail"
        critical += int(status == "fail" and rubric["critical"])
    return {"status": "fail" if failed else "pending" if pending else "pass", "critical_failures": critical,
            "pending_items": sum(item["status"] == "pending" for item in items)}


def usage_sum(rows):
    calls = [c for t in rows for c in t["model_calls"]]
    if not calls or any(c.get("usage") is None for c in calls):
        return {"prompt_tokens": None, "completion_tokens": None, "total_tokens": None,
                "missing_usage_calls": sum(c.get("usage") is None for c in calls)}
    prompt = sum(c["usage"]["prompt_tokens"] for c in calls)
    completion = sum(c["usage"]["completion_tokens"] for c in calls)
    return {"prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": prompt + completion,
            "missing_usage_calls": 0}


def summarize(manifest, traces, review, fixture):
    trace_ids = [t["trace_id"] for t in traces]
    if len(trace_ids) != len(set(trace_ids)):
        raise ValueError("Duplicate trace ID")
    expected = {p["trace_id"]: p for p in manifest["planned"]}
    if set(trace_ids) - set(expected):
        raise ValueError("Unplanned trace found")
    if manifest["fixture_hash"] != digest(fixture):
        raise ValueError("Fixture changed since evaluation")
    cp_by_id = {p["checkpoint_id"]: p for c in fixture["cases"] for p in c["checkpoints"]}
    for t in traces:
        planned = expected[t["trace_id"]]
        if any(t[k] != planned[k] for k in ("case_id", "checkpoint_id", "variant", "repeat")):
            raise ValueError("Trace identity mismatch")
        if t["checkpoint_hash"] != digest(cp_by_id[t["checkpoint_id"]]):
            raise ValueError("Checkpoint hash mismatch")
        if t["prompt_hash"] != manifest["snapshot_hashes"][t["variant"]]:
            raise ValueError("Prompt hash mismatch")
    reviews = {t["trace_id"]: checked_review(t, review, fixture) for t in traces}
    groups = {}
    for variant in ("baseline", "candidate"):
        rows = [t for t in traces if t["variant"] == variant]
        planned = [p for p in manifest["planned"] if p["variant"] == variant]
        completed = [t for t in rows if t["status"] == "completed"]
        params = [p for t in rows for p in t["score"]["parameter_checks"]]
        sem = [reviews[t["trace_id"]] for t in rows]
        final_pass = {t["trace_id"] for t in rows
                      if t["score"]["automatic_pass"] and reviews[t["trace_id"]]["status"] == "pass"}
        cases = {}
        for case in fixture["cases"]:
            needed = {p["trace_id"] for p in planned if p["case_id"] == case["case_id"]}
            cases[case["case_id"]] = bool(needed) and needed <= final_pass
        durations = [t["seconds"] for t in rows]
        critical_keys = {k for t in rows for k in t["score"]["critical"]}
        groups[variant] = {
            "planned_checkpoints": len(planned), "executed_checkpoints": len(rows),
            "execution_completion": ratio(len(completed), len(planned)),
            "tool_decision": ratio(sum(t["score"]["tool_decision"] for t in completed), len(completed)),
            "parameter_mechanical": ratio(sum(not p["errors"] for p in params), len(params)),
            "raw_json": ratio(sum(t["status"] == "completed" and t["score"]["raw_json_valid"] for t in rows), len(rows)),
            "semantic": ratio(sum(s["status"] == "pass" for s in sem), sum(s["status"] != "pending" for s in sem)),
            "semantic_pending": sum(s["status"] == "pending" for s in sem),
            "semantic_pending_items": sum(s["pending_items"] for s in sem),
            "critical": {k: sum(t["score"]["critical"].get(k, 0) for t in rows) for k in critical_keys},
            "critical_semantic_failures": sum(s["critical_failures"] for s in sem),
            "case_pass": ratio(sum(cases.values()), len(fixture["cases"])), "cases": cases,
            "model_calls": sum(len(t["model_calls"]) for t in rows),
            "mean_model_calls": mean([len(t["model_calls"]) for t in rows]) if rows else None,
            "requested_tool_calls": sum(len(t["score"]["parameter_checks"]) for t in rows),
            "tokens": usage_sum(rows),
            "seconds": {"mean": mean(durations), "median": median(durations), "min": min(durations), "max": max(durations)} if rows else None}
    pairs = {}
    for t in traces:
        pairs.setdefault((t["checkpoint_id"], t["repeat"]), {})[t["variant"]] = t
    valid_pairs = [p for p in pairs.values() if set(p) == {"baseline", "candidate"}]
    regressions = []
    for p in valid_pairs:
        a, b = p["baseline"], p["candidate"]
        if (a["score"]["automatic_pass"] and b["status"] in {"completed", "application_error"}
                and not b["score"]["automatic_pass"]):
            regressions.append(b["trace_id"] + ":automatic")
        if reviews[a["trace_id"]]["status"] == "pass" and reviews[b["trace_id"]]["status"] == "fail":
            regressions.append(b["trace_id"] + ":semantic")
    first = {v: 0 for v in groups}
    usage_pairs = 0
    for p in valid_pairs:
        calls = {v: p[v]["model_calls"] for v in groups}
        if all(calls[v] and calls[v][0]["usage"] is not None for v in groups):
            usage_pairs += 1
            for v in groups:
                first[v] += calls[v][0]["usage"]["prompt_tokens"]
    all_planned = bool(expected) and len(traces) == len(expected) and all(t["status"] == "completed" for t in traces)
    total_a, total_b = [groups[v]["tokens"]["total_tokens"] for v in ("baseline", "candidate")]
    all_usage = (all_planned and usage_pairs == len(valid_pairs) and len(valid_pairs) * 2 == len(expected)
                 and total_a is not None and total_b is not None)
    first_reduction = (first["baseline"] - first["candidate"]) / first["baseline"] if first["baseline"] else None
    candidate = [t for t in traces if t["variant"] == "candidate"]
    behavior_fail = any(
        (t["status"] in {"completed", "application_error"} and not t["score"]["automatic_pass"])
        or any(t["score"]["critical"].values()) or reviews[t["trace_id"]]["status"] == "fail"
        for t in candidate)
    # A failed checkpoint can still contain unexercised/pending criteria.
    reviewed = all(reviews[t["trace_id"]]["pending_items"] == 0 for t in traces)
    # If the provider silently changed model versions between requests, do not accept the A/B.
    response_models = {c.get("response_model") for t in traces for c in t["model_calls"] if c.get("response_model")}
    model_consistent = len(response_models) <= 1
    accepted = (all_usage and reviewed and not behavior_fail and not regressions and model_consistent
                and first_reduction is not None and first_reduction > 0 and total_b <= total_a)
    cost_fail = all_usage and reviewed and (first_reduction is None or first_reduction <= 0 or total_b > total_a)
    return {"groups": groups, "paired_first_input": {"sums": first, "complete_pairs": usage_pairs,
                "reduction": first_reduction, "all_usage_available": all_usage},
            "regressions": regressions, "response_models": sorted(response_models),
            "model_consistent": model_consistent, "all_planned_completed": all_planned,
            "review_status": "completed" if traces and reviewed else "pending",
            "execution_errors": [t["trace_id"] for t in traces if t["status"] == "error"],
            "cost_gate_failed": bool(cost_fail),
            "acceptance_status": "passed" if accepted else "failed" if behavior_fail or regressions or not model_consistent or cost_fail else "incomplete",
            "scope": "10 fixed synthetic scenarios; not production performance or a reliability guarantee"}


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def write_report(directory, manifest, traces, review, fixture):
    metrics = summarize(manifest, traces, review, fixture)
    write_json(directory / "metrics.json", metrics)
    lines = ["# Prompt A/B evaluation", "", f"Run: {manifest['run_id']}",
             f"Live status: {manifest['live_status']}; review: {metrics['review_status']}; acceptance: {metrics['acceptance_status']}",
             "", "Real model decisions; ALL business tool responses are simulated.",
             "Missing usage is unknown, not zero. Semantic pending is NOT pass.", "",
             "| Trace | Execution | Tool | Raw JSON | Semantic |", "| --- | --- | --- | --- | --- |"]
    for t in traces:
        sem = checked_review(t, review, fixture)["status"]
        lines.append(f"| {t['trace_id']} | {t['status']} | {t['score']['tool_decision']} | {t['score']['raw_json_valid']} | {sem} |")
    lines += ["", "## Metrics", "", "```json", json.dumps(metrics, ensure_ascii=False, indent=2), "```", "",
              "## Actual final outputs", ""]
    for t in traces:
        lines += ["### " + t["trace_id"], "", "```json", json.dumps({"raw_final": t["raw_final"],
                  "tool_calls": t["tool_calls"], "score": t["score"],
                  "semantic_review": review.get(t["trace_id"])}, ensure_ascii=False, indent=2), "```", ""]
    (directory / "report.md").write_text("\n".join(lines), encoding="utf-8")
    return metrics


def finalize(directory):
    directory = Path(directory).resolve()
    manifest = read_json(directory / "manifest.json")
    traces = [json.loads(line) for line in (directory / "traces.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    # Immutable evidence hashes are recorded when execution finishes; edited reviews are separate.
    if manifest.get("trace_hashes") != {t["trace_id"]: digest(t) for t in traces}:
        raise ValueError("Recorded trace hashes do not match")
    return write_report(directory, manifest, traces, read_json(directory / "review.json"), read_json(directory / "cases.json"))
