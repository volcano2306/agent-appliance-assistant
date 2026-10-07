package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "searxng")
public class SearxngProperties {

    private String baseUrl = "";
    private String searchPath = "/search";
    private String language = "zh-CN";
    private String generalCategories = "general";
    private String latestCategories = "general,news";
    private String latestTimeRange = "month";
    private int timeoutSeconds = 10;
    private int maxCandidates = 12;
    private int maxResults = 5;
}
