"""Read an explicit evaluation report, write supplemental usage; never call a model."""
import argparse
import json
from pathlib import Path
import sys
parser = argparse.ArgumentParser()
parser.add_argument("--run", type=Path, required=True)
args = parser.parse_args()
sys.path.insert(0, str(Path(__file__).resolve().parent))
from session_memory_guard import install
install()
state = json.loads((args.run / "state.json").read_text(encoding="utf-8"))
calls = json.loads((args.run / "calls.json").read_text(encoding="utf-8"))["calls"]
rows = state["rows"]
def totals(selected):
    unknown = sum(c.get("inputTokens") is None or c.get("outputTokens") is None for c in selected)
    cache_unknown = sum(c.get("cachedInputTokens") is None for c in selected)
    input_n = sum(c.get("inputTokens") or 0 for c in selected)
    output_n = sum(c.get("outputTokens") or 0 for c in selected)
    cache_n = sum(c.get("cachedInputTokens") or 0 for c in selected)
    lower = None if unknown or cache_unknown else (input_n-cache_n+cache_n*.02+output_n*4)/1_000_000
    upper = None if lower is None else lower*2
    return dict(calls=len(selected), knownInputTokens=input_n, knownOutputTokens=output_n,
        totalTokens=None if unknown else input_n+output_n, unknownUsageCalls=unknown,
        cacheUnknownCalls=cache_unknown, costRangeCny=[lower,upper], accountBillVerified=False)
groups = []
for arm in state["configuration"]["selectedArms"]:
    selected_calls = [c for c in calls if c["runId"].split("/")[1] == arm]
    selected_rows = [r for r in rows if r["runId"].split("/")[1] == arm]
    summaries = [r for r in selected_rows if r["kind"] == "summary"]
    answer_rows = [r for r in selected_rows if r["kind"] == "answer"]
    expected_runs = len(state["configuration"]["selected"]) * state["configuration"]["repeats"]
    done_runs = sum(run_id.split("/")[1] == arm for run_id in state["done"])
    coverage = "not_run" if not selected_calls else "complete_selected" if done_runs == expected_runs else "partial"
    groups.append(dict(arm=arm, coverageStatus=coverage, completedRuns=done_runs, expectedRuns=expected_runs,
        **totals(selected_calls), summaries=len(summaries),
        summariesSucceeded=sum(r["ok"] for r in summaries),
        degradedAnswers=sum(r.get("memoryStatus") == "degraded" for r in answer_rows),
        failures=[dict(runId=r["runId"],turn=r["turn"],error=r.get("error")) for r in summaries if not r["ok"]]))
report = dict(status=state["status"], completeSelectedRuns=state.get("completeSelectedRuns"),
    completeRealMatrix=state.get("completeRealMatrix"), total=totals(calls), groups=groups,
    costSource="https://api-docs.deepseek.com/zh-cn/quick_start/pricing/", verifiedDate="2026-10-06",
    note="Official Flash peak/off-peak range, actual account bill not inspected; semantic gates unverified.")
(args.run/"usage-supplement.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps(report, ensure_ascii=False))
