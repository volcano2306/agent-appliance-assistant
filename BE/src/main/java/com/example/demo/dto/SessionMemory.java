package com.example.demo.dto;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Internal Java/Python wire contracts; never bind a Snapshot from a public QARequest. */
public final class SessionMemory {
    private SessionMemory() { }

    public record Fact(String text, String source) { }
    public record Attempt(String action, String result, String state, String source) { }
    public record Summary(List<Fact> device, List<Fact> currentIssue, List<Attempt> attempts,
            List<Fact> constraints, List<Fact> openQuestions, List<String> evidenceRefs,
            List<Fact> background) { }
    public record Source(String messageId, long turnSeq, String role, List<String> evidenceRefs) { }
    public record Message(String messageId, String role, String content) { }
    public record Turn(long turnSeq, String requestId, Message user, Message assistant) { }
    public record Snapshot(String userId, String conversationId, String requestId,
            long historyRevision, long summaryVersion, String policyVersion,
            long summarizedThroughTurnSeq, Summary summary, List<Turn> recentTurns,
            List<Turn> pendingTurns, Map<String, Source> sourceMap, Long executionVersion,
            String memoryStatus) { }
    public record SummaryRequest(String userId, String conversationId, String requestId,
            long historyRevision, long summaryVersion, String policyVersion,
            long summarizedThroughTurnSeq, Summary oldSummary, List<Turn> turns,
            Map<String, Source> sourceMap, int summaryMaxTokens, String sourceSnapshotHash,
            List<ToolResult> toolResults) {
        public SummaryRequest(String userId, String conversationId, String requestId,
                long historyRevision, long summaryVersion, String policyVersion,
                long summarizedThroughTurnSeq, Summary oldSummary, List<Turn> turns,
                Map<String, Source> sourceMap, int summaryMaxTokens, String sourceSnapshotHash) {
            this(userId, conversationId, requestId, historyRevision, summaryVersion, policyVersion,
                    summarizedThroughTurnSeq, oldSummary, turns, sourceMap, summaryMaxTokens,
                    sourceSnapshotHash, List.of());
        }
    }
    public record Usage(String phase, Long inputTokens, Long outputTokens,
            Long cachedInputTokens, double elapsedMs, String status) { }
    public record PlanRequest(Snapshot memory, SummaryRequest summaryRequest, int triggerTokens) { }
    public record PlanResult(int memoryTokens, int batchTurnCount, String reason, String counter, boolean exactCount) { }
    public record SummaryResult(boolean ok, String error, Summary summary, Integer summaryTokens,
            String counter, boolean exactCount, String sourceSnapshotHash,
            long summarizedThroughTurnSeq, List<Usage> usage) { }
    public record WriteOutcome(String name, String writeStatus, String target,
            String identifier, String requestId) { }
    public record ToolResult(String source, WriteOutcome receipt) { }

    /** Separate internal response type prevents sending receipts and usage to the mini app. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PythonResponse extends QAResponse {
        private List<Usage> usageBreakdown = List.of();
        private List<WriteOutcome> observedWriteOutcomes = List.of();
        public List<Usage> getUsageBreakdown() { return usageBreakdown; }
        public void setUsageBreakdown(List<Usage> value) { usageBreakdown = value; }
        public List<WriteOutcome> getObservedWriteOutcomes() { return observedWriteOutcomes; }
        public void setObservedWriteOutcomes(List<WriteOutcome> value) { observedWriteOutcomes = value; }
    }
}
