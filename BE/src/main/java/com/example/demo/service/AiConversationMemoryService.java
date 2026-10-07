package com.example.demo.service;

import com.example.demo.config.SessionMemoryProperties;
import com.example.demo.dto.SessionMemory;
import com.example.demo.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiConversationMemoryService {
    private final MongoTemplate mongo;
    private final AiTurnLedgerService ledger;
    private final PythonAgentClient python;
    private final SessionMemoryProperties config;
    private final ObjectMapper mapper;

    public String policy() {
        return config.getPolicyVersion() + ":N" + config.getRecentTurns() + ":S" + config.getSummaryMaxTokens()
                + ":" + config.getCompressionStrategy() + ":T" + config.getTriggerTokens()
                + ":G" + config.getMinGrowthTokens() + ":deepseek-v4-text-8f9f37ca37fd:evidence-carriers-v1";
    }
    public record Window(List<SessionMemory.Turn> old, List<SessionMemory.Turn> recent) { }
    public static Window window(List<SessionMemory.Turn> turns, int n) {
        List<SessionMemory.Turn> sorted = turns.stream().sorted(Comparator.comparingLong(SessionMemory.Turn::turnSeq)).toList();
        int cut = Math.max(0, sorted.size() - n);
        return new Window(List.copyOf(sorted.subList(0, cut)), List.copyOf(sorted.subList(cut, sorted.size())));
    }
    public SessionMemory.Snapshot prepare(AiTurnLedgerService.Started execution) {
        AiTurnRequest request = execution.request();
        AiConversation c = ledger.owned(request.getUserId(), request.getConversationId());
        if (c == null) throw new SessionMemoryFault("conversation_not_found");
        List<AiMessage> messages = ledger.messages(c.getUserId(), c.getId());
        List<SessionMemory.Turn> turns = CompleteTurnReader.read(messages);
        if (!config.isEnabled()) {
            // Roll back compression, not request deduplication or unknown-write
            // protection. Keep stored summaries intact for later reconstruction.
            return new SessionMemory.Snapshot(c.getUserId(), c.getId(), request.getRequestId(),
                value(c.getHistoryRevision()), value(c.getSummaryVersion()), "compression-disabled:B6", 0,
                null, window(turns, 3).recent(), List.of(), Map.of(), request.getExecutionVersion(), "disabled");
        }
        Window window = window(turns, config.getRecentTurns());
        boolean rebuild = !policy().equals(c.getSummaryPolicyVersion());
        SessionMemory.Summary summary = rebuild ? null : c.getMemorySummary();
        long through = rebuild ? 0 : value(c.getSummarizedThroughTurnSeq());
        long version = value(c.getSummaryVersion());
        long revision = value(c.getHistoryRevision());
        Map<String, SessionMemory.Source> sources = rebuild || c.getMemorySourceMap() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(c.getMemorySourceMap());
        Map<String, SessionMemory.Source> all = sources(messages);
        Map<Long, List<SessionMemory.ToolResult>> toolResults = receipts(c, all);
        List<SessionMemory.Turn> pending = new ArrayList<>();
        for (SessionMemory.Turn turn : window.old()) if (turn.turnSeq() > through) pending.add(turn);
        String status = "ok";
        // Commit only a complete rebuilt policy. A failed rebuild keeps raw history,
        // not an old-policy summary whose coverage may overlap the larger window.
        SessionMemory.Summary staged = summary;
        Map<String, SessionMemory.Source> stagedSources = sources;
        long stagedThrough = through;
        List<SessionMemory.Usage> usages = new ArrayList<>();
        boolean threshold = "threshold".equals(config.getCompressionStrategy());
        int nextTrigger = policy().equals(c.getMemoryTriggerPolicyVersion()) && c.getMemoryNextTriggerTokens() != null
                ? Math.max(config.getTriggerTokens(), c.getMemoryNextTriggerTokens()) : config.getTriggerTokens();
        int activeTrigger = nextTrigger;
        Integer rebuildInputTokens = null;
        int target = config.getTriggerTokens() - config.getMinGrowthTokens();
        while (!pending.isEmpty()) {
            if (!ledger.renew(request)) throw new SessionMemoryFault("execution_qualification_lost");
            Map<String, SessionMemory.Source> available = selectSources(staged, pending, all, stagedSources);
            List<SessionMemory.ToolResult> availableTools = pending.stream()
                    .flatMap(t -> toolResults.getOrDefault(t.turnSeq(), List.of()).stream()).toList();
            availableTools.forEach(t -> available.put(t.source(), all.get(t.source())));
            SessionMemory.SummaryRequest planningInput = new SessionMemory.SummaryRequest(c.getUserId(), c.getId(),
                    request.getRequestId(), revision, version, policy(), stagedThrough, staged, List.copyOf(pending),
                    available, config.getSummaryMaxTokens(), hash(List.of(revision, version, stagedThrough, pending)), availableTools);
            SessionMemory.PlanResult plan = null;
            List<SessionMemory.Turn> batch;
            try {
                if (threshold) {
                    var snapshot = new SessionMemory.Snapshot(c.getUserId(), c.getId(), request.getRequestId(), revision,
                            version, policy(), stagedThrough, staged, window.recent(), List.copyOf(pending),
                            stagedSources, request.getExecutionVersion(), "ok");
                    plan = python.planMemory(new SessionMemory.PlanRequest(snapshot, planningInput, activeTrigger));
                    if (rebuildInputTokens == null) rebuildInputTokens = plan.memoryTokens();
                    if (!"deepseek-v4-text-8f9f37ca37fd".equals(plan.counter()) || plan.memoryTokens() < 0
                            || plan.batchTurnCount() < 0 || plan.batchTurnCount() > pending.size())
                        throw new SessionMemoryFault("memory_plan_invalid");
                    if ("below_threshold".equals(plan.reason())) break;
                    if (!"compress".equals(plan.reason()) || plan.batchTurnCount() == 0) {
                        status = "degraded"; nextTrigger = growthTrigger(plan.memoryTokens()); break;
                    }
                    batch = List.copyOf(pending.subList(0, plan.batchTurnCount()));
                } else {
                    batch = batch(pending, staged, config.getSummaryInputBudget(), toolResults);
                }
            } catch (Exception ex) { status = "degraded"; break; }
            if (batch.isEmpty()) { status = "degraded"; break; }
            Map<String, SessionMemory.Source> batchSources = selectSources(staged, batch, all, stagedSources);
            List<SessionMemory.ToolResult> batchTools = batch.stream()
                    .flatMap(t -> toolResults.getOrDefault(t.turnSeq(), List.of()).stream()).toList();
            batchTools.forEach(t -> batchSources.put(t.source(), all.get(t.source())));
            String hash = hash(List.of(revision, version, policy(), stagedThrough, batch, batchSources, batchTools));
            SessionMemory.SummaryRequest input = new SessionMemory.SummaryRequest(c.getUserId(), c.getId(),
                    request.getRequestId(), revision, version, policy(), stagedThrough, staged, batch,
                    batchSources, config.getSummaryMaxTokens(), hash, batchTools);
            SessionMemory.SummaryResult candidate;
            if (!ledger.renew(request)) throw new SessionMemoryFault("execution_qualification_lost");
            try { candidate = python.summarize(input); }
            catch (Exception ex) {
                status = "degraded"; if (plan != null) nextTrigger = growthTrigger(plan.memoryTokens()); break;
            }
            if (candidate != null && candidate.usage() != null) usages.addAll(candidate.usage());
            if (!valid(input, candidate) || (plan != null && !plan.counter().equals(candidate.counter()))) {
                status = "degraded"; if (plan != null) nextTrigger = growthTrigger(plan.memoryTokens()); break;
            }
            staged = candidate.summary(); stagedThrough = batch.get(batch.size() - 1).turnSeq();
            stagedSources = selectSources(staged, List.of(), all, batchSources);
            pending = new ArrayList<>(pending.subList(batch.size(), pending.size()));
            if (!rebuild) {
                version = commit(c, request, revision, version, stagedThrough, staged, stagedSources, hash);
                summary = staged; through = stagedThrough; sources = stagedSources;
            }
            if (threshold) {
                var after = new SessionMemory.Snapshot(c.getUserId(), c.getId(), request.getRequestId(), revision,
                        version, policy(), stagedThrough, staged, window.recent(), List.copyOf(pending),
                        stagedSources, request.getExecutionVersion(), "ok");
                try {
                    var counted = python.planMemory(new SessionMemory.PlanRequest(after, null, Integer.MAX_VALUE));
                    if (!plan.counter().equals(counted.counter()) || counted.memoryTokens() < 0)
                        throw new SessionMemoryFault("memory_plan_invalid");
                    int remaining = counted.memoryTokens();
                    if (remaining <= target) { nextTrigger = config.getTriggerTokens(); break; }
                    if (pending.isEmpty() || plan.memoryTokens() - remaining < config.getMinGrowthTokens()) {
                        nextTrigger = growthTrigger(remaining); break;
                    }
                    activeTrigger = target + 1; // Finish the triggered event with headroom, not merely below the trigger.
                } catch (Exception ex) {
                    status = "degraded"; nextTrigger = growthTrigger(plan.memoryTokens()); break;
                }
            }
        }
        if (rebuild && "ok".equals(status)) {
            version = commit(c, request, revision, version, stagedThrough, staged, stagedSources,
                    hash(List.of(revision, policy(), stagedThrough, turns)));
            summary = staged; through = stagedThrough; sources = stagedSources;
        }
        if (rebuild && !"ok".equals(status)) {
            summary = null; through = 0; sources = new LinkedHashMap<>();
            pending = new ArrayList<>(window.old()); // No partial policy publication.
            if (threshold && rebuildInputTokens != null) nextTrigger = growthTrigger(rebuildInputTokens);
        }
        if (threshold) {
            Query guard = Query.query(Criteria.where("_id").is(c.getId()).and("userId").is(c.getUserId())
                    .and("deletedAt").is(null).and("historyRevision").is(revision).and("summaryVersion").is(version)
                    .and("executionVersion").is(request.getExecutionVersion()).and("leaseRequestId").is(request.getRequestId())
                    .and("leaseExpiresAt").gt(Instant.now()));
            if (mongo.updateFirst(guard, new Update().set("memoryNextTriggerTokens", nextTrigger)
                    .set("memoryTriggerPolicyVersion", policy()), AiConversation.class).getMatchedCount() != 1)
                throw new SessionMemoryFault("memory_cas_conflict");
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(request.getId()).and("state").is("running")),
                new Update().set("usage", usages), AiTurnRequest.class);
        return new SessionMemory.Snapshot(c.getUserId(), c.getId(), request.getRequestId(), revision, version,
                policy(), through, summary, window.recent(), List.copyOf(pending), Map.copyOf(sources),
                request.getExecutionVersion(), status);
    }

    private int growthTrigger(int tokens) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(config.getTriggerTokens(), (long) tokens + config.getMinGrowthTokens()));
    }

    public List<SessionMemory.Turn> batch(List<SessionMemory.Turn> pending, SessionMemory.Summary old, int budget) {
        return batch(pending, old, budget, Map.of());
    }
    private List<SessionMemory.Turn> batch(List<SessionMemory.Turn> pending, SessionMemory.Summary old,
            int budget, Map<Long, List<SessionMemory.ToolResult>> tools) {
        // Historical rolling baseline only. Threshold mode delegates envelope token
        // counting and batching to Python, avoiding a second byte-based limit.
        int count = 3500 + bytes(old);
        List<SessionMemory.Turn> result = new ArrayList<>();
        for (var turn : pending) {
            int next = bytes(turn) + 400 + bytes(tools.getOrDefault(turn.turnSeq(), List.of()));
            if ((long) count + next > budget) break;
            result.add(turn); count += next;
        }
        return List.copyOf(result);
    }
    private long commit(AiConversation c, AiTurnRequest request, long revision, long version, long through,
            SessionMemory.Summary summary, Map<String, SessionMemory.Source> sources, String hash) {
        AiMemoryVersion record = new AiMemoryVersion();
        record.setId(c.getId() + ":" + (version + 1) + ":" + UUID.randomUUID()); record.setUserId(c.getUserId()); record.setConversationId(c.getId());
        record.setSummaryVersion(version + 1); record.setHistoryRevision(revision); record.setSummarizedThroughTurnSeq(through);
        record.setPolicyVersion(policy()); record.setSourceSnapshotHash(hash); record.setSummary(summary);
        record.setSourceMap(sources); record.setCreatedAt(Instant.now());
        // Save the immutable candidate before making it visible. A failed CAS
        // leaves an unreferenced record, never a visible missing version.
        mongo.insert(record);
        Query cas = Query.query(Criteria.where("_id").is(c.getId()).and("userId").is(c.getUserId())
                .and("deletedAt").is(null).and("historyRevision").is(revision)
                .and("summaryVersion").is(version).and("executionVersion").is(request.getExecutionVersion())
                .and("leaseRequestId").is(request.getRequestId()).and("leaseExpiresAt").gt(Instant.now()));
        var updated = mongo.updateFirst(cas, new Update().set("memorySummary", summary).set("memorySourceMap", sources)
                .set("memoryVersionId", record.getId())
                .set("summaryVersion", version + 1).set("summarizedThroughTurnSeq", through)
                .set("summaryPolicyVersion", policy()), AiConversation.class);
        if (updated.getMatchedCount() != 1) {
            mongo.remove(Query.query(Criteria.where("_id").is(record.getId())), AiMemoryVersion.class);
            throw new SessionMemoryFault("memory_cas_conflict");
        }
        return version + 1;
    }
    public boolean valid(SessionMemory.SummaryRequest input, SessionMemory.SummaryResult result) {
        if (result == null || !result.ok() || result.summary() == null || result.summaryTokens() == null
                || result.summaryTokens() > input.summaryMaxTokens() || result.summaryTokens() < 1
                || !input.sourceSnapshotHash().equals(result.sourceSnapshotHash())
                || result.summarizedThroughTurnSeq() != input.turns().get(input.turns().size() - 1).turnSeq()) return false;
        try {
            Set<String> ids = references(result.summary());
            if (ids.isEmpty() || !input.sourceMap().keySet().containsAll(ids)) return false;
            Set<String> evidence = new HashSet<>(); input.sourceMap().values().forEach(s -> evidence.addAll(s.evidenceRefs()));
            if (!evidence.containsAll(result.summary().evidenceRefs())) return false;
            for (SessionMemory.Attempt attempt : result.summary().attempts()) {
                String role = input.sourceMap().get(attempt.source()).role();
                if (!Set.of("suggested", "reported_done", "tool_observed").contains(attempt.state())) return false;
                if ("suggested".equals(attempt.state()) && !"assistant".equals(role)) return false;
                if ("tool_observed".equals(attempt.state()) && !"tool".equals(role)) return false;
                if ("reported_done".equals(attempt.state()) && !"user".equals(role)) return false;
            }
            // The authenticated Python service validates canonical JSON with the same local tokenizer.
            return result.counter() != null && !result.counter().isBlank();
        } catch (RuntimeException ex) { return false; }
    }
    public static Set<String> references(SessionMemory.Summary summary) {
        Set<String> ids = new HashSet<>();
        if (summary == null) return ids;
        for (List<SessionMemory.Fact> group : List.of(summary.device(), summary.currentIssue(), summary.constraints(),
                summary.openQuestions(), summary.background())) for (var fact : group) ids.add(fact.source());
        for (var attempt : summary.attempts()) ids.add(attempt.source());
        return ids;
    }
    public static Map<String, SessionMemory.Source> selectSources(SessionMemory.Summary summary,
            List<SessionMemory.Turn> turns, Map<String, SessionMemory.Source> all, Map<String, SessionMemory.Source> old) {
        Set<String> needed = references(summary);
        turns.forEach(t -> { needed.add(t.user().messageId()); needed.add(t.assistant().messageId()); });
        Map<String, SessionMemory.Source> available = new LinkedHashMap<>(old);
        available.putAll(all);
        if (summary != null) for (String ref : new TreeSet<>(summary.evidenceRefs())) {
            boolean covered = needed.stream().anyMatch(id -> available.containsKey(id)
                    && available.get(id).evidenceRefs().contains(ref));
            if (!covered) {
                String carrier = available.entrySet().stream()
                        .filter(entry -> entry.getValue().evidenceRefs().contains(ref))
                        .sorted(Comparator.<Map.Entry<String, SessionMemory.Source>>comparingLong(
                                entry -> entry.getValue().turnSeq()).thenComparing(Map.Entry::getKey))
                        .map(Map.Entry::getKey).findFirst()
                        .orElseThrow(() -> new SessionMemoryFault("memory_evidence_source_missing"));
                needed.add(carrier);
            }
        }
        Map<String, SessionMemory.Source> output = new LinkedHashMap<>();
        needed.stream().sorted().forEach(id -> {
            var source = all.containsKey(id) ? all.get(id) : old.get(id);
            if (source == null) throw new SessionMemoryFault("memory_source_missing"); output.put(id, source);
        });
        return output;
    }
    private static Map<String, SessionMemory.Source> sources(List<AiMessage> messages) {
        Map<String, SessionMemory.Source> result = new LinkedHashMap<>();
        for (var message : messages) {
            if (message.getTurnSeq() == null) continue;
            List<String> refs = message.getSources() == null ? List.of() : message.getSources().stream()
                    .map(AiMessage.SourceRef::getFragmentId).filter(Objects::nonNull).toList();
            result.put(message.getId(), new SessionMemory.Source(message.getId(), message.getTurnSeq(),
                    "ai".equals(message.getRole()) ? "assistant" : message.getRole(), refs));
        }
        return result;
    }
    private Map<Long, List<SessionMemory.ToolResult>> receipts(AiConversation c, Map<String, SessionMemory.Source> all) {
        Map<Long, List<SessionMemory.ToolResult>> result = new HashMap<>();
        var records = mongo.find(Query.query(Criteria.where("userId").is(c.getUserId())
                .and("conversationId").is(c.getId()).and("state").is("completed")), AiTurnRequest.class);
        for (var record : records) {
            if (record.getTurnSeq() == null || record.getWriteOutcomes() == null) continue;
            List<SessionMemory.ToolResult> values = new ArrayList<>(); int ordinal = 0;
            for (var receipt : record.getWriteOutcomes()) {
                // These are server-saved observations, never model/client supplied history.
                if (receipt == null || !Set.of("add_manual_to_account", "create_repair_order").contains(receipt.name())
                        || receipt.writeStatus() == null || !Set.of("succeeded", "already_exists", "not_dispatched", "failed", "unknown").contains(receipt.writeStatus())) continue;
                String source = record.getId() + ":receipt:" + ordinal++;
                all.put(source, new SessionMemory.Source(source, record.getTurnSeq(), "tool", List.of()));
                values.add(new SessionMemory.ToolResult(source, receipt));
            }
            result.put(record.getTurnSeq(), List.copyOf(values));
        }
        return result;
    }
    private int bytes(Object value) {
        if (value == null) return 0;
        try { return mapper.writeValueAsBytes(value).length; }
        catch (Exception ex) { throw new SessionMemoryFault("memory_serialization_failed"); }
    }
    private String hash(Object value) {
        try { return AiTurnLedgerService.hex(mapper.writeValueAsBytes(value)); }
        catch (Exception ex) { throw new SessionMemoryFault("memory_hash_failed"); }
    }
    private static long value(Long value) { return value == null ? 0 : value; }
}
