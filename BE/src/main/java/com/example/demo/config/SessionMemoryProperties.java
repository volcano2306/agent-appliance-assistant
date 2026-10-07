package com.example.demo.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Component
@ConfigurationProperties(prefix = "ai.memory")
public class SessionMemoryProperties {
    private boolean enabled = false;
    private boolean executionEnabled = false;
    public boolean isRuntimeEnabled() { return enabled || executionEnabled; }
    private int recentTurns = 5;
    @NotBlank private String compressionStrategy = "threshold";
    @Min(256) private int triggerTokens = 1500;
    @Min(1) private int minGrowthTokens = 500;
    @AssertTrue(message = "memory strategy must be threshold or rolling, and growth below trigger")
    public boolean isCompressionValid() {
        return ("threshold".equals(compressionStrategy) || "rolling".equals(compressionStrategy))
                && minGrowthTokens < triggerTokens;
    }
    private int summaryMaxTokens = 600;
    @Min(256) private int summaryInputBudget = 8000;
    @Min(256) private int mainInputBudget = 24000;
    @Min(1) private int answerReserve = 2000;
    @Min(0) private int safetyMargin = 1024;
    private Integer modelContextWindow;
    private int summaryRetries = 1;
    @Min(1) private int summaryTimeoutSeconds = 30;
    @NotBlank private String policyVersion = "session-memory-v1";

    @AssertTrue(message = "memory window must be 3 or 5")
    public boolean isWindowValid() { return recentTurns == 3 || recentTurns == 5; }
    @AssertTrue(message = "summary limit must be 300, 600 or 1000")
    public boolean isSummaryLimitValid() {
        return summaryMaxTokens == 300 || summaryMaxTokens == 600 || summaryMaxTokens == 1000;
    }
    @AssertTrue(message = "summary retry must be 0 or 1")
    public boolean isRetryValid() { return summaryRetries == 0 || summaryRetries == 1; }
    @AssertTrue(message = "confirm model context window and keep all budgets within it")
    public boolean isBudgetValid() {
        if (modelContextWindow == null) return !isRuntimeEnabled();
        long needed = Math.max((long) mainInputBudget + answerReserve,
                (long) summaryInputBudget + summaryMaxTokens) + safetyMargin;
        return modelContextWindow >= needed;
    }
}
