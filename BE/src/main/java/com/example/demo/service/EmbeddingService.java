package com.example.demo.service;

import com.example.demo.config.AiCompatProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private static final Duration RETRY_COOLDOWN = Duration.ofSeconds(30);

    private final AiCompatProperties aiCompatProperties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile Instant cooldownUntil = Instant.EPOCH;

    public boolean isEnabled() {
        return resolveDefaultEmbeddingTargetConfig() != null;
    }

    public String getEmbeddingModel() {
        AiCompatProperties.TargetConfig targetConfig = resolveDefaultEmbeddingTargetConfig();
        return targetConfig != null ? trimToNull(targetConfig.getEmbeddingModel()) : null;
    }

    public List<Double> embed(String text) {
        return embed(text, aiCompatProperties.getTimeoutSeconds());
    }

    public List<Double> embed(String text, int requestTimeoutSeconds) {
        String normalizedText = trimToNull(text);
        AiCompatProperties.TargetConfig targetConfig = resolveDefaultEmbeddingTargetConfig();
        if (targetConfig == null || normalizedText == null) {
            return List.of();
        }
        if (Instant.now().isBefore(cooldownUntil)) {
            return List.of();
        }

        try {
            List<Double> embedding = embedOne(targetConfig, normalizedText, requestTimeoutSeconds);
            cooldownUntil = Instant.EPOCH;
            return embedding;
        } catch (Exception ex) {
            cooldownUntil = Instant.now().plus(RETRY_COOLDOWN);
            log.warn("EmbeddingService: 生成 embedding 失败 model={} reason={}",
                    targetConfig.getEmbeddingModel(),
                    ex.getMessage());
            return List.of();
        }
    }

    public List<List<Double>> embedMany(List<String> inputs) {
        return embedMany(inputs, aiCompatProperties.getTimeoutSeconds());
    }

    public List<List<Double>> embedMany(List<String> inputs, int requestTimeoutSeconds) {
        AiCompatProperties.TargetConfig targetConfig = resolveDefaultEmbeddingTargetConfig();
        if (targetConfig == null) {
            return List.of();
        }
        if (Instant.now().isBefore(cooldownUntil)) {
            return List.of();
        }

        try {
            List<List<Double>> embeddings = embedMany(targetConfig, inputs, requestTimeoutSeconds);
            cooldownUntil = Instant.EPOCH;
            return embeddings;
        } catch (Exception ex) {
            cooldownUntil = Instant.now().plus(RETRY_COOLDOWN);
            log.warn("EmbeddingService: 批量生成 embedding 失败 model={} reason={}",
                    targetConfig.getEmbeddingModel(),
                    ex.getMessage());
            return List.of();
        }
    }

    public List<Double> embedOne(
            AiCompatProperties.TargetConfig targetConfig,
            String input,
            int requestTimeoutSeconds) {
        List<List<Double>> embeddings = embedMany(targetConfig, List.of(input), requestTimeoutSeconds);
        if (embeddings.isEmpty()) {
            throw new IllegalStateException("Embedding response did not contain vectors.");
        }
        return embeddings.get(0);
    }

    public List<List<Double>> embedMany(
            AiCompatProperties.TargetConfig targetConfig,
            List<String> inputs,
            int requestTimeoutSeconds) {

        if (targetConfig == null || trimToNull(targetConfig.getEmbeddingModel()) == null) {
            throw new IllegalStateException("Embedding model is not configured.");
        }

        List<String> effectiveInputs = inputs == null
                ? List.of()
                : inputs.stream().map(this::trimToNull).filter(value -> value != null).toList();
        if (effectiveInputs.isEmpty()) {
            return List.of();
        }

        String baseUrl = firstNonBlank(targetConfig.getEmbeddingBaseUrl(), targetConfig.getBaseUrl());
        String path = firstNonBlank(targetConfig.getEmbeddingPath(), "/embeddings");
        String apiKey = firstNonBlank(targetConfig.getEmbeddingApiKey(), targetConfig.getApiKey());
        Map<String, Object> payload = buildRequestBody(targetConfig.getEmbeddingModel(), path, effectiveInputs);

        try {
            JsonNode root = sendRequest(baseUrl, path, apiKey, payload, requestTimeoutSeconds);
            return parseEmbeddings(path, root);
        } catch (Exception ex) {
            throw new IllegalStateException("Upstream embedding request failed: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> buildRequestBody(String model, String path, List<String> inputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", trimToNull(model));

        String normalizedPath = normalizePath(path);
        if (normalizedPath.startsWith("/api/embeddings")) {
            if (inputs.size() != 1) {
                throw new IllegalStateException("Legacy Ollama embeddings API only supports single input.");
            }
            payload.put("prompt", inputs.get(0));
            return payload;
        }

        payload.put("input", inputs.size() == 1 ? inputs.get(0) : inputs);
        if (!normalizedPath.startsWith("/api/embed")) {
            payload.put("encoding_format", "float");
        }
        return payload;
    }

    private JsonNode sendRequest(
            String baseUrl,
            String path,
            String apiKey,
            Map<String, Object> payload,
            int requestTimeoutSeconds) throws IOException, InterruptedException {
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(baseUrl) + ensureLeadingSlash(path)))
                .timeout(Duration.ofSeconds(Math.max(requestTimeoutSeconds, 1)))
                .header("Content-Type", "application/json");
        if (trimToNull(apiKey) != null) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }

        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("status=" + response.statusCode() + " body=" + truncateForError(response.body()));
        }
        return objectMapper.readTree(response.body());
    }

    private List<List<Double>> parseEmbeddings(String path, JsonNode root) {
        String normalizedPath = normalizePath(path);
        if (normalizedPath.startsWith("/api/embed")) {
            JsonNode embeddingsNode = root.path("embeddings");
            if (!embeddingsNode.isArray() || embeddingsNode.isEmpty()) {
                return List.of();
            }

            List<List<Double>> result = new ArrayList<>();
            for (JsonNode item : embeddingsNode) {
                List<Double> embedding = parseEmbeddingArray(item);
                if (!embedding.isEmpty()) {
                    result.add(embedding);
                }
            }
            return result;
        }

        if (normalizedPath.startsWith("/api/embeddings")) {
            List<Double> embedding = parseEmbeddingArray(root.path("embedding"));
            return embedding.isEmpty() ? List.of() : List.of(embedding);
        }

        JsonNode dataNode = root.path("data");
        if (!dataNode.isArray() || dataNode.isEmpty()) {
            return List.of();
        }

        List<List<Double>> result = new ArrayList<>();
        for (JsonNode item : dataNode) {
            List<Double> embedding = parseEmbeddingArray(item.path("embedding"));
            if (!embedding.isEmpty()) {
                result.add(embedding);
            }
        }
        return result;
    }

    private List<Double> parseEmbeddingArray(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }

        List<Double> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item != null && item.isNumber()) {
                values.add(item.asDouble());
            }
        }
        return values;
    }

    private String firstNonBlank(String first, String second) {
        String normalizedFirst = trimToNull(first);
        return normalizedFirst != null ? normalizedFirst : trimToNull(second);
    }

    private AiCompatProperties.TargetConfig resolveDefaultEmbeddingTargetConfig() {
        String targetName = trimToNull(aiCompatProperties.getDefaultTarget());
        if (targetName == null) {
            targetName = "local";
        }
        AiCompatProperties.TargetConfig targetConfig = aiCompatProperties.getTargets().get(targetName.toLowerCase(Locale.ROOT));
        if (targetConfig == null || !StringUtils.hasText(targetConfig.getEmbeddingModel())) {
            return null;
        }
        return targetConfig;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String trimTrailingSlash(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return "http://127.0.0.1:11434";
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private String ensureLeadingSlash(String path) {
        String normalized = trimToNull(path);
        if (normalized == null) {
            return "/embeddings";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private String normalizePath(String path) {
        return ensureLeadingSlash(path).toLowerCase();
    }

    private String truncateForError(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return "";
        }
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }
}
