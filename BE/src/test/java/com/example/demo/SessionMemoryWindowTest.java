package com.example.demo;

import com.example.demo.config.SessionMemoryProperties;
import com.example.demo.dto.QARequest;
import com.example.demo.dto.SessionMemory;
import com.example.demo.model.AiMessage;
import com.example.demo.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionMemoryWindowTest {
    SessionMemory.Turn turn(long n) {
        return new SessionMemory.Turn(n, "r" + n, new SessionMemory.Message("u" + n, "user", "问" + n),
                new SessionMemory.Message("a" + n, "assistant", "答" + n));
    }
    @Test void sevenTurnsKeepThreeOrFiveCompletePairs() {
        var turns = LongStream.rangeClosed(1, 7).mapToObj(this::turn).toList();
        var three = AiConversationMemoryService.window(turns, 3);
        assertEquals(List.of(5L, 6L, 7L), three.recent().stream().map(SessionMemory.Turn::turnSeq).toList());
        assertEquals(4, three.old().size()); assertEquals(2, AiConversationMemoryService.window(turns, 5).old().size());
    }
    @Test void sequenceGapsDoNotChangeWindowSize() {
        var turns = List.of(turn(2), turn(8), turn(9), turn(20), turn(31));
        var window = AiConversationMemoryService.window(turns, 3);
        assertEquals(List.of(9L, 20L, 31L), window.recent().stream().map(SessionMemory.Turn::turnSeq).toList());
    }
    @Test void twentyTurnsAreNotReducedToSixMessages() {
        var window = AiConversationMemoryService.window(LongStream.rangeClosed(1, 20).mapToObj(this::turn).toList(), 5);
        assertEquals(15, window.old().size()); assertEquals(5, window.recent().size());
    }
    AiMessage message(String id, long seq, String role) {
        return AiMessage.builder().id(id).turnSeq(seq).role(role).requestId("r" + seq).text(id)
                .completedTurn(true).createdAt(Instant.EPOCH).build();
    }
    @Test void halfTurnsAndToolMessagesDoNotCountAsPairs() {
        List<AiMessage> messages = List.of(message("a1", 1, "ai"), message("u2", 2, "user"),
                message("a2", 2, "ai"), message("t2", 2, "tool"), message("u1", 1, "user"), message("u3", 3, "user"));
        assertEquals(List.of(1L, 2L), CompleteTurnReader.read(messages).stream().map(SessionMemory.Turn::turnSeq).toList());
    }
    @Test void duplicateAnswersAndOrphansAreExcluded() {
        AiMessage orphan = message("u2", 2, "user"); orphan.setOrphaned(true);
        assertTrue(CompleteTurnReader.read(List.of(message("u1", 1, "user"), message("a1", 1, "ai"),
                message("b1", 1, "ai"), orphan, message("a2", 2, "ai"))).isEmpty());
    }
    @Test void stableLegacySortUsesIdWhenTimestampTies() {
        var sorted = CompleteTurnReader.stableOrder(List.of(message("b", 1, "ai"), message("a", 1, "user")));
        assertEquals("a", sorted.get(0).getId());
    }
    @Test void sourceMapOnlyCarriesRequiredSources() {
        Map<String, SessionMemory.Source> all = new HashMap<>();
        for (long n = 1; n <= 20; n++) for (String prefix : List.of("u", "a"))
            all.put(prefix + n, new SessionMemory.Source(prefix + n, n, prefix.equals("u") ? "user" : "assistant", List.of()));
        var selected = AiConversationMemoryService.selectSources(null, List.of(turn(20)), all, Map.of());
        assertEquals(Set.of("u20", "a20"), selected.keySet());
    }
    @Test void requestHashIgnoresConversationBindingButRejectsChangedQuestion() {
        var ledger = new AiTurnLedgerService(null, new ObjectMapper(), new SessionMemoryProperties());
        QARequest request = new QARequest(); request.setUserId("u"); request.setQuery("first");
        String hash = ledger.payloadHash(request); request.setConversationId("canonical");
        assertEquals(hash, ledger.payloadHash(request)); request.setQuery("different");
        assertNotEquals(hash, ledger.payloadHash(request));
    }
    @Test void evidenceCarrierSurvivesMultipleReplacementsWithoutUnrelatedHistory() {
        var summary = new SessionMemory.Summary(List.of(new SessionMemory.Fact("当前型号", "u7")),
                List.of(), List.of(), List.of(), List.of(), List.of("fragment18"), List.of());
        var all = Map.of("u7", new SessionMemory.Source("u7", 7, "user", List.of()),
                "a5", new SessionMemory.Source("a5", 5, "assistant", List.of("fragment18")),
                "a6", new SessionMemory.Source("a6", 6, "assistant", List.of("fragment18")),
                "a1", new SessionMemory.Source("a1", 1, "assistant", List.of("unrelated")));
        var first = AiConversationMemoryService.selectSources(summary, List.of(), all, Map.of());
        assertEquals(Set.of("u7", "a5"), first.keySet());
        var second = AiConversationMemoryService.selectSources(summary, List.of(), Map.of(), first);
        assertEquals(first, second);
        assertThrows(SessionMemoryFault.class, () -> AiConversationMemoryService.selectSources(
                summary, List.of(), Map.of(), Map.of("u7", all.get("u7"))));
    }
    @Test void singleOversizeTurnCannotBeSilentlySplitOrTruncated() {
        var memory = new AiConversationMemoryService(null, null, null, new SessionMemoryProperties(), new ObjectMapper());
        var huge = new SessionMemory.Turn(1, "r", new SessionMemory.Message("u", "user", "x".repeat(9000)),
                new SessionMemory.Message("a", "assistant", "answer"));
        assertTrue(memory.batch(List.of(huge), null, 8000).isEmpty());
        assertEquals(9000, huge.user().content().length());
    }
    @Test void legacyPlanPreservesIdsBodiesAndSourcesAndIsIdempotent() {
        AiMessage u = message("u", 1, "user"), a = message("a", 1, "ai"), orphan = message("o", 2, "user");
        u.setCompletedTurn(null); a.setCompletedTurn(null); orphan.setCompletedTurn(null);
        u.setTurnSeq(null); a.setTurnSeq(null); orphan.setTurnSeq(null);
        u.setCreatedAt(Instant.EPOCH); a.setCreatedAt(Instant.EPOCH.plusMillis(1)); orphan.setCreatedAt(Instant.EPOCH.plusMillis(2));
        List<AiMessage> raw = List.of(orphan, a, u);
        var plan = AiMemoryMigrationService.plan(raw);
        assertEquals(1, plan.pairs().size()); assertEquals(List.of("o"), plan.orphans());
        assertSame(u, plan.pairs().get(0).user()); assertEquals("u", u.getText());
        u.setCompletedTurn(true); a.setCompletedTurn(true);
        assertTrue(AiMemoryMigrationService.plan(raw).pairs().isEmpty());
    }
}
