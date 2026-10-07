package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_messages")
public class AiMessage {

    @Id
    private String id;

    private String conversationId;
    private String role;
    private String text;
    private String image;
    private ManualRef manualRef;
    private StructuredAnswer structuredAnswer;
    private Boolean insufficientContext;
    private List<SourceRef> sources;
    private Instant createdAt;
    private String userId;
    private String requestId;
    private Long turnSeq;
    private Integer ordinal;
    private Boolean completedTurn;
    private Boolean orphaned;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManualRef {
        private String id;
        private String manualId;
        private String name;
        private String type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StructuredAnswer {
        private String summary;
        private List<String> steps;
        private List<String> cautions;
        private String basisNote;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceRef {
        private String kind;
        private String fragmentId;
        private Integer page;
        private Integer chunkIndex;
        private String title;
        private String url;
        private String snippet;
        private String sourceType;
    }
}
