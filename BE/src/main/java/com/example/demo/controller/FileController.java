package com.example.demo.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

/**
 * 静态文件访问控制器 提供图片和PDF文件的访问接口
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
public class FileController {

    /**
     * 数据根目录，配置于 application.properties: app.data.root
     */
    @Value("${app.data.root}")
    private String dataRoot;

    @Value("${app.upload.local-dir}")
    private String uploadDir;

    // 候选子目录（按顺序查找）
    private static final String[] SUB_DIRS = {"sms10(camera)", "sms10(jiadian)"};

    private Path resolveDataRoot() {
        Path configured = Paths.get(dataRoot);
        if (Files.exists(configured)) {
            return configured;
        }

        Path cwdData = Paths.get("data");
        if (Files.exists(cwdData)) {
            return cwdData;
        }

        Path parentData = Paths.get("..", "data");
        if (Files.exists(parentData)) {
            return parentData;
        }

        return configured;
    }

    /**
     * 在两个子目录中查找文件，返回第一个存在的路径；如均不存在返回 null
     */
    private Path resolveFile(String folder, String filename) {
        Path root = resolveDataRoot();
        Path flat = root.resolve(Paths.get(folder, filename));
        if (Files.exists(flat)) {
            return flat;
        }

        for (String sub : SUB_DIRS) {
            Path p = root.resolve(Paths.get(sub, folder, filename));
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 获取图片文件 GET /api/files/images/{folder}/{filename}
     * 例如：/api/files/images/Mamiya%20645%20AFD%20III/Mamiya%20645%20AFD%20III.jpg
     */
    @GetMapping("/images/{folder}/{filename:.+}")
    public ResponseEntity<Resource> getImage(
            @PathVariable String folder,
            @PathVariable String filename) {

        try {
            Path filePath = resolveFile(folder, filename);
            log.info("尝试访问图片: {}/{}", folder, filename);

            if (filePath == null) {
                log.warn("图片文件不存在: {}/{}", folder, filename);
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(filePath);
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "image/jpeg"; // 默认JPEG
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=86400") // 缓存1天
                    .body(resource);

        } catch (IOException e) {
            log.error("读取图片失败: {}/{}", folder, filename, e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 获取PDF文件 GET /api/files/pdfs/{folder}/{filename}
     * 例如：/api/files/pdfs/Mamiya%20645%20AFD%20III/Mamiya_645AFD_III.pdf
     */
    @GetMapping("/pdfs/{folder}/{filename:.+}")
    public ResponseEntity<Resource> getPdf(
            @PathVariable String folder,
            @PathVariable String filename) {

        Path filePath = resolveFile(folder, filename);
        log.info("尝试访问PDF: {}/{}", folder, filename);

        if (filePath == null) {
            log.warn("PDF文件不存在: {}/{}", folder, filename);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
                .body(new FileSystemResource(filePath));
    }

    /**
     * 获取上传目录资源（当前用于封面图） GET /api/files/uploads/images/{filename}
     */
    @GetMapping("/uploads/{folder}/{filename:.+}")
    public ResponseEntity<Resource> getUploadedFile(
            @PathVariable String folder,
            @PathVariable String filename) {

        try {
            Path filePath = Paths.get(uploadDir, folder, filename);
            log.info("尝试访问上传资源: {}/{}", folder, filename);

            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
                    .body(new FileSystemResource(filePath));
        } catch (IOException e) {
            log.error("读取上传资源失败: {}/{}", folder, filename, e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 获取品牌 LOGO（data/LOGO） GET /api/files/logos/{filename}
     */
    @GetMapping("/logos/{filename:.+}")
    public ResponseEntity<Resource> getLogo(@PathVariable String filename) {
        try {
            Path logoRoot = resolveDataRoot().resolve("LOGO");
            Path filePath = null;
            if (Files.isDirectory(logoRoot)) {
                try (Stream<Path> stream = Files.walk(logoRoot)) {
                    List<Path> files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
                    for (Path path : files) {
                        if (path.getFileName().toString().equals(filename)) {
                            filePath = path;
                            break;
                        }
                    }
                }
            }
            log.info("尝试访问品牌LOGO: {}", filename);

            if (filePath == null || !Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = "image/png";
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CACHE_CONTROL, "max-age=86400")
                    .body(new FileSystemResource(filePath));
        } catch (IOException e) {
            log.error("读取品牌LOGO失败: {}", filename, e);
            return ResponseEntity.status(500).build();
        }
    }
}
