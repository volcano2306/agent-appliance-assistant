package com.example.demo.model;
import com.example.demo.dto.SessionMemory;
import java.time.Instant;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document("ai_memory_versions")
public class AiMemoryVersion {
    @Id private String id;
    private String userId;
    private String conversationId;
    private long summaryVersion;
    private long historyRevision;
    private long summarizedThroughTurnSeq;
    private String policyVersion;
    private String sourceSnapshotHash;
    private SessionMemory.Summary summary;
    private Map<String, SessionMemory.Source> sourceMap;
    private Instant createdAt;
}
