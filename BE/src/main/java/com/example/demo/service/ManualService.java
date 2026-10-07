package com.example.demo.service;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.model.Brand;
import com.example.demo.model.BrandMessage;
import com.example.demo.model.Manual;
import com.example.demo.model.MyManual;
import com.example.demo.model.SysMessage;
import com.example.demo.repository.BrandMessageRepository;
import com.example.demo.repository.ManualRepository;
import com.example.demo.repository.MyManualRepository;
import com.example.demo.repository.SysMessageRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ManualService {

    private final ManualRepository manualRepository;
    private final MyManualRepository myManualRepository;
    private final SysMessageRepository sysMessageRepository;
    private final BrandMessageRepository brandMessageRepository;
    private final BrandCatalogService brandCatalogService;

    @Value("${app.upload.local-dir}")
    private String uploadDir;

    @Value("${app.python.command:python}")
    private String pythonCommand;

    @Value("${app.python.parser-script}")
    private String parserScript;

    private static final String UPLOAD_PDF_FOLDER = "pdfs";
    private static final String UPLOAD_IMAGE_FOLDER = "images";

    /**
     * 上传说明书（支持公开/私有）
     */
    public Map<String, Object> uploadManual(MultipartFile file, String userId, boolean isPublic,
            String productId, String productName,
            String brandName, String modelName, String categoryCode,
            String summary, String imageUrl) throws IOException {
        String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
        File dir = new File(uploadDir, UPLOAD_PDF_FOLDER);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File dest = new File(dir, filename);
        file.transferTo(dest);
        String filePath = dest.getAbsolutePath();
        String pdfUrl = "/api/files/uploads/" + UPLOAD_PDF_FOLDER + "/" + encode(filename);

        Instant now = Instant.now();
        Map<String, Object> result = new HashMap<>();

        if (isPublic) {
            // 公开：创建 Manual + MyManual
            Manual manual = Manual.builder()
                    .productId(productId)
                    .productName(productName)
                    .brandName(brandName)
                    .modelName(modelName)
                    .categoryCode(categoryCode)
                    .summary(summary)
                    .imageUrl(isBlank(imageUrl) ? null : imageUrl)
                    .pdfUrl(pdfUrl)
                    .filename(filename)
                    .filePath(filePath)
                    .fileSize(file.getSize())
                    .uploaderId(userId)
                    .uploadedAt(now)
                    .isParsing(false)
                    .isParsed(false)
                    .viewCount(0)
                    .addedCount(1) // 上传者自动添加
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            manual = manualRepository.save(manual);

            // 同时添加到用户的"我的说明书"
            MyManual myManual = MyManual.builder()
                    .ownerUserId(userId)
                    .manualId(manual.getId()) // 引用平台说明书
                    .productName(productName)
                    .brandName(brandName)
                    .modelName(modelName)
                    .categoryCode(categoryCode)
                    .imageUrl(manual.getImageUrl())
                    .visibility("private") // 默认私有
                    .isFromUserUpload(false) // 从平台引用
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            myManual = myManualRepository.save(myManual);

            sendUploadThanks(userId, manual, true);

            result.put("manualId", manual.getId());
            result.put("myManualId", myManual.getId());
            result.put("isPublic", true);
        } else {
            // 私有：只创建 MyManual
            MyManual myManual = MyManual.builder()
                    .ownerUserId(userId)
                    .uploadedFilePath(filePath)
                    .uploadedFileName(filename)
                    .uploadedFileSize(file.getSize())
                    .productName(productName)
                    .brandName(brandName)
                    .modelName(modelName)
                    .categoryCode(categoryCode)
                    .imageUrl(isBlank(imageUrl) ? null : imageUrl)
                    .visibility("private")
                    .isFromUserUpload(true) // 用户上传
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            myManual = myManualRepository.save(myManual);

            sendUploadThanks(userId, myManual, false);

            result.put("myManualId", myManual.getId());
            result.put("isPublic", false);
        }

        result.put("filename", filename);
        result.put("filePath", filePath);
        result.put("pdfUrl", pdfUrl);
        if (!isBlank(imageUrl)) {
            result.put("imageUrl", imageUrl);
        }
        result.put("uploadedAt", now);

        return result;
    }

    /**
     * 上传封面图，返回可访问的 imageUrl
     */
    public Map<String, Object> uploadCoverImage(MultipartFile image, String userId) throws IOException {
        String original = image.getOriginalFilename() != null ? image.getOriginalFilename() : "cover.jpg";
        String filename = UUID.randomUUID() + "_" + original;
        File dir = new File(uploadDir, UPLOAD_IMAGE_FOLDER);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File dest = new File(dir, filename);
        image.transferTo(dest);

        String imageUrl = "/api/files/uploads/" + UPLOAD_IMAGE_FOLDER + "/" + encode(filename);
        Map<String, Object> result = new HashMap<>();
        result.put("imageUrl", imageUrl);
        result.put("filePath", dest.getAbsolutePath());
        return result;
    }

    /**
     * 获取说明书并补齐预览图字段
     */
    public Optional<Manual> getManualWithPreview(String id) {
        return manualRepository.findById(id).map(this::ensurePreviewImages);
    }

    /**
     * 确保预览图字段非空（不生成新图片）
     */
    public Manual ensurePreviewImages(Manual manual) {
        if (manual == null) {
            return null;
        }
        if (manual.getPageImages() == null) {
            manual.setPageImages(Collections.emptyList());
        }
        if (manual.getPages() == null && manual.getPageImages() != null) {
            manual.setPages(manual.getPageImages().size());
        }
        return manual;
    }

    /**
     * 收藏说明书后发送系统感谢消息
     */
    public void sendFollowThanks(String userId, Manual manual) {
        if (isBlank(userId) || manual == null) {
            return;
        }
        String name = firstNonBlank(manual.getProductName(), manual.getModelName(), "说明书");
        String content = "已添加" + name + "，感谢支持！";
        sendBrandOrSystemMessage(userId, manual.getBrandName(), content, true);
    }

    public void sendUploadThanks(String userId, Manual manual, boolean publicUpload) {
        if (isBlank(userId) || manual == null) {
            return;
        }
        String name = firstNonBlank(manual.getProductName(), manual.getModelName(), "说明书");
        String content = publicUpload
                ? "已成功上传" + name + "说明书，感谢分享！"
                : "已成功上传" + name + "说明书到我的说明书！";
        sendBrandOrSystemMessage(userId, manual.getBrandName(), content, publicUpload);
    }

    public void sendUploadThanks(String userId, MyManual myManual, boolean publicUpload) {
        if (isBlank(userId) || myManual == null) {
            return;
        }
        String name = firstNonBlank(myManual.getProductName(), myManual.getModelName(), "说明书");
        String content = publicUpload
                ? "已成功上传" + name + "说明书，感谢分享！"
                : "已成功上传" + name + "说明书到我的说明书！";
        sendBrandOrSystemMessage(userId, myManual.getBrandName(), content, publicUpload);
    }

    private void sendBrandOrSystemMessage(String userId, String brandName, String content, boolean preferBrand) {
        if (preferBrand) {
            Optional<Brand> brandOpt = brandCatalogService.findBrand(brandName);
            if (brandOpt.isPresent()) {
                Brand brand = brandOpt.get();
                brandMessageRepository.save(BrandMessage.builder()
                        .brandId(brand.getId())
                        .brandName(brand.getName())
                        .toUserId(userId)
                        .content(content)
                        .createdAt(Instant.now())
                        .build());
                return;
            }
        }
        sysMessageRepository.save(SysMessage.builder()
                .toUserId(userId)
                .content(content)
                .createdAt(Instant.now())
                .build());
    }

    private void sendFollowThanksLegacy(String userId, Manual manual) {
        if (isBlank(userId) || manual == null) {
            return;
        }
        String name = firstNonBlank(manual.getProductName(), "说明书");
        String brand = firstNonBlank(manual.getBrandName(), "");
        String prefix = brand.isEmpty() ? "" : brand + " ";
        String content = "已收藏" + prefix + name + "，感谢支持！";
        sysMessageRepository.save(SysMessage.builder()
                .toUserId(userId)
                .content(content)
                .createdAt(Instant.now())
                .build());
    }

    @Async("taskExecutor")
    public void triggerParseAsync(Manual manual) {
        manual.setParsing(true);
        manual.setParseStartedAt(Instant.now());
        manual.setParseError(null);
        manualRepository.save(manual);

        try {
            String pdfPath = manual.getFilePath() != null ? manual.getFilePath() : manual.getPdfUrl();
            ProcessBuilder pb = new ProcessBuilder(
                    pythonCommand,
                    parserScript,
                    pdfPath,
                    manual.getId()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exit = p.waitFor();

            if (exit == 0) {
                manual.setParsed(true);
                manual.setParseFinishedAt(Instant.now());
            } else {
                manual.setParsed(false);
                manual.setParseError("parse failed, exit code=" + exit);
            }
        } catch (Exception ex) {
            manual.setParsed(false);
            manual.setParseError(ex.getMessage());
        } finally {
            manual.setParsing(false);
            manualRepository.save(manual);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (!isBlank(first)) {
            return first.trim();
        }
        if (!isBlank(second)) {
            return second.trim();
        }
        return fallback;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
