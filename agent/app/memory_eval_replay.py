"""Fixed-prefix memory experiments, distinct from production conversation storage."""
import hashlib
import re
from app.memory import compact, history_messages
from app.schemas import AgentRequest, MemorySnapshot, MemorySource, SummaryRequest, MemoryPlanRequest


def request_for(trajectory, arm, prefix, summary, through, source_map, memory_status=None):
    if arm["mode"] == "messages":
        recent, pending = prefix[-(arm["messages"] // 2):], []
    elif arm["mode"] == "full":
        recent, pending = prefix, []
    else:
        n = arm["recentTurns"]
        recent = prefix[-n:]
        pending = [turn for turn in prefix[:-n] if turn.turnSeq > through]
    snapshot = MemorySnapshot(userId="fixture-user", conversationId=trajectory.id, requestId="replay",
        historyRevision=len(prefix), summaryVersion=through, policyVersion=arm["id"], summarizedThroughTurnSeq=through,
        summary=summary, recentTurns=recent, pendingTurns=pending, sourceMap=source_map, executionVersion=1,
        memoryStatus=memory_status or ("degraded" if pending else "ok"))
    return AgentRequest(userId=snapshot.userId, conversationId=snapshot.conversationId,
                        requestId="replay", memory=snapshot, query="placeholder")


def prepare_prefix(trajectory, arm, prefix, service, summary, through, sources,
                   next_trigger, strategy="threshold", on_result=None):
    """Same threshold/complete-prefix policy as Java, with durable replay state."""
    events, status = [], "ok"
    if arm["mode"] != "summary":
        return summary, through, sources, next_trigger, status, events
    settings = service.settings
    trigger = next_trigger
    target = settings.memory_trigger_tokens - settings.memory_min_growth_tokens
    while True:
        snapshot = request_for(trajectory, arm, prefix, summary, through, sources, "ok").memory
        pending = snapshot.pendingTurns
        available = dict(sources)
        for turn in pending:
            for message in (turn.user, turn.assistant):
                available[message.messageId] = MemorySource(messageId=message.messageId,
                    turnSeq=turn.turnSeq, role=message.role,
                    evidenceRefs=re.findall(r"\[引用:([a-z0-9-]+)\]", message.content))
        request = SummaryRequest(userId=snapshot.userId, conversationId=snapshot.conversationId,
            requestId=snapshot.requestId, historyRevision=snapshot.historyRevision,
            summaryVersion=snapshot.summaryVersion, policyVersion=snapshot.policyVersion,
            summarizedThroughTurnSeq=through, oldSummary=summary, turns=pending, sourceMap=available,
            summaryMaxTokens=arm["summaryMaxTokens"], sourceSnapshotHash="0" * 64) if pending else None
        plan = service.plan(MemoryPlanRequest(memory=snapshot, summaryRequest=request,
            triggerTokens=trigger if strategy == "threshold" else 0))
        events.append(dict(kind="memory_decision", memoryTokens=plan.memoryTokens,
            triggerTokens=trigger, reason=plan.reason, counter=plan.counter,
            batchTurnCount=plan.batchTurnCount))
        if plan.reason in {"below_threshold", "no_older_turns"}:
            break
        if not plan.batchTurnCount and strategy != "rolling":
            status = "degraded"
            next_trigger = max(settings.memory_trigger_tokens, plan.memoryTokens + settings.memory_min_growth_tokens)
            break
        # Historical rolling baseline keeps the former all-pending request behavior.
        batch = pending if strategy == "rolling" else pending[:plan.batchTurnCount]
        request = request.model_copy(update={"turns": batch, "sourceSnapshotHash":
            hashlib.sha256(compact([through, [turn.model_dump() for turn in batch]]).encode()).hexdigest()})
        result = service.summarize(request)
        if on_result:
            on_result(result)
        events.append(dict(kind="summary", ok=result.ok, error=result.error,
            through=result.summarizedThroughTurnSeq, scoreBoundary=result.summarizedThroughTurnSeq,
            counter=result.counter, exactCount=result.exactCount, tokens=result.summaryTokens,
            raw=result.summary.model_dump() if result.ok else None))
        if not result.ok:
            status = "degraded"
            next_trigger = max(settings.memory_trigger_tokens, plan.memoryTokens + settings.memory_min_growth_tokens)
            break
        summary, through = result.summary, result.summarizedThroughTurnSeq
        sources = {key: available[key] for key in summary.retained_sources(available)}
        after = request_for(trajectory, arm, prefix, summary, through, sources, "ok").memory
        remaining = service.counter.count(history_messages(after))
        events[-1]["memoryTokensBefore"] = plan.memoryTokens
        events[-1]["memoryTokensAfter"] = remaining
        if strategy == "rolling" or remaining <= target:
            next_trigger = settings.memory_trigger_tokens
            break
        if not after.pendingTurns or plan.memoryTokens - remaining < settings.memory_min_growth_tokens:
            next_trigger = max(settings.memory_trigger_tokens, remaining + settings.memory_min_growth_tokens)
            break
        trigger = target + 1
    return summary, through, sources, next_trigger, status, events


def assert_pure_memory_request(request):
    # Legitimate fixed history and current question are allowed. External metadata
    # is forbidden even when a test author believes it contains no answer.
    fields = ("manualId", "manualRefId", "manualName", "manualType", "manualBrand", "manualModel", "productId")
    if any(getattr(request, field) for field in fields) or request.history:
        raise ValueError("pure_memory_external_hint_detected")


def score_text(text, facts):
    """A lexical triage screen, NEVER a semantic evaluation result."""
    return [dict(factId=fact.id, key=fact.key, priority=fact.priority,
        screenPass=any(value in text for value in fact.acceptable) and not any(value in text for value in fact.forbidden),
        forbiddenDetected=any(value in text for value in fact.forbidden), requiresHumanReview=True) for fact in facts]
