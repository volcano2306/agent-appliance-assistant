"""Budgeted evaluation; only an explicit live flag grants one model-only dotenv read."""
import argparse
import os
from pathlib import Path
import sys
from urllib.parse import urlparse

parser = argparse.ArgumentParser()
parser.add_argument("--live", action="store_true")
parser.add_argument("--use-project-model", action="store_true", help="Authorized one-shot load of local agent/.env model fields, live only")
parser.add_argument("--offline-replay", action="store_true", help="Synthetic model only; not real-model evidence")
parser.add_argument("--split", choices=["dev", "heldout", "all"], default="dev")
parser.add_argument("--track", choices=["pure-memory", "integration"], default="pure-memory")
parser.add_argument("--limit-trajectories", type=int)
parser.add_argument("--trajectory-ids", nargs="+", help="Run named trajectories from the selected split")
parser.add_argument("--arms", nargs="+", help="Run named arms; omitted means the complete eight-arm matrix")
parser.add_argument("--repeats", type=int, default=1)
parser.add_argument("--max-calls", type=int, default=1000)
parser.add_argument("--max-total-tokens", type=int, default=2000000)
parser.add_argument("--max-duration", type=int, default=3600)
parser.add_argument("--max-cost-cny", type=float, help="CNY cap; requires frozen CNY prices in manifest")
parser.add_argument("--min-interval", type=float, default=1)
parser.add_argument("--context-window", type=int)
parser.add_argument("--output", type=Path, default=Path("agent/reports/session-memory-eval"))
parser.add_argument("--manifest", type=Path, help="Explicit frozen fixture manifest; default is the original short-history set")
parser.add_argument("--resume", action="store_true")
parser.add_argument("--compression-strategy", choices=["threshold", "rolling"], default="threshold")
parser.add_argument("--trigger-tokens", type=int, default=1500)
parser.add_argument("--min-growth-tokens", type=int, default=500)
parser.add_argument("--token-counter", choices=["deepseek-v4", "legacy-byte"], default="deepseek-v4")
args = parser.parse_args()
if args.trigger_tokens < 256 or not 0 < args.min_growth_tokens < args.trigger_tokens:
    raise SystemExit("Invalid memory threshold/growth")
if args.live and args.offline_replay:
    raise SystemExit("live and offline-replay are mutually exclusive")
if args.use_project_model and (not args.live or args.offline_replay):
    raise SystemExit("use-project-model requires live mode; offline dotenv access remains forbidden")
if args.offline_replay and args.context_window is None:
    args.context_window = 40000  # Synthetic test capacity, NOT an actual provider window.
root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root))
sys.path.insert(0, str(root / "tests"))
credentials = {key: os.environ.get("MEMORY_EVAL_" + key, "") for key in ("API_KEY", "BASE_URL", "MODEL")}
if args.use_project_model:
    from session_memory_model_config import load_authorized_project_model, verified_context_window
    try:
        credentials = load_authorized_project_model()
    except ValueError:
        raise SystemExit("Project model configuration unavailable; no model call performed") from None
    if args.context_window is None:
        args.context_window = verified_context_window(credentials)
from session_memory_guard import install, guard_settings, configure_live_network
if args.live and all(credentials.values()) and args.context_window:
    url = urlparse(credentials["BASE_URL"])
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise SystemExit("Only an explicit HTTPS provider URL without embedded credentials is allowed")
    try:
        configure_live_network(url.hostname, url.port or 443)
    except Exception:
        raise SystemExit("Provider DNS resolution failed; no model call performed")
install()
guard_settings()

import hashlib
import json
import re
import time
from types import SimpleNamespace
from app.agent import AgentService, SYSTEM_INSTRUCTIONS
from app.config import Settings
from app.memory import SummaryService, TokenCounter, compact
from app.memory_eval_data import validate_dataset
from app.memory_eval_metrics import atomic_json, execution_metrics
from app.memory_eval_runtime import MeteredClient, BudgetStop
from app.memory_eval_cost import validate_prices, liability_cny
from app.memory_eval_replay import request_for, assert_pure_memory_request, score_text, prepare_prefix
from app.schemas import MemorySource, MemorySummary, SummaryRequest, ToolResult, Source


counter = TokenCounter() if args.token_counter == "deepseek-v4" else TokenCounter(
    lambda text: len(text.encode("utf-8")), "utf8-byte-estimate-v1", False)
manifest_path = args.manifest or root / "tests/fixtures/session_memory/manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if args.max_cost_cny is not None:
    import math
    if not math.isfinite(args.max_cost_cny) or args.max_cost_cny <= 0:
        raise SystemExit("max-cost-cny must be finite and positive")
    validate_prices(manifest["prices"])
trajectories = validate_dataset(manifest)
selected = [t for t in trajectories if args.split == "all" or t.split == args.split]
if args.trajectory_ids is not None:
    requested = set(args.trajectory_ids)
    if len(requested) != len(args.trajectory_ids) or not requested <= {t.id for t in selected}:
        raise SystemExit("trajectory-ids contains duplicate, unknown or out-of-split IDs")
    selected = [t for t in selected if t.id in requested]
arms = manifest["arms"]
if args.arms is not None:
    requested = set(args.arms)
    if len(requested) != len(args.arms) or not requested <= {arm["id"] for arm in arms}:
        raise SystemExit("arms contains duplicate or unknown IDs")
    arms = [arm for arm in arms if arm["id"] in requested]
if args.limit_trajectories is not None:
    if args.limit_trajectories < 1: raise SystemExit("limit-trajectories must be positive")
    selected = selected[:args.limit_trajectories]
if args.repeats < 1 or min(args.max_calls, args.max_total_tokens, args.max_duration) < 1:
    raise SystemExit("repeats and hard budgets must be positive")
if args.min_interval < 0: raise SystemExit("min-interval cannot be negative")
answer_count = sum((len(t.turns) + len(t.probes)) for t in selected) * len(arms) * args.repeats
summary_upper = sum(max(0, len(t.turns) - arm.get("recentTurns", len(t.turns))) * 2
                    for t in selected for arm in arms if arm["mode"] == "summary") * args.repeats
# 4 tool-capable + final + optional format repair. Never claim this is actual usage.
upper = {"trajectoryArmRuns": len(selected) * len(arms) * args.repeats,
         "answerEvents": answer_count, "answerCallUpper": answer_count * 6,
         "summaryCallUpper": summary_upper, "maxCallUpper": answer_count * 6 + summary_upper}
configuration = {"manifest": manifest, "promptHash": hashlib.sha256(SYSTEM_INSTRUCTIONS.encode()).hexdigest(),
                 "selected": [t.id for t in selected], "repeats": args.repeats,
                 "selectedArms": [arm["id"] for arm in arms],
                 "model": credentials["MODEL"], "providerHost": urlparse(credentials["BASE_URL"]).hostname,
                 "contextWindow": args.context_window, "counter": counter.name,
                 "compressionStrategy": args.compression_strategy, "triggerTokens": args.trigger_tokens,
                 "minGrowthTokens": args.min_growth_tokens,
                 "track": args.track, "maxCalls": args.max_calls, "maxTokens": args.max_total_tokens,
                 "maxDuration": args.max_duration, "interval": args.min_interval, "maxCostCny": args.max_cost_cny}
configuration["mode"] = "offline-replay" if args.offline_replay else "live"
configuration["credentialSource"] = "authorized-project-model" if args.use_project_model else "explicit-process"
configuration["codeHashes"] = {name: hashlib.sha256((root / name).read_bytes()).hexdigest()
    for name in ("app/agent.py", "app/memory.py", "app/memory_tokens.py", "app/schemas.py", "app/config.py",
                 "app/memory_eval_data.py", "app/memory_eval_metrics.py", "app/memory_eval_runtime.py", "app/memory_eval_replay.py",
                 "app/memory_eval_quality.py", "app/memory_eval_review.py", "app/memory_eval_cost.py",
                 "tests/run_session_memory_eval.py", "tests/session_memory_model_config.py")}
config_hash = hashlib.sha256(compact(configuration).encode()).hexdigest()
args.output.mkdir(parents=True, exist_ok=True)
if not args.offline_replay and (not args.live or not all(credentials.values()) or not args.context_window):
    report = {"mode": "dry-run", "realModelVerified": False, "configHash": config_hash,
              "selectedTrajectories": configuration["selected"], "selectedArms": configuration["selectedArms"],
              "budget": upper, "status": "ready" if not args.live else "blocked_missing_explicit_config",
              "metrics": None, "requiredVariables": ["MEMORY_EVAL_API_KEY", "MEMORY_EVAL_BASE_URL", "MEMORY_EVAL_MODEL"],
              "contextWindowRequired": True, "businessTools": "fixed doubles only", "dotenvRead": False}
    (args.output / "dry-run.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(0 if not args.live else 2)


class FixedTools:
    def __init__(self, integration=False):
        self.integration = integration
    def execute(self, name, arguments, context):
        if self.integration and name in {"manual_search", "diagnose_fault"}:
            source = Source(kind="manual", fragmentId="synthetic-manual-f2", page=2,
                snippet="断电后查看外观是否异常；不得拆机。", title="合成说明书")
            return ToolResult(ok=True, data={"evidence": source.snippet}, sources=[source])
        return ToolResult(ok=False, error="no_manual_evidence" if name in {"manual_search", "diagnose_fault"} else
                          "explicit_confirmation_required" if name == "create_repair_order" else "login_required",
                          write_status="not_dispatched" if name in {"create_repair_order", "add_manual_to_account"} else None)


class SyntheticModel:
    """Protocol double; deliberately not capable of proving semantic quality."""
    def __init__(self):
        self.chat = SimpleNamespace(completions=self)
    def close(self):
        pass
    def create(self, **kwargs):
        messages = kwargs["messages"]
        if "历史摘要组件" in messages[0]["content"]:
            payload = json.loads(messages[1]["content"])
            turn = payload["turns"][-1]
            value = dict(device=[dict(text=turn["user"]["content"][:12], source=turn["user"]["messageId"])],
                currentIssue=[], attempts=[], constraints=[], openQuestions=[], evidenceRefs=[], background=[])
        else:
            value = dict(answerText="合成测试回答；不执行任何操作。", insufficientContext=True,
                sourceFragmentIds=[], sources=[], structuredAnswer=dict(summary="合成测试回答", steps=[], cautions=[], basisNote=""))
        return SimpleNamespace(choices=[SimpleNamespace(finish_reason="stop", message=SimpleNamespace(
            content=compact(value), tool_calls=[]))], usage=SimpleNamespace(prompt_tokens=100,
            completion_tokens=20, prompt_cache_hit_tokens=0))


def configure_track(request, trajectory):
    if args.track == "pure-memory":
        assert_pure_memory_request(request)
    else:
        spec = next(spec for spec in manifest["trajectories"] if spec["id"] == trajectory.id)
        # Metadata is an independent fixture source. Keep the initial model even
        # after user corrections; integration must still respect latest raw text.
        request.manualId = "synthetic-manual"; request.manualName = "合成说明书"
        request.manualModel = spec["model"]


def persist_state(state):
    from session_memory_model_config import redact_model_secret
    atomic_json(args.output / "state.json", redact_model_secret(state, credentials["API_KEY"]))


state_file = args.output / "state.json"
if args.resume and state_file.exists():
    state = json.loads(state_file.read_text(encoding="utf-8"))
    if state.get("configHash") != config_hash: raise SystemExit("Resume rejected: configuration changed")
else:
    if state_file.exists(): raise SystemExit("Output already contains a run; use --resume or a new directory")
    state = {"configHash": config_hash, "configuration": configuration, "mode": configuration["mode"], "track": args.track, "done": [], "rows": [], "status": "running", "current": None}
from openai import OpenAI
journal = args.output / "calls.json"
saved_calls = json.loads(journal.read_text(encoding="utf-8")) if args.resume and journal.exists() else {}
model_client = SyntheticModel() if args.offline_replay else OpenAI(
    api_key=credentials["API_KEY"], base_url=credentials["BASE_URL"], timeout=30, max_retries=0)
client = MeteredClient(model_client,
    max_calls=args.max_calls, max_tokens=args.max_total_tokens, max_duration=args.max_duration,
    min_interval=args.min_interval, journal=journal, calls=saved_calls.get("calls"),
    prior_elapsed=max(state.get("elapsedSeconds", 0), saved_calls.get("elapsedSeconds", 0)),
    counter=counter, max_cost_cny=args.max_cost_cny, prices=manifest["prices"])
interrupted = False
row_start = None
try:
    for repeat in range(args.repeats):
        for trajectory in selected:
            for arm in arms:
                run_id = f"{trajectory.id}/{arm['id']}/{repeat}"
                if run_id in state["done"]: continue
                settings = Settings(_env_file=None,
                    deepseek_api_key="synthetic-not-a-secret" if args.offline_replay else credentials["API_KEY"],
                    deepseek_base_url="https://synthetic.invalid/v1" if args.offline_replay else credentials["BASE_URL"],
                    deepseek_model="synthetic-memory-model" if args.offline_replay else credentials["MODEL"],
                    memory_enabled=True, memory_model_context_window=args.context_window,
                    memory_recent_turns=arm.get("recentTurns", 5), memory_summary_max_tokens=arm.get("summaryMaxTokens", 600),
                    memory_compression_strategy=args.compression_strategy, memory_trigger_tokens=args.trigger_tokens,
                    memory_min_growth_tokens=args.min_growth_tokens)
                summarizer = SummaryService(settings, client=client, counter=counter)
                client.counter = counter
                service = AgentService.__new__(AgentService); service.settings = settings; service.client = client
                service.token_counter = counter
                service.registry = FixedTools(args.track == "integration"); service.execution_checker = lambda request: None
                summary, through, source_map, start_seq = None, 0, {}, 1
                next_trigger = args.trigger_tokens
                if state["current"] and state["current"].get("runId") == run_id:
                    current = state["current"]; summary = MemorySummary.model_validate(current["summary"]) if current["summary"] else None
                    through = current["through"]; source_map = {k: MemorySource.model_validate(v) for k, v in current["sourceMap"].items()}
                    start_seq = current["nextSeq"]
                    next_trigger = current["nextTriggerTokens"]
                for turn in trajectory.turns[start_seq - 1:]:
                    row_start = len(state["rows"])
                    turn_started = time.monotonic()
                    prefix = list(trajectory.turns[:turn.turnSeq - 1])
                    client.tag = {"runId": run_id, "turn": turn.turnSeq, "phase": "memory_summary"}
                    operation_start = len(client.calls)
                    def annotate_summary(result):
                        global operation_start
                        client.annotate(operation_start, result.usage)
                        operation_start = len(client.calls)
                    summary, through, source_map, next_trigger, memory_status, events = prepare_prefix(
                        trajectory, arm, prefix, summarizer, summary, through, source_map,
                        next_trigger, args.compression_strategy, annotate_summary)
                    for event in events:
                        if event["kind"] == "summary":
                            event["facts"] = score_text(compact(event["raw"]), trajectory.active(event["scoreBoundary"])) if event["ok"] else []
                        state["rows"].append({"runId": run_id, "turn": turn.turnSeq, **event})
                    request = request_for(trajectory, arm, prefix, summary, through, source_map, memory_status)
                    request.query = turn.user.content
                    configure_track(request, trajectory)
                    client.tag = {"runId": run_id, "turn": turn.turnSeq, "phase": "answer"}
                    call_start = len(client.calls)
                    began = time.monotonic(); answer = service.run(request)
                    client.annotate(call_start, answer.usageBreakdown)
                    # Generated answers are logged but NEVER appended to the next fixed prefix.
                    state["rows"].append({"runId": run_id, "turn": turn.turnSeq, "kind": "answer", "status": answer.status,
                        "error": answer.error, "memoryStatus": answer.memoryStatus, "raw": answer.answerText,
                        "compressionTriggered": any(event["kind"] == "summary" for event in events),
                        "applicable": not (arm["mode"] == "full" and answer.error == "context_budget_exceeded"),
                        "toolCalls": answer.toolCalls,
                        "observedWriteOutcomes": [value.model_dump() for value in answer.observedWriteOutcomes],
                        "endToEndMs": (time.monotonic() - turn_started) * 1000})
                    if turn.turnSeq in trajectory.probes:
                        # Independent probe branch: its output never becomes a replay turn.
                        probe = request_for(trajectory, arm, list(trajectory.turns[:turn.turnSeq]), summary, through, source_map, memory_status)
                        probe.query = "回顾这次对话已确认的设备型号、预算、操作限制和检查状态，以及其他仍有效的条件。未知就说未知，不执行任何工具。"
                        configure_track(probe, trajectory)
                        client.tag = {"runId": run_id, "turn": turn.turnSeq, "phase": "probe"}
                        call_start = len(client.calls)
                        result = service.run(probe)
                        client.annotate(call_start, result.usageBreakdown)
                        state["rows"].append({"runId": run_id, "turn": turn.turnSeq, "kind": "probe", "raw": result.answerText,
                            "status": result.status, "facts": score_text(result.answerText, trajectory.active(turn.turnSeq)),
                            "applicable": not (arm["mode"] == "full" and result.error == "context_budget_exceeded"),
                            "toolCalls": result.toolCalls,
                            "observedWriteOutcomes": [value.model_dump() for value in result.observedWriteOutcomes],
                            "requiresHumanReview": True})
                    state["current"] = {"runId": run_id, "nextSeq": turn.turnSeq + 1, "summary": summary.model_dump() if summary else None,
                                        "through": through, "nextTriggerTokens": next_trigger, "sourceMap": {k: v.model_dump() for k, v in source_map.items()}}
                    state["elapsedSeconds"] = client.elapsed
                    persist_state(state)
                    row_start = None
                state["done"].append(run_id); state["current"] = None; persist_state(state)
except (BudgetStop, KeyboardInterrupt) as exc:
    interrupted = True
    if row_start is not None: state["rows"] = state["rows"][:row_start]
    state["stopReason"] = str(exc) if isinstance(exc, BudgetStop) else "interrupted"
    state["status"] = "incomplete_model_configuration" if state["stopReason"] == "fatal_model_api_configuration_error" else "incomplete_budget_or_interrupt"
except Exception as exc:
    interrupted = True
    if row_start is not None: state["rows"] = state["rows"][:row_start]
    state["status"] = "incomplete_execution_error"  # Do not log raw SDK/credential errors.
    # Record code locations only; exception values and frame locals may contain secrets.
    import traceback
    state["executionError"] = {"type": type(exc).__name__, "frames": [
        {"file": Path(frame.filename).name, "line": frame.lineno, "function": frame.name}
        for frame in traceback.extract_tb(exc.__traceback__)]}
finally:
    client.client.close()
client._save()  # Also persist an empty journal when a budget stops the first call.
state["status"] = state["status"] if interrupted else "requires_human_review"
state["realModelVerified"] = not args.offline_replay and any(call["status"] == "ok" for call in client.calls)
state["completeSelectedRuns"] = not interrupted and len(state["done"]) == len(selected) * len(arms) * args.repeats
state["completeMatrix"] = args.split == "all" and args.repeats >= 3 and len(selected) >= 12 and len(arms) == len(manifest["arms"]) and not interrupted and len(state["done"]) >= 288
state["completeRealMatrix"] = state["completeMatrix"] and state["realModelVerified"]
state["callCount"] = len(client.calls)
state["unknownUsageCalls"] = sum(c["inputTokens"] is None or c["outputTokens"] is None for c in client.calls)
state["actualTotalCost"] = None  # Prices and semantic review must be supplied, never invented.
state["recommendedConfig"] = None
state["executionMetrics"] = execution_metrics(state["rows"], client.calls, manifest["prices"])
state["executionMetrics"]["usageKind"] = "synthetic" if args.offline_replay else "provider"
if args.max_cost_cny is not None:
    state["costBudget"] = {"limitCny": args.max_cost_cny, "occupiedCny": str(liability_cny(client.calls)),
        "basis": "frozen_peak_rates; unknown_usage_retains_reservation", "actualAccountBillVerified": False}
atomic_json(args.output / "metrics.json", state["executionMetrics"])
with (args.output / "calls.jsonl").open("w", encoding="utf-8") as stream:
    for call in client.calls: stream.write(compact(call) + "\n")
state["elapsedSeconds"] = client.elapsed
persist_state(state)
(args.output / "report.md").write_text("# Session memory evaluation\n\n"
    f"Status: {state['status']}\n\nActual model calls: {len(client.calls)}\n\n"
    f"Credential source: {configuration['credentialSource']}. Only the selected model host was allowed; all business tools were fixed doubles.\n\n"
    f"Track: {args.track}. Integration metadata/RAG evidence must not be reported as pure memory capability. Lexical screening requires human review; no best parameters selected.\n",
    encoding="utf-8")
print(json.dumps({key: state[key] for key in ("status", "realModelVerified", "completeSelectedRuns", "completeMatrix", "callCount", "unknownUsageCalls", "recommendedConfig")}, ensure_ascii=False))
raise SystemExit(2 if interrupted else 0)
