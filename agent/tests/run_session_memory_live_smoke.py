"""Small authorized real-model compatibility smoke; business tools remain doubles."""
import argparse
from pathlib import Path
import sys
from urllib.parse import urlparse

parser = argparse.ArgumentParser()
parser.add_argument("--live", action="store_true")
parser.add_argument("--use-project-model", action="store_true")
parser.add_argument("--context-window", type=int)
parser.add_argument("--max-calls", type=int, default=12)
parser.add_argument("--max-total-tokens", type=int, default=200000)
parser.add_argument("--max-duration", type=int, default=300)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
if not args.live or not args.use_project_model:
    raise SystemExit("This smoke requires explicit live and authorized project-model flags")
if min(args.max_calls, args.max_total_tokens, args.max_duration) < 1:
    raise SystemExit("All hard budgets must be positive")
sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from session_memory_model_config import load_authorized_project_model, verified_context_window, redact_model_secret
try:
    credentials = load_authorized_project_model()
except ValueError:
    raise SystemExit("Project model configuration unavailable; no API call performed") from None
window = args.context_window or verified_context_window(credentials)
if not window:
    raise SystemExit("Model loaded privately, but its context window needs explicit confirmation; no API call performed")
from session_memory_guard import configure_live_network, install, guard_settings
try:
    provider = urlparse(credentials["BASE_URL"])
    configure_live_network(provider.hostname, provider.port or 443)
except Exception:
    raise SystemExit("Provider DNS unavailable; no API call performed") from None
install()
guard_settings()

import hashlib
import json
from openai import OpenAI
from app.agent import AgentService
from app.config import Settings
from app.memory import SummaryService, compact
from app.memory_eval_metrics import atomic_json, execution_metrics
from app.memory_eval_runtime import BudgetStop, MeteredClient
from app.schemas import (AgentRequest, CompleteTurn, MemorySnapshot, MemorySource,
                         MemoryToolResult, ObservedWriteOutcome, SummaryRequest, ToolResult, TurnMessage)


def redact(value):
    return redact_model_secret(value, credentials["API_KEY"])


class DisabledTools:
    def __init__(self): self.calls = []
    def execute(self, name, arguments, context):
        self.calls.append(dict(name=name, arguments=arguments))
        return ToolResult(ok=False, error="smoke_business_tools_disabled",
            write_status="not_dispatched" if name in {"create_repair_order", "add_manual_to_account"} else "")


specs = [
    dict(id="correction-and-cancellation", cap=600, messages=[
        ("我的洗衣机型号是W-A01。", "已记录用户提供的型号。"),
        ("型号说错了，是W-A02；暂时不要报修。", "按最新型号讨论，暂不提交报修。")],
        query="请复述我当前确认的型号和报修意愿。不要执行任何工具。"),
    dict(id="suggestion-not-execution", cap=1000, messages=[
        ("我的洗衣机排水不畅，想先了解安全的检查办法。", "建议断电后查看外露排水管；不要拆机。")],
        query="你之前建议的检查，我已经执行过了吗？只依据历史回答，不执行工具。"),
    dict(id="unknown-write-outcome", cap=300, messages=[
        ("之前那次报修请求现在是什么状态？", "请求已发出，但尚未获得可确认的业务结果。")],
        query="之前的报修已经创建成功了吗？只说明已知状态，不重试或执行工具。"),
]
if args.output.exists():
    raise SystemExit("Output already exists; choose a new directory")
args.output.mkdir(parents=True)
client = MeteredClient(OpenAI(api_key=credentials["API_KEY"], base_url=credentials["BASE_URL"],
    max_retries=0, timeout=30), max_calls=args.max_calls, max_tokens=args.max_total_tokens,
    max_duration=args.max_duration, min_interval=1, journal=args.output / "calls.json")
state = dict(kind="real-model-compatibility-smoke", credentialSource="authorized-project-model",
    model=credentials["MODEL"], providerHost=provider.hostname, confirmedContextWindow=window,
    maxCalls=args.max_calls, maxTotalTokens=args.max_total_tokens, maxDuration=args.max_duration,
    rows=[], status="running", completeRealMatrix=False, recommendedConfig=None,
    realBusinessWrites=0, semanticQualityVerified=False)


def save():
    atomic_json(args.output / "state.json", redact(state))


try:
    for spec in specs:
        settings = Settings(_env_file=None, deepseek_api_key=credentials["API_KEY"],
            deepseek_base_url=credentials["BASE_URL"], deepseek_model=credentials["MODEL"],
            memory_enabled=True, memory_model_context_window=window, memory_summary_max_tokens=spec["cap"])
        turns = [CompleteTurn(turnSeq=i, requestId=f"fixture-{i}",
            user=TurnMessage(messageId=f"u{i}", role="user", content=user),
            assistant=TurnMessage(messageId=f"a{i}", role="assistant", content=answer))
            for i, (user, answer) in enumerate(spec["messages"], 1)]
        sources = {message.messageId: MemorySource(messageId=message.messageId, turnSeq=turn.turnSeq, role=message.role)
            for turn in turns for message in (turn.user, turn.assistant)}
        receipts = []
        if spec["id"] == "unknown-write-outcome":
            sources["t1"] = MemorySource(messageId="t1", turnSeq=1, role="tool")
            receipts = [MemoryToolResult(source="t1", receipt=ObservedWriteOutcome(
                name="create_repair_order", writeStatus="unknown", target="synthetic-device", identifier="",
                requestId="synthetic-previous-request"))]
        summary_request = SummaryRequest(userId="fixture-user", conversationId=spec["id"], requestId="smoke",
            historyRevision=len(turns), summaryVersion=0, policyVersion="compatibility-smoke-v1",
            summarizedThroughTurnSeq=0, turns=turns, sourceMap=sources, summaryMaxTokens=spec["cap"],
            sourceSnapshotHash=hashlib.sha256(compact(spec).encode()).hexdigest(), toolResults=receipts)
        client.tag = dict(case=spec["id"], phase="memory_summary")
        start = len(client.calls)
        result = SummaryService(settings, client=client).summarize(summary_request)
        client.annotate(start, result.usage)
        state["rows"].append(dict(case=spec["id"], kind="summary", ok=result.ok, error=result.error,
            counter=result.counter, exactCount=result.exactCount, summaryTokens=result.summaryTokens,
            summaryCap=spec["cap"], raw=result.summary.model_dump() if result.summary else None))
        save()
        snapshot = MemorySnapshot(userId="fixture-user", conversationId=spec["id"], requestId="smoke",
            historyRevision=len(turns), summaryVersion=1 if result.ok else 0, policyVersion="compatibility-smoke-v1",
            summarizedThroughTurnSeq=result.summarizedThroughTurnSeq, summary=result.summary,
            recentTurns=[] if result.ok else turns, sourceMap=sources, executionVersion=1,
            memoryStatus="ok" if result.ok else "degraded")
        tools = DisabledTools()
        service = AgentService.__new__(AgentService)
        service.settings, service.client, service.registry = settings, client, tools
        service.execution_checker = lambda request: None
        client.tag = dict(case=spec["id"], phase="answer")
        start = len(client.calls)
        answer = service.run(AgentRequest(userId="fixture-user", conversationId=spec["id"],
            requestId="smoke", query=spec["query"], memory=snapshot))
        client.annotate(start, answer.usageBreakdown)
        state["rows"].append(dict(case=spec["id"], kind="answer", status=answer.status,
            error=answer.error, raw=answer.answerText, toolCalls=tools.calls,
            usedRawFallback=not result.ok, requiresHumanReview=True))
        save()
    state["status"] = "requires_human_review"
except (BudgetStop, KeyboardInterrupt) as exc:
    state["status"] = "incomplete"
    state["stopReason"] = str(exc) if isinstance(exc, BudgetStop) else "interrupted"
except Exception:
    state["status"] = "incomplete_execution_error"
finally:
    client.client.close()
state["callCount"] = len(client.calls)
state["realModelVerified"] = any(call["status"] == "ok" for call in client.calls)
state["elapsedSeconds"] = client.elapsed
state["usage"] = dict(inputTokens=sum(call["inputTokens"] or 0 for call in client.calls),
    outputTokens=sum(call["outputTokens"] or 0 for call in client.calls),
    unknownUsageCalls=sum(call["inputTokens"] is None or call["outputTokens"] is None for call in client.calls))
save()
print(json.dumps({key: state[key] for key in ("status", "callCount", "realModelVerified", "usage")}, ensure_ascii=False))
raise SystemExit(0 if state["status"] == "requires_human_review" else 2)
