package com.example.demo.service;

import com.example.demo.dto.QARequest;
import com.example.demo.dto.QAResponse;
import com.example.demo.model.AiConversation;
import com.example.demo.model.AiMessage;
import com.example.demo.repository.AiConversationRepository;
import com.example.demo.repository.AiMessageRepository;
import com.example.demo.config.SessionMemoryProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiConversationService {

    private final AiConversationRepository aiConversationRepository;
    private final AiMessageRepository aiMessageRepository;
    private final SessionMemoryProperties memoryProperties;
    private final AiTurnLedgerService turnLedger;

    @Value("${ai.history.max-messages:6}")
    private int maxHistoryMessages;

    public List<AiConversation> listConversations(String userId) {
        String normalizedUserId = trimToNull(userId);
        if (normalizedUserId == null) {
            return List.of();
        }
        return aiConversationRepository.findByUserIdOrderByUpdatedAtDesc(normalizedUserId).stream()
                .filter(c -> c.getDeletedAt() == null).toList();
    }

    public Optional<AiConversation> getConversation(String userId, String conversationId) {
        String normalizedUserId = trimToNull(userId);
        String normalizedConversationId = trimToNull(conversationId);
        if (normalizedUserId == null || normalizedConversationId == null) {
            return Optional.empty();
        }
        return aiConversationRepository.findByIdAndUserId(normalizedConversationId, normalizedUserId)
                .filter(c -> c.getDeletedAt() == null);
    }

    public List<AiMessage> getConversationMessages(String userId, String conversationId) {
        return getConversation(userId, conversationId)
                .map(conversation -> aiMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId()))
                .orElse(List.of());
    }

    public Optional<AiConversation> renameConversation(String userId, String conversationId, String title) {
        String normalizedTitle = trimToNull(title);
        if (normalizedTitle == null) {
            return Optional.empty();
        }
        if (memoryProperties.isRuntimeEnabled()) return turnLedger.rename(userId, conversationId, shorten(normalizedTitle, 24));

        return getConversation(userId, conversationId)
                .map(conversation -> {
                    conversation.setTitle(shorten(normalizedTitle, 24));
                    conversation.setCustomTitle(true);
                    return aiConversationRepository.save(conversation);
                });
    }

    public boolean deleteConversation(String userId, String conversationId) {
        if (memoryProperties.isRuntimeEnabled()) return turnLedger.tombstone(userId, conversationId);
        Optional<AiConversation> conversation = getConversation(userId, conversationId);
        if (conversation.isEmpty()) {
            return false;
        }

        aiMessageRepository.deleteByConversationId(conversation.get().getId());
        aiConversationRepository.deleteById(conversation.get().getId());
        return true;
    }

    public int deleteAllConversations(String userId) {
        String normalizedUserId = trimToNull(userId);
        if (normalizedUserId == null) {
            return 0;
        }

        List<AiConversation> conversations = aiConversationRepository.findByUserIdOrderByUpdatedAtDesc(normalizedUserId);
        if (conversations.isEmpty()) {
            return 0;
        }
        if (memoryProperties.isRuntimeEnabled()) {
            return (int) conversations.stream().filter(c -> turnLedger.tombstone(normalizedUserId, c.getId())).count();
        }

        for (AiConversation conversation : conversations) {
            if (conversation == null || trimToNull(conversation.getId()) == null) {
                continue;
            }
            aiMessageRepository.deleteByConversationId(conversation.getId());
        }
        aiConversationRepository.deleteAll(conversations);
        return conversations.size();
    }

    public AiConversation resolveConversation(QARequest req) {
        if (req != null && Boolean.FALSE.equals(req.getPersistHistory())) return null;
        String userId = trimToNull(req != null ? req.getUserId() : null);
        if (userId == null) {
            return null;
        }

        String conversationId = trimToNull(req.getConversationId());
        if (conversationId != null) {
            Optional<AiConversation> existing = aiConversationRepository.findByIdAndUserId(conversationId, userId);
            if (existing.isPresent()) {
                if (existing.get().getDeletedAt() != null) throw new SessionMemoryFault("conversation_not_found");
                return existing.get();
            }
        }

        AiMessage.ManualRef manualRef = buildManualRef(req);
        Instant now = Instant.now();
        return AiConversation.builder()
                .userId(userId)
                .title(buildInitialTitle(req, manualRef))
                .manualRefId(manualRef != null ? manualRef.getId() : null)
                .manualId(manualRef != null ? manualRef.getManualId() : null)
                .manualName(manualRef != null ? manualRef.getName() : null)
                .manualType(manualRef != null ? manualRef.getType() : null)
                .lastMessagePreview("")
                .lastMessageRole("")
                .messageCount(0)
                .customTitle(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public List<AiMessage> getRecentHistoryMessages(String conversationId) {
        String normalizedConversationId = trimToNull(conversationId);
        if (normalizedConversationId == null) {
            return List.of();
        }

        List<AiMessage> messages = aiMessageRepository.findTop6ByConversationIdOrderByCreatedAtDesc(normalizedConversationId);
        List<AiMessage> ordered = new ArrayList<>(messages);
        ordered.sort(Comparator.comparing(AiMessage::getCreatedAt, Comparator.nullsLast(Instant::compareTo)));
        if (ordered.size() <= maxHistoryMessages) {
            return ordered;
        }
        return ordered.subList(Math.max(ordered.size() - maxHistoryMessages, 0), ordered.size());
    }

    public AiConversation appendQaTurn(AiConversation conversation,
                                       QARequest req,
                                       String answerText,
                                       QAResponse.StructuredAnswer structuredAnswer,
                                       boolean insufficientContext,
                                       List<QAResponse.SourceFragment> sources) {
        if (conversation == null) {
            return null;
        }

        AiMessage.ManualRef manualRef = buildManualRef(req);
        if (conversation.getId() == null) {
            conversation.setCreatedAt(conversation.getCreatedAt() != null ? conversation.getCreatedAt() : Instant.now());
            conversation.setUpdatedAt(Instant.now());
            conversation = aiConversationRepository.save(conversation);
        }
        Instant now = Instant.now();

        AiMessage userMessage = AiMessage.builder()
                .conversationId(conversation.getId())
                .role("user")
                .text(trimToNull(req != null ? req.getQuery() : null))
                .image("")
                .manualRef(manualRef)
                .sources(List.of())
                .createdAt(now)
                .build();

        AiMessage aiMessage = AiMessage.builder()
                .conversationId(conversation.getId())
                .role("ai")
                .text(trimToNull(answerText))
                .image("")
                .manualRef(manualRef)
                .structuredAnswer(toStructuredAnswer(structuredAnswer))
                .insufficientContext(insufficientContext)
                .sources(toSourceRefs(sources))
                .createdAt(now.plusMillis(1))
                .build();

        aiMessageRepository.save(userMessage);
        aiMessageRepository.save(aiMessage);

        conversation.setManualRefId(manualRef != null ? manualRef.getId() : null);
        conversation.setManualId(manualRef != null ? manualRef.getManualId() : null);
        conversation.setManualName(manualRef != null ? manualRef.getName() : null);
        conversation.setManualType(manualRef != null ? manualRef.getType() : null);
        conversation.setTitle(buildConversationTitle(conversation.getTitle(), conversation.getCustomTitle(), req, manualRef));
        conversation.setLastMessagePreview(buildPreview(answerText, structuredAnswer));
        conversation.setLastMessageRole("ai");
        conversation.setMessageCount((conversation.getMessageCount() == null ? 0 : conversation.getMessageCount()) + 2);
        conversation.setUpdatedAt(aiMessage.getCreatedAt());
        return aiConversationRepository.save(conversation);
    }

    private AiMessage.StructuredAnswer toStructuredAnswer(QAResponse.StructuredAnswer structuredAnswer) {
        if (structuredAnswer == null) {
            return null;
        }
        return AiMessage.StructuredAnswer.builder()
                .summary(structuredAnswer.getSummary())
                .steps(structuredAnswer.getSteps())
                .cautions(structuredAnswer.getCautions())
                .basisNote(structuredAnswer.getBasisNote())
                .build();
    }

    private List<AiMessage.SourceRef> toSourceRefs(List<QAResponse.SourceFragment> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream()
                .map(source -> AiMessage.SourceRef.builder()
                        .kind(source.getKind())
                        .fragmentId(source.getFragmentId())
                        .page(source.getPage())
                        .chunkIndex(source.getChunkIndex())
                        .title(source.getTitle())
                        .url(source.getUrl())
                        .snippet(source.getSnippet())
                        .sourceType(source.getSourceType())
                        .build())
                .toList();
    }

    private String buildInitialTitle(QARequest req, AiMessage.ManualRef manualRef) {
        String query = trimToNull(req != null ? req.getQuery() : null);
        if (query != null) {
            return shorten(query, 18);
        }
        String manualName = manualRef != null ? trimToNull(manualRef.getName()) : null;
        if (manualName != null) {
            return manualName + " 问答";
        }
        return "新对话";
    }

    private String buildConversationTitle(String currentTitle, Boolean customTitle, QARequest req, AiMessage.ManualRef manualRef) {
        if (Boolean.TRUE.equals(customTitle)) {
            return currentTitle;
        }
        String normalizedCurrent = trimToNull(currentTitle);
        if (normalizedCurrent != null && !"新对话".equals(normalizedCurrent) && !normalizedCurrent.endsWith("问答")) {
            return normalizedCurrent;
        }
        String query = trimToNull(req != null ? req.getQuery() : null);
        if (query != null) {
            return shorten(query, 18);
        }
        String manualName = manualRef != null ? trimToNull(manualRef.getName()) : null;
        return manualName != null ? manualName + " 问答" : "新对话";
    }

    private String buildPreview(String text, QAResponse.StructuredAnswer structuredAnswer) {
        if (structuredAnswer != null) {
            String summary = trimToNull(structuredAnswer.getSummary());
            if (summary != null) {
                return shorten(summary.replaceAll("\\s+", " ").trim(), 36);
            }
        }
        String normalized = trimToNull(text);
        if (normalized == null) {
            return "";
        }
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return shorten(normalized, 36);
    }

    private String shorten(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private AiMessage.ManualRef buildManualRef(QARequest req) {
        if (req == null) {
            return null;
        }

        String refId = trimToNull(req.getManualRefId());
        String manualId = trimToNull(req.getManualId());
        String name = trimToNull(req.getManualName());
        String type = trimToNull(req.getManualType());
        if (refId == null && manualId == null && name == null) {
            return null;
        }

        return AiMessage.ManualRef.builder()
                .id(refId != null ? refId : manualId)
                // A private my_manuals reference is not a platform manuals._id.
                .manualId(manualId)
                .name(name)
                .type(type != null ? type : "platform")
                .build();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
