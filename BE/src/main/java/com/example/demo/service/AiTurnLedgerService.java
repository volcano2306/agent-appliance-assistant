package com.example.demo.service;

import com.example.demo.config.SessionMemoryProperties;
import com.example.demo.dto.QARequest;
import com.example.demo.dto.QAResponse;
import com.example.demo.dto.SessionMemory;
import com.example.demo.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

/** Result-first ledger plus fencing. Expired RUNNING requests are NOT re-executed. */
@Service
@RequiredArgsConstructor
public class AiTurnLedgerService {
    private static final int LEASE_SECONDS = 45;
    private final MongoTemplate mongo;
    private final ObjectMapper mapper;
    private final SessionMemoryProperties config;
    public record Started(AiTurnRequest request, AiConversation conversation, QAResponse cached) { }

    @PostConstruct
    public void ensureIndexes() {
        if (!config.isRuntimeEnabled()) return; // Both flags off means no startup DB migration.
        mongo.indexOps(AiTurnRequest.class).ensureIndex(new Index().on("userId", Sort.Direction.ASC)
                .on("requestId", Sort.Direction.ASC).unique().named("user_request_unique"));
        mongo.indexOps(AiMessage.class).ensureIndex(new Index().on("conversationId", Sort.Direction.ASC)
                .on("turnSeq", Sort.Direction.ASC).on("ordinal", Sort.Direction.ASC).unique()
                .partial(PartialIndexFilter.of(Criteria.where("turnSeq").type(18)
                        .and("ordinal").type(16))).named("complete_turn_ordinal_unique"));
    }

    public Started begin(QARequest req) {
        String user = require(req.getUserId(), "user_required");
        String requestId = require(req.getRequestId(), "request_id_required");
        String suppliedConversation = trim(req.getConversationId());
        if (suppliedConversation != null && owned(user, suppliedConversation) == null)
            throw new SessionMemoryFault("conversation_not_found");
        String hash = payloadHash(req);
        Query key = Query.query(Criteria.where("userId").is(user).and("requestId").is(requestId));
        AiTurnRequest ledger = mongo.findOne(key, AiTurnRequest.class);
        if (ledger == null) {
            ledger = new AiTurnRequest();
            ledger.setId(UUID.randomUUID().toString());
            ledger.setUserId(user); ledger.setRequestId(requestId); ledger.setPayloadHash(hash);
            ledger.setConversationId(suppliedConversation != null ? suppliedConversation : UUID.randomUUID().toString());
            ledger.setState("prepared"); ledger.setCreatedAt(Instant.now()); ledger.setUpdatedAt(Instant.now());
            ledger.setUserText(req.getQuery()); ledger.setManualRef(manualRef(req));
            try { mongo.insert(ledger); }
            catch (DuplicateKeyException ex) { ledger = mongo.findOne(key, AiTurnRequest.class); }
        }
        if (ledger == null || !hash.equals(ledger.getPayloadHash()))
            throw new SessionMemoryFault("request_id_conflict");
        // Retried first turn can carry the canonical ID returned by a previous attempt.
        if (suppliedConversation != null && !suppliedConversation.equals(ledger.getConversationId()))
            throw new SessionMemoryFault("request_id_conflict");
        req.setUserId(user); req.setRequestId(requestId); req.setConversationId(ledger.getConversationId());
        AiConversation conversation = owned(user, ledger.getConversationId());
        if (conversation == null && suppliedConversation == null && "prepared".equals(ledger.getState())) {
            AiConversation fresh = AiConversation.builder().id(ledger.getConversationId()).userId(user)
                    .title(shorten(req.getQuery(), 18)).createdAt(Instant.now()).updatedAt(Instant.now())
                    .messageCount(0).customTitle(false).historyRevision(0L).nextTurnSeq(0L)
                    .summaryVersion(0L).summarizedThroughTurnSeq(0L).executionVersion(0L).build();
            try { mongo.insert(fresh); }
            catch (DuplicateKeyException ignored) { }
            conversation = owned(user, ledger.getConversationId());
        }
        if (conversation == null) throw new SessionMemoryFault("conversation_not_found");
        if (ledger.getFinalResponse() != null && Set.of("result_saved", "completed").contains(ledger.getState())) {
            recoverProjection(ledger);
            return new Started(ledger, owned(user, ledger.getConversationId()), ledger.getFinalResponse());
        }
        if (!"prepared".equals(ledger.getState())) {
            expireToUnknown(ledger, conversation);
            throw new SessionMemoryFault("unknown".equals(ledger.getState()) ? "request_result_unknown" : "request_in_progress");
        }
        // Do not steal an expired lease: an issued business write may have succeeded.
        if (conversation.getLeaseRequestId() != null) {
            AiTurnRequest holder = mongo.findOne(Query.query(Criteria.where("userId").is(user)
                    .and("requestId").is(conversation.getLeaseRequestId())), AiTurnRequest.class);
            if (holder != null && holder.getFinalResponse() != null) recoverProjection(holder);
            else if (holder != null) expireToUnknown(holder, conversation);
        }
        Query available = Query.query(Criteria.where("_id").is(conversation.getId()).and("userId").is(user)
                .and("deletedAt").is(null).and("leaseRequestId").is(null).and("blockedRequestId").is(null));
        AiConversation acquired = mongo.findAndModify(available, new Update()
                .set("leaseRequestId", requestId).set("leaseExpiresAt", Instant.now().plusSeconds(LEASE_SECONDS))
                .inc("executionVersion", 1L).inc("nextTurnSeq", 1L),
                FindAndModifyOptions.options().returnNew(true), AiConversation.class);
        if (acquired == null) throw new SessionMemoryFault("conversation_busy");
        AiTurnRequest running = mongo.findAndModify(Query.query(Criteria.where("_id").is(ledger.getId())
                        .and("state").is("prepared")), new Update().set("state", "running")
                        .set("executionVersion", acquired.getExecutionVersion()).set("turnSeq", acquired.getNextTurnSeq())
                        .set("updatedAt", Instant.now()), FindAndModifyOptions.options().returnNew(true), AiTurnRequest.class);
        if (running == null) {
            release(acquired, requestId);
            throw new SessionMemoryFault("request_in_progress");
        }
        return new Started(running, acquired, null);
    }

    public boolean check(String user, String conversation, String request, long fence) {
        return mongo.exists(active(user, conversation, request, fence), AiConversation.class);
    }
    public boolean renew(AiTurnRequest ledger) {
        return mongo.updateFirst(active(ledger.getUserId(), ledger.getConversationId(), ledger.getRequestId(),
                ledger.getExecutionVersion()), new Update().set("leaseExpiresAt", Instant.now().plusSeconds(LEASE_SECONDS)),
                AiConversation.class).getMatchedCount() == 1;
    }
    private Query active(String user, String conversation, String request, long fence) {
        return Query.query(Criteria.where("_id").is(conversation).and("userId").is(user)
                .and("deletedAt").is(null).and("leaseRequestId").is(request).and("executionVersion").is(fence)
                .and("blockedRequestId").is(null).and("leaseExpiresAt").gt(Instant.now()));
    }
    public QAResponse finish(AiTurnRequest ledger, SessionMemory.PythonResponse response) {
        AiTurnRequest current = mongo.findById(ledger.getId(), AiTurnRequest.class);
        if (current != null && current.getFinalResponse() != null && Set.of("result_saved", "completed").contains(current.getState())) {
            recoverProjection(current); return current.getFinalResponse();
        }
        AiConversation conversation = owned(ledger.getUserId(), ledger.getConversationId());
        if (current != null && conversation != null) {
            // Terminal publication can be the first observer of lease expiry.
            // Record unknown without redispatch, then accept only this same fence.
            expireToUnknown(current, conversation);
            conversation = owned(ledger.getUserId(), ledger.getConversationId());
        }
        boolean lateKnownTerminal = current != null && "unknown".equals(current.getState()) && conversation != null
                && Objects.equals(conversation.getExecutionVersion(), ledger.getExecutionVersion())
                && Objects.equals(conversation.getLeaseRequestId(), ledger.getRequestId())
                && Objects.equals(conversation.getBlockedRequestId(), ledger.getRequestId());
        // A trusted late terminal may settle an unknown request, but does not
        // authorize any new model/tool dispatch, new fence, or deleted conversation.
        if (!lateKnownTerminal && !check(ledger.getUserId(), ledger.getConversationId(), ledger.getRequestId(), ledger.getExecutionVersion()))
            throw new SessionMemoryFault("execution_qualification_lost");
        response.setConversationId(ledger.getConversationId()); response.setRequestId(ledger.getRequestId());
        // Never let the internal subclass leak through Spring's runtime serializer.
        QAResponse publicResponse = mapper.copy().configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .convertValue(response, QAResponse.class);
        Query running = Query.query(Criteria.where("_id").is(ledger.getId()).and("state").in("running", "unknown")
                .and("executionVersion").is(ledger.getExecutionVersion()));
        List<SessionMemory.WriteOutcome> receipts = response.getObservedWriteOutcomes() == null ? List.of()
                : response.getObservedWriteOutcomes().stream()
                    .filter(r -> ledger.getRequestId().equals(r.requestId())).toList();
        List<SessionMemory.Usage> usage = new ArrayList<>();
        AiTurnRequest latest = mongo.findById(ledger.getId(), AiTurnRequest.class);
        if (latest != null && latest.getUsage() != null) usage.addAll(latest.getUsage());
        if (response.getUsageBreakdown() != null) usage.addAll(response.getUsageBreakdown());
        AiTurnRequest saved = mongo.findAndModify(running, new Update().set("state", "result_saved")
                .set("finalResponse", publicResponse).set("writeOutcomes", receipts)
                .set("usage", usage).set("updatedAt", Instant.now()),
                FindAndModifyOptions.options().returnNew(true), AiTurnRequest.class);
        if (saved == null) throw new SessionMemoryFault("execution_qualification_lost");
        recoverProjection(saved);
        return publicResponse;
    }

    public QAResponse acceptTerminal(String user, String conversation, String requestId, long fence,
            SessionMemory.PythonResponse response) {
        AiTurnRequest request = mongo.findOne(Query.query(Criteria.where("userId").is(user).and("requestId").is(requestId)), AiTurnRequest.class);
        if (request == null || !Objects.equals(conversation, request.getConversationId())
                || !Objects.equals(fence, request.getExecutionVersion())
                || !Objects.equals(response.getRequestId(), requestId) || !Objects.equals(response.getConversationId(), conversation))
            throw new SessionMemoryFault("terminal_identity_mismatch");
        return finish(request, response);
    }

    /** Internal operator recovery acknowledges uncertainty, never retries old writes. */
    public QAResponse closeUnknown(String user, String conversation, String requestId, long fence, boolean reviewed) {
        if (!reviewed) throw new SessionMemoryFault("explicit_recovery_required");
        if (!mongo.exists(Query.query(Criteria.where("userId").is(user).and("requestId").is(requestId)
                .and("conversationId").is(conversation).and("executionVersion").is(fence).and("state").is("unknown")), AiTurnRequest.class))
            throw new SessionMemoryFault("request_is_not_unknown");
        var response = new SessionMemory.PythonResponse(); response.setStatus("error");
        response.setError("request_closed_with_unknown_outcome"); response.setConversationId(conversation); response.setRequestId(requestId);
        response.setAnswerText("原请求已结束，但先前操作结果仍未知；没有自动重试。请先核实业务状态，避免重复提交。");
        response.setMemoryStatus("degraded"); response.setInsufficientContext(true);
        return acceptTerminal(user, conversation, requestId, fence, response);
    }

    public void markUnknown(AiTurnRequest ledger) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(ledger.getId()).and("state").is("running")),
                new Update().set("state", "unknown").set("updatedAt", Instant.now()), AiTurnRequest.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(ledger.getConversationId()).and("deletedAt").is(null)
                .and("executionVersion").is(ledger.getExecutionVersion()).and("leaseRequestId").is(ledger.getRequestId())),
                new Update().set("blockedRequestId", ledger.getRequestId()), AiConversation.class);
    }
    private void expireToUnknown(AiTurnRequest ledger, AiConversation conversation) {
        if (Set.of("running", "prepared").contains(ledger.getState()) && conversation.getLeaseExpiresAt() != null
                && Objects.equals(ledger.getRequestId(), conversation.getLeaseRequestId())
                && !conversation.getLeaseExpiresAt().isAfter(Instant.now())) {
            if ("prepared".equals(ledger.getState())) {
                // Recover a crash after the lease/sequence allocation but before
                // changing the prepared ledger to running. Still never redispatch.
                mongo.updateFirst(Query.query(Criteria.where("_id").is(ledger.getId()).and("state").is("prepared")),
                        new Update().set("state", "running").set("executionVersion", conversation.getExecutionVersion())
                                .set("turnSeq", conversation.getNextTurnSeq()), AiTurnRequest.class);
                ledger.setExecutionVersion(conversation.getExecutionVersion()); ledger.setTurnSeq(conversation.getNextTurnSeq());
            }
            markUnknown(ledger); ledger.setState("unknown");
        }
    }
    public void recoverProjection(AiTurnRequest ledger) {
        AiConversation conversation = owned(ledger.getUserId(), ledger.getConversationId());
        if (conversation == null || ledger.getFinalResponse() == null) throw new SessionMemoryFault("conversation_not_found");
        if ("completed".equals(ledger.getState())) return;
        QAResponse finalResponse = ledger.getFinalResponse();
        insertMessage(ledger, 0, "user", ledger.getUserText(), null);
        insertMessage(ledger, 1, "ai", finalResponse.getAnswerText(), finalResponse);
        // A crash after either message is safe: deterministic IDs make projection idempotent.
        long completed = mongo.count(Query.query(Criteria.where("conversationId").is(ledger.getConversationId())
                .and("state").in("completed", "result_saved")), AiTurnRequest.class);
        Query fence = Query.query(Criteria.where("_id").is(ledger.getConversationId()).and("userId").is(ledger.getUserId())
                .and("deletedAt").is(null).and("executionVersion").is(ledger.getExecutionVersion())
                .and("leaseRequestId").is(ledger.getRequestId()));
        var updated = mongo.updateFirst(fence, new Update().set("historyRevision", completed)
                .set("messageCount", (int) (completed * 2)).set("updatedAt", Instant.now())
                .set("lastMessageRole", "ai").set("lastMessagePreview", shorten(finalResponse.getAnswerText(), 36))
                .unset("leaseRequestId").unset("leaseExpiresAt").unset("blockedRequestId"), AiConversation.class);
        if (updated.getMatchedCount() != 1) {
            AiConversation current = owned(ledger.getUserId(), ledger.getConversationId());
            if (current == null) {
                mongo.remove(Query.query(Criteria.where("conversationId").is(ledger.getConversationId())), AiMessage.class);
                throw new SessionMemoryFault("conversation_not_found");
            }
            // Recover a crash after the conversation update but before marking
            // the ledger complete. Never overwrite a newer execution's history.
            if (current.getHistoryRevision() == null || current.getHistoryRevision() < completed)
                throw new SessionMemoryFault("projection_fence_lost");
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(ledger.getId()).and("state").is("result_saved")),
                new Update().set("state", "completed").set("updatedAt", Instant.now()), AiTurnRequest.class);
    }
    private void insertMessage(AiTurnRequest ledger, int ordinal, String role, String text, QAResponse response) {
        AiMessage message = AiMessage.builder().id(ledger.getId() + ":" + ordinal)
                .conversationId(ledger.getConversationId()).userId(ledger.getUserId()).requestId(ledger.getRequestId())
                .turnSeq(ledger.getTurnSeq()).ordinal(ordinal).completedTurn(true).role(role).text(text)
                .manualRef(ledger.getManualRef()).createdAt(ledger.getCreatedAt().plusMillis(ordinal)).build();
        if (response != null) {
            message.setInsufficientContext(response.getInsufficientContext());
            message.setSources(response.getSources() == null ? List.of() : response.getSources().stream().map(s ->
                    AiMessage.SourceRef.builder().kind(s.getKind()).fragmentId(s.getFragmentId()).page(s.getPage())
                    .chunkIndex(s.getChunkIndex()).title(s.getTitle()).url(s.getUrl()).snippet(s.getSnippet())
                    .sourceType(s.getSourceType()).build()).toList());
            if (response.getStructuredAnswer() != null) {
                var s = response.getStructuredAnswer();
                message.setStructuredAnswer(AiMessage.StructuredAnswer.builder().summary(s.getSummary())
                        .steps(s.getSteps()).cautions(s.getCautions()).basisNote(s.getBasisNote()).build());
            }
        }
        try { mongo.insert(message); }
        catch (DuplicateKeyException ex) {
            AiMessage existing = mongo.findById(message.getId(), AiMessage.class);
            if (existing == null || !Objects.equals(text, existing.getText()))
                throw new SessionMemoryFault("projection_conflict");
        }
    }
    public AiConversation owned(String user, String conversation) {
        return mongo.findOne(Query.query(Criteria.where("_id").is(conversation).and("userId").is(user)
                .and("deletedAt").is(null)), AiConversation.class);
    }
    public List<AiMessage> messages(String user, String conversation) {
        if (owned(user, conversation) == null) throw new SessionMemoryFault("conversation_not_found");
        return mongo.find(Query.query(Criteria.where("conversationId").is(conversation).and("userId").is(user)
                .and("completedTurn").is(true)).with(Sort.by("turnSeq", "ordinal")), AiMessage.class);
    }
    public boolean tombstone(String user, String conversation) {
        var result = mongo.updateFirst(Query.query(Criteria.where("_id").is(conversation).and("userId").is(user)
                .and("deletedAt").is(null)), new Update().set("deletedAt", Instant.now()).inc("executionVersion", 1L)
                .unset("memorySummary").unset("memorySourceMap").unset("memoryNextTriggerTokens")
                .unset("memoryTriggerPolicyVersion").unset("leaseRequestId").unset("leaseExpiresAt"), AiConversation.class);
        if (result.getMatchedCount() != 1) return false;
        // IDs/hash/tombstone stay to reject retries; purge all question/answer bodies.
        mongo.remove(Query.query(Criteria.where("conversationId").is(conversation)), AiMessage.class);
        mongo.updateMulti(Query.query(Criteria.where("conversationId").is(conversation)),
                new Update().set("state", "deleted").unset("userText").unset("finalResponse")
                    .unset("writeOutcomes").unset("usage").unset("manualRef"), AiTurnRequest.class);
        mongo.remove(Query.query(Criteria.where("conversationId").is(conversation)), AiMemoryVersion.class);
        return true;
    }
    public Optional<AiConversation> rename(String user, String conversation, String title) {
        return Optional.ofNullable(mongo.findAndModify(Query.query(Criteria.where("_id").is(conversation)
                .and("userId").is(user).and("deletedAt").is(null)), new Update().set("title", title)
                .set("customTitle", true), FindAndModifyOptions.options().returnNew(true), AiConversation.class));
    }
    private void release(AiConversation c, String request) {
        mongo.updateFirst(active(c.getUserId(), c.getId(), request, c.getExecutionVersion()),
                new Update().unset("leaseRequestId").unset("leaseExpiresAt"), AiConversation.class);
    }
    public String payloadHash(QARequest req) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("userId", trim(req.getUserId())); payload.put("query", req.getQuery());
        payload.put("manualId", req.getManualId()); payload.put("manualRefId", req.getManualRefId());
        payload.put("productId", req.getProductId()); payload.put("target", req.getTarget());
        payload.put("persistHistory", !Boolean.FALSE.equals(req.getPersistHistory()));
        payload.put("enableWebSearch", req.getEnableWebSearch()); payload.put("imageUrl", req.getImageUrl());
        payload.put("imageDataUrl", req.getImageDataUrl()); payload.put("enableImageRecognition", req.getEnableImageRecognition());
        try { return hex(mapper.writeValueAsBytes(payload)); }
        catch (Exception ex) { throw new SessionMemoryFault("request_hash_failed"); }
    }
    public static String hex(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String require(String value, String code) {
        String normalized = trim(value); if (normalized == null || normalized.length() > 128) throw new SessionMemoryFault(code);
        return normalized;
    }
    private static String shorten(String value, int max) { return value == null ? "新对话" : value.substring(0, Math.min(value.length(), max)); }
    private static AiMessage.ManualRef manualRef(QARequest req) {
        return AiMessage.ManualRef.builder().id(req.getManualRefId()).manualId(req.getManualId())
                .name(req.getManualName()).type(req.getManualType()).build();
    }
}
