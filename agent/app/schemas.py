from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class MemoryContract(BaseModel):
    """Strict internal DTOs: unknown fields cannot become trusted metadata."""
    model_config = ConfigDict(extra="forbid", strict=True)


class MemoryFact(MemoryContract):
    text: str = Field(min_length=1)
    source: str = Field(min_length=1)


class MemoryAttempt(MemoryContract):
    action: str = Field(min_length=1)
    result: str
    state: Literal["suggested", "reported_done", "tool_observed"]
    source: str = Field(min_length=1)


class MemorySummary(MemoryContract):
    device: list[MemoryFact]
    currentIssue: list[MemoryFact]
    attempts: list[MemoryAttempt]
    constraints: list[MemoryFact]
    openQuestions: list[MemoryFact]
    evidenceRefs: list[str]
    background: list[MemoryFact]

    def referenced_sources(self) -> set[str]:
        return {item.source for group in (
            self.device, self.currentIssue, self.attempts, self.constraints,
            self.openQuestions, self.background,
        ) for item in group}

    def retained_sources(self, source_map) -> set[str]:
        needed = self.referenced_sources()
        for ref in sorted(set(self.evidenceRefs)):
            if any(key in source_map and ref in source_map[key].evidenceRefs for key in needed):
                continue
            carriers = sorted((source.turnSeq, key) for key, source in source_map.items()
                              if ref in source.evidenceRefs)
            if not carriers:
                raise ValueError("memory_evidence_source_missing")
            needed.add(carriers[0][1])
        return needed


class MemorySource(MemoryContract):
    messageId: str = Field(min_length=1)
    turnSeq: int = Field(ge=1)
    role: Literal["user", "assistant", "tool"]
    evidenceRefs: list[str] = Field(default_factory=list)


class TurnMessage(MemoryContract):
    messageId: str = Field(min_length=1)
    role: Literal["user", "assistant", "ai"]
    content: str


class CompleteTurn(MemoryContract):
    turnSeq: int = Field(ge=1)
    requestId: str = Field(min_length=1)
    user: TurnMessage
    assistant: TurnMessage

    @model_validator(mode="after")
    def paired_roles(self):
        if self.user.role != "user" or self.assistant.role not in {"assistant", "ai"}:
            raise ValueError("memory_turn_roles_invalid")
        if self.user.messageId == self.assistant.messageId:
            raise ValueError("memory_message_ids_duplicate")
        return self


class MemorySnapshot(MemoryContract):
    userId: str = Field(min_length=1)
    conversationId: str = Field(min_length=1)
    requestId: str = Field(min_length=1)
    historyRevision: int = Field(ge=0)
    summaryVersion: int = Field(ge=0)
    policyVersion: str = Field(min_length=1)
    summarizedThroughTurnSeq: int = Field(ge=0)
    summary: MemorySummary | None = None
    recentTurns: list[CompleteTurn] = Field(default_factory=list)
    pendingTurns: list[CompleteTurn] = Field(default_factory=list)
    sourceMap: dict[str, MemorySource] = Field(default_factory=dict)
    executionVersion: int | None = Field(default=None, ge=1)
    memoryStatus: Literal["ok", "degraded", "disabled"] = "ok"

    @model_validator(mode="after")
    def consistent_ranges(self):
        turns = [*self.pendingTurns, *self.recentTurns]
        sequences = [turn.turnSeq for turn in turns]
        if sequences != sorted(set(sequences)) or any(
            sequence <= self.summarizedThroughTurnSeq for sequence in sequences
        ):
            raise ValueError("memory_ranges_overlap_or_unordered")
        if self.summary is None and self.summarizedThroughTurnSeq != 0:
            raise ValueError("memory_summary_missing")
        if self.summary is not None and not self.summary.referenced_sources() <= self.sourceMap.keys():
            raise ValueError("memory_source_unknown")
        if self.summary is not None:
            self.summary.retained_sources(self.sourceMap)
        return self


class MemoryToolResult(MemoryContract):
    source: str = Field(min_length=1)
    receipt: "ObservedWriteOutcome"


class SummaryRequest(MemoryContract):
    userId: str = Field(min_length=1)
    conversationId: str = Field(min_length=1)
    requestId: str = Field(min_length=1)
    historyRevision: int = Field(ge=0)
    summaryVersion: int = Field(ge=0)
    policyVersion: str = Field(min_length=1)
    summarizedThroughTurnSeq: int = Field(ge=0)
    oldSummary: MemorySummary | None = None
    turns: list[CompleteTurn] = Field(min_length=1)
    sourceMap: dict[str, MemorySource]
    summaryMaxTokens: Literal[300, 600, 1000] = 600
    sourceSnapshotHash: str = Field(pattern=r"^[a-f0-9]{64}$")
    toolResults: list[MemoryToolResult] = Field(default_factory=list)

    @model_validator(mode="after")
    def incremental_ranges(self):
        sequences = [turn.turnSeq for turn in self.turns]
        if sequences != sorted(set(sequences)) or sequences[0] <= self.summarizedThroughTurnSeq:
            raise ValueError("summary_ranges_invalid")
        if self.oldSummary is None and self.summarizedThroughTurnSeq:
            raise ValueError("summary_previous_missing")
        if self.oldSummary and not self.oldSummary.referenced_sources() <= self.sourceMap.keys():
            raise ValueError("summary_previous_source_unknown")
        if self.oldSummary:
            self.oldSummary.retained_sources(self.sourceMap)
        for result in self.toolResults:
            source = self.sourceMap.get(result.source)
            if (source is None or source.role != "tool" or source.messageId != result.source
                    or source.turnSeq not in sequences):
                raise ValueError("summary_tool_source_invalid")
        return self


class MemoryPlanRequest(MemoryContract):
    memory: MemorySnapshot
    summaryRequest: SummaryRequest | None = None
    triggerTokens: int = Field(default=1500, ge=0)

    @model_validator(mode="after")
    def same_snapshot(self):
        candidate = self.summaryRequest
        if candidate is not None:
            for field in ("userId", "conversationId", "requestId", "historyRevision",
                          "summaryVersion", "policyVersion", "summarizedThroughTurnSeq"):
                if getattr(candidate, field) != getattr(self.memory, field):
                    raise ValueError("memory_plan_snapshot_mismatch")
            if candidate.oldSummary != self.memory.summary or candidate.turns != self.memory.pendingTurns:
                raise ValueError("memory_plan_ranges_mismatch")
        return self


class MemoryPlanResult(MemoryContract):
    memoryTokens: int = Field(ge=0)
    batchTurnCount: int = Field(ge=0)
    reason: Literal["below_threshold", "no_older_turns", "compress", "memory_turn_too_large"]
    counter: str
    exactCount: bool


class ModelUsage(MemoryContract):
    phase: str
    inputTokens: int | None = Field(default=None, ge=0)
    outputTokens: int | None = Field(default=None, ge=0)
    cachedInputTokens: int | None = Field(default=None, ge=0)
    elapsedMs: float = Field(ge=0)
    status: str


class SummaryResult(MemoryContract):
    ok: bool
    error: str | None = None
    summary: MemorySummary | None = None
    summaryTokens: int | None = Field(default=None, ge=0)
    counter: str
    exactCount: bool
    sourceSnapshotHash: str
    summarizedThroughTurnSeq: int = Field(ge=0)
    usage: list[ModelUsage] = Field(default_factory=list)


class ObservedWriteOutcome(MemoryContract):
    name: Literal["add_manual_to_account", "create_repair_order"]
    writeStatus: Literal["succeeded", "already_exists", "not_dispatched", "failed", "unknown"]
    target: str
    identifier: str
    requestId: str


class HistoryMessage(BaseModel):
    role: Literal["user", "assistant", "ai", "system"]
    content: str = ""


class AgentRequest(BaseModel):
    userId: str = ""
    userOpenId: str = ""
    productId: str = ""
    manualId: str = ""
    manualRefId: str = ""
    manualName: str = ""
    manualType: str = ""
    manualBrand: str = ""
    manualModel: str = ""
    query: str
    conversationId: str = ""
    requestId: str = ""
    persistHistory: bool | None = None
    memory: MemorySnapshot | None = None
    history: list[HistoryMessage] = Field(default_factory=list)

    @model_validator(mode="after")
    def memory_ownership(self):
        if self.memory is not None and (
            self.persistHistory is False
            or (self.userId, self.conversationId, self.requestId) != (
                self.memory.userId, self.memory.conversationId, self.memory.requestId
            )
        ):
            raise ValueError("memory_ownership_mismatch")
        return self

    @property
    def lookup_manual_id(self) -> str:
        # manualId is the platform-manual id. manualRefId may instead be the
        # user's my_manuals document id, which cannot be used to search or add
        # a platform manual.
        return self.manualId or self.manualRefId


class Source(BaseModel):
    kind: str
    fragmentId: str = ""
    page: int | None = None
    chunkIndex: int | None = None
    title: str = ""
    url: str = ""
    snippet: str = ""
    sourceType: str = ""


class StructuredAnswer(BaseModel):
    summary: str = ""
    steps: list[str] = Field(default_factory=list)
    cautions: list[str] = Field(default_factory=list)
    basisNote: str = ""


class AgentResponse(BaseModel):
    status: str = "ok"
    error: str | None = None
    model: str = ""
    target: str = "deepseek"
    conversationId: str = ""
    requestId: str = ""
    memoryStatus: str = "disabled"
    # Java deserializes these internal fields separately and strips them for FE.
    usageBreakdown: list[ModelUsage] = Field(default_factory=list)
    observedWriteOutcomes: list[ObservedWriteOutcome] = Field(default_factory=list)
    answerText: str = ""
    usedWebSearch: bool = False
    insufficientContext: bool = False
    sourceFragmentIds: list[str] = Field(default_factory=list)
    sources: list[Source] = Field(default_factory=list)
    structuredAnswer: StructuredAnswer | None = None
    toolCalls: list[dict[str, Any]] = Field(default_factory=list)


class ToolContext(BaseModel):
    user_id: str = ""
    conversation_id: str = ""
    # The platform manuals._id, never a my_manuals._id.
    manual_id: str = ""
    # A my_manuals._id may be used for retrieval of a private upload only.
    manual_ref_id: str = ""
    manual_name: str = ""
    manual_brand: str = ""
    manual_model: str = ""
    product_id: str = ""
    user_query: str = ""
    user_history: list[str] = Field(default_factory=list)
    last_assistant_message: str = ""
    unconfirmed_change: bool = False


class ToolResult(BaseModel):
    ok: bool = True
    data: Any = None
    error: str = ""
    sources: list[Source] = Field(default_factory=list)
    used_web_search: bool = False
    # Internal write evidence; excluded from the model-visible tool JSON.
    write_status: Literal["succeeded", "already_exists", "not_dispatched", "failed", "unknown"] | None = Field(default=None, exclude=True)
    write_target: str = Field(default="", exclude=True)
    write_identifier: str = Field(default="", exclude=True)
