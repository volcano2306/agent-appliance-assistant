package com.example.demo.controller;

import java.time.Instant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.model.Manual;
import com.example.demo.model.MyManual;
import com.example.demo.repository.ManualRepository;
import com.example.demo.repository.MyManualRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.ManualService;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 我的说明书接口 GET /api/my-manuals?userId= — 获取用户说明书列表（含说明书详情） GET
 * /api/my-manuals/{id} — 获取单条说明书详情 POST /api/my-manuals — 收藏平台说明书到我的列表
 */
@RestController
@RequestMapping("/api/my-manuals")
@RequiredArgsConstructor
public class MyManualController {

    private final MyManualRepository myManualRepository;
    private final ManualRepository manualRepository;
    private final UserRepository userRepository;
    private final ManualService manualService;

    /**
     * 获取指定用户的所有说明书（附带说明书详细信息） GET /api/my-manuals?userId=xxx
     */
    @GetMapping
    public List<Map<String, Object>> getByUser(@RequestParam String userId) {
        return myManualRepository.findByOwnerUserId(userId).stream()
                // 过滤掉 manualId 引用已失效的条目（平台说明书被删除后产生的孤记录）
                .filter(mm -> mm.getManualId() == null || manualRepository.existsById(mm.getManualId()))
                .map(this::enrich)
                .collect(Collectors.toList());
    }

    /**
     * 获取单条我的说明书（附带说明书详细信息） GET /api/my-manuals/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable String id) {
        return myManualRepository.findById(id)
                .map(mm -> ResponseEntity.ok(enrich(mm)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 收藏平台说明书到"我的说明书" POST /api/my-manuals 请求体：{ "userId": "...", "manualId":
     * "..." }
     */
    @PostMapping
    public ResponseEntity<?> addFromPlatform(@RequestBody AddManualRequest req) {
        if (req == null || req.getUserId() == null || req.getUserId().isBlank()) {
            return ResponseEntity.status(401).body("请先登录后再添加说明书");
        }
        if (!userRepository.existsById(req.getUserId().trim())) {
            return ResponseEntity.status(401).body("登录已失效，请重新登录");
        }
        if (req.getManualId() == null || req.getManualId().isBlank()) {
            return ResponseEntity.badRequest().body("manualId is required");
        }
        var manualOpt = manualRepository.findById(req.getManualId());
        if (manualOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("说明书不存在: " + req.getManualId());
        }
        if (myManualRepository.existsByOwnerUserIdAndManualId(req.getUserId(), req.getManualId())) {
            return ResponseEntity.status(409).body("已收藏该说明书");
        }
        Manual manual = manualOpt.get();

        MyManual saved = myManualRepository.save(
                MyManual.builder()
                        .ownerUserId(req.getUserId())
                        .manualId(req.getManualId())
                        .productName(manual.getProductName())
                        .brandName(manual.getBrandName())
                        .modelName(manual.getModelName())
                        .categoryCode(manual.getCategoryCode())
                        .imageUrl(manual.getImageUrl())
                        .visibility("private")
                        .isFromUserUpload(false)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build());

        manualService.sendFollowThanks(req.getUserId(), manual);

        return ResponseEntity.status(201).body(enrich(saved));
    }

    /**
     * 删除我的说明书（按用户隔离） DELETE /api/my-manuals/{id}?userId=xxx
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteById(@PathVariable String id, @RequestParam String userId) {
        return myManualRepository.findById(id)
                .map(mm -> {
                    if (!userId.equals(mm.getOwnerUserId())) {
                        return ResponseEntity.status(403).body("无权删除该说明书");
                    }
                    myManualRepository.deleteById(id);
                    return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ----------------------------------------------------------------
    // 私有方法：将 MyManual 与 Manual 联合构建响应体
    // ----------------------------------------------------------------
    private Map<String, Object> enrich(MyManual mm) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", mm.getId());
        // 优先取用户自定义 nickname，其次显示 productName
        r.put("rename", mm.getNickname() != null ? mm.getNickname() : null);

        if (mm.getManualId() != null) {
            r.put("manualId", mm.getManualId());
            r.put("oname", mm.getProductName());
            r.put("model", mm.getModelName());
            r.put("imageUrl", mm.getImageUrl());
            r.put("brandName", mm.getBrandName());
            r.put("categoryName", mm.getCategoryCode());
            manualRepository.findById(mm.getManualId()).ifPresent(m -> {
                m = manualService.ensurePreviewImages(m);
                r.put("oname", m.getProductName());
                r.put("model", m.getModelName());
                r.put("imageUrl", firstNonBlank(m.getImageUrl(), mm.getImageUrl()));
                r.put("pdfUrl", m.getPdfUrl());
                r.put("pageImages", m.getPageImages());
                r.put("pages", m.getPages());
                r.put("brandName", m.getBrandName());
                r.put("categoryName", m.getCategoryCode());
                r.put("summary", m.getSummary());
                r.put("manualContent", m.getManualContent());
            });
        } else {
            // 用户自行上传的说明书
            r.put("oname", mm.getProductName());
            r.put("model", mm.getModelName());
            r.put("imageUrl", mm.getImageUrl());
            r.put("pdfUrl", uploadFileUrl(mm));
            r.put("uploadedFileUrl", uploadFileUrl(mm));
            r.put("uploadedFileName", mm.getUploadedFileName());
            r.put("brandName", mm.getBrandName());
            r.put("categoryName", mm.getCategoryCode());
        }
        return r;
    }

    private static String uploadFileUrl(MyManual mm) {
        if (mm == null || mm.getUploadedFileName() == null || mm.getUploadedFileName().isBlank()) {
            return null;
        }
        return "/api/files/uploads/pdfs/" + URLEncoder.encode(mm.getUploadedFileName(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    // DTO
    @Data
    public static class AddManualRequest {

        private String userId;
        private String manualId;
    }
}
