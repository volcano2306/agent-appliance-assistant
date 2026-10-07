package com.example.demo;

import com.example.demo.config.SessionMemoryProperties;
import com.example.demo.dto.*;
import com.example.demo.model.*;
import com.example.demo.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in real Mongo semantics against a NEW UUID-named synthetic DB only.
 * No Spring boot/config, no dotenv, no model/client/business endpoint calls.
 */
@EnabledIfSystemProperty(named="session.memory.mongo.tests", matches="true")
class SessionMemoryMongoIntegrationTest {
    MongoClient client;
    String database;
    MongoTemplate mongo;
    ObjectMapper mapper;
    SessionMemoryProperties config;
    AiTurnLedgerService ledger;
    PythonAgentClient python;
    AiConversationMemoryService memory;

    @BeforeEach void setup() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.mongodb.driver"))
                .setLevel(ch.qos.logback.classic.Level.WARN);
        database = "session_memory_test_" + UUID.randomUUID().toString().replace("-", "");
        client = MongoClients.create("mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000&connectTimeoutMS=5000");
        client.getDatabase(database).runCommand(new Document("ping", 1));
        mongo = new MongoTemplate(client, database);
        mapper = new ObjectMapper().findAndRegisterModules();
        config = new SessionMemoryProperties(); config.setEnabled(true); config.setModelContextWindow(40000);
        config.setCompressionStrategy("rolling"); // Preserve historical rolling regression cases below.
        ledger = new AiTurnLedgerService(mongo, mapper, config); ledger.ensureIndexes();
        python = mock(PythonAgentClient.class);
        memory = new AiConversationMemoryService(mongo, ledger, python, config, mapper);
        when(python.summarize(any())).thenAnswer(invocation -> candidate(invocation.getArgument(0)));
    }
    @AfterEach void cleanup() {
        if (client != null) {
            if (database == null || !database.matches("session_memory_test_[0-9a-f]{32}"))
                throw new IllegalStateException("Refusing unsafe test DB cleanup");
            client.getDatabase(database).drop(); client.close();
        }
    }
    QARequest req(String user, String request, String conversation, String text) {
        QARequest req = new QARequest(); req.setUserId(user); req.setRequestId(request);
        req.setConversationId(conversation); req.setQuery(text); return req;
    }
    SessionMemory.PythonResponse answer(String text) {
        var response = new SessionMemory.PythonResponse(); response.setStatus("ok"); response.setAnswerText(text); return response;
    }
    SessionMemory.SummaryResult candidate(SessionMemory.SummaryRequest input) throws Exception {
        var turn = input.turns().get(input.turns().size() - 1);
        var summary = new SessionMemory.Summary(List.of(new SessionMemory.Fact("设备信息", turn.user().messageId())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new SessionMemory.SummaryResult(true, null, summary, mapper.writeValueAsBytes(summary).length,
                "utf8-byte-estimate-v1", false, input.sourceSnapshotHash(), turn.turnSeq(), List.of());
    }
    String seed(int turns) {
        String conversation = null;
        for (int i = 1; i <= turns; i++) {
            var started = ledger.begin(req("u1", "seed-" + i, conversation, "问题" + i));
            conversation = started.conversation().getId(); ledger.finish(started.request(), answer("回答" + i));
        }
        return conversation;
    }
    void thresholdCandidates() throws Exception {
        config.setCompressionStrategy("threshold");
        doAnswer(invocation -> {
            var result = candidate(invocation.getArgument(0));
            return new SessionMemory.SummaryResult(result.ok(), result.error(), result.summary(), 80,
                    "deepseek-v4-text-8f9f37ca37fd", false, result.sourceSnapshotHash(), result.summarizedThroughTurnSeq(), result.usage());
        }).when(python).summarize(any());
    }
    @Test void thresholdBelow1500KeepsAllBufferedHistoryWithoutSummaryCall() throws Exception {
        thresholdCandidates(); String conversation = seed(8);
        when(python.planMemory(any())).thenReturn(new SessionMemory.PlanResult(1499, 0, "below_threshold",
                "deepseek-v4-text-8f9f37ca37fd", false));
        var a = ledger.begin(req("u1", "threshold-a", conversation, "A")); var snapshot = memory.prepare(a);
        assertEquals("ok", snapshot.memoryStatus()); assertNull(snapshot.summary());
        assertEquals(3, snapshot.pendingTurns().size()); assertEquals(5, snapshot.recentTurns().size());
        verify(python, never()).summarize(any());
        assertEquals(memory.policy(), ledger.owned("u1", conversation).getSummaryPolicyVersion());
    }
    @Test void thresholdBatchPreservesFiveTurnsAndResetsTriggerAfterHeadroom() throws Exception {
        thresholdCandidates(); String conversation = seed(8);
        when(python.planMemory(any())).thenReturn(
                new SessionMemory.PlanResult(1500, 3, "compress", "deepseek-v4-text-8f9f37ca37fd", false),
                new SessionMemory.PlanResult(700, 0, "below_threshold", "deepseek-v4-text-8f9f37ca37fd", false));
        var a = ledger.begin(req("u1", "threshold-a", conversation, "A")); var snapshot = memory.prepare(a);
        assertEquals(3, snapshot.summarizedThroughTurnSeq()); assertTrue(snapshot.pendingTurns().isEmpty());
        assertEquals(List.of(4L, 5L, 6L, 7L, 8L), snapshot.recentTurns().stream().map(SessionMemory.Turn::turnSeq).toList());
        assertEquals(1500, ledger.owned("u1", conversation).getMemoryNextTriggerTokens());
        verify(python, times(1)).summarize(any());
    }
    @Test void failedThresholdSummaryPreservesRawAndGrowthGateSurvivesServiceRestart() throws Exception {
        thresholdCandidates(); String conversation = seed(8);
        when(python.planMemory(any())).thenAnswer(invocation -> {
            SessionMemory.PlanRequest plan = invocation.getArgument(0);
            return new SessionMemory.PlanResult(1500, plan.triggerTokens() > 1500 ? 0 : 3,
                    plan.triggerTokens() > 1500 ? "below_threshold" : "compress", "deepseek-v4-text-8f9f37ca37fd", false);
        });
        doThrow(new IllegalStateException("synthetic timeout")).when(python).summarize(any());
        var a = ledger.begin(req("u1", "threshold-a", conversation, "A")); var failed = memory.prepare(a);
        assertEquals("degraded", failed.memoryStatus()); assertNull(failed.summary());
        assertEquals(0, failed.summarizedThroughTurnSeq()); assertEquals(3, failed.pendingTurns().size());
        assertEquals(2000, ledger.owned("u1", conversation).getMemoryNextTriggerTokens());
        ledger.finish(a.request(), answer("A"));
        var restarted = new AiConversationMemoryService(mongo, new AiTurnLedgerService(mongo, mapper, config), python, config, mapper);
        var b = ledger.begin(req("u1", "threshold-b", conversation, "B")); var next = restarted.prepare(b);
        assertNull(next.summary()); assertEquals(4, next.pendingTurns().size());
        verify(python, times(1)).summarize(any());
    }
    @Test void insufficientThresholdSavingsPersistGrowthGateAndPolicyChangeResetsIt() throws Exception {
        thresholdCandidates(); String conversation = seed(8);
        when(python.planMemory(any())).thenReturn(
                new SessionMemory.PlanResult(3500, 3, "compress", "deepseek-v4-text-8f9f37ca37fd", false),
                new SessionMemory.PlanResult(3400, 0, "below_threshold", "deepseek-v4-text-8f9f37ca37fd", false));
        var a = ledger.begin(req("u1", "threshold-a", conversation, "A")); memory.prepare(a);
        assertEquals(3900, ledger.owned("u1", conversation).getMemoryNextTriggerTokens());
        ledger.finish(a.request(), answer("A")); config.setSummaryMaxTokens(1000);
        when(python.planMemory(any())).thenAnswer(invocation -> {
            SessionMemory.PlanRequest plan = invocation.getArgument(0);
            assertEquals(1500, plan.triggerTokens());
            assertNull(plan.memory().summary()); assertEquals(0, plan.memory().summarizedThroughTurnSeq());
            return new SessionMemory.PlanResult(1499, 0, "below_threshold", "deepseek-v4-text-8f9f37ca37fd", false);
        });
        var b = ledger.begin(req("u1", "threshold-b", conversation, "B")); var rebuilt = memory.prepare(b);
        assertEquals(0, rebuilt.summarizedThroughTurnSeq()); assertEquals(4, rebuilt.pendingTurns().size());
        assertTrue(rebuilt.policyVersion().contains("S1000")); assertEquals("ok", rebuilt.memoryStatus());
    }
    @Test void deletionDuringThresholdPlanningRejectsGuardCas() throws Exception {
        thresholdCandidates(); String conversation = seed(8);
        when(python.planMemory(any())).thenAnswer(invocation -> {
            ledger.tombstone("u1", conversation);
            return new SessionMemory.PlanResult(1499, 0, "below_threshold", "deepseek-v4-text-8f9f37ca37fd", false);
        });
        var a = ledger.begin(req("u1", "threshold-a", conversation, "A"));
        assertThrows(SessionMemoryFault.class, () -> memory.prepare(a));
        assertNull(ledger.owned("u1", conversation)); verify(python, never()).summarize(any());
    }
    @Test void canonicalIdExistsBeforeAnyModelDispatchAndErrorAnswersStillComplete() {
        QARequest req = req("u1", "first", null, "问题"); var started = ledger.begin(req);
        assertNotNull(req.getConversationId()); assertEquals(req.getConversationId(), started.request().getConversationId());
        assertEquals(req.getConversationId(), started.conversation().getId());
        var error = answer("安全兜底"); error.setStatus("error"); error.setError("upstream_unavailable");
        QAResponse result = ledger.finish(started.request(), error);
        assertEquals(req.getConversationId(), result.getConversationId());
        assertEquals(1, CompleteTurnReader.read(ledger.messages("u1", req.getConversationId())).size());
        assertEquals("error", ledger.begin(req("u1", "first", null, "问题")).cached().getStatus());
    }
    @Test void concurrentDuplicateFirstTurnHasOnlyOneConversationAndOneExecution() throws Exception {
        var executor = Executors.newFixedThreadPool(6); var gate = new CountDownLatch(1);
        try {
            List<Future<AiTurnLedgerService.Started>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> {
                gate.await(); try { return ledger.begin(req("u1", "duplicate", null, "相同问题")); }
                catch (SessionMemoryFault ex) { assertTrue(Set.of("conversation_busy", "request_in_progress").contains(ex.getMessage())); return null; }
            }));
            gate.countDown(); List<AiTurnLedgerService.Started> winners = new ArrayList<>();
            for (var future : futures) { var result = future.get(10, TimeUnit.SECONDS); if (result != null) winners.add(result); }
            assertEquals(1, winners.size()); assertEquals(1, mongo.count(new Query(), AiConversation.class));
            assertEquals(1, mongo.count(new Query(), AiTurnRequest.class));
            ledger.finish(winners.get(0).request(), answer("唯一结果"));
            assertEquals("唯一结果", ledger.begin(req("u1", "duplicate", null, "相同问题")).cached().getAnswerText());
            assertEquals(2, mongo.count(new Query(), AiMessage.class));
        } finally { executor.shutdownNow(); }
    }
    @Test void requestIdContentConflictCreatesNoSecondExecution() {
        var started = ledger.begin(req("u1", "same-id", null, "A"));
        assertEquals("request_id_conflict", assertThrows(SessionMemoryFault.class,
                () -> ledger.begin(req("u1", "same-id", null, "B"))).getMessage());
        assertEquals(1, mongo.count(new Query(), AiTurnRequest.class));
    }
    @Test void foreignAndInvalidConversationIdsAreRejectedBeforeLedgerInsert() {
        var started = ledger.begin(req("u1", "first", null, "A"));
        for (QARequest req : List.of(req("u2", "foreign", started.conversation().getId(), "B"), req("u1", "missing", "missing", "C")))
            assertEquals("conversation_not_found", assertThrows(SessionMemoryFault.class, () -> ledger.begin(req)).getMessage());
        assertEquals(1, mongo.count(new Query(), AiTurnRequest.class)); verifyNoInteractions(python);
    }
    @Test void sameConversationBusyButAnotherConversationCanProceed() {
        var a = ledger.begin(req("u1", "a1", null, "A"));
        QARequest second = req("u1", "a2", a.conversation().getId(), "A2");
        assertEquals("conversation_busy", assertThrows(SessionMemoryFault.class, () -> ledger.begin(second)).getMessage());
        var b = ledger.begin(req("u1", "b1", null, "B"));
        assertNotEquals(a.conversation().getId(), b.conversation().getId());
        ledger.finish(a.request(), answer("A answer"));
        var next = ledger.begin(second); assertEquals(2L, next.request().getTurnSeq());
        assertEquals(List.of(1L), CompleteTurnReader.read(ledger.messages("u1", a.conversation().getId())).stream().map(SessionMemory.Turn::turnSeq).toList());
    }
    @Test void expiredRequestBecomesUnknownAndCannotBeAutomaticallyReexecuted() {
        var a = ledger.begin(req("u1", "a", null, "A"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(a.conversation().getId())),
                new Update().set("leaseExpiresAt", Instant.EPOCH), AiConversation.class);
        assertEquals("conversation_busy", assertThrows(SessionMemoryFault.class,
                () -> ledger.begin(req("u1", "b", a.conversation().getId(), "B"))).getMessage());
        assertEquals("request_result_unknown", assertThrows(SessionMemoryFault.class,
                () -> ledger.begin(req("u1", "a", null, "A"))).getMessage());
        assertFalse(ledger.renew(a.request())); assertFalse(ledger.check("u1", a.conversation().getId(), "a", a.request().getExecutionVersion()));
        assertEquals(1L, ledger.owned("u1", a.conversation().getId()).getExecutionVersion());
    }
    @Test void crashBetweenMessageProjectionsRecoversFromSavedResultWithoutReexecution() {
        MongoTemplate spy = spy(mongo); var failed = new AtomicBoolean();
        doAnswer(invocation -> {
            AiMessage message = invocation.getArgument(0);
            if (message.getOrdinal() == 1 && failed.compareAndSet(false, true)) throw new IllegalStateException("synthetic crash");
            return invocation.callRealMethod();
        }).when(spy).insert(any(AiMessage.class));
        var faulty = new AiTurnLedgerService(spy, mapper, config);
        var a = faulty.begin(req("u1", "a", null, "A"));
        assertThrows(IllegalStateException.class, () -> faulty.finish(a.request(), answer("saved final")));
        assertEquals(1, mongo.count(new Query(), AiMessage.class));
        var restored = new AiTurnLedgerService(mongo, mapper, config);
        var replay = restored.begin(req("u1", "a", null, "A"));
        assertEquals("saved final", replay.cached().getAnswerText()); assertEquals(2, mongo.count(new Query(), AiMessage.class));
        assertEquals(1L, restored.owned("u1", a.conversation().getId()).getHistoryRevision());
        verifyNoInteractions(python);
    }
    @Test void failureSavingFinalLedgerResultDoesNotProjectMessagesOrRedispatchUnknown() {
        MongoTemplate faulty = spy(mongo);
        doAnswer(invocation -> {
            Update update = invocation.getArgument(1);
            var set = (Document) update.getUpdateObject().get("$set");
            if (set != null && "result_saved".equals(set.get("state"))) throw new IllegalStateException("synthetic ledger failure");
            return invocation.callRealMethod();
        }).when(faulty).findAndModify(any(Query.class), any(Update.class), any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(AiTurnRequest.class));
        var writer = new AiTurnLedgerService(faulty, mapper, config);
        var a = writer.begin(req("u1", "a", null, "A"));
        assertThrows(IllegalStateException.class, () -> writer.finish(a.request(), answer("observed final")));
        assertEquals(0, mongo.count(new Query(), AiMessage.class));
        ledger.markUnknown(a.request());
        var restarted = new AiTurnLedgerService(mongo, mapper, config);
        assertEquals("request_result_unknown", assertThrows(SessionMemoryFault.class,
                () -> restarted.begin(req("u1", "a", null, "A"))).getMessage());
        assertNull(mongo.findById(a.request().getId(), AiTurnRequest.class).getFinalResponse());
        verifyNoInteractions(python);
    }
    @Test void crashAfterHeaderAdvanceBeforeLedgerCompletionIsAlsoRecoverable() {
        MongoTemplate spy = spy(mongo); var failed = new AtomicBoolean();
        doAnswer(invocation -> {
            Update update = invocation.getArgument(1);
            var set = (Document) update.getUpdateObject().get("$set");
            if (set != null && "completed".equals(set.get("state")) && failed.compareAndSet(false, true))
                throw new IllegalStateException("synthetic crash");
            return invocation.callRealMethod();
        }).when(spy).updateFirst(any(Query.class), any(Update.class), eq(AiTurnRequest.class));
        var faulty = new AiTurnLedgerService(spy, mapper, config); var a = faulty.begin(req("u1", "a", null, "A"));
        assertThrows(IllegalStateException.class, () -> faulty.finish(a.request(), answer("saved")));
        assertNull(ledger.owned("u1", a.conversation().getId()).getLeaseRequestId());
        assertNotNull(ledger.begin(req("u1", "a", null, "A")).cached());
        assertEquals(2, mongo.count(new Query(), AiMessage.class));
    }
    @Test void deletionInvalidatesLateAnswerAndPreservesTombstoneWithoutBody() {
        var a = ledger.begin(req("u1", "a", null, "A"));
        assertTrue(ledger.tombstone("u1", a.conversation().getId()));
        assertThrows(SessionMemoryFault.class, () -> ledger.finish(a.request(), answer("late")));
        assertEquals(0, mongo.count(new Query(), AiMessage.class)); assertEquals(0, mongo.count(new Query(), AiMemoryVersion.class));
        assertNotNull(mongo.findById(a.conversation().getId(), AiConversation.class).getDeletedAt());
        assertNull(mongo.findById(a.request().getId(), AiTurnRequest.class).getUserText());
        assertThrows(SessionMemoryFault.class, () -> ledger.begin(req("u1", "a", null, "A")));
        assertEquals(1, mongo.count(new Query(), AiConversation.class));
    }
    @Test void deleteDuringProjectionCannotLeaveLateQuestionAnswerBodies() {
        MongoTemplate spy = spy(mongo); var deleted = new AtomicBoolean();
        doAnswer(invocation -> {
            AiMessage message = invocation.getArgument(0);
            if (message.getOrdinal() == 1 && deleted.compareAndSet(false, true)) ledger.tombstone("u1", message.getConversationId());
            return invocation.callRealMethod();
        }).when(spy).insert(any(AiMessage.class));
        var faulty = new AiTurnLedgerService(spy, mapper, config); var a = faulty.begin(req("u1", "a", null, "A"));
        assertThrows(SessionMemoryFault.class, () -> faulty.finish(a.request(), answer("late")));
        assertEquals(0, mongo.count(new Query(), AiMessage.class));
    }
    @Test void prepareSevenTurnsProducesSummaryAndFiveCompleteRecentTurns() {
        String conversation = seed(7); var a = ledger.begin(req("u1", "next", conversation, "当前问题"));
        var snapshot = memory.prepare(a);
        assertEquals(2, snapshot.summarizedThroughTurnSeq()); assertEquals(5, snapshot.recentTurns().size());
        assertTrue(snapshot.pendingTurns().isEmpty()); assertNotNull(snapshot.summary());
        assertEquals(1, snapshot.sourceMap().size());
        assertEquals(1, mongo.count(new Query(), AiMemoryVersion.class));
        assertEquals(7L, snapshot.historyRevision());
    }
    @Test void savedWriteReceiptsEnterOnlyTheirOldTurnSummaryBatch() throws Exception {
        var first = ledger.begin(req("u1", "receipt-turn", null, "报修"));
        var response = answer("结果未知");
        response.setObservedWriteOutcomes(List.of(new SessionMemory.WriteOutcome("create_repair_order", "unknown", "device", "", "receipt-turn")));
        ledger.finish(first.request(), response);
        String conversation = first.conversation().getId();
        for (int i = 2; i <= 7; i++) {
            var next = ledger.begin(req("u1", "receipt-seed-" + i, conversation, "问题" + i));
            ledger.finish(next.request(), answer("回答" + i));
        }
        doAnswer(invocation -> {
            SessionMemory.SummaryRequest input = invocation.getArgument(0);
            assertEquals(1, input.toolResults().size());
            var receipt = input.toolResults().get(0);
            assertEquals("unknown", receipt.receipt().writeStatus());
            assertEquals("tool", input.sourceMap().get(receipt.source()).role());
            var summary = new SessionMemory.Summary(List.of(), List.of(), List.of(new SessionMemory.Attempt(
                    "报修", "结果未知", "tool_observed", receipt.source())), List.of(), List.of(), List.of(), List.of());
            return new SessionMemory.SummaryResult(true, null, summary, mapper.writeValueAsBytes(summary).length,
                    "utf8-byte-estimate-v1", false, input.sourceSnapshotHash(), input.turns().get(input.turns().size()-1).turnSeq(), List.of());
        }).when(python).summarize(any());
        var next = ledger.begin(req("u1", "receipt-next", conversation, "当前问题"));
        var snapshot = memory.prepare(next);
        assertEquals("tool_observed", snapshot.summary().attempts().get(0).state());
        assertEquals(1, snapshot.sourceMap().size());
    }
    @Test void failedVersionInsertNeverPublishesSummaryOrCoverage() {
        String conversation = seed(7); var next = ledger.begin(req("u1", "a", conversation, "A"));
        MongoTemplate faulty = spy(mongo);
        doThrow(new IllegalStateException("synthetic record failure")).when(faulty).insert(any(AiMemoryVersion.class));
        var manager = new AiConversationMemoryService(faulty, ledger, python, config, mapper);
        assertThrows(IllegalStateException.class, () -> manager.prepare(next));
        var saved = ledger.owned("u1", conversation);
        assertNull(saved.getMemorySummary()); assertEquals(0L, saved.getSummarizedThroughTurnSeq());
        assertEquals(7, CompleteTurnReader.read(ledger.messages("u1", conversation)).size());
    }
    @Test void staleHistoryRevisionCandidateCannotOverwriteAndIsCleaned() throws Exception {
        String conversation = seed(7); var next = ledger.begin(req("u1", "a", conversation, "A"));
        doAnswer(invocation -> {
            var result = candidate(invocation.getArgument(0));
            mongo.updateFirst(Query.query(Criteria.where("_id").is(conversation)), new Update().inc("historyRevision", 1L), AiConversation.class);
            return result;
        }).when(python).summarize(any());
        assertEquals("memory_cas_conflict", assertThrows(SessionMemoryFault.class, () -> memory.prepare(next)).getMessage());
        assertEquals(0, mongo.count(new Query(), AiMemoryVersion.class));
        assertNull(ledger.owned("u1", conversation).getMemorySummary());
    }
    @Test void twentyTurnsAreBatchedAndThreeToFivePolicyCanBeRebuiltAfterServiceRestart() {
        String conversation = seed(20); var a = ledger.begin(req("u1", "a", conversation, "A"));
        var five = memory.prepare(a); assertEquals(15, five.summarizedThroughTurnSeq()); assertEquals(5, five.recentTurns().size());
        ledger.finish(a.request(), answer("A answer"));
        config.setRecentTurns(3); config.setSummaryMaxTokens(1000);
        var restarted = new AiConversationMemoryService(mongo, new AiTurnLedgerService(mongo, mapper, config), python, config, mapper);
        var b = ledger.begin(req("u1", "b", conversation, "B")); var three = restarted.prepare(b);
        assertEquals(18, three.summarizedThroughTurnSeq()); assertEquals(3, three.recentTurns().size());
        assertTrue(three.policyVersion().contains("S1000"));
        verify(python, atLeast(2)).summarize(any());
    }
    @Test void incrementalBatchFailureKeepsSuccessfulCoverageAndRemainingRawTurns() throws Exception {
        String conversation = seed(7); var a = ledger.begin(req("u1", "a", conversation, "A")); memory.prepare(a);
        ledger.finish(a.request(), answer("A answer"));
        for (int i = 0; i < 12; i++) {
            var s = ledger.begin(req("u1", "more-" + i, conversation, "Q" + "x".repeat(900))); ledger.finish(s.request(), answer("A"));
        }
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> { if (calls.incrementAndGet() > 1) throw new IllegalStateException("second batch failed");
            return candidate(invocation.getArgument(0)); }).when(python).summarize(any());
        var b = ledger.begin(req("u1", "b", conversation, "B")); var degraded = memory.prepare(b);
        assertEquals("degraded", degraded.memoryStatus()); assertTrue(degraded.summarizedThroughTurnSeq() > 2);
        assertFalse(degraded.pendingTurns().isEmpty());
        assertEquals(20, degraded.summarizedThroughTurnSeq() + degraded.pendingTurns().size() + degraded.recentTurns().size());
        assertEquals(degraded.summarizedThroughTurnSeq(), ledger.owned("u1", conversation).getSummarizedThroughTurnSeq());
    }
    @Test void anOversizedSingleTurnDoesNotCallSummaryAndIsKeptRaw() {
        String conversation = seed(6); var messages = ledger.messages("u1", conversation);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(messages.get(0).getId())), new Update().set("text", "x".repeat(20000)), AiMessage.class);
        var a = ledger.begin(req("u1", "a", conversation, "A")); var snapshot = memory.prepare(a);
        assertEquals("degraded", snapshot.memoryStatus()); assertEquals(1, snapshot.pendingTurns().size());
        assertEquals(20000, snapshot.pendingTurns().get(0).user().content().length());
        verifyNoInteractions(python); assertEquals(0, snapshot.summarizedThroughTurnSeq());
    }
    @Test void compressionRollbackKeepsUnknownProtectionAndOriginalSummaryForReenable() {
        String conversation = seed(7); var a = ledger.begin(req("u1", "a", conversation, "A"));
        var original = memory.prepare(a); ledger.finish(a.request(), answer("A answer"));
        var b = ledger.begin(req("u1", "b", conversation, "B")); ledger.markUnknown(b.request());
        config.setEnabled(false); config.setExecutionEnabled(true); assertTrue(config.isRuntimeEnabled());
        assertEquals("request_result_unknown", assertThrows(SessionMemoryFault.class,
                () -> ledger.begin(req("u1", "b", conversation, "B"))).getMessage());
        ledger.closeUnknown("u1", conversation, "b", b.request().getExecutionVersion(), true);
        clearInvocations(python);
        var next = ledger.begin(req("u1", "next", conversation, "当前问题"));
        var fallback = memory.prepare(next);
        assertEquals("disabled", fallback.memoryStatus()); assertNull(fallback.summary());
        assertEquals(List.of(7L, 8L, 9L), fallback.recentTurns().stream().map(SessionMemory.Turn::turnSeq).toList());
        assertEquals(original.summary(), ledger.owned("u1", conversation).getMemorySummary());
        verifyNoInteractions(python); assertFalse(ledger.check("u1", conversation, "b", b.request().getExecutionVersion()));
    }
    @Test void summaryFailureFallsBackToOldSummaryAndAllPendingRawTurns() {
        String conversation = seed(7); var a = ledger.begin(req("u1", "a", conversation, "A"));
        var first = memory.prepare(a); ledger.finish(a.request(), answer("A answer"));
        doThrow(new IllegalStateException("synthetic summary timeout")).when(python).summarize(any());
        var b = ledger.begin(req("u1", "b", conversation, "B")); var snapshot = memory.prepare(b);
        assertEquals("degraded", snapshot.memoryStatus()); assertEquals(first.summary(), snapshot.summary());
        assertEquals(2, snapshot.summarizedThroughTurnSeq());
        assertEquals(List.of(3L), snapshot.pendingTurns().stream().map(SessionMemory.Turn::turnSeq).toList());
        assertEquals(5, snapshot.recentTurns().size());
    }
    @Test void policyThreeToFiveRebuildsCoverageFromRawInsteadOfOverlappingOldSummary() {
        config.setRecentTurns(3); String conversation = seed(10);
        var a = ledger.begin(req("u1", "a", conversation, "A")); var three = memory.prepare(a);
        assertEquals(7, three.summarizedThroughTurnSeq()); ledger.finish(a.request(), answer("A answer"));
        config.setRecentTurns(5); var b = ledger.begin(req("u1", "b", conversation, "B")); var five = memory.prepare(b);
        assertEquals(6, five.summarizedThroughTurnSeq());
        assertEquals(List.of(7L, 8L, 9L, 10L, 11L), five.recentTurns().stream().map(SessionMemory.Turn::turnSeq).toList());
        assertTrue(five.pendingTurns().isEmpty()); assertTrue(five.policyVersion().contains("N5"));
    }
    @Test void failedPolicyRebuildPublishesNoMixedSummaryAndKeepsAllRawTurns() {
        config.setRecentTurns(3); String conversation = seed(10);
        var a = ledger.begin(req("u1", "a", conversation, "A")); memory.prepare(a); ledger.finish(a.request(), answer("answer"));
        config.setRecentTurns(5); doThrow(new IllegalStateException("fail")).when(python).summarize(any());
        var b = ledger.begin(req("u1", "b", conversation, "B")); var failed = memory.prepare(b);
        assertNull(failed.summary()); assertEquals(0, failed.summarizedThroughTurnSeq());
        assertEquals(6, failed.pendingTurns().size()); assertEquals(5, failed.recentTurns().size());
        assertTrue(ledger.owned("u1", conversation).getSummaryPolicyVersion().contains("N3"));
    }
    @Test void deletionDuringSummaryGenerationRejectsCasAndCleansCandidateBody() throws Exception {
        String conversation = seed(7); var a = ledger.begin(req("u1", "a", conversation, "A"));
        doAnswer(invocation -> {
            SessionMemory.SummaryRequest input = invocation.getArgument(0); var result = candidate(input);
            ledger.tombstone("u1", conversation); return result;
        }).when(python).summarize(any());
        assertEquals("memory_cas_conflict", assertThrows(SessionMemoryFault.class, () -> memory.prepare(a)).getMessage());
        assertEquals(0, mongo.count(new Query(), AiMemoryVersion.class)); assertEquals(0, mongo.count(new Query(), AiMessage.class));
    }
    @Test void legacyMigrationIsExplicitDryRunAndRepeatDoesNotDuplicateOrChangeText() {
        AiConversation c = AiConversation.builder().id("legacy-conversation").userId("u1").title("原始标题").build(); mongo.insert(c);
        AiMessage u = AiMessage.builder().id("legacy-user").conversationId(c.getId()).role("user").text("原始问题")
                .createdAt(Instant.EPOCH).build();
        AiMessage a = AiMessage.builder().id("legacy-answer").conversationId(c.getId()).role("ai").text("原始回答")
                .createdAt(Instant.EPOCH.plusMillis(1)).sources(List.of(AiMessage.SourceRef.builder().fragmentId("f-original").page(2).build())).build();
        AiMessage orphan = AiMessage.builder().id("orphan").conversationId(c.getId()).role("user").text("孤立问题")
                .createdAt(Instant.EPOCH.plusMillis(2)).build();
        mongo.insertAll(List.of(u, a, orphan)); var migration = new AiMemoryMigrationService(mongo);
        assertEquals(1, migration.migrate("u1", c.getId(), true).pairs()); assertNull(mongo.findById(u.getId(), AiMessage.class).getTurnSeq());
        assertEquals(1, migration.migrate("u1", c.getId(), false).pairs()); assertEquals(0, migration.migrate("u1", c.getId(), false).pairs());
        assertEquals(3, mongo.count(new Query(), AiMessage.class)); assertEquals(1, mongo.count(new Query(), AiTurnRequest.class));
        assertEquals("原始回答", mongo.findById(a.getId(), AiMessage.class).getText());
        assertEquals("f-original", mongo.findById(a.getId(), AiMessage.class).getSources().get(0).getFragmentId());
        assertTrue(mongo.findById(orphan.getId(), AiMessage.class).getOrphaned());
        assertEquals("原始标题", mongo.findById(c.getId(), AiConversation.class).getTitle());
    }
    @Test void internalReceiptsAndUsageAreSavedButNeverReturnedAsPythonSubclass() throws Exception {
        var a = ledger.begin(req("u1", "a", null, "A")); var response = answer("结果");
        response.setObservedWriteOutcomes(List.of(new SessionMemory.WriteOutcome("create_repair_order", "unknown", "target", "", "a")));
        response.setUsageBreakdown(List.of(new SessionMemory.Usage("answer", 100L, 20L, null, 1.0, "ok")));
        QAResponse result = ledger.finish(a.request(), response);
        assertEquals(QAResponse.class, result.getClass()); assertFalse(mapper.writeValueAsString(result).contains("observedWriteOutcomes"));
        var saved = mongo.findById(a.request().getId(), AiTurnRequest.class);
        assertEquals(1, saved.getWriteOutcomes().size()); assertEquals(1, saved.getUsage().size());
    }
    @Test void trustedLateTerminalSettlesUnknownWithoutReexecutingItsWrite() {
        var a = ledger.begin(req("u1", "a", null, "A")); ledger.markUnknown(a.request());
        var result = answer("已观察到工单结果"); result.setRequestId("a"); result.setConversationId(a.conversation().getId());
        result.setObservedWriteOutcomes(List.of(new SessionMemory.WriteOutcome("create_repair_order", "succeeded", "verified-target", "verified-order", "a")));
        var terminal = ledger.acceptTerminal("u1", a.conversation().getId(), "a", a.request().getExecutionVersion(), result);
        assertEquals("已观察到工单结果", terminal.getAnswerText());
        assertNotNull(ledger.begin(req("u1", "a", null, "A")).cached());
        assertNull(ledger.owned("u1", a.conversation().getId()).getBlockedRequestId());
        assertNotNull(ledger.begin(req("u1", "next", a.conversation().getId(), "下一问题")));
        verifyNoInteractions(python);
    }
    @Test void trustedTerminalCanBeTheFirstObserverOfLeaseExpiryWithoutLosingWriteReceipt() {
        var a = ledger.begin(req("u1", "expired-terminal", null, "A"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(a.conversation().getId())),
                new Update().set("leaseExpiresAt", Instant.EPOCH), AiConversation.class);
        var result = answer("已观察到的业务结果"); result.setConversationId(a.conversation().getId()); result.setRequestId("expired-terminal");
        result.setObservedWriteOutcomes(List.of(new SessionMemory.WriteOutcome("add_manual_to_account", "succeeded", "manual", "", "expired-terminal")));
        ledger.acceptTerminal("u1", a.conversation().getId(), "expired-terminal", a.request().getExecutionVersion(), result);
        var saved = mongo.findById(a.request().getId(), AiTurnRequest.class);
        assertEquals("completed", saved.getState()); assertEquals("succeeded", saved.getWriteOutcomes().get(0).writeStatus());
        assertNull(ledger.owned("u1", a.conversation().getId()).getBlockedRequestId());
        assertFalse(ledger.check("u1", a.conversation().getId(), "expired-terminal", a.request().getExecutionVersion()));
        assertNotNull(ledger.begin(req("u1", "new", a.conversation().getId(), "new")));
        verifyNoInteractions(python);
    }
    @Test void explicitRecoveryAcknowledgesUnknownAndDoesNotClaimSuccessOrRetry() {
        var a = ledger.begin(req("u1", "a", null, "A")); ledger.markUnknown(a.request());
        assertThrows(SessionMemoryFault.class, () -> ledger.closeUnknown("u1", a.conversation().getId(), "a", a.request().getExecutionVersion(), false));
        var recovered = ledger.closeUnknown("u1", a.conversation().getId(), "a", a.request().getExecutionVersion(), true);
        assertEquals("error", recovered.getStatus()); assertTrue(recovered.getAnswerText().contains("结果仍未知"));
        assertEquals(0, mongo.findById(a.request().getId(), AiTurnRequest.class).getWriteOutcomes().size());
        assertEquals(1, CompleteTurnReader.read(ledger.messages("u1", a.conversation().getId())).size());
    }
    @Test void terminalCannotChangeIdentityOrResurrectDeletedConversation() {
        var a = ledger.begin(req("u1", "a", null, "A")); ledger.markUnknown(a.request());
        var result = answer("late"); result.setRequestId("a"); result.setConversationId(a.conversation().getId());
        assertThrows(SessionMemoryFault.class, () -> ledger.acceptTerminal("u2", a.conversation().getId(), "a", a.request().getExecutionVersion(), result));
        ledger.tombstone("u1", a.conversation().getId());
        assertThrows(SessionMemoryFault.class, () -> ledger.acceptTerminal("u1", a.conversation().getId(), "a", a.request().getExecutionVersion(), result));
        assertEquals(0, mongo.count(new Query(), AiMessage.class));
    }
    @Test void crashBetweenLeaseAllocationAndLedgerRunningDoesNotLeaveAnUnrecoverablePreparedLease() {
        var a = ledger.begin(req("u1", "a", null, "A"));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(a.request().getId())),
                new Update().set("state", "prepared").unset("executionVersion").unset("turnSeq"), AiTurnRequest.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(a.conversation().getId())),
                new Update().set("leaseExpiresAt", Instant.EPOCH), AiConversation.class);
        assertThrows(SessionMemoryFault.class, () -> ledger.begin(req("u1", "a", null, "A")));
        var unknown = mongo.findById(a.request().getId(), AiTurnRequest.class);
        assertEquals("unknown", unknown.getState()); assertEquals(a.request().getExecutionVersion(), unknown.getExecutionVersion());
        ledger.closeUnknown("u1", a.conversation().getId(), "a", unknown.getExecutionVersion(), true);
        assertNotNull(ledger.begin(req("u1", "next", a.conversation().getId(), "next")));
    }
}
