package com.timetracker.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.timetracker.util.PathUtils;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record LoggingConfig(
        String level,
        String file,
        Integer maxSizeMB,
        Integer rotationCount
) {

    private static final Set<String> ALLOWED_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final String DEFAULT_LEVEL = "INFO";
    private static final int DEFAULT_MAX_SIZE_MB = 5;
    private static final int DEFAULT_ROTATION_COUNT = 5;

    @JsonCreator
    public LoggingConfig(
            @JsonProperty("level") String level,
            @JsonProperty("file") String file,
            @JsonProperty("maxSizeMB") Integer maxSizeMB,
            @JsonProperty("rotationCount") Integer rotationCount
    ) {
        this.level = level;
        this.file = file;
        this.maxSizeMB = maxSizeMB;
        this.rotationCount = rotationCount;
    }

    public LoggingConfig withDefaults(Path rootDir) {
        Objects.requireNonNull(rootDir, "rootDir");
        String resolvedLevel = normalizeLevel(level);
        Path defaultLog = rootDir.resolve("logs").resolve("app.log");
        String resolvedFile = PathUtils.resolveOrDefault(file, defaultLog).toString();
        int resolvedMaxSize = (maxSizeMB == null || maxSizeMB <= 0)
                ? DEFAULT_MAX_SIZE_MB
                : maxSizeMB;
        int resolvedRotation = (rotationCount == null || rotationCount <= 0)
                ? DEFAULT_ROTATION_COUNT
                : rotationCount;
        return new LoggingConfig(resolvedLevel, resolvedFile, resolvedMaxSize, resolvedRotation);
    }

    private String normalizeLevel(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return DEFAULT_LEVEL;
        }
        String upper = candidate.trim().toUpperCase(Locale.ROOT);
        if (ALLOWED_LEVELS.contains(upper)) {
            return upper;
        }
        return DEFAULT_LEVEL;
    }

    public static LoggingConfig defaults(Path rootDir) {
        return new LoggingConfig(DEFAULT_LEVEL,
                rootDir.resolve("logs").resolve("app.log").toString(),
                DEFAULT_MAX_SIZE_MB,
                DEFAULT_ROTATION_COUNT);
    }
}
