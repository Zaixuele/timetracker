package com.timetracker.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.util.Objects;

public record StorageConfig(
        StorageType type,
        CsvStorageConfig csv,
        SqliteStorageConfig sqlite
) {

    private static final StorageType DEFAULT_TYPE = StorageType.CSV;

    @JsonCreator
    public StorageConfig(
            @JsonProperty("type") StorageType type,
            @JsonProperty("csv") CsvStorageConfig csv,
            @JsonProperty("sqlite") SqliteStorageConfig sqlite
    ) {
        this.type = type == null ? DEFAULT_TYPE : type;
        this.csv = csv;
        this.sqlite = sqlite;
    }

    public StorageConfig withDefaults(Path rootDir) {
        Objects.requireNonNull(rootDir, "rootDir");
        CsvStorageConfig csvConfig = (csv == null)
                ? CsvStorageConfig.defaults(rootDir.resolve("data"))
                : csv.withDefaults(rootDir.resolve("data"));
        SqliteStorageConfig sqliteConfig = (sqlite == null)
                ? SqliteStorageConfig.defaults(rootDir.resolve("data"))
                : sqlite.withDefaults(rootDir.resolve("data"));
        return new StorageConfig(type, csvConfig, sqliteConfig);
    }

    public static StorageConfig defaults(Path rootDir) {
        return new StorageConfig(DEFAULT_TYPE,
                CsvStorageConfig.defaults(rootDir.resolve("data")),
                SqliteStorageConfig.defaults(rootDir.resolve("data")));
    }
}
