package com.example.demo;

import com.example.demo.config.SessionMemoryProperties;
import com.example.demo.dto.*;
import com.example.demo.model.*;
import com.example.demo.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Two separate JVMs with an actual dedicated mongod process restart between them.
 * Never boots Spring, loads dotenv, or connects to the existing Mongo engine.
 */
@EnabledIfSystemProperty(named="session.memory.restart.phase", matches="seed|verify")
class SessionMemoryEngineRestartTest {
    static final String USER = "engine-test-user", CONVERSATION = "legacy-engine-test";
    MongoClient client;
    MongoTemplate mongo;
    ObjectMapper mapper;
    SessionMemoryProperties config;
    AiTurnLedgerService ledger;
    PythonAgentClient python;
    AiConversationMemoryService memory;
    Path evidence;

    @BeforeEach void setup() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.mongodb.driver"))
            .setLevel(ch.qos.logback.classic.Level.WARN);
        String uri = System.getProperty("session.memory.mongo.uri", "");
        String database = System.getProperty("session.memory.restart.database", "");
        if (!uri.matches("mongodb://127\\.0\\.0\\.1:[0-9]{1,5}/?")
                || uri.contains(":27017") || !database.matches("session_memory_test_[0-9a-f]{32}"))
            throw new IllegalArgumentException("dedicated_engine_required");
        evidence = Path.of(System.getProperty("session.memory.restart.evidence"));
        mapper = new ObjectMapper().findAndRegisterModules();
        client = MongoClients.create(uri); mongo = new MongoTemplate(client, database);
        config = new SessionMemoryProperties(); config.setEnabled(true); config.setExecutionEnabled(true);
        config.setModelContextWindow(40000);
        ledger = new AiTurnLedgerService(mongo, mapper, config); ledger.ensureIndexes();
        python = mock(PythonAgentClient.class);
        when(python.planMemory(any())).thenAnswer(invocation -> {
            SessionMemory.PlanRequest input = invocation.getArgument(0);
            return input.summaryRequest() == null
                ? new SessionMemory.PlanResult(700, 0, "below_threshold", "deepseek-v4-text-8f9f37ca37fd", false)
                : new SessionMemory.PlanResult(1800, input.memory().pendingTurns().size(), "compress", "deepseek-v4-text-8f9f37ca37fd", false);
        });
        when(python.summarize(any())).thenAnswer(invocation -> {
            SessionMemory.SummaryRequest input = invocation.getArgument(0);
            var turn = input.turns().get(input.turns().size()-1);
            var summary = new SessionMemory.Summary(List.of(new SessionMemory.Fact("合成型号M100", turn.user().messageId())),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            return new SessionMemory.SummaryResult(true, null, summary, 80, "deepseek-v4-text-8f9f37ca37fd", false,
                input.sourceSnapshotHash(), turn.turnSeq(), List.of());
        });
        memory = new AiConversationMemoryService(mongo, ledger, python, config, mapper);
    }
    @AfterEach void close() { if (client != null) client.close(); }

    QARequest request(String id, String text) {
        var request = new QARequest(); request.setUserId(USER); request.setConversationId(CONVERSATION);
        request.setRequestId(id); request.setQuery(text); return request;
    }
    SessionMemory.PythonResponse answer(String text) {
        var response = new SessionMemory.PythonResponse(); response.setStatus("ok"); response.setAnswerText(text); return response;
    }
    void assertOriginals() {
        for (int n=1; n<=10; n++) {
            var u = mongo.findById("legacy-u"+n, AiMessage.class);
            var a = mongo.findById("legacy-a"+n, AiMessage.class);
            assertEquals("合成旧问题"+n, u.getText()); assertEquals("合成旧回答"+n, a.getText());
            assertEquals("synthetic-original-source", a.getSources().get(0).getFragmentId());
            assertEquals(Instant.EPOCH.plusMillis(n*2L), u.getCreatedAt());
        }
    }
    @Test void migrationRestartPolicyRollbackAndUnknownRecovery() throws Exception {
        if ("seed".equals(System.getProperty("session.memory.restart.phase"))) {
            assertEquals(0, mongo.count(new Query(), AiConversation.class));
            mongo.insert(AiConversation.builder().id(CONVERSATION).userId(USER).title("合成旧会话").build());
            for (int n=1; n<=10; n++) {
                mongo.insert(AiMessage.builder().id("legacy-u"+n).conversationId(CONVERSATION).role("user")
                    .text("合成旧问题"+n).createdAt(Instant.EPOCH.plusMillis(n*2L)).build());
                mongo.insert(AiMessage.builder().id("legacy-a"+n).conversationId(CONVERSATION).role("ai")
                    .text("合成旧回答"+n).createdAt(Instant.EPOCH.plusMillis(n*2L+1))
                    .sources(List.of(AiMessage.SourceRef.builder().fragmentId("synthetic-original-source").page(2).build())).build());
            }
            var migration = new AiMemoryMigrationService(mongo);
            assertEquals(10, migration.migrate(USER, CONVERSATION, true).pairs());
            assertNull(mongo.findById("legacy-u1", AiMessage.class).getTurnSeq());
            assertEquals(10, migration.migrate(USER, CONVERSATION, false).pairs());
            assertEquals(0, migration.migrate(USER, CONVERSATION, false).pairs());
            assertOriginals();
            var started = ledger.begin(request("before-restart", "合成已完成问题"));
            var snapshot = memory.prepare(started); assertEquals(5, snapshot.summarizedThroughTurnSeq());
            var response = answer("重启前已保存的合成回答");
            response.setObservedWriteOutcomes(List.of(new SessionMemory.WriteOutcome(
                "create_repair_order", "unknown", "synthetic-device", "", "before-restart")));
            ledger.finish(started.request(), response);
            var unresolved = ledger.begin(request("unresolved", "合成未知问题")); ledger.markUnknown(unresolved.request());
            mapper.writeValue(evidence.toFile(), Map.of("phase", "seed-complete", "turns", 11,
                "summaryVersion", snapshot.summaryVersion(), "through", 5, "unknownFence", unresolved.request().getExecutionVersion()));
            return;
        }
        var saved = mapper.readTree(evidence.toFile()); assertEquals("seed-complete", saved.get("phase").asText());
        assertOriginals(); assertEquals(11, CompleteTurnReader.read(ledger.messages(USER, CONVERSATION)).size());
        assertEquals(5, ledger.owned(USER, CONVERSATION).getSummarizedThroughTurnSeq());
        assertEquals(saved.get("summaryVersion").asLong(), ledger.owned(USER, CONVERSATION).getSummaryVersion());
        assertEquals(1500, ledger.owned(USER, CONVERSATION).getMemoryNextTriggerTokens());
        assertEquals("重启前已保存的合成回答", ledger.begin(request("before-restart", "合成已完成问题")).cached().getAnswerText());
        assertEquals("request_result_unknown", assertThrows(SessionMemoryFault.class,
            () -> ledger.begin(request("unresolved", "合成未知问题"))).getMessage());
        assertThrows(SessionMemoryFault.class, () -> ledger.begin(request("next", "合成下一问题")));
        assertEquals(1, mongo.find(Query.query(Criteria.where("requestId").is("before-restart")), AiTurnRequest.class).get(0).getWriteOutcomes().size());
        verifyNoInteractions(python);
        var recovered = ledger.closeUnknown(USER, CONVERSATION, "unresolved", saved.get("unknownFence").asLong(), true);
        assertEquals("error", recovered.getStatus()); assertTrue(recovered.getAnswerText().contains("结果仍未知"));
        config.setRecentTurns(3); config.setSummaryMaxTokens(1000);
        var changed = ledger.begin(request("changed-policy", "改策略合成问题")); var three = memory.prepare(changed);
        assertEquals(9, three.summarizedThroughTurnSeq()); assertEquals(3, three.recentTurns().size());
        assertTrue(three.policyVersion().contains("N3:S1000")); ledger.finish(changed.request(), answer("改策略回答"));
        var retainedSummary = ledger.owned(USER, CONVERSATION).getMemorySummary();
        config.setEnabled(false); clearInvocations(python);
        var rollback = ledger.begin(request("rollback", "回滚合成问题")); var disabled = memory.prepare(rollback);
        assertEquals("disabled", disabled.memoryStatus()); assertEquals(3, disabled.recentTurns().size());
        assertNull(disabled.summary()); assertEquals(retainedSummary, ledger.owned(USER, CONVERSATION).getMemorySummary());
        verifyNoInteractions(python); ledger.finish(rollback.request(), answer("回滚回答"));
        config.setEnabled(true); config.setRecentTurns(5); config.setSummaryMaxTokens(600);
        var enabled = ledger.begin(request("re-enabled", "重新启用合成问题")); var five = memory.prepare(enabled);
        assertEquals(9, five.summarizedThroughTurnSeq()); assertEquals(5, five.recentTurns().size());
        ledger.finish(enabled.request(), answer("恢复回答")); assertOriginals();
        assertEquals(15, CompleteTurnReader.read(ledger.messages(USER, CONVERSATION)).size());
        var late = ledger.begin(request("late", "迟到合成问题")); assertTrue(ledger.tombstone(USER, CONVERSATION));
        assertThrows(SessionMemoryFault.class, () -> ledger.finish(late.request(), answer("不允许复活")));
        assertEquals(0, mongo.count(new Query(), AiMessage.class)); assertEquals(0, mongo.count(new Query(), AiMemoryVersion.class));
        mapper.writeValue(evidence.toFile(), Map.of("phase", "verify-complete", "originalTurnsPreserved", 10,
            "turnsBeforeDelete", 15, "engineRestartRecovered", true, "unknownNotRedispatched", true,
            "policyRebuild", true, "rollbackAndReEnable", true, "lateDeleteRejected", true));
    }
}
