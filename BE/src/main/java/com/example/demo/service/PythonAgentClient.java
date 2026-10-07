package com.example.demo.service;

import com.example.demo.dto.QARequest;
import com.example.demo.dto.QAResponse;
import com.example.demo.dto.SessionMemory;
import com.example.demo.model.AiMessage;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PythonAgentClient {

    private final RestClient restClient;
    private final String internalToken;
    private final AgentManualContextResolver manualContextResolver;

    @Getter
    private final boolean enabled;

    @Getter
    private final boolean fallbackToJava;

    public PythonAgentClient(
            @Value("${agent.python.base-url:http://localhost:8001}") String baseUrl,
            @Value("${agent.python.internal-token:}") String internalToken,
            @Value("${agent.python.enabled:true}") boolean enabled,
            @Value("${agent.python.fallback-to-java:true}") boolean fallbackToJava,
            @Value("${agent.python.timeout-seconds:100}") int timeoutSeconds,
            AgentManualContextResolver manualContextResolver) {
        this.internalToken = internalToken == null ? "" : internalToken;
        this.manualContextResolver = manualContextResolver;
        this.enabled = enabled;
        this.fallbackToJava = fallbackToJava;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(Math.max(timeoutSeconds, 10) * 1_000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(requestFactory)
                .build();
    }

    public QAResponse answer(QARequest request, List<AiMessage> history) {
        Map<String, Object> payload = buildPayload(request, history);
        QAResponse response = restClient.post()
                .uri("/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Token", internalToken)
                .body(payload)
                .retrieve()
                .body(QAResponse.class);
        if (response == null) {
            throw new IllegalStateException("Python Agent returned an empty response");
        }
        return response;
    }

    public SessionMemory.PythonResponse answerWithMemory(QARequest request) {
        if (request.getMemory() == null) throw new IllegalArgumentException("memory_snapshot_required");
        SessionMemory.PythonResponse response = restClient.post().uri("/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON).header("X-Internal-Token", internalToken)
                .body(buildPayload(request, List.of())).retrieve().body(SessionMemory.PythonResponse.class);
        if (response == null) throw new IllegalStateException("empty_python_response");
        return response;
    }

    public SessionMemory.SummaryResult summarize(SessionMemory.SummaryRequest request) {
        if (internalToken.isBlank()) throw new IllegalStateException("memory_internal_token_required");
        SessionMemory.SummaryResult result = restClient.post().uri("/v1/agent/summarize")
                .contentType(MediaType.APPLICATION_JSON).header("X-Internal-Token", internalToken)
                .body(request).retrieve().body(SessionMemory.SummaryResult.class);
        if (result == null) throw new IllegalStateException("empty_summary_response");
        return result;
    }

    public SessionMemory.PlanResult planMemory(SessionMemory.PlanRequest request) {
        if (internalToken.isBlank()) throw new IllegalStateException("memory_internal_token_required");
        var result = restClient.post().uri("/v1/agent/memory/plan")
                .contentType(MediaType.APPLICATION_JSON).header("X-Internal-Token", internalToken)
                .body(request).retrieve().body(SessionMemory.PlanResult.class);
        if (result == null) throw new IllegalStateException("empty_memory_plan_response");
        return result;
    }

    /** Use verified record fields for both the Python call and saved conversation. */
    public void normalizeRequestContext(QARequest request) {
        if (request == null) {
            return;
        }
        AgentManualContextResolver.Target target = manualContextResolver.resolve(request);
        request.setManualId(target.platformId());
        request.setManualRefId(target.referenceId());
        request.setManualName(target.name());
        request.setManualType(target.type());
        request.setProductId(target.productId());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> transcribe(MultipartFile audio) throws IOException {
        ByteArrayResource resource = new ByteArrayResource(audio.getBytes()) {
            @Override
            public String getFilename() {
                return audio.getOriginalFilename() == null ? "voice.pcm" : audio.getOriginalFilename();
            }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("audio", resource);
        Map<String, Object> response = restClient.post()
                .uri("/v1/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .header("X-Internal-Token", internalToken)
                .body(parts)
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("Python ASR returned an empty response");
        }
        return response;
    }

    private Map<String, Object> buildPayload(QARequest request, List<AiMessage> history) {
        Map<String, Object> payload = new LinkedHashMap<>();
        AgentManualContextResolver.Target target = manualContextResolver.resolve(request);
        payload.put("userId", text(request != null ? request.getUserId() : null));
        payload.put("userOpenId", text(request != null ? request.getUserOpenId() : null));
        payload.put("productId", target.productId());
        payload.put("manualId", target.platformId());
        payload.put("manualRefId", target.referenceId());
        payload.put("manualName", target.name());
        payload.put("manualType", target.type());
        payload.put("manualBrand", target.brand());
        payload.put("manualModel", target.model());
        payload.put("query", text(request != null ? request.getQuery() : null));
        payload.put("conversationId", text(request != null ? request.getConversationId() : null));
        payload.put("requestId", text(request != null ? request.getRequestId() : null));
        payload.put("persistHistory", request != null ? request.getPersistHistory() : null);
        if (request != null && request.getMemory() != null) payload.put("memory", request.getMemory());

        List<Map<String, String>> messages = new ArrayList<>();
        if (history != null && request.getMemory() == null && !Boolean.FALSE.equals(request.getPersistHistory())) {
            for (AiMessage message : history) {
                if (message == null || message.getText() == null || message.getText().isBlank()) {
                    continue;
                }
                messages.add(Map.of(
                        "role", "ai".equalsIgnoreCase(message.getRole()) ? "assistant" : text(message.getRole()),
                        "content", message.getText()));
            }
        }
        payload.put("history", messages);
        return payload;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
