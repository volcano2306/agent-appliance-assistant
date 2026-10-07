package com.example.demo.service;

import com.example.demo.config.SearxngProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SearxngSearchService {

    private static final List<String> BLOCKED_HINTS = List.of(
            "github", "gitlab", "stack overflow", "stackoverflow", "bingx",
            "coin", "crypto", "token", "wallet", "stock", "stocks",
            "investor", "investment", "ipo", "career", "job", "salary");

    private static final List<String> OFFICIAL_HINTS = List.of(
            "official", "support", "service", "manual", "help", "customer",
            "after-sales", "official site", "官方", "售后", "客服", "服务网点",
            "支持", "说明书", "用户手册", "使用说明", "故障代码", "维修", "安装",
            "清洗", "清洁", "保养");

    private static final List<String> APPLIANCE_HINTS = List.of(
            "家电", "空调", "冰箱", "洗衣机", "热水器", "油烟机", "烟机", "灶",
            "燃气灶", "电视", "净水器", "洗碗机", "电饭煲", "微波炉", "烤箱",
            "吸尘器", "扫地机器人", "除湿机", "空气净化器", "遥控器");

    private static final List<String> OPERATION_HINTS = List.of(
            "说明书", "用户手册", "使用说明", "安装", "清洗", "清洁", "保养",
            "怎么", "如何", "单脱水", "脱水", "单洗", "单漂", "操作", "步骤",
            "设置", "模式", "按键", "功能", "童锁", "预约", "解锁");

    private static final List<String> TROUBLESHOOTING_HINTS = List.of(
            "故障", "故障码", "故障代码", "错误代码", "报错", "维修", "售后", "客服",
            "打不开", "不启动", "不制冷", "不加热", "不脱水", "漏水", "异响", "报警");

    private static final List<String> LATEST_HINTS = List.of(
            "最新", "今天", "今日", "最近", "近期", "新闻", "发布", "召回", "价格", "活动");

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final SearxngProperties searxngProperties;

    public List<SearchResult> search(SearchRequest request) {
        if (!StringUtils.hasText(searxngProperties.getBaseUrl())) {
            return List.of();
        }

        if (request == null || !StringUtils.hasText(request.getSearchQuery())) {
            return List.of();
        }

        for (SearchAttempt attempt : buildAttempts(request)) {
            List<SearchResult> results = executeSearch(request, attempt);
            if (!results.isEmpty()) {
                return results;
            }
        }
        return List.of();
    }

    private List<SearchResult> executeSearch(SearchRequest request, SearchAttempt attempt) {
        String url = buildUrl(attempt);

        try {
            String responseBody = createClient(searxngProperties.getTimeoutSeconds())
                    .get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL)
                    .exchange((clientRequest, clientResponse) -> {
                        String body = clientResponse.getBody() == null
                                ? ""
                                : StreamUtils.copyToString(clientResponse.getBody(), StandardCharsets.UTF_8);
                        if (clientResponse.getStatusCode().isError()) {
                            throw new IllegalStateException("SearXNG request failed: "
                                    + (StringUtils.hasText(body) ? body : clientResponse.getStatusCode().toString()));
                        }
                        return body;
                    });

            SearchResponse response = parseResponse(responseBody);
            return extractResults(response, request);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("SearXNG request failed: " + ex.getMessage(), ex);
        }
    }

    private RestClient createClient(int timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = Math.max(timeoutSeconds, 1) * 1000;
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        return restClientBuilder.requestFactory(requestFactory).build();
    }

    private SearchResponse parseResponse(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            throw new IllegalStateException("SearXNG response body was empty.");
        }
        try {
            return objectMapper.readValue(responseBody, SearchResponse.class);
        } catch (Exception ex) {
            throw new IllegalStateException("SearXNG returned invalid JSON.", ex);
        }
    }

    private String buildUrl(SearchAttempt attempt) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(trimTrailingSlash(searxngProperties.getBaseUrl()))
                .path(normalizePath(searxngProperties.getSearchPath()))
                .queryParam("q", attempt.query())
                .queryParam("format", "json");

        if (StringUtils.hasText(searxngProperties.getLanguage())) {
            builder.queryParam("language", searxngProperties.getLanguage().trim());
        }
        if (StringUtils.hasText(attempt.categories())) {
            builder.queryParam("categories", attempt.categories());
        }
        if (StringUtils.hasText(attempt.timeRange())) {
            builder.queryParam("time_range", attempt.timeRange());
        }
        return builder.toUriString();
    }

    private List<SearchAttempt> buildAttempts(SearchRequest request) {
        List<SearchAttempt> attempts = new ArrayList<>();
        String primaryCategories = resolveCategories(request.getIntent());
        String primaryTimeRange = resolveTimeRange(request.getIntent());

        addAttempt(attempts, request.getSearchQuery(), primaryCategories, primaryTimeRange);
        addAttempt(attempts, request.getRawQuery(), primaryCategories, primaryTimeRange);

        if (request.getIntent() == SearchIntent.LATEST_INFO) {
            addAttempt(attempts, request.getSearchQuery(), searxngProperties.getGeneralCategories(), primaryTimeRange);
            addAttempt(attempts, request.getRawQuery(), searxngProperties.getGeneralCategories(), primaryTimeRange);
            addAttempt(attempts, request.getSearchQuery(), searxngProperties.getGeneralCategories(), null);
            addAttempt(attempts, request.getRawQuery(), searxngProperties.getGeneralCategories(), null);
        } else if (request.isApplianceRelated()) {
            addAttempt(attempts, request.getSearchQuery(), searxngProperties.getGeneralCategories(), null);
        }

        return attempts;
    }

    private void addAttempt(List<SearchAttempt> attempts, String query, String categories, String timeRange) {
        if (!StringUtils.hasText(query)) {
            return;
        }
        SearchAttempt candidate = new SearchAttempt(query.trim(), trimToNull(categories), trimToNull(timeRange));
        if (!attempts.contains(candidate)) {
            attempts.add(candidate);
        }
    }

    private String resolveCategories(SearchIntent intent) {
        if (intent == SearchIntent.LATEST_INFO) {
            return trimToNull(searxngProperties.getLatestCategories());
        }
        return trimToNull(searxngProperties.getGeneralCategories());
    }

    private String resolveTimeRange(SearchIntent intent) {
        if (intent == SearchIntent.LATEST_INFO) {
            return trimToNull(searxngProperties.getLatestTimeRange());
        }
        return null;
    }

    private List<SearchResult> extractResults(SearchResponse response, SearchRequest request) {
        List<ScoredSearchResult> scoredResults = new ArrayList<>();
        if (response == null || response.getResults() == null) {
            return List.of();
        }

        int maxCandidates = Math.max(searxngProperties.getMaxCandidates(), 1);
        for (SearchResultItem item : response.getResults()) {
            if (item == null || !StringUtils.hasText(item.getTitle()) || !StringUtils.hasText(item.getUrl())) {
                continue;
            }

            SearchResult result = new SearchResult(
                    item.getTitle().trim(),
                    item.getUrl().trim(),
                    trimToEmpty(item.getContent()));

            int score = scoreResult(result, request);
            if (score > Integer.MIN_VALUE) {
                scoredResults.add(new ScoredSearchResult(result, score));
            }

            if (scoredResults.size() >= maxCandidates) {
                break;
            }
        }

        scoredResults.sort(Comparator.comparingInt(ScoredSearchResult::score).reversed());

        List<SearchResult> output = new ArrayList<>();
        int configuredMax = Math.max(searxngProperties.getMaxResults(), 1);
        int requestedMax = request.getRequestedMaxResults() != null
                ? Math.max(request.getRequestedMaxResults(), 1)
                : configuredMax;
        int maxResults = Math.min(configuredMax, requestedMax);
        for (ScoredSearchResult item : scoredResults) {
            output.add(item.result());
            if (output.size() >= maxResults) {
                break;
            }
        }
        return output;
    }

    private int scoreResult(SearchResult result, SearchRequest request) {
        String titleLower = lower(result.getTitle());
        String urlLower = lower(result.getUrl());
        String snippetLower = lower(result.getSnippet());
        String text = titleLower + "\n" + urlLower + "\n" + snippetLower;

        if (containsAny(text, BLOCKED_HINTS)) {
            return Integer.MIN_VALUE;
        }

        int score = 0;
        int priorityMatches = countMatches(text, request.getPriorityKeywords());
        int officialMatches = countMatches(text, OFFICIAL_HINTS);
        int operationMatches = countMatches(text, OPERATION_HINTS);
        int troubleshootingMatches = countMatches(text, TROUBLESHOOTING_HINTS);
        int applianceMatches = countMatches(text, APPLIANCE_HINTS);
        int latestMatches = countMatches(text, LATEST_HINTS);

        score += priorityMatches * 5;
        score += officialMatches * 4;
        score += operationMatches * 3;
        score += troubleshootingMatches * 3;
        score += applianceMatches * 2;

        if (request.getIntent() == SearchIntent.LATEST_INFO) {
            score += latestMatches * 2;
        }
        if (titleLower.contains("说明书") || titleLower.contains("用户手册") || titleLower.contains("使用说明")) {
            score += 4;
        }
        if (titleLower.contains("官方") || urlLower.contains("support") || urlLower.contains("service")) {
            score += 3;
        }

        if (request.getIntent() == SearchIntent.OPERATION_GUIDE) {
            score += operationMatches * 2;
            score -= troubleshootingMatches;
        }
        if (request.getIntent() == SearchIntent.TROUBLESHOOTING) {
            score += troubleshootingMatches * 2;
            score -= operationMatches;
        }

        boolean hasStrongSignal = priorityMatches > 0
                || officialMatches > 0
                || operationMatches > 0
                || troubleshootingMatches > 0
                || applianceMatches > 0;
        if (request.isApplianceRelated() && !hasStrongSignal) {
            return Integer.MIN_VALUE;
        }

        if (request.getIntent() == SearchIntent.LATEST_INFO && latestMatches == 0 && request.isApplianceRelated()) {
            score -= 2;
        }

        return score;
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String normalizePath(String value) {
        String path = StringUtils.hasText(value) ? value.trim() : "/search";
        return path.startsWith("/") ? path : "/" + path;
    }

    private String trimToEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String lower(String value) {
        return StringUtils.hasText(value) ? value.toLowerCase(Locale.ROOT) : "";
    }

    private boolean containsAny(String text, List<String> keywords) {
        return countMatches(text, keywords) > 0;
    }

    private int countMatches(String text, List<String> keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                count++;
            }
        }
        return count;
    }

    @Data
    @AllArgsConstructor
    public static class SearchResult {
        private String title;
        private String url;
        private String snippet;
    }

    @Data
    @AllArgsConstructor
    public static class SearchRequest {
        private String rawQuery;
        private String searchQuery;
        private SearchIntent intent;
        private boolean applianceRelated;
        private List<String> priorityKeywords;
        private Integer requestedMaxResults;
    }

    public enum SearchIntent {
        OPERATION_GUIDE,
        TROUBLESHOOTING,
        PRODUCT_INFO,
        LATEST_INFO,
        GENERIC_CHAT;

        public static SearchIntent fromValue(String value) {
            if (!StringUtils.hasText(value)) {
                return GENERIC_CHAT;
            }

            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "operation_guide", "operation", "guide" -> OPERATION_GUIDE;
                case "troubleshooting", "trouble", "repair" -> TROUBLESHOOTING;
                case "product_info", "product", "spec", "specs" -> PRODUCT_INFO;
                case "latest_info", "latest", "news" -> LATEST_INFO;
                default -> GENERIC_CHAT;
            };
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class SearchResponse {
        private List<SearchResultItem> results;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class SearchResultItem {
        private String title;
        private String url;
        private String content;
    }

    private record SearchAttempt(String query, String categories, String timeRange) {
    }

    private record ScoredSearchResult(SearchResult result, int score) {
    }
}
