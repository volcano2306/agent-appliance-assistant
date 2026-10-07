package com.example.demo;

import com.example.demo.config.SessionMemoryProperties;
import com.example.demo.dto.*;
import com.example.demo.model.*;
import com.example.demo.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Real Java RestClient -> guarded FastAPI -> production Agent/SummaryService;
 * model/tools synthetic, only a fresh UUID Mongo DB, no Spring/default config.
 */
@EnabledIfSystemProperty(named="session.memory.wire.port", matches="[0-9]+")
class SessionMemoryPythonWireIntegrationTest {
    MongoClient mongoClient;
    MongoTemplate mongo;
    String database, base;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AiTurnLedgerService ledger;
    AiConversationMemoryService memory;
    PythonAgentClient python;
    SessionMemoryProperties config;
    @BeforeEach void setup() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.mongodb.driver"))
                .setLevel(ch.qos.logback.classic.Level.WARN);
        int port = Integer.parseInt(System.getProperty("session.memory.wire.port"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid_test_port");
        base = "http://127.0.0.1:" + port;
        database = "session_memory_test_" + UUID.randomUUID().toString().replace("-", "");
        mongoClient = MongoClients.create("mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000");
        mongo = new MongoTemplate(mongoClient, database);
        config = new SessionMemoryProperties(); config.setEnabled(true); config.setModelContextWindow(40000);
        config.setCompressionStrategy("rolling"); // Existing eviction wire scenario; threshold has separate tests.
        ledger = new AiTurnLedgerService(mongo, mapper, config); ledger.ensureIndexes();
        var resolver = mock(AgentManualContextResolver.class);
        when(resolver.resolve(any())).thenReturn(new AgentManualContextResolver.Target("", "", "", "", "", "", ""));
        python = new PythonAgentClient(base, "session-memory-synthetic-wire-token", true, false, 10, resolver);
        memory = new AiConversationMemoryService(mongo, ledger, python, config, mapper);
    }
    @AfterEach void cleanup() {
        if (mongoClient != null) {
            if (!database.matches("session_memory_test_[0-9a-f]{32}")) throw new IllegalStateException("unsafe_cleanup");
            mongoClient.getDatabase(database).drop(); mongoClient.close();
        }
    }
    QARequest request(String id, String conversation) {
        var req = new QARequest(); req.setUserId("wire-user"); req.setRequestId(id);
        req.setConversationId(conversation); req.setQuery("合成问题" + id); return req;
    }
    Map<String, Object> stats() throws Exception {
        var response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(HttpRequest.newBuilder(URI.create(base + "/__test__/stats")).GET().build(), HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(response.body(), Map.class);
    }
    @Test void canonicalSnapshotCrossesHttpThenResponseAndUsagePersistWithoutReexecution() throws Exception {
        String conversation = null;
        for (int i = 1; i <= 7; i++) {
            var req = request("seed" + i, conversation); var started = ledger.begin(req); conversation = req.getConversationId();
            var response = new SessionMemory.PythonResponse(); response.setStatus("ok"); response.setAnswerText("合成历史回答" + i);
            ledger.finish(started.request(), response);
        }
        Map<String, Object> before = stats();
        var req = request("current", conversation); var execution = ledger.begin(req);
        req.setMemory(memory.prepare(execution));
        assertEquals(2, req.getMemory().summarizedThroughTurnSeq()); assertEquals(5, req.getMemory().recentTurns().size());
        var response = python.answerWithMemory(req);
        assertEquals("ok", response.getStatus()); assertEquals(conversation, response.getConversationId());
        assertEquals("current", response.getRequestId()); assertEquals(1, response.getUsageBreakdown().size());
        var saved = ledger.finish(execution.request(), response); assertEquals(QAResponse.class, saved.getClass());
        var after = stats(); assertEquals(((Number) before.get("modelCalls")).intValue() + 2, ((Number) after.get("modelCalls")).intValue());
        assertEquals(List.of("wire-user", conversation, "current", execution.request().getExecutionVersion().intValue()), after.get("lastIdentity"));
        var restarted = new AiTurnLedgerService(mongo, mapper, new SessionMemoryProperties());
        assertNotNull(restarted.begin(request("current", conversation)).cached());
        assertEquals(after.get("modelCalls"), stats().get("modelCalls"));
        assertEquals(8, CompleteTurnReader.read(ledger.messages("wire-user", conversation)).size());
        assertFalse(mapper.writeValueAsString(saved).contains("usageBreakdown"));
    }
    @Test void wrongInternalTokenIsRejectedWithoutAnyModelDispatch() throws Exception {
        var before = stats();
        var request = HttpRequest.newBuilder(URI.create(base + "/v1/agent/chat"))
                .header("Content-Type", "application/json").header("X-Internal-Token", "wrong")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"合成问题\"}")).build();
        var response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode()); assertEquals(before.get("modelCalls"), stats().get("modelCalls"));
    }
    @Test void thresholdPlannerCountsActualWireHistoryWithoutCallingModelBelowThreshold() throws Exception {
        config.setCompressionStrategy("threshold");
        String conversation = null;
        for (int i = 1; i <= 8; i++) {
            var req = request("buffer" + i, conversation); var started = ledger.begin(req);
            conversation = req.getConversationId();
            var response = new SessionMemory.PythonResponse(); response.setStatus("ok"); response.setAnswerText("合成短历史");
            ledger.finish(started.request(), response);
        }
        var before = stats(); var req = request("buffer-current", conversation); var started = ledger.begin(req);
        var snapshot = memory.prepare(started);
        assertEquals("ok", snapshot.memoryStatus()); assertNull(snapshot.summary());
        assertEquals(3, snapshot.pendingTurns().size()); assertEquals(5, snapshot.recentTurns().size());
        assertEquals(before.get("modelCalls"), stats().get("modelCalls"));
        var counted = python.planMemory(new SessionMemory.PlanRequest(snapshot, null, Integer.MAX_VALUE));
        assertTrue(counted.memoryTokens() > 0 && counted.memoryTokens() < 1500);
        assertEquals("deepseek-v4-text-8f9f37ca37fd", counted.counter()); assertFalse(counted.exactCount());
    }
    @Test void thresholdCompactionCrossesRealHttpAndPreservesFiveRawTurns() throws Exception {
        config.setCompressionStrategy("threshold");
        String conversation = null;
        for (int i = 1; i <= 8; i++) {
            var req = request("large" + i, conversation);
            if (i <= 3) req.setQuery("不同内容随机排查状态".repeat(450));
            var started = ledger.begin(req); conversation = req.getConversationId();
            var response = new SessionMemory.PythonResponse(); response.setStatus("ok"); response.setAnswerText("合成短历史");
            ledger.finish(started.request(), response);
        }
        var before = stats(); var req = request("large-current", conversation); var started = ledger.begin(req);
        var snapshot = memory.prepare(started);
        assertEquals("ok", snapshot.memoryStatus()); assertEquals(3, snapshot.summarizedThroughTurnSeq());
        assertEquals(5, snapshot.recentTurns().size()); assertTrue(snapshot.pendingTurns().isEmpty());
        assertNotNull(snapshot.summary());
        assertTrue(((Number) stats().get("summaryCalls")).intValue() > ((Number) before.get("summaryCalls")).intValue());
        assertEquals(1500, ledger.owned("wire-user", conversation).getMemoryNextTriggerTokens());
    }
    @Test void foreignSnapshotIsRejectedByPythonContractWithoutCallingModel() throws Exception {
        var req = request("current", null); var execution = ledger.begin(req);
        var snapshot = memory.prepare(execution); var before = stats();
        var body = mapper.writeValueAsString(Map.of("query", "合成问题", "userId", "foreign-user",
                "conversationId", req.getConversationId(), "requestId", "current", "memory", snapshot));
        var call = HttpRequest.newBuilder(URI.create(base + "/v1/agent/chat"))
                .header("Content-Type", "application/json").header("X-Internal-Token", "session-memory-synthetic-wire-token")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var result = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(call, HttpResponse.BodyHandlers.ofString());
        assertEquals(422, result.statusCode()); assertEquals(before.get("modelCalls"), stats().get("modelCalls"));
    }
}
