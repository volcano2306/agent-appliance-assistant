package com.example.demo.controller;

import com.example.demo.model.ServiceOrder;
import com.example.demo.model.Manual;
import com.example.demo.repository.ManualRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.ServiceOrderRepository;
import com.example.demo.repository.UserRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/service-orders")
@RequiredArgsConstructor
public class ServiceOrderController {

    private final ServiceOrderRepository repository;
    private final UserRepository userRepository;
    private final ManualRepository manualRepository;
    private final ProductRepository productRepository;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request,
                                    @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String userId = normalize(request != null ? request.getUserId() : null);
        if (userId == null) {
            userId = normalize(headerUserId);
        }
        if (userId == null || !userRepository.existsById(userId)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_user"));
        }
        if (normalize(headerUserId) != null && !userId.equals(normalize(headerUserId))) {
            return ResponseEntity.status(403).body(Map.of("error", "user_mismatch"));
        }
        String issueDesc = normalize(request.getIssueDesc());
        if (issueDesc == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "issue_desc_required"));
        }
        String manualId = normalize(request.getManualId());
        Manual selectedManual = manualId == null ? null : manualRepository.findById(manualId).orElse(null);
        if (manualId != null && selectedManual == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_platform_manual_id"));
        }
        String deviceId = normalize(request.getDeviceId());
        if (deviceId != null && !productRepository.existsById(deviceId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_device_id"));
        }
        if (selectedManual != null && deviceId != null && normalize(selectedManual.getProductId()) != null
                && !deviceId.equals(normalize(selectedManual.getProductId()))) {
            return ResponseEntity.badRequest().body(Map.of("error", "manual_device_mismatch"));
        }
        if (selectedManual != null && normalize(selectedManual.getBrandName()) != null
                && normalize(request.getBrandName()) != null
                && !selectedManual.getBrandName().equalsIgnoreCase(request.getBrandName().trim())) {
            return ResponseEntity.badRequest().body(Map.of("error", "manual_brand_mismatch"));
        }
        if (selectedManual != null && normalize(selectedManual.getModelName()) != null
                && normalize(request.getModelName()) != null
                && !selectedManual.getModelName().equalsIgnoreCase(request.getModelName().trim())) {
            return ResponseEntity.badRequest().body(Map.of("error", "manual_model_mismatch"));
        }

        Instant now = Instant.now();
        ServiceOrder saved = repository.save(ServiceOrder.builder()
                .userId(userId)
                .deviceId(deviceId)
                .manualId(manualId)
                .serviceType(defaultText(request.getServiceType(), "repair"))
                .deviceName(defaultText(request.getDeviceName(), "待确认设备"))
                .brandName(selectedManual != null ? normalize(selectedManual.getBrandName()) : normalize(request.getBrandName()))
                .modelName(selectedManual != null ? normalize(selectedManual.getModelName()) : normalize(request.getModelName()))
                .faultCode(normalize(request.getFaultCode()))
                .issueDesc(issueDesc)
                .contactPhone(normalize(request.getContactPhone()))
                .preferredTime(normalize(request.getPreferredTime()))
                .conversationId(normalize(request.getConversationId()))
                .status("pending")
                .statusText("待处理")
                .createdAt(now)
                .updatedAt(now)
                .build());
        return ResponseEntity.status(201).body(saved);
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String userId,
                                  @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "user_id_required"));
        }
        if (normalize(headerUserId) != null && !normalizedUserId.equals(normalize(headerUserId))) {
            return ResponseEntity.status(403).body(Map.of("error", "user_mismatch"));
        }
        List<ServiceOrder> orders = repository.findByUserIdOrderByCreatedAtDesc(normalizedUserId);
        return ResponseEntity.ok(orders);
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id, @RequestBody CancelRequest request,
                                    @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String userId = normalize(request != null ? request.getUserId() : null);
        if (userId == null) {
            userId = normalize(headerUserId);
        }
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "login_required"));
        }
        return repository.findByIdAndUserId(id, userId)
                .map(order -> {
                    order.setStatus("canceled");
                    order.setStatusText("已取消");
                    order.setUpdatedAt(Instant.now());
                    return ResponseEntity.ok(repository.save(order));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String defaultText(String value, String fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : normalized;
    }

    @Data
    public static class CreateRequest {
        private String userId;
        private String deviceId;
        private String manualId;
        private String serviceType;
        private String deviceName;
        private String brandName;
        private String modelName;
        private String faultCode;
        private String issueDesc;
        private String contactPhone;
        private String preferredTime;
        private String conversationId;
    }

    @Data
    public static class CancelRequest {
        private String userId;
    }
}
