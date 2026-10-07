package com.example.demo.model;

import com.example.demo.dto.QAResponse;
import com.example.demo.dto.SessionMemory;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Durable execution ledger; not a public controller response. */
@Data
@Document("ai_turn_requests")
public class AiTurnRequest {
    @Id private String id;
    private String userId;
    private String requestId;
    private String conversationId;
    private String payloadHash;
    private String state;
    private Long turnSeq;
    private Long executionVersion;
    private QAResponse finalResponse;
    private String userText;
    private AiMessage.ManualRef manualRef;
    private List<SessionMemory.WriteOutcome> writeOutcomes;
    private List<SessionMemory.Usage> usage;
    private Instant createdAt;
    private Instant updatedAt;
}
