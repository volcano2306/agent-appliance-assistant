package com.example.demo.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.Data;

@Data
public class QARequest {

    private String userId;
    private String userOpenId;
    private String productId;
    private String manualId;
    private String manualRefId;
    private String manualName;
    private String manualType;
    private String target;
    private String query;
    private String conversationId;
    private String requestId;
    private Boolean persistHistory;
    // Populated only by the server; public requests cannot inject a snapshot.
    @JsonIgnore
    private SessionMemory.Snapshot memory;
    private Boolean enableWebSearch;
    private Boolean enableImageRecognition;
    private String imageUrl;
    private String imageDataUrl;
    private List<Message> history;
    private List<Message> messages;

    @Data
    public static class Message {

        private String role;
        private String content;

        @JsonProperty("reasoning_details")
        private JsonNode reasoningDetails;
    }

    public List<Message> getHistory() {
        if (history != null && !history.isEmpty()) {
            return history;
        }
        return messages;
    }

    public void setHistory(List<Message> history) {
        this.history = history;
    }

    public static class ConversationMessage extends Message {
    }
}
