package com.timetracker.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.timetracker.util.PathUtils;

import java.nio.file.Path;
import java.util.Objects;

public record SqliteStorageConfig(
        String databasePath,
        String journalMode
) {

    private static final String DEFAULT_DB_NAME = "timetracker.db";
    private static final String DEFAULT_JOURNAL_MODE = "WAL";

    @JsonCreator
    public SqliteStorageConfig(
            @JsonProperty("databasePath") String databasePath,
            @JsonProperty("journalMode") String journalMode
    ) {
        this.databasePath = databasePath;
        this.journalMode = journalMode;
    }

    public SqliteStorageConfig withDefaults(Path defaultDir) {
        Objects.requireNonNull(defaultDir, "defaultDir");
        Path resolvedDb = PathUtils.resolveOrDefault(databasePath, defaultDir.resolve(DEFAULT_DB_NAME));
        String resolvedPath = resolvedDb.toString();
        String resolvedJournalMode = (journalMode == null || journalMode.isBlank())
                ? DEFAULT_JOURNAL_MODE
                : journalMode;
        return new SqliteStorageConfig(resolvedPath, resolvedJournalMode);
    }

    public static SqliteStorageConfig defaults(Path defaultDir) {
        return new SqliteStorageConfig(defaultDir.resolve(DEFAULT_DB_NAME).toString(), DEFAULT_JOURNAL_MODE);
    }
}
