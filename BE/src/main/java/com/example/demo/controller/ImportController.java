package com.example.demo.controller;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.model.Manual;
import com.example.demo.repository.ManualRepository;
import com.example.demo.repository.MyManualRepository;
import com.example.demo.service.BrandCatalogService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class ImportController {

    private final ManualRepository manualRepository;
    private final MyManualRepository myManualRepository;
    private final BrandCatalogService brandCatalogService;

    @Value("${app.data.root:}")
    private String configuredDataRoot;

    private static final float PREVIEW_DPI = 100f;
    private static final int MAX_PREVIEW_PAGES = 10;

    @GetMapping("/import")
    public ResponseEntity<Map<String, Object>> importManuals(
            @RequestParam(defaultValue = "no") String confirm,
            @RequestParam(defaultValue = "false") boolean force) {

        if (!"yes".equals(confirm)) {
            return ResponseEntity.ok(Map.of(
                    "status", "waiting",
                    "tip", "Use /api/admin/import?confirm=yes to import local manuals",
                    "warning", "Add force=true only when you want to clear manuals + my_manuals first"
            ));
        }

        long existingCount = manualRepository.count();
        if (!force && existingCount > 0) {
            return ResponseEntity.ok(Map.of(
                    "status", "skipped",
                    "message", "manuals already has " + existingCount
                            + " records. Use /api/admin/import?confirm=yes&force=true to clear and reimport.",
                    "existingCount", existingCount
            ));
        }

        if (force) {
            manualRepository.deleteAll();
            myManualRepository.deleteAll();
            log.info("ImportController: force=true, cleared manuals + my_manuals");
        }

        File dataRoot = resolveDataRoot();
        log.info("ImportController: using data root {}", dataRoot.getAbsolutePath());

        List<Map<String, Object>> results = new ArrayList<>();
        importCamera(dataRoot, results);
        importJiadian(dataRoot, results);

        long ok = results.stream().filter(r -> "ok".equals(r.get("status"))).count();
        long err = results.stream().filter(r -> "error".equals(r.get("status"))).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalProcessed", results.size());
        summary.put("success", ok);
        summary.put("failed", err);
        summary.put("details", results);

        log.info("ImportController: import finished success={} failed={}", ok, err);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/import-brands")
    public ResponseEntity<Map<String, Object>> importBrands(
            @RequestParam(defaultValue = "no") String confirm) {
        if (!"yes".equals(confirm)) {
            return ResponseEntity.ok(Map.of(
                    "status", "waiting",
                    "tip", "Use /api/admin/import-brands?confirm=yes to import data/LOGO"
            ));
        }
        try {
            return ResponseEntity.ok(brandCatalogService.importLogoBrands());
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of(
                    "status", "error",
                    "error", e.getMessage()
            ));
        }
    }

    private File resolveDataRoot() {
        List<File> candidates = new ArrayList<>();
        if (configuredDataRoot != null && !configuredDataRoot.isBlank()) {
            candidates.add(new File(configuredDataRoot));
        }

        File userDir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        candidates.add(new File(userDir, "data"));
        File parent = userDir.getParentFile();
        if (parent != null) {
            candidates.add(new File(parent, "data"));
        }
        candidates.add(new File("data").getAbsoluteFile());
        candidates.add(new File("..", "data").getAbsoluteFile());

        for (File candidate : candidates) {
            if (candidate.isDirectory()) {
                File camera = new File(candidate, "sms10(camera)");
                File jiadianOld = new File(candidate, "sms10(jiadian)");
                File jiadianNew = new File(candidate, "jiadian");
                if (camera.isDirectory() || jiadianOld.isDirectory() || jiadianNew.isDirectory()) {
                    return candidate;
                }
            }
        }

        return candidates.get(0);
    }

    private void importCamera(File dataRoot, List<Map<String, Object>> results) {
        File cameraDir = firstExistingDirectory(
                new File(dataRoot, "sms10(camera)"),
                new File(dataRoot, "camera")
        );
        if (cameraDir == null) {
            log.warn("ImportController: camera directory not found under {}", dataRoot.getAbsolutePath());
            return;
        }

        Map<String, CameraInfo> docxMap = parseDocx(firstFileByExt(cameraDir, ".docx"));
        File[] folders = cameraDir.listFiles(File::isDirectory);
        if (folders == null) {
            return;
        }
        Arrays.sort(folders, Comparator.comparing(File::getName));

        for (File folder : folders) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("folder", folder.getName());
            row.put("source", "camera");
            try {
                Manual manual = buildCameraManual(folder, docxMap);
                manualRepository.save(manual);
                row.put("status", "ok");
                row.put("imageUrl", manual.getImageUrl());
                row.put("pdfUrl", manual.getPdfUrl());
                log.info("ImportController: imported camera {}", folder.getName());
            } catch (Exception e) {
                row.put("status", "error");
                row.put("error", e.getMessage());
                log.error("ImportController: camera {} failed: {}", folder.getName(), e.getMessage(), e);
            }
            results.add(row);
        }
    }

    private Manual buildCameraManual(File folder, Map<String, CameraInfo> docxMap) {
        String productName = folder.getName();
        String folderNorm = normalize(productName);

        CameraInfo info = null;
        for (Map.Entry<String, CameraInfo> entry : docxMap.entrySet()) {
            String key = entry.getKey();
            if (folderNorm.contains(key) || key.contains(folderNorm)) {
                info = entry.getValue();
                break;
            }
        }

        String brandName = info != null && info.brand() != null ? info.brand() : parseBrand(productName);
        String modelName = info != null && info.model() != null ? info.model() : productName;
        String summary = info != null ? info.summary() : null;

        File image = findCameraCoverImage(folder);
        File pdf = firstFileByExt(folder, ".pdf");

        String folderEnc = encode(productName);
        String imageUrl = image != null ? "/api/files/images/" + folderEnc + "/" + encode(image.getName()) : null;
        String pdfUrl = pdf != null ? "/api/files/pdfs/" + folderEnc + "/" + encode(pdf.getName()) : null;

        List<String> pageImages = pdf != null
                ? renderPdfPreviewImages(pdf, folder, folder.getName(), "camera_preview")
                : List.of();

        return Manual.builder()
                .productName(productName)
                .brandName(brandName)
                .modelName(modelName)
                .categoryCode("camera")
                .imageUrl(imageUrl)
                .pdfUrl(pdfUrl)
                .summary(summary)
                .pageImages(pageImages)
                .filename(pdf != null ? pdf.getName() : null)
                .filePath(pdf != null ? pdf.getAbsolutePath() : null)
                .fileSize(pdf != null ? pdf.length() : null)
                .pages(pageImages.isEmpty() ? null : pageImages.size())
                .isParsing(false)
                .isParsed(!pageImages.isEmpty())
                .viewCount(0)
                .addedCount(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private File findCameraCoverImage(File folder) {
        File[] images = folder.listFiles(f -> f.isFile() && isImage(f.getName()));
        if (images == null || images.length == 0) {
            return null;
        }
        Arrays.sort(images, Comparator.comparing(File::getName));
        for (File image : images) {
            String name = image.getName().toLowerCase();
            if (!name.startsWith("camera_preview_") && !name.startsWith("preview_")) {
                return image;
            }
        }
        return images[0];
    }

    private Map<String, CameraInfo> parseDocx(File docxFile) {
        Map<String, CameraInfo> result = new LinkedHashMap<>();
        if (docxFile == null || !docxFile.exists()) {
            return result;
        }
        try (XWPFDocument doc = new XWPFDocument(new FileInputStream(docxFile))) {
            String brand = null;
            String model = null;
            StringBuilder descBuilder = null;
            boolean collectingDesc = false;

            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                text = text.trim();

                if (text.contains("\u4ea7\u54c1\u6838\u5fc3\u4fe1\u606f")) {
                    if (model != null && descBuilder != null) {
                        result.put(normalize(model),
                                new CameraInfo(cleanBrand(brand), model, descBuilder.toString().trim()));
                    }
                    brand = null;
                    model = null;
                    descBuilder = null;
                    collectingDesc = false;
                } else if (text.startsWith("\u54c1\u724c\uff1a")) {
                    brand = text.substring("\u54c1\u724c\uff1a".length()).trim();
                    collectingDesc = false;
                } else if (text.startsWith("\u578b\u53f7\uff1a")) {
                    model = text.substring("\u578b\u53f7\uff1a".length()).trim();
                    collectingDesc = false;
                } else if (text.contains("\u4ea7\u54c1\u8bf4\u660e")) {
                    collectingDesc = true;
                    descBuilder = new StringBuilder();
                } else if (collectingDesc && descBuilder != null) {
                    if (descBuilder.length() > 0) {
                        descBuilder.append("\n");
                    }
                    descBuilder.append(text);
                    if (model != null) {
                        result.put(normalize(model),
                                new CameraInfo(cleanBrand(brand), model, descBuilder.toString().trim()));
                    }
                }
            }
            if (model != null && descBuilder != null) {
                result.put(normalize(model),
                        new CameraInfo(cleanBrand(brand), model, descBuilder.toString().trim()));
            }
        } catch (Exception e) {
            log.warn("ImportController: parse camera docx failed: {}", e.getMessage());
        }
        return result;
    }

    private String cleanBrand(String brand) {
        if (brand == null) {
            return null;
        }
        return brand.replaceAll("[\uff08(][^\uff09)]*[\uff09)]", "").trim();
    }

    private record CameraInfo(String brand, String model, String summary) {
    }

    private void importJiadian(File dataRoot, List<Map<String, Object>> results) {
        File jiadianDir = firstExistingDirectory(
                new File(dataRoot, "sms10(jiadian)"),
                new File(dataRoot, "jiadian")
        );
        if (jiadianDir == null) {
            log.warn("ImportController: jiadian directory not found under {}", dataRoot.getAbsolutePath());
            return;
        }

        File xlsxFile = firstFileByExt(jiadianDir, ".xlsx");
        if (xlsxFile == null) {
            log.warn("ImportController: jiadian xlsx not found under {}", jiadianDir.getAbsolutePath());
            return;
        }

        try (Workbook wb = new XSSFWorkbook(new FileInputStream(xlsxFile))) {
            Sheet sheet = wb.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();

            for (int i = 1; i <= lastRow; i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }

                String serial = cellStr(row, 0);
                String productName = cellStr(row, 1);
                String modelName = cellStr(row, 2);
                String category = cellStr(row, 3);
                String brandName = cellStr(row, 4);
                String summary = cellStr(row, 5);
                String pdfRef = cellStr(row, 6);
                String imgRef = cellStr(row, 7);

                if (productName == null || productName.isBlank()) {
                    continue;
                }

                Map<String, Object> rowResult = new LinkedHashMap<>();
                rowResult.put("row", i);
                rowResult.put("serial", serial);
                rowResult.put("productName", productName);
                rowResult.put("category", category);

                try {
                    File image = resolveReferencedFile(jiadianDir, "IMG", imgRef, serial,
                            List.of(".jpg", ".jpeg", ".png", ".webp"));
                    File pdf = resolveReferencedFile(jiadianDir, "PDF", pdfRef, serial, List.of(".pdf"));

                    String imageUrl = image != null ? fileUrl("/api/files/images", jiadianDir, image) : null;
                    String pdfUrl = pdf != null ? fileUrl("/api/files/pdfs", jiadianDir, pdf) : null;

                    List<String> pageImages = pdf != null
                            ? renderPdfPreviewImages(pdf, new File(jiadianDir, "IMG"), "IMG", "jiadian_preview")
                            : List.of();

                    Manual manual = Manual.builder()
                            .productName(productName)
                            .brandName(brandName)
                            .modelName(modelName)
                            .categoryCode(category != null && !category.isBlank() ? category : "jiadian")
                            .imageUrl(imageUrl)
                            .pdfUrl(pdfUrl)
                            .summary(summary)
                            .pageImages(pageImages)
                            .filename(pdf != null ? pdf.getName() : pdfRef)
                            .filePath(pdf != null ? pdf.getAbsolutePath() : null)
                            .fileSize(pdf != null ? pdf.length() : null)
                            .pages(pageImages.isEmpty() ? null : pageImages.size())
                            .isParsing(false)
                            .isParsed(!pageImages.isEmpty())
                            .viewCount(0)
                            .addedCount(0)
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();

                    manualRepository.save(manual);
                    rowResult.put("status", "ok");
                    rowResult.put("imageUrl", imageUrl);
                    rowResult.put("pdfUrl", pdfUrl);
                    if (image == null && imgRef != null && !imgRef.isBlank()) {
                        rowResult.put("imageWarning", "image file not found: " + imgRef);
                    }
                    if (pdf == null && pdfRef != null && !pdfRef.isBlank()) {
                        rowResult.put("pdfWarning", "pdf file not found: " + pdfRef);
                    }
                    log.info("ImportController: imported jiadian {}", productName);
                } catch (Exception e) {
                    rowResult.put("status", "error");
                    rowResult.put("error", e.getMessage());
                    log.error("ImportController: jiadian row {} failed: {}", i, e.getMessage(), e);
                }
                results.add(rowResult);
            }
        } catch (Exception e) {
            log.error("ImportController: parse jiadian xlsx failed: {}", e.getMessage(), e);
        }
    }

    private File resolveReferencedFile(File baseDir, String preferredFolder, String ref, String serial, List<String> exts) {
        List<String> names = new ArrayList<>();
        if (ref != null && !ref.isBlank()) {
            names.add(ref.trim());
        }
        if (serial != null && !serial.isBlank()) {
            String raw = serial.trim();
            String noLeadingZero = raw.replaceFirst("^0+(?!$)", "");
            for (String ext : exts) {
                names.add(raw + ext);
                names.add(noLeadingZero + ext);
                names.add("IMG_" + raw + ext);
                names.add("IMG_" + noLeadingZero + ext);
                names.add("PDF_" + raw + ext);
                names.add("PDF_" + noLeadingZero + ext);
            }
        }

        for (String name : names) {
            File exact = findByRelativeOrName(baseDir, preferredFolder, name);
            if (exact != null) {
                return exact;
            }
        }
        return null;
    }

    private File findByRelativeOrName(File baseDir, String preferredFolder, String name) {
        File direct = new File(baseDir, name);
        if (direct.isFile()) {
            return direct;
        }

        File inPreferred = new File(new File(baseDir, preferredFolder), name);
        if (inPreferred.isFile()) {
            return inPreferred;
        }

        String target = new File(name).getName();
        File[] folders = baseDir.listFiles(File::isDirectory);
        if (folders == null) {
            return null;
        }
        Arrays.sort(folders, Comparator.comparing(File::getName));
        for (File folder : folders) {
            File candidate = new File(folder, target);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private String fileUrl(String baseUrl, File jiadianDir, File file) {
        File parent = file.getParentFile();
        String folder = parent != null && parent.equals(jiadianDir) ? "." : parent.getName();
        return baseUrl + "/" + encode(folder) + "/" + encode(file.getName());
    }

    private File firstExistingDirectory(File... dirs) {
        for (File dir : dirs) {
            if (dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }

    private File firstFileByExt(File dir, String ext) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(ext));
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files[0];
    }

    private String cellStr(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double v = cell.getNumericCellValue();
            return String.valueOf((long) v);
        }
        String s = cell.getCellType() == CellType.STRING
                ? cell.getStringCellValue()
                : cell.toString();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private boolean isImage(String name) {
        String l = name.toLowerCase();
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp");
    }

    private String parseBrand(String name) {
        String[] parts = name.split("[_ ]");
        return parts.length > 0 && !parts[0].isEmpty() ? parts[0] : name;
    }

    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private List<String> renderPdfPreviewImages(File pdfFile, File outputDir, String urlFolder, String keyPrefix) {
        List<String> urls = new ArrayList<>();
        if (pdfFile == null || !pdfFile.exists()) {
            return urls;
        }

        if (!outputDir.exists() && !outputDir.mkdirs()) {
            log.warn("ImportController: cannot create preview directory {}", outputDir.getAbsolutePath());
            return urls;
        }

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int totalPages = Math.min(doc.getNumberOfPages(), MAX_PREVIEW_PAGES);
            String base = pdfFile.getName().replaceAll("\\.[^.]+$", "");

            for (int i = 0; i < totalPages; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, PREVIEW_DPI, ImageType.RGB);
                String previewName = keyPrefix + "_" + normalize(base) + "_p" + (i + 1) + ".jpg";
                File out = new File(outputDir, previewName);
                ImageIO.write(image, "jpg", out);
                urls.add("/api/files/images/" + encode(urlFolder) + "/" + encode(previewName));
            }
        } catch (IOException e) {
            log.warn("ImportController: render preview failed {} -> {}", pdfFile.getName(), e.getMessage());
        }
        return urls;
    }
}
