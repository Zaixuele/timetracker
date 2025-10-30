package com.timetracker.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.timetracker.util.PathUtils;

import java.nio.file.Path;
import java.util.Objects;

public record CsvStorageConfig(
        String rootDir,
        Integer flushIntervalMinutes,
        Integer maxBatchSize
) {

    private static final int DEFAULT_FLUSH_MINUTES = 1;
    private static final int DEFAULT_MAX_BATCH = 100;

    @JsonCreator
    public CsvStorageConfig(
            @JsonProperty("rootDir") String rootDir,
            @JsonProperty("flushIntervalMinutes") Integer flushIntervalMinutes,
            @JsonProperty("maxBatchSize") Integer maxBatchSize
    ) {
        this.rootDir = rootDir;
        this.flushIntervalMinutes = flushIntervalMinutes;
        this.maxBatchSize = maxBatchSize;
    }

    public CsvStorageConfig withDefaults(Path defaultDir) {
        Objects.requireNonNull(defaultDir, "defaultDir");
        Path resolvedPath = PathUtils.resolveOrDefault(rootDir, defaultDir);
        String resolvedRoot = resolvedPath.toString();
        int flushMinutes = flushIntervalMinutes == null || flushIntervalMinutes <= 0
                ? DEFAULT_FLUSH_MINUTES
                : flushIntervalMinutes;
        int batchSize = maxBatchSize == null || maxBatchSize <= 0
                ? DEFAULT_MAX_BATCH
                : maxBatchSize;
        return new CsvStorageConfig(resolvedRoot, flushMinutes, batchSize);
    }

    public static CsvStorageConfig defaults(Path defaultDir) {
        return new CsvStorageConfig(defaultDir.toString(), DEFAULT_FLUSH_MINUTES, DEFAULT_MAX_BATCH);
    }
}
