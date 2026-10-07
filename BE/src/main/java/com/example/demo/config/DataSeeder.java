package com.example.demo.config;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.demo.model.Manual;
import com.example.demo.repository.ManualRepository;
import com.example.demo.service.OssService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动时扫描 app.data.camera-dir，将每个产品文件夹的图片 + PDF 上传到 OSS，
 * 并将 PDF 每页渲染为图片上传，URL 存入 MongoDB manuals 集合。
 *
 * 仅当 app.db.seed=true 时生效，数据写入完成后务必改回 false。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.db.seed", havingValue = "true")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final ManualRepository manualRepository;
    private final OssService ossService;

    @Value("${app.data.camera-dir}")
    private String cameraDirPath;

    /** PDF 页面渲染 DPI（100 对手机屏幕已足够清晰） */
    private static final float RENDER_DPI = 100f;

    @Override
    public void run(ApplicationArguments args) {
        // PDFBox 在无显示器环境下运行需要 headless 模式
        System.setProperty("java.awt.headless", "true");

        File cameraDir = new File(cameraDirPath);
        if (!cameraDir.isDirectory()) {
            log.warn("DataSeeder: camera 目录不存在或不是目录: {}", cameraDirPath);
            return;
        }

        File[] folders = cameraDir.listFiles(File::isDirectory);
        if (folders == null || folders.length == 0) {
            log.warn("DataSeeder: camera 目录下没有产品子目录");
            return;
        }

        log.info("DataSeeder: 开始填充数据，共 {} 个产品目录", folders.length);
        manualRepository.deleteAll();

        Arrays.sort(folders);
        for (File folder : folders) {
            try {
                seedProduct(folder);
            } catch (Exception e) {
                log.error("DataSeeder: 处理产品 [{}] 失败，跳过", folder.getName(), e);
            }
        }
        log.info("DataSeeder: 数据填充完毕");
    }

    // ----------------------------------------------------------------
    // 私有方法
    // ----------------------------------------------------------------

    private void seedProduct(File folder) throws Exception {
        String productName = folder.getName();
        String folderKey  = "camera/" + encode(productName);

        // 查找封面图和 PDF
        File[] imgs = folder.listFiles(f -> f.isFile() && isImageFile(f.getName()));
        File[] pdfs = folder.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".pdf"));

        // --- 上传封面图 ---
        String imageUrl = null;
        if (imgs != null && imgs.length > 0) {
            File img  = imgs[0];
            String key = folderKey + "/cover" + ext(img.getName());
            imageUrl   = ossService.upload(key, Files.newInputStream(img.toPath()),
                    img.length(), "image/jpeg");
            log.info("DataSeeder: [{}] 封面图已上传 -> {}", productName, imageUrl);
        }

        // --- 上传 PDF + 转页面图片 ---
        String pdfUrl = null;
        List<String> pageImages = new ArrayList<>();

        if (pdfs != null && pdfs.length > 0) {
            File pdf = pdfs[0];

            // 上传原始 PDF
            String pdfKey = folderKey + "/" + encode(pdf.getName());
            pdfUrl = ossService.upload(pdfKey, Files.newInputStream(pdf.toPath()),
                    pdf.length(), "application/pdf");
            log.info("DataSeeder: [{}] PDF 已上传 -> {}", productName, pdfUrl);

            // PDF 转页面图片
            pageImages = convertPdfToImages(pdf, folderKey);
        }

        Manual manual = Manual.builder()
                .productName(productName)
                .brandName(parseBrand(productName))
                .categoryCode("camera")
                .imageUrl(imageUrl)
                .pdfUrl(pdfUrl)
                .pageImages(pageImages)
                .filename(pdfs != null && pdfs.length > 0 ? pdfs[0].getName() : null)
                .filePath(pdfs != null && pdfs.length > 0 ? pdfs[0].getAbsolutePath() : null)
                .fileSize(pdfs != null && pdfs.length > 0 ? pdfs[0].length() : null)
                .pages(pageImages.isEmpty() ? null : pageImages.size())
                .isParsing(false)
                .isParsed(!pageImages.isEmpty())
                .viewCount(0)
                .addedCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        manualRepository.save(manual);
        log.info("DataSeeder: [{}] 保存完毕，共 {} 页", productName, pageImages.size());
    }

    /**
     * 将 PDF 每页渲染为 JPEG 并上传到 OSS，返回 URL 列表。
     * 单页失败不影响其他页。
     */
    private List<String> convertPdfToImages(File pdfFile, String folderKey) {
        List<String> urls = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int total = doc.getNumberOfPages();
            log.info("DataSeeder: 开始转换 PDF [{}]，共 {} 页", pdfFile.getName(), total);

            for (int i = 0; i < total; i++) {
                try {
                    BufferedImage img = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(img, "jpeg", baos);
                    byte[] bytes = baos.toByteArray();

                    String key = folderKey + "/pages/page_" + i + ".jpg";
                    String url = ossService.upload(key,
                            new ByteArrayInputStream(bytes), bytes.length, "image/jpeg");
                    urls.add(url);
                    log.debug("DataSeeder: 第 {}/{} 页已上传", i + 1, total);
                } catch (Exception e) {
                    log.warn("DataSeeder: 第 {} 页转换失败，跳过: {}", i, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("DataSeeder: PDF 转图片失败: {}", pdfFile.getName(), e);
        }
        return urls;
    }

    private boolean isImageFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    /** 从产品名中提取品牌（下划线或空格分隔的第一段） */
    private String parseBrand(String productName) {
        String first = productName.split("[_ ]")[0];
        return first.isEmpty() ? productName : first;
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String ext(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".jpg";
    }
}