package com.example.demo.service;

import com.example.demo.dto.QARequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OpenRouterChatClient {

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Value("${openrouter.base-url:https://openrouter.ai/api/v1}")
    private String baseUrl;

    @Value("${openrouter.api-key:}")
    private String apiKey;

    @Value("${openrouter.models:google/gemma-4-26b-a4b-it:free,google/gemma-3-27b-it:free,google/gemma-3-12b-it:free,google/gemma-3-4b-it:free,google/gemma-3n-e4b-it:free}")
    private String models;

    public ChatTurn chat(List<QARequest.ConversationMessage> messages, boolean reasoningEnabled) {
        List<String> modelCandidates = parseModelCandidates();
        List<String> errors = new ArrayList<>();

        for (String candidate : modelCandidates) {
            try {
                return chatWithModel(candidate, messages, reasoningEnabled);
            } catch (ModelRateLimitedException e) {
                errors.add(candidate + " -> " + e.getMessage());
            }
        }

        throw new IllegalStateException("OpenRouter 暂时不可用，已尝试备用模型: " + String.join("; ", errors));
    }

    private ChatTurn chatWithModel(String model, List<QARequest.ConversationMessage> messages, boolean reasoningEnabled) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("model", model);
            payload.set("messages", objectMapper.valueToTree(messages));

            payload.putObject("reasoning").put("enabled", reasoningEnabled);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(baseUrl) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("HTTP-Referer", "http://localhost")
                    .header("X-Title", "sms-demo")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                if (response.statusCode() == 429) {
                    throw new ModelRateLimitedException(extractOpenRouterError(response.body()));
                }
                throw new IllegalStateException("OpenRouter 调用失败: HTTP " + response.statusCode() + " - " + extractOpenRouterError(response.body()));
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            JsonNode reasoningDetails = message.get("reasoning_details");
            return new ChatTurn(content, reasoningDetails, root);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenRouter 调用被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("OpenRouter 调用失败", e);
        }
    }

    private List<String> parseModelCandidates() {
        if (models == null || models.isBlank()) {
            return List.of("google/gemma-4-26b-a4b-it:free");
        }
        return Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toList());
    }

    private String extractOpenRouterError(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                String message = error.path("message").asText("");
                JsonNode metadata = error.path("metadata");
                String raw = metadata.path("raw").asText("");
                if (!raw.isBlank()) {
                    return message.isBlank() ? raw : message + " - " + raw;
                }
                if (!message.isBlank()) {
                    return message;
                }
            }
        } catch (IOException ignored) {
        }
        return responseBody;
    }

    private String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "https://openrouter.ai/api/v1";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static class ModelRateLimitedException extends RuntimeException {

        ModelRateLimitedException(String message) {
            super(message);
        }
    }

    public record ChatTurn(String content, JsonNode reasoningDetails, JsonNode rawResponse) {

    }
}
