package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.example.demo.dto.SessionMemory;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_conversations")
public class AiConversation {

    @Id
    private String id;

    private String userId;
    private String title;

    private String manualRefId;
    private String manualId;
    private String manualName;
    private String manualType;

    private String lastMessagePreview;
    private String lastMessageRole;
    private Integer messageCount;
    private Boolean customTitle;

    private Instant createdAt;
    private Instant updatedAt;
    @JsonIgnore private Long historyRevision;
    @JsonIgnore private Long nextTurnSeq;
    @JsonIgnore private SessionMemory.Summary memorySummary;
    @JsonIgnore private Long summaryVersion;
    @JsonIgnore private String memoryVersionId;
    @JsonIgnore private Long summarizedThroughTurnSeq;
    @JsonIgnore private String summaryPolicyVersion;
    @JsonIgnore private Integer memoryNextTriggerTokens;
    @JsonIgnore private String memoryTriggerPolicyVersion;
    @JsonIgnore private Map<String, SessionMemory.Source> memorySourceMap;
    @JsonIgnore private Long executionVersion;
    @JsonIgnore private String leaseRequestId;
    @JsonIgnore private Instant leaseExpiresAt;
    @JsonIgnore private String blockedRequestId;
    @JsonIgnore private Instant deletedAt;
}
