package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
public class QAResponse {

    private String status;
    private String error;
    private String model;
    private String target;
    private String conversationId;
    private String requestId;
    private String memoryStatus;
    private String answerText;
    private Boolean usedWebSearch;
    private Boolean insufficientContext;
    private List<String> sourceFragmentIds;
    private List<SourceFragment> sources;
    private StructuredAnswer structuredAnswer;
    private VisionResult vision;

    @JsonProperty("reasoning_details")
    private JsonNode reasoningDetails;

    @Data
    @Builder
    public static class StructuredAnswer {
        private String summary;
        private List<String> steps;
        private List<String> cautions;
        private String basisNote;
    }

    @Data
    @Builder
    public static class SourceFragment {
        private String kind;
        private String fragmentId;
        private Integer page;
        private Integer chunkIndex;
        private String title;
        private String url;
        private String snippet;
        private String sourceType;
    }

    @Data
    @Builder
    public static class VisionResult {
        private String imageType;
        private String categoryCode;
        private String brandName;
        private String modelName;
        private String faultCode;
        private String recognizedText;
        private Double confidence;
        private String queryHint;
        private String rawText;
    }
}
