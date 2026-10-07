package com.example.demo.service;

import com.example.demo.model.Fragment;
import com.example.demo.model.Manual;
import com.example.demo.model.MyManual;
import com.example.demo.repository.FragmentRepository;
import com.example.demo.repository.ManualRepository;
import com.example.demo.repository.MyManualRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FragmentService {

    private final FragmentRepository fragmentRepository;
    private final ManualRepository manualRepository;
    private final MyManualRepository myManualRepository;

    @Value("${ai.fragment.chunk-size:700}")
    private int chunkSize;

    @Value("${ai.fragment.chunk-overlap:120}")
    private int chunkOverlap;

    public List<Fragment> getOrBuildFragments(String manualRefId) {
        List<Fragment> existing = sortFragments(fragmentRepository.findByManualId(manualRefId));
        if (!existing.isEmpty()) {
            return existing;
        }

        manualRepository.findById(manualRefId).ifPresent(this::rebuildFragmentsForManual);
        existing = sortFragments(fragmentRepository.findByManualId(manualRefId));
        if (!existing.isEmpty()) {
            return existing;
        }

        myManualRepository.findById(manualRefId).ifPresent(this::rebuildFragmentsForMyManual);
        return sortFragments(fragmentRepository.findByManualId(manualRefId));
    }

    public int rebuildFragmentsForManual(Manual manual) {
        if (manual == null || !hasText(manual.getId())) {
            return 0;
        }

        ExtractionResult extraction = extractFromManual(manual);
        List<Fragment> fragments = buildFragments(
                manual.getId(),
                manual.getProductId(),
                extraction.pageTexts(),
                extraction.source()
        );
        replaceFragments(manual.getId(), fragments);

        boolean updated = false;
        if (hasText(extraction.combinedText()) && !extraction.combinedText().equals(trimToEmpty(manual.getManualContent()))) {
            manual.setManualContent(extraction.combinedText());
            updated = true;
        }
        if (extraction.totalPages() > 0 && (manual.getPages() == null || manual.getPages() <= 0)) {
            manual.setPages(extraction.totalPages());
            updated = true;
        }
        if (updated) {
            manual.setUpdatedAt(Instant.now());
            manualRepository.save(manual);
        }

        return fragments.size();
    }

    public int rebuildFragmentsForMyManual(MyManual myManual) {
        if (myManual == null || !hasText(myManual.getId())) {
            return 0;
        }

        ExtractionResult extraction = extractFromUploadedFile(myManual.getUploadedFilePath(), "user_upload_pdf_text");
        List<Fragment> fragments = buildFragments(
                myManual.getId(),
                null,
                extraction.pageTexts(),
                extraction.source()
        );
        replaceFragments(myManual.getId(), fragments);
        return fragments.size();
    }

    private ExtractionResult extractFromManual(Manual manual) {
        ExtractionResult fromPdf = extractFromUploadedFile(manual.getFilePath(), "pdf_text");
        if (!fromPdf.pageTexts().isEmpty()) {
            return fromPdf;
        }

        String manualContent = normalizeText(manual.getManualContent());
        if (hasText(manualContent)) {
            return new ExtractionResult(
                    manual.getPages() != null && manual.getPages() > 0 ? manual.getPages() : 1,
                    List.of(new PageText(1, manualContent)),
                    manualContent,
                    "manual_content"
            );
        }

        return ExtractionResult.empty();
    }

    private ExtractionResult extractFromUploadedFile(String filePath, String source) {
        if (!hasText(filePath)) {
            return ExtractionResult.empty();
        }

        File pdfFile = new File(filePath);
        if (!pdfFile.isFile()) {
            return ExtractionResult.empty();
        }

        List<PageText> pages = new ArrayList<>();
        StringBuilder combined = new StringBuilder();

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = document.getNumberOfPages();

            for (int pageNumber = 1; pageNumber <= totalPages; pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String text = normalizeText(stripper.getText(document));
                if (!hasText(text)) {
                    continue;
                }
                pages.add(new PageText(pageNumber, text));
                if (combined.length() > 0) {
                    combined.append("\n\n");
                }
                combined.append(text);
            }

            return new ExtractionResult(totalPages, pages, combined.toString().trim(), source);
        } catch (Exception ex) {
            log.warn("FragmentService: PDF 提取失败 file={} reason={}", filePath, ex.getMessage());
            return ExtractionResult.empty();
        }
    }

    private List<Fragment> buildFragments(String manualRefId, String productId, List<PageText> pages, String source) {
        List<Fragment> result = new ArrayList<>();
        Instant now = Instant.now();

        for (PageText pageText : pages) {
            List<String> chunks = chunkText(pageText.text());
            for (int i = 0; i < chunks.size(); i++) {
                result.add(Fragment.builder()
                        .manualId(manualRefId)
                        .productId(productId)
                        .page(pageText.pageNumber())
                        .chunkIndex(i)
                        .text(chunks.get(i))
                        .source(source)
                        .createdAt(now)
                        .build());
            }
        }

        return result;
    }

    private void replaceFragments(String manualRefId, List<Fragment> fragments) {
        if (fragments.isEmpty()) {
            return;
        }
        fragmentRepository.deleteByManualId(manualRefId);
        fragmentRepository.saveAll(fragments);
    }

    private List<String> chunkText(String text) {
        String normalized = normalizeText(text);
        if (!hasText(normalized)) {
            return List.of();
        }
        if (normalized.length() <= chunkSize) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int tentativeEnd = Math.min(start + chunkSize, normalized.length());
            int end = adjustChunkEnd(normalized, start, tentativeEnd);
            if (end <= start) {
                end = tentativeEnd;
            }

            String chunk = normalized.substring(start, end).trim();
            if (hasText(chunk)) {
                chunks.add(chunk);
            }

            if (end >= normalized.length()) {
                break;
            }

            int nextStart = Math.max(end - chunkOverlap, start + 1);
            while (nextStart < normalized.length() && Character.isWhitespace(normalized.charAt(nextStart))) {
                nextStart++;
            }
            start = nextStart;
        }

        return chunks;
    }

    private int adjustChunkEnd(String text, int start, int tentativeEnd) {
        if (tentativeEnd >= text.length()) {
            return text.length();
        }

        int minSearch = Math.max(start + Math.max(chunkSize / 2, 120), start + 1);
        for (int i = tentativeEnd; i >= minSearch; i--) {
            char ch = text.charAt(i - 1);
            if (isBoundary(ch)) {
                return i;
            }
        }
        return tentativeEnd;
    }

    private boolean isBoundary(char ch) {
        return ch == '\n'
                || ch == '。'
                || ch == '！'
                || ch == '？'
                || ch == '；'
                || ch == '.'
                || ch == '!'
                || ch == '?'
                || ch == ';';
    }

    private List<Fragment> sortFragments(List<Fragment> fragments) {
        return fragments.stream()
                .sorted(Comparator
                        .comparing(Fragment::getPage, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Fragment::getChunkIndex, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Fragment::getId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private String normalizeText(String text) {
        String normalized = trimToNull(text);
        if (normalized == null) {
            return "";
        }
        normalized = normalized.replace("\r", "");
        normalized = normalized.replace('\u0000', ' ');
        normalized = normalized.replaceAll("[\\t\\x0B\\f]+", " ");
        normalized = normalized.replaceAll("[ ]{2,}", " ");
        normalized = normalized.replaceAll(" *\n *", "\n");
        normalized = normalized.replaceAll("\n{3,}", "\n\n");
        return normalized.trim();
    }

    private boolean hasText(String value) {
        return trimToNull(value) != null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private record PageText(int pageNumber, String text) {
    }

    private record ExtractionResult(int totalPages, List<PageText> pageTexts, String combinedText, String source) {
        private static ExtractionResult empty() {
            return new ExtractionResult(0, List.of(), "", "unknown");
        }
    }
}
