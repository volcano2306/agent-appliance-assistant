package com.example.demo.service;

import com.example.demo.config.AiCompatProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OpenAiCompatibleClient {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    public ChatResult chat(
            AiCompatProperties.TargetConfig targetConfig,
            List<ChatMessage> messages,
            double temperature,
            int timeoutSeconds) {
        return chatWithTools(targetConfig, null, messages, null, null, temperature, timeoutSeconds);
    }

    public ChatResult chatWithTools(
            AiCompatProperties.TargetConfig targetConfig,
            String modelOverride,
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            String toolChoice,
            double temperature,
            int timeoutSeconds) {
        return chatWithTools(targetConfig, modelOverride, messages, tools, toolChoice, temperature, timeoutSeconds, null);
    }

    public ChatResult chatWithTools(AiCompatProperties.TargetConfig targetConfig, String modelOverride,
            List<ChatMessage> messages, List<ToolSpec> tools, String toolChoice,
            double temperature, int timeoutSeconds, Integer maxOutputTokens) {

        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel(StringUtils.hasText(modelOverride) ? modelOverride.trim() : targetConfig.getModel());
        request.setMessages(messages);
        request.setTemperature(temperature);
        request.setStream(false);
        request.setTools(tools);
        request.setToolChoice(toolChoice);
        request.setMaxTokens(maxOutputTokens);

        String url = buildUrl(targetConfig.getBaseUrl(), targetConfig.getChatPath());

        try {
            RestClient.RequestBodySpec spec = createClient(timeoutSeconds)
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL);

            if (StringUtils.hasText(targetConfig.getApiKey())) {
                spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + targetConfig.getApiKey().trim());
            }

            String responseBody = spec
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> {
                        String body = clientResponse.getBody() == null
                                ? ""
                                : StreamUtils.copyToString(clientResponse.getBody(), StandardCharsets.UTF_8);
                        if (clientResponse.getStatusCode().isError()) {
                            throw new IllegalStateException("Upstream AI request failed: "
                                    + (StringUtils.hasText(body) ? body : clientResponse.getStatusCode().toString()));
                        }
                        return body;
                    });

            ChatCompletionResponse response = parseResponse(responseBody);

            Choice firstChoice = extractFirstChoice(response);
            ChatMessage message = firstChoice.getMessage();
            String content = extractContent(message);
            List<ToolCall> toolCalls = message != null && message.getToolCalls() != null
                    ? message.getToolCalls()
                    : List.of();
            String model = StringUtils.hasText(response != null ? response.getModel() : null)
                    ? response.getModel()
                    : request.getModel();

            if (!StringUtils.hasText(content) && toolCalls.isEmpty()) {
                throw new IllegalStateException("Upstream AI response did not contain a message.");
            }
            return new ChatResult(model, content, toolCalls);
        } catch (RestClientResponseException ex) {
            String body = StringUtils.hasText(ex.getResponseBodyAsString())
                    ? ex.getResponseBodyAsString()
                    : ex.getMessage();
            throw new IllegalStateException("Upstream AI request failed: " + body, ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Upstream AI request failed: " + ex.getMessage(), ex);
        }
    }

    public ChatResult chatWithVision(
            AiCompatProperties.TargetConfig targetConfig,
            String prompt,
            String userText,
            String imageUrl,
            String imageDataUrl,
            double temperature,
            int timeoutSeconds) {

        String model = resolveVisionModel(targetConfig);
        if (!StringUtils.hasText(model)) {
            throw new IllegalStateException("vision model is not configured");
        }

        String url = buildUrl(resolveVisionBaseUrl(targetConfig), resolveVisionChatPath(targetConfig));

        Map<String, Object> systemMessage = Map.of(
                "role", "system",
                "content", prompt
        );

        List<Map<String, Object>> content = new ArrayList<>();
        if (StringUtils.hasText(userText)) {
            content.add(Map.of("type", "text", "text", userText.trim()));
        }

        String resolvedImageUrl = StringUtils.hasText(imageDataUrl) ? imageDataUrl.trim() : trimToNull(imageUrl);
        if (!StringUtils.hasText(resolvedImageUrl)) {
            throw new IllegalStateException("image input is required for vision request");
        }
        content.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", resolvedImageUrl)
        ));

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", List.of(systemMessage, userMessage));
        request.put("temperature", temperature);
        request.put("stream", false);

        try {
            RestClient.RequestBodySpec spec = createClient(timeoutSeconds)
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL);

            String visionApiKey = resolveVisionApiKey(targetConfig);
            if (StringUtils.hasText(visionApiKey)) {
                spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + visionApiKey.trim());
            }

            String responseBody = spec
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> {
                        String body = clientResponse.getBody() == null
                                ? ""
                                : StreamUtils.copyToString(clientResponse.getBody(), StandardCharsets.UTF_8);
                        if (clientResponse.getStatusCode().isError()) {
                            throw new IllegalStateException("Upstream AI request failed: "
                                    + (StringUtils.hasText(body) ? body : clientResponse.getStatusCode().toString()));
                        }
                        return body;
                    });

            ChatCompletionResponse response = parseResponse(responseBody);
            Choice firstChoice = extractFirstChoice(response);
            ChatMessage message = firstChoice.getMessage();
            String resultContent = extractContent(message);
            String resultModel = StringUtils.hasText(response != null ? response.getModel() : null)
                    ? response.getModel()
                    : model;

            if (!StringUtils.hasText(resultContent)) {
                throw new IllegalStateException("Upstream vision response did not contain text content.");
            }
            return new ChatResult(resultModel, resultContent, List.of());
        } catch (RestClientResponseException ex) {
            String body = StringUtils.hasText(ex.getResponseBodyAsString())
                    ? ex.getResponseBodyAsString()
                    : ex.getMessage();
            throw new IllegalStateException("Upstream AI request failed: " + body, ex);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Upstream AI request failed: " + ex.getMessage(), ex);
        }
    }

    private RestClient createClient(int timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = Math.max(timeoutSeconds, 1) * 1000;
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        return restClientBuilder.requestFactory(requestFactory).build();
    }

    private String buildUrl(String baseUrl, String chatPath) {
        String base = StringUtils.hasText(baseUrl) ? baseUrl.trim() : "";
        String path = StringUtils.hasText(chatPath) ? chatPath.trim() : "/chat/completions";

        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    private String resolveVisionBaseUrl(AiCompatProperties.TargetConfig targetConfig) {
        if (StringUtils.hasText(targetConfig.getVisionBaseUrl())) {
            return targetConfig.getVisionBaseUrl().trim();
        }
        return targetConfig.getBaseUrl();
    }

    private String resolveVisionChatPath(AiCompatProperties.TargetConfig targetConfig) {
        if (StringUtils.hasText(targetConfig.getVisionChatPath())) {
            return targetConfig.getVisionChatPath().trim();
        }
        return targetConfig.getChatPath();
    }

    private String resolveVisionApiKey(AiCompatProperties.TargetConfig targetConfig) {
        if (StringUtils.hasText(targetConfig.getVisionApiKey())) {
            return targetConfig.getVisionApiKey().trim();
        }
        return targetConfig.getApiKey();
    }

    private String resolveVisionModel(AiCompatProperties.TargetConfig targetConfig) {
        if (StringUtils.hasText(targetConfig.getVisionModel())) {
            return targetConfig.getVisionModel().trim();
        }
        return null;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private ChatCompletionResponse parseResponse(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            throw new IllegalStateException("Upstream AI response body was empty.");
        }
        try {
            return objectMapper.readValue(responseBody, ChatCompletionResponse.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Upstream AI returned a non-JSON or invalid JSON body: "
                    + truncateForError(responseBody), ex);
        }
    }

    private String truncateForError(String responseBody) {
        String trimmed = responseBody.trim();
        if (trimmed.length() <= 240) {
            return trimmed;
        }
        return trimmed.substring(0, 240) + "...";
    }

    private Choice extractFirstChoice(ChatCompletionResponse response) {
        if (response == null
                || response.getChoices() == null
                || response.getChoices().isEmpty()
                || response.getChoices().get(0) == null
                || response.getChoices().get(0).getMessage() == null) {
            throw new IllegalStateException("Upstream AI response did not contain a valid choice.");
        }
        return response.getChoices().get(0);
    }

    private String extractContent(ChatMessage message) {
        if (message == null || !StringUtils.hasText(message.getContent())) {
            return null;
        }
        return message.getContent().trim();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessage {
        private String role;
        private String content;
        @JsonProperty("tool_call_id")
        private String toolCallId;
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;

        public ChatMessage(String role, String content) {
            this(role, content, null, null);
        }

        public static ChatMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
            return new ChatMessage("assistant", content, null, toolCalls);
        }

        public static ChatMessage toolResponse(String toolCallId, String content) {
            return new ChatMessage("tool", content, toolCallId, null);
        }
    }

    @Data
    private static class ChatCompletionRequest {
        private String model;
        private List<ChatMessage> messages;
        private double temperature;
        private boolean stream;
        private List<ToolSpec> tools;
        @JsonProperty("tool_choice")
        private String toolChoice;
        @JsonProperty("max_tokens")
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private Integer maxTokens;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ChatCompletionResponse {
        private String id;
        private String model;
        private List<Choice> choices;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Choice {
        private int index;
        private ChatMessage message;
    }

    @Data
    @AllArgsConstructor
    public static class ChatResult {
        private String model;
        private String content;
        private List<ToolCall> toolCalls;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolSpec {
        private String type;
        private ToolDefinition function;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolDefinition {
        private String name;
        private String description;
        private Map<String, Object> parameters;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {
        private String id;
        private String type;
        private ToolFunction function;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolFunction {
        private String name;
        private String arguments;
    }
}
