package com.example.demo.service;

import com.example.demo.config.AiCompatProperties;
import com.example.demo.dto.QARequest;
import com.example.demo.dto.QAResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class VisionRecognitionService {

    private static final String VISION_PROMPT = """
            Identify the appliance or manual-related image. Return compact JSON only:
            {"imageType":"","categoryCode":"","brandName":"","modelName":"","faultCode":"",
             "recognizedText":"","confidence":0.0,"queryHint":""}
            Use empty strings when uncertain.
            """;

    private final OpenAiCompatibleClient openAiCompatibleClient;
    private final ObjectMapper objectMapper;

    public boolean hasImage(QARequest req) {
        return req != null
                && (StringUtils.hasText(req.getImageUrl()) || StringUtils.hasText(req.getImageDataUrl()));
    }

    public QAResponse.VisionResult recognize(
            AiCompatProperties.TargetConfig targetConfig,
            QARequest req,
            String query,
            double temperature,
            int timeoutSeconds) {
        if (!hasImage(req)) {
            return null;
        }

        OpenAiCompatibleClient.ChatResult result = openAiCompatibleClient.chatWithVision(
                targetConfig,
                VISION_PROMPT,
                query,
                req.getImageUrl(),
                req.getImageDataUrl(),
                temperature,
                timeoutSeconds);

        String content = result.getContent();
        QAResponse.VisionResult parsed = parseVisionResult(content);
        parsed.setRawText(content);
        return parsed;
    }

    private QAResponse.VisionResult parseVisionResult(String content) {
        if (!StringUtils.hasText(content)) {
            return QAResponse.VisionResult.builder().build();
        }

        String json = extractJsonObject(content.trim());
        try {
            JsonNode root = objectMapper.readTree(json);
            return QAResponse.VisionResult.builder()
                    .imageType(text(root, "imageType"))
                    .categoryCode(text(root, "categoryCode"))
                    .brandName(text(root, "brandName"))
                    .modelName(text(root, "modelName"))
                    .faultCode(text(root, "faultCode"))
                    .recognizedText(text(root, "recognizedText"))
                    .confidence(root.hasNonNull("confidence") ? root.path("confidence").asDouble() : null)
                    .queryHint(text(root, "queryHint"))
                    .build();
        } catch (Exception ex) {
            return QAResponse.VisionResult.builder()
                    .recognizedText(content.trim())
                    .queryHint(content.trim())
                    .build();
        }
    }

    private String extractJsonObject(String value) {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }
}
