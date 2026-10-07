package com.example.demo.service;

import com.example.demo.model.Fragment;
import com.example.demo.model.Manual;
import com.example.demo.model.MyManual;
import com.example.demo.repository.ManualRepository;
import com.example.demo.repository.MyManualRepository;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ManualRetrievalService {

    private static final Pattern SEARCH_TERM_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}|[a-z0-9]{2,}");

    private final ManualRepository manualRepository;
    private final MyManualRepository myManualRepository;
    private final FragmentService fragmentService;
    private final EmbeddingService embeddingService;

    @Value("${ai.max-retrieved-fragments:5}")
    private int maxRetrievedFragments;

    @Value("${ai.min-fragment-score:16}")
    private double minFragmentScore;

    @Value("${ai.embedding.min-similarity:0.45}")
    private double minEmbeddingSimilarity;

    @Value("${ai.embedding.keyword-weight:0.4}")
    private double keywordWeight;

    @Value("${ai.embedding.bm25-weight:0.3}")
    private double bm25Weight;

    @Value("${ai.embedding.vector-weight:0.6}")
    private double vectorWeight;

    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;

    public RetrievalResult retrieve(String manualRefId, String query, int timeoutSeconds) {
        return retrieve(manualRefId, query, timeoutSeconds, null);
    }

    public RetrievalResult retrieve(
            String manualRefId,
            String query,
            int timeoutSeconds,
            Integer requestedTopK) {

        String effectiveManualRefId = trimToNull(manualRefId);
        String effectiveQuery = trimToNull(query);
        if (effectiveManualRefId == null) {
            throw new ManualRetrievalException("invalid_request", "manualId is required for manual retrieval.");
        }
        if (effectiveQuery == null) {
            throw new ManualRetrievalException("query_required", "Query is required for manual retrieval.");
        }

        ResolvedManualContext resolvedContext = resolveManualContext(effectiveManualRefId);
        List<Fragment> fragments = fragmentService.getOrBuildFragments(resolvedContext.fragmentLookupId());
        if (fragments.isEmpty()) {
            throw new ManualRetrievalException(
                    "manual_context_missing",
                    "The selected manual does not contain indexed fragments yet.");
        }
        validateIndexedFragments(fragments);

        List<RetrievedFragment> selected = selectRelevantFragments(effectiveQuery, fragments, requestedTopK, timeoutSeconds);
        return new RetrievalResult(resolvedContext.manualContext(), selected, selected.isEmpty());
    }

    private ResolvedManualContext resolveManualContext(String manualRefId) {
        Optional<Manual> manual = manualRepository.findById(manualRefId);
        if (manual.isPresent()) {
            Manual item = manual.get();
            return new ResolvedManualContext(
                    new ManualContext(
                            item.getId(),
                            item.getId(),
                            "platform",
                            firstNonBlank(item.getProductName(), item.getFilename(), "平台说明书"),
                            trimToNull(item.getBrandName()),
                            trimToNull(item.getModelName()),
                            trimToNull(item.getCategoryCode())),
                    item.getId());
        }

        Optional<MyManual> myManual = myManualRepository.findById(manualRefId);
        if (myManual.isPresent()) {
            MyManual item = myManual.get();
            Manual linkedManual = null;
            if (StringUtils.hasText(item.getManualId())) {
                linkedManual = manualRepository.findById(item.getManualId().trim()).orElse(null);
            }
            String fragmentLookupId = linkedManual != null ? linkedManual.getId() : item.getId();
            return new ResolvedManualContext(
                    new ManualContext(
                            item.getId(),
                            trimToNull(item.getManualId()),
                            "mine",
                            firstNonBlank(
                                    item.getNickname(),
                                    item.getProductName(),
                                    linkedManual != null ? linkedManual.getProductName() : null,
                                    item.getUploadedFileName(),
                                    "我的说明书"),
                            firstNonBlank(item.getBrandName(), linkedManual != null ? linkedManual.getBrandName() : null),
                            firstNonBlank(item.getModelName(), linkedManual != null ? linkedManual.getModelName() : null),
                            firstNonBlank(item.getCategoryCode(), linkedManual != null ? linkedManual.getCategoryCode() : null)),
                    fragmentLookupId);
        }

        throw new ManualRetrievalException("invalid_request", "The selected manual was not found.");
    }

    private void validateIndexedFragments(List<Fragment> fragments) {
        if (!embeddingService.isEnabled()) {
            return;
        }

        String model = trimToNull(embeddingService.getEmbeddingModel());
        if (model == null) {
            return;
        }

        boolean indexed = fragments.stream().allMatch(fragment -> isIndexed(fragment, model));
        if (!indexed) {
            throw new ManualRetrievalException(
                    "manual_not_indexed",
                    "The selected manual fragments have not been embedded yet.");
        }
    }

    private List<RetrievedFragment> selectRelevantFragments(
            String query,
            List<Fragment> fragments,
            Integer requestedTopK,
            int timeoutSeconds) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }

        List<Double> queryEmbedding = embeddingService.embed(query, timeoutSeconds);
        boolean useEmbedding = queryEmbedding != null && !queryEmbedding.isEmpty();
        List<String> queryTerms = extractSearchTerms(query);
        List<FragmentDocument> documents = buildFragmentDocuments(fragments, queryTerms);
        double avgDocLength = documents.stream()
                .mapToInt(FragmentDocument::docLength)
                .average()
                .orElse(0);

        List<FragmentMatch> scored = new ArrayList<>();
        double maxKeywordScore = 0;
        double maxBm25Score = 0;

        for (FragmentDocument document : documents) {
            Fragment fragment = document.fragment();
            if (fragment == null) {
                continue;
            }
            double keywordScore = scoreFragment(query, fragment);
            double bm25Score = scoreBm25(queryTerms, document, documents.size(), avgDocLength);
            maxKeywordScore = Math.max(maxKeywordScore, keywordScore);
            maxBm25Score = Math.max(maxBm25Score, bm25Score);
            double vectorSimilarity = useEmbedding ? cosineSimilarity(queryEmbedding, fragment.getEmbedding()) : 0;
            scored.add(new FragmentMatch(fragment, keywordScore, bm25Score, vectorSimilarity, 0));
        }

        List<FragmentMatch> candidates = new ArrayList<>();
        for (FragmentMatch item : scored) {
            boolean keywordCandidate = item.keywordScore() >= minFragmentScore;
            boolean bm25Candidate = item.bm25Score() > 0;
            boolean vectorCandidate = useEmbedding && item.vectorSimilarity() >= minEmbeddingSimilarity;
            if (!keywordCandidate && !bm25Candidate && !vectorCandidate) {
                continue;
            }

            double normalizedKeyword = maxKeywordScore > 0 ? item.keywordScore() / maxKeywordScore : 0;
            double normalizedBm25 = maxBm25Score > 0 ? item.bm25Score() / maxBm25Score : 0;
            double lexicalScore = mixLexicalScore(normalizedKeyword, normalizedBm25);
            double hybridScore = useEmbedding
                    ? lexicalScore + Math.max(item.vectorSimilarity(), 0) * resolveNormalizedWeight(vectorWeight)
                    : lexicalScore;
            candidates.add(new FragmentMatch(
                    item.fragment(),
                    item.keywordScore(),
                    item.bm25Score(),
                    item.vectorSimilarity(),
                    hybridScore));
        }

        candidates.sort(Comparator
                .comparing(FragmentMatch::hybridScore, Comparator.reverseOrder())
                .thenComparing(FragmentMatch::vectorSimilarity, Comparator.reverseOrder())
                .thenComparing(FragmentMatch::bm25Score, Comparator.reverseOrder())
                .thenComparing(FragmentMatch::keywordScore, Comparator.reverseOrder())
                .thenComparing(item -> item.fragment().getPage(), Comparator.nullsLast(Integer::compareTo))
                .thenComparing(item -> item.fragment().getChunkIndex(), Comparator.nullsLast(Integer::compareTo)));

        int topK = requestedTopK != null
                ? Math.max(Math.min(requestedTopK, 8), 1)
                : Math.max(maxRetrievedFragments, 1);
        List<RetrievedFragment> selected = new ArrayList<>();
        for (FragmentMatch item : candidates) {
            selected.add(new RetrievedFragment(
                    item.fragment().getId(),
                    item.fragment().getManualId(),
                    item.fragment().getPage(),
                    item.fragment().getChunkIndex(),
                    item.fragment().getText(),
                    item.fragment().getSource(),
                    item.hybridScore(),
                    item.keywordScore(),
                    item.bm25Score(),
                    item.vectorSimilarity()));
            if (selected.size() >= topK) {
                break;
            }
        }
        return selected;
    }

    private List<FragmentDocument> buildFragmentDocuments(List<Fragment> fragments, List<String> queryTerms) {
        List<FragmentDocument> documents = new ArrayList<>();
        Map<String, Integer> docFreqByTerm = new HashMap<>();

        for (Fragment fragment : fragments) {
            if (fragment == null || !StringUtils.hasText(fragment.getText())) {
                continue;
            }

            List<String> tokens = tokenizeDocument(fragment.getText());
            if (tokens.isEmpty()) {
                continue;
            }

            Map<String, Integer> termFreq = new HashMap<>();
            for (String token : tokens) {
                termFreq.merge(token, 1, Integer::sum);
            }

            Set<String> seenTerms = new HashSet<>();
            for (String queryTerm : queryTerms) {
                if (termFreq.containsKey(queryTerm) && seenTerms.add(queryTerm)) {
                    docFreqByTerm.merge(queryTerm, 1, Integer::sum);
                }
            }

            documents.add(new FragmentDocument(fragment, termFreq, tokens.size(), new HashMap<>()));
        }

        List<FragmentDocument> resolved = new ArrayList<>(documents.size());
        for (FragmentDocument item : documents) {
            resolved.add(new FragmentDocument(
                    item.fragment(),
                    item.termFreq(),
                    item.docLength(),
                    docFreqByTerm));
        }
        return resolved;
    }

    private double scoreFragment(String query, Fragment fragment) {
        String text = normalizeForSearch(fragment.getText());
        String normalizedQuery = normalizeForSearch(query);
        if (text == null || normalizedQuery == null) {
            return 0;
        }

        double score = 0;
        if (text.contains(normalizedQuery)) {
            score += 120 + Math.min(normalizedQuery.length(), 30);
        }

        for (String term : extractSearchTerms(query)) {
            int occurrences = countOccurrences(text, term, 3);
            if (occurrences > 0) {
                score += occurrences * Math.min(term.length(), 6) * (isAscii(term) ? 4 : 8);
            }
        }

        return score;
    }

    private List<String> extractSearchTerms(String query) {
        List<String> tokens = tokenizeDocument(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        Set<String> terms = new LinkedHashSet<>();
        for (String token : tokens) {
            terms.add(token);
        }
        return List.copyOf(terms);
    }

    private List<String> tokenizeDocument(String value) {
        String normalized = normalizeForSearch(value);
        if (normalized == null) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        Matcher matcher = SEARCH_TERM_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.chars().allMatch(ch -> ch < 128)) {
                tokens.add(token);
                continue;
            }

            if (token.length() <= 4) {
                tokens.add(token);
                continue;
            }

            for (int size = 2; size <= 3; size++) {
                for (int i = 0; i <= token.length() - size; i++) {
                    tokens.add(token.substring(i, i + size));
                }
            }
        }
        return tokens;
    }

    private int countOccurrences(String text, String term, int maxCount) {
        int count = 0;
        int fromIndex = 0;
        while (count < maxCount) {
            int idx = text.indexOf(term, fromIndex);
            if (idx < 0) {
                break;
            }
            count++;
            fromIndex = idx + term.length();
        }
        return count;
    }

    private boolean isAscii(String value) {
        return value.chars().allMatch(ch -> ch < 128);
    }

    private double scoreBm25(
            List<String> queryTerms,
            FragmentDocument document,
            int totalDocs,
            double avgDocLength) {
        if (queryTerms == null || queryTerms.isEmpty() || document == null || document.docLength() <= 0 || totalDocs <= 0) {
            return 0;
        }

        double safeAvgDocLength = avgDocLength > 0 ? avgDocLength : document.docLength();
        double score = 0;
        for (String term : queryTerms) {
            Integer freq = document.termFreq().get(term);
            Integer docFreq = document.docFreqByTerm().get(term);
            if (freq == null || freq <= 0 || docFreq == null || docFreq <= 0) {
                continue;
            }

            double idf = Math.log(1 + (totalDocs - docFreq + 0.5) / (docFreq + 0.5));
            double tf = freq;
            double denominator = tf + BM25_K1 * (1 - BM25_B + BM25_B * document.docLength() / safeAvgDocLength);
            score += idf * ((tf * (BM25_K1 + 1)) / denominator);
        }
        return score;
    }

    private double mixLexicalScore(double normalizedKeyword, double normalizedBm25) {
        double keywordContribution = normalizedKeyword * resolveNormalizedWeight(keywordWeight);
        double bm25Contribution = normalizedBm25 * resolveNormalizedWeight(bm25Weight);
        return keywordContribution + bm25Contribution;
    }

    private double resolveNormalizedWeight(double primaryWeight) {
        double total = Math.max(keywordWeight, 0) + Math.max(bm25Weight, 0) + Math.max(vectorWeight, 0);
        if (total <= 0) {
            return 0;
        }
        return Math.max(primaryWeight, 0) / total;
    }

    private String normalizeForSearch(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^\\p{L}\\p{N}]+", " ");
        normalized = normalized.replaceAll("\\s{2,}", " ");
        return trimToNull(normalized);
    }

    private double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0;
        }

        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            double lv = left.get(i);
            double rv = right.get(i);
            dot += lv * rv;
            leftNorm += lv * lv;
            rightNorm += rv * rv;
        }
        if (leftNorm <= 0 || rightNorm <= 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private boolean isIndexed(Fragment fragment, String model) {
        if (fragment == null || !StringUtils.hasText(fragment.getText())) {
            return false;
        }
        if (fragment.getEmbedding() == null || fragment.getEmbedding().isEmpty()) {
            return false;
        }
        String existingModel = trimToNull(fragment.getEmbeddingModel());
        return existingModel != null && existingModel.equals(model);
    }

    @Data
    @AllArgsConstructor
    public static class RetrievalResult {
        private ManualContext manual;
        private List<RetrievedFragment> fragments;
        private boolean insufficientContext;
    }

    @Data
    @AllArgsConstructor
    public static class ManualContext {
        private String refId;
        private String manualId;
        private String manualType;
        private String manualName;
        private String brandName;
        private String modelName;
        private String categoryCode;
    }

    @Data
    @AllArgsConstructor
    public static class RetrievedFragment {
        private String fragmentId;
        private String manualId;
        private Integer page;
        private Integer chunkIndex;
        private String text;
        private String sourceType;
        private double score;
        private double keywordScore;
        private double bm25Score;
        private double vectorSimilarity;
    }

    public static class ManualRetrievalException extends RuntimeException {
        private final String errorCode;

        public ManualRetrievalException(String errorCode, String message) {
            super(message);
            this.errorCode = StringUtils.hasText(errorCode)
                    ? errorCode.trim().toLowerCase(Locale.ROOT)
                    : "manual_context_missing";
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    private record FragmentMatch(
            Fragment fragment,
            double keywordScore,
            double bm25Score,
            double vectorSimilarity,
            double hybridScore) {
    }

    private record FragmentDocument(
            Fragment fragment,
            Map<String, Integer> termFreq,
            int docLength,
            Map<String, Integer> docFreqByTerm) {
    }

    private record ResolvedManualContext(ManualContext manualContext, String fragmentLookupId) {
    }
}
