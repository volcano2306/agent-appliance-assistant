package com.example.demo.controller;

import com.example.demo.service.AiConversationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/conversations")
@RequiredArgsConstructor
public class AiConversationController {

    private final AiConversationService aiConversationService;

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body("userId is required");
        }
        return ResponseEntity.ok(aiConversationService.listConversations(userId));
    }

    @DeleteMapping
    public ResponseEntity<?> deleteAll(@RequestParam String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body("userId is required");
        }
        int deletedCount = aiConversationService.deleteAllConversations(userId);
        return ResponseEntity.ok(Map.of("status", "deleted", "count", deletedCount));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable String id, @RequestParam String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body("userId is required");
        }
        return aiConversationService.getConversation(userId, id)
                .<ResponseEntity<?>>map(conversation -> ResponseEntity.ok(Map.of(
                        "conversation", conversation,
                        "messages", aiConversationService.getConversationMessages(userId, id)
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/title")
    public ResponseEntity<?> rename(@PathVariable String id, @RequestBody RenameConversationRequest req) {
        if (req == null || req.getUserId() == null || req.getUserId().isBlank()) {
            return ResponseEntity.badRequest().body("userId is required");
        }
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body("title is required");
        }
        return aiConversationService.renameConversation(req.getUserId(), id, req.getTitle())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("conversation not found"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, @RequestParam String userId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body("userId is required");
        }
        boolean deleted = aiConversationService.deleteConversation(userId, id);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }

    @Data
    public static class RenameConversationRequest {
        private String userId;
        private String title;
    }
}
