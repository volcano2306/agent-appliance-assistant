package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiCompatProperties {

    private String defaultTarget = "openrouter";
    private double temperature = 0.2;
    private int timeoutSeconds = 60;
    private int manualRagTopK = 5;
    private Map<String, TargetConfig> targets = new LinkedHashMap<>();

    @Data
    public static class TargetConfig {
        private String baseUrl;
        private String apiKey;
        private String chatPath = "/chat/completions";
        private String model;
        private String toolModel;

        private String visionBaseUrl;
        private String visionApiKey;
        private String visionChatPath;
        private String visionModel;

        private String embeddingBaseUrl;
        private String embeddingApiKey;
        private String embeddingPath = "/embeddings";
        private String embeddingModel;
    }
}
