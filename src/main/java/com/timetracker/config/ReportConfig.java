package com.timetracker.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.timetracker.util.PathUtils;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

public record ReportConfig(
        String rootDir,
        String generateTime,
        Integer topN,
        Boolean includeIdleInBar
) {

    private static final String DEFAULT_GENERATE_TIME = "23:59";

    @JsonCreator
    public ReportConfig(
            @JsonProperty("rootDir") String rootDir,
            @JsonProperty("generateTime") String generateTime,
            @JsonProperty("topN") Integer topN,
            @JsonProperty("includeIdleInBar") Boolean includeIdleInBar
    ) {
        this.rootDir = rootDir;
        this.generateTime = generateTime;
        this.topN = topN;
        this.includeIdleInBar = includeIdleInBar;
    }

    public ReportConfig withDefaults(Path rootDir, int defaultTopN) {
        Objects.requireNonNull(rootDir, "rootDir");
        Path resolvedRootPath = PathUtils.resolveOrDefault(this.rootDir, rootDir.resolve("report"));
        String resolvedRoot = resolvedRootPath.toString();
        String resolvedGenerateTime = isValidTime(generateTime)
                ? generateTime
                : DEFAULT_GENERATE_TIME;
        int resolvedTopN = (topN == null || topN <= 0)
                ? defaultTopN
                : topN;
        boolean resolvedIncludeIdle = includeIdleInBar != null && includeIdleInBar;
        return new ReportConfig(resolvedRoot, resolvedGenerateTime, resolvedTopN, resolvedIncludeIdle);
    }

    private boolean isValidTime(String time) {
        if (time == null || time.isBlank()) {
            return false;
        }
        try {
            LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"));
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    public static ReportConfig defaults(Path rootDir, int defaultTopN) {
        return new ReportConfig(
                rootDir.resolve("report").toString(),
                DEFAULT_GENERATE_TIME,
                defaultTopN,
                Boolean.FALSE
        );
    }
}
