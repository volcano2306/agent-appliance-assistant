package com.example.demo.controller;

import java.io.IOException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.repository.ManualRepository;
import com.example.demo.service.ManualService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/manuals")
@RequiredArgsConstructor
public class ManualController {

    private final ManualService manualService;
    private final ManualRepository manualRepository;

    /**
     * 上传说明书（支持公开/私有）
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadManual(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") String userId,
            @RequestParam(value = "isPublic", defaultValue = "false") boolean isPublic,
            @RequestParam(value = "productId", required = false) String productId,
            @RequestParam(value = "productName", required = false) String productName,
            @RequestParam(value = "brandName", required = false) String brandName,
            @RequestParam(value = "modelName", required = false) String modelName,
            @RequestParam(value = "categoryCode", required = false) String categoryCode,
            @RequestParam(value = "summary", required = false) String summary,
            @RequestParam(value = "imageUrl", required = false) String imageUrl) {

        // 如果公开，必须提供产品信息
        if (isPublic && (productName == null || productName.isBlank())) {
            return ResponseEntity.badRequest().body("公开说明书必须提供产品名称");
        }

        try {
            var result = manualService.uploadManual(file, userId, isPublic,
                    productId, productName, brandName, modelName, categoryCode, summary, imageUrl);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            return ResponseEntity.status(500).body("upload error: " + e.getMessage());
        }
    }

    @PostMapping("/upload-image")
    public ResponseEntity<?> uploadImage(
            @RequestParam("image") MultipartFile image,
            @RequestParam("userId") String userId) {
        try {
            return ResponseEntity.ok(manualService.uploadCoverImage(image, userId));
        } catch (IOException e) {
            return ResponseEntity.status(500).body("image upload error: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getManual(@PathVariable String id) {
        return manualService.getManualWithPreview(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/parse")
    public ResponseEntity<?> parseManual(@PathVariable String id) {
        return manualRepository.findById(id).map(manual -> {
            // 防止重复触发
            if (manual.isParsing()) {
                return ResponseEntity.status(409).body("manual is parsing");
            }
            manualService.triggerParseAsync(manual);
            return ResponseEntity.accepted().body("parse started");
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 获取平台说明书列表，支持按 categoryCode / brandName 过滤 GET
     * /api/manuals?categoryCode=xxx&brandName=yyy&keyword=zzz
     */
    @GetMapping
    public ResponseEntity<?> listManuals(
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) String keyword) {

        var result = (categoryCode != null && brandName != null)
                ? manualRepository.findByCategoryCodeAndBrandName(categoryCode, brandName)
                : (categoryCode != null)
                        ? manualRepository.findByCategoryCode(categoryCode)
                        : (brandName != null)
                                ? manualRepository.findByBrandName(brandName)
                                : manualRepository.findAll();

        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.toLowerCase();
            result = result.stream()
                    .filter(m -> (m.getProductName() != null && m.getProductName().toLowerCase().contains(kw))
                    || (m.getModelName() != null && m.getModelName().toLowerCase().contains(kw))
                    || (m.getBrandName() != null && m.getBrandName().toLowerCase().contains(kw)))
                    .collect(java.util.stream.Collectors.toList());
        }

        return ResponseEntity.ok(result);
    }
}
