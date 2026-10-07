package com.example.demo.service;

import com.example.demo.dto.QAResponse;
import com.example.demo.model.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

/** Explicit offline maintenance operation. Never runs automatically on startup. */
@Service
@RequiredArgsConstructor
public class AiMemoryMigrationService {
    private final MongoTemplate mongo;
    public record Pair(AiMessage user, AiMessage assistant, long turnSeq, String requestId) { }
    public record Plan(List<Pair> pairs, List<String> orphans) { }
    public record Report(boolean dryRun, int pairs, int orphans, long revision) { }
    public static Plan plan(List<AiMessage> raw) {
        List<AiMessage> sorted = CompleteTurnReader.stableOrder(raw);
        List<Pair> pairs = new ArrayList<>(); List<String> orphans = new ArrayList<>();
        long seq = raw.stream().map(AiMessage::getTurnSeq).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0);
        for (int i = 0; i < sorted.size(); i++) {
            AiMessage user = sorted.get(i);
            if ("user".equals(user.getRole()) && i + 1 < sorted.size()) {
                AiMessage answer = sorted.get(i + 1);
                if (Set.of("ai", "assistant").contains(answer.getRole())) {
                    if (Boolean.TRUE.equals(user.getCompletedTurn()) && Boolean.TRUE.equals(answer.getCompletedTurn())) { i++; continue; }
                    long assigned = user.getTurnSeq() != null ? user.getTurnSeq() : answer.getTurnSeq() != null ? answer.getTurnSeq() : ++seq;
                    pairs.add(new Pair(user, answer, assigned, "legacy:" + user.getId())); i++; continue;
                }
            }
            if (Boolean.TRUE.equals(user.getCompletedTurn())) continue;
            if (!"tool".equals(user.getRole())) orphans.add(user.getId());
        }
        return new Plan(List.copyOf(pairs), List.copyOf(orphans));
    }
    public Report migrate(String userId, String conversationId, boolean dryRun) {
        Query owned = Query.query(Criteria.where("_id").is(conversationId).and("userId").is(userId)
                .and("deletedAt").is(null).and("leaseRequestId").is(null));
        AiConversation conversation = mongo.findOne(owned, AiConversation.class);
        if (conversation == null) throw new SessionMemoryFault("migration_conversation_busy_or_missing");
        List<AiMessage> original = mongo.find(Query.query(Criteria.where("conversationId").is(conversationId)), AiMessage.class);
        Plan plan = plan(original);
        long revision = CompleteTurnReader.read(original).size() + plan.pairs().size();
        if (dryRun) return new Report(true, plan.pairs().size(), plan.orphans().size(), revision);
        for (Pair pair : plan.pairs()) {
            AiTurnRequest record = new AiTurnRequest(); record.setId(pair.requestId()); record.setRequestId(pair.requestId());
            record.setUserId(userId); record.setConversationId(conversationId); record.setTurnSeq(pair.turnSeq());
            record.setState("completed"); record.setUserText(pair.user().getText());
            record.setPayloadHash(AiTurnLedgerService.hex((pair.user().getId() + "|" + pair.assistant().getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            record.setCreatedAt(pair.user().getCreatedAt()); record.setUpdatedAt(Instant.now());
            QAResponse response = new QAResponse(); response.setStatus("ok"); response.setAnswerText(pair.assistant().getText());
            response.setConversationId(conversationId); response.setRequestId(pair.requestId()); record.setFinalResponse(response);
            try { mongo.insert(record); } catch (DuplicateKeyException ignored) { }
            for (int ordinal = 0; ordinal <= 1; ordinal++) {
                String id = ordinal == 0 ? pair.user().getId() : pair.assistant().getId();
                mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("conversationId").is(conversationId)),
                    new Update().set("userId", userId).set("turnSeq", pair.turnSeq()).set("requestId", pair.requestId())
                            .set("ordinal", ordinal).set("completedTurn", true).set("orphaned", false), AiMessage.class);
            }
        }
        for (String id : plan.orphans()) mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("orphaned", true), AiMessage.class);
        long max = original.stream().map(AiMessage::getTurnSeq).filter(Objects::nonNull).mapToLong(Long::longValue).max().orElse(0);
        max = Math.max(max, plan.pairs().stream().mapToLong(Pair::turnSeq).max().orElse(0));
        Update header = new Update().set("historyRevision", revision).set("nextTurnSeq", max);
        if (conversation.getSummaryVersion() == null) header.set("summaryVersion", 0L).set("summarizedThroughTurnSeq", 0L);
        if (conversation.getExecutionVersion() == null) header.set("executionVersion", 0L);
        if (mongo.updateFirst(owned, header, AiConversation.class).getMatchedCount() != 1)
            throw new SessionMemoryFault("migration_conversation_changed");
        return new Report(false, plan.pairs().size(), plan.orphans().size(), revision);
    }
}
