package com.example.demo.service;

import com.example.demo.dto.SessionMemory;
import com.example.demo.model.AiMessage;
import java.time.Instant;
import java.util.*;

/** Only explicit completed user/final-answer pairs count as turns. */
public final class CompleteTurnReader {
    private CompleteTurnReader() { }
    public static List<SessionMemory.Turn> read(List<AiMessage> messages) {
        Map<Long, List<AiMessage>> groups = new TreeMap<>();
        for (AiMessage message : messages) {
            if (message.getTurnSeq() != null && Boolean.TRUE.equals(message.getCompletedTurn())
                    && !Boolean.TRUE.equals(message.getOrphaned())) {
                groups.computeIfAbsent(message.getTurnSeq(), k -> new ArrayList<>()).add(message);
            }
        }
        List<SessionMemory.Turn> result = new ArrayList<>();
        groups.forEach((seq, group) -> {
            List<AiMessage> users = group.stream().filter(m -> "user".equals(m.getRole())).toList();
            List<AiMessage> answers = group.stream().filter(m -> Set.of("ai", "assistant").contains(m.getRole())).toList();
            if (users.size() != 1 || answers.size() != 1) return;
            AiMessage u = users.get(0), a = answers.get(0);
            if (u.getId() == null || a.getId() == null || u.getText() == null || a.getText() == null
                    || !Objects.equals(u.getRequestId(), a.getRequestId())) return;
            result.add(new SessionMemory.Turn(seq, u.getRequestId(),
                    new SessionMemory.Message(u.getId(), "user", u.getText()),
                    new SessionMemory.Message(a.getId(), "assistant", a.getText())));
        });
        return List.copyOf(result);
    }

    public static List<AiMessage> stableOrder(List<AiMessage> messages) {
        List<AiMessage> sorted = new ArrayList<>(messages);
        sorted.sort(Comparator.comparing(AiMessage::getCreatedAt, Comparator.nullsLast(Instant::compareTo))
                .thenComparing(AiMessage::getId, Comparator.nullsLast(String::compareTo)));
        return sorted;
    }
}
