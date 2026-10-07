"""Finalize a stopped evaluation journal without dispatching calls or changing its frozen inputs."""
import argparse
import json
from pathlib import Path
import sys
parser=argparse.ArgumentParser()
parser.add_argument("--run",type=Path,required=True)
parser.add_argument("--reason",required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root));sys.path.insert(0,str(root/"tests"))
from session_memory_guard import install
install()
from app.memory_eval_metrics import atomic_json,execution_metrics
from app.memory_eval_cost import liability_cny
state=json.loads((args.run/"state.json").read_text(encoding="utf-8"))
calls=json.loads((args.run/"calls.json").read_text(encoding="utf-8"))["calls"]
if state["status"]!="running": raise SystemExit("Only a stopped running-state journal may be finalized")
state.update(status="incomplete_operator_stop",stopReason=args.reason,completeSelectedRuns=False,
    completeMatrix=False,completeRealMatrix=False,realModelVerified=any(c["status"]=="ok" for c in calls),
    callCount=len(calls),unknownUsageCalls=sum(c["inputTokens"] is None or c["outputTokens"] is None for c in calls),
    recommendedConfig=None,finalizedBy="explicit_offline_interruption_report")
state["executionMetrics"]=execution_metrics(state["rows"],calls,state["configuration"]["manifest"]["prices"])
state["costBudget"]=dict(limitCny=state["configuration"]["maxCostCny"],occupiedCny=str(liability_cny(calls)),
    basis="frozen_peak_rates; interrupted_unknown_call_keeps_full_reservation",actualAccountBillVerified=False)
atomic_json(args.run/"state.json",state)
atomic_json(args.run/"metrics.json",state["executionMetrics"])
print(json.dumps(dict(callCount=len(calls),costBudget=state["costBudget"],unknownUsageCalls=state["unknownUsageCalls"])))
