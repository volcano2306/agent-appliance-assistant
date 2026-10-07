package com.example.demo.service;

import com.example.demo.dto.QARequest;
import com.example.demo.dto.QAResponse;
import com.example.demo.model.AiConversation;
import com.example.demo.model.AiMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class PersonalizedContextService {

    public PersonalizedContext build(QARequest req,
                                     AiConversation conversation,
                                     List<AiMessage> historyMessages,
                                     QAResponse.VisionResult vision,
                                     String query) {
        return new PersonalizedContext(req, conversation, historyMessages, vision, query);
    }

    public String buildContextualQuery(String query, PersonalizedContext context) {
        String normalized = trimToNull(query);
        if (context == null || context.getVision() == null) {
            return normalized != null ? normalized : "";
        }

        String hint = trimToNull(context.getVision().getQueryHint());
        if (hint == null || (normalized != null && normalized.contains(hint))) {
            return normalized != null ? normalized : "";
        }
        return (normalized != null ? normalized : "") + "\n" + hint;
    }

    public String buildPromptBlock(PersonalizedContext context) {
        if (context == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        QARequest req = context.getReq();
        AiConversation conversation = context.getConversation();

        appendLine(builder, "userId", req != null ? req.getUserId() : null);
        appendLine(builder, "manualRefId", req != null ? firstNonBlank(req.getManualRefId(), req.getManualId()) : null);
        appendLine(builder, "manualName", req != null ? req.getManualName() : null);
        appendLine(builder, "conversationTitle", conversation != null ? conversation.getTitle() : null);

        QAResponse.VisionResult vision = context.getVision();
        if (vision != null) {
            appendLine(builder, "visionCategory", vision.getCategoryCode());
            appendLine(builder, "visionBrand", vision.getBrandName());
            appendLine(builder, "visionModel", vision.getModelName());
            appendLine(builder, "visionFaultCode", vision.getFaultCode());
        }

        return builder.toString();
    }

    private void appendLine(StringBuilder builder, String key, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            builder.append(key).append(": ").append(normalized).append('\n');
        }
    }

    private String firstNonBlank(String first, String second) {
        String normalized = trimToNull(first);
        return normalized != null ? normalized : trimToNull(second);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    @Data
    @AllArgsConstructor
    public static class PersonalizedContext {
        private QARequest req;
        private AiConversation conversation;
        private List<AiMessage> historyMessages;
        private QAResponse.VisionResult vision;
        private String query;
    }
}
