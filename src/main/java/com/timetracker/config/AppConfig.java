package com.timetracker.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record AppConfig(
        int samplingIntervalSeconds,
        int minActiveInMinuteSeconds,
        int minIdleSeconds,
        StorageConfig storage,
        ReportConfig report,
        LoggingConfig logging,
        List<AliasRule> aliases,
        List<String> whitelist,
        List<String> blacklist,
        PrivacyConfig privacy
        ) {

    private static final int DEFAULT_SAMPLING_INTERVAL_SECONDS = 1;
    private static final int DEFAULT_MIN_ACTIVE_SECONDS = 15;
    private static final int DEFAULT_MIN_IDLE_SECONDS = 60;
    private static final int DEFAULT_TOP_N = 10;

    @JsonCreator
    public static AppConfig create(
            @JsonProperty("samplingIntervalSeconds") Integer samplingIntervalSeconds,
            @JsonProperty("minActiveInMinuteSeconds") Integer minActiveInMinuteSeconds,
            @JsonProperty("minIdleSeconds") Integer minIdleSeconds,
            @JsonProperty("storage") StorageConfig storage,
            @JsonProperty("report") ReportConfig report,
            @JsonProperty("logging") LoggingConfig logging,
            @JsonProperty("aliases") List<AliasRule> aliases,
            @JsonProperty("whitelist") List<String> whitelist,
            @JsonProperty("blacklist") List<String> blacklist,
            @JsonProperty("privacy") PrivacyConfig privacy
    ) {
        int sampling = samplingIntervalSeconds == null
                ? DEFAULT_SAMPLING_INTERVAL_SECONDS
                : samplingIntervalSeconds;
        int minActive = minActiveInMinuteSeconds == null
                ? DEFAULT_MIN_ACTIVE_SECONDS
                : minActiveInMinuteSeconds;
        int minIdle = minIdleSeconds == null
                ? DEFAULT_MIN_IDLE_SECONDS
                : minIdleSeconds;

        Path root = defaultRoot();
        StorageConfig resolvedStorage = storage == null
                ? StorageConfig.defaults(root)
                : storage.withDefaults(root);
        ReportConfig resolvedReport = report == null
                ? ReportConfig.defaults(root, DEFAULT_TOP_N)
                : report.withDefaults(root, DEFAULT_TOP_N);
        LoggingConfig resolvedLogging = logging == null
                ? LoggingConfig.defaults(root)
                : logging.withDefaults(root);
        List<AliasRule> resolvedAliases = aliases == null ? List.of() : List.copyOf(aliases);
        List<String> resolvedWhitelist = whitelist == null ? List.of() : normalizeList(whitelist);
        List<String> resolvedBlacklist = blacklist == null ? List.of() : normalizeList(blacklist);
        PrivacyConfig resolvedPrivacy = privacy == null ? PrivacyConfig.defaults() : privacy.withDefaults();

        return new AppConfig(
                sampling,
                minActive,
                minIdle,
                resolvedStorage,
                resolvedReport,
                resolvedLogging,
                resolvedAliases,
                resolvedWhitelist,
                resolvedBlacklist,
                resolvedPrivacy
        );
    }

    private static List<String> normalizeList(List<String> entries) {
        return entries.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static Path defaultRoot() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return Paths.get(System.getProperty("user.home"), "TimeTracker");
        }
        return Paths.get(appData, "TimeTracker");
    }

    public static AppConfig defaults() {
        return new AppConfig(
                DEFAULT_SAMPLING_INTERVAL_SECONDS,
                DEFAULT_MIN_ACTIVE_SECONDS,
                DEFAULT_MIN_IDLE_SECONDS,
                StorageConfig.defaults(defaultRoot()),
                ReportConfig.defaults(defaultRoot(), DEFAULT_TOP_N),
                LoggingConfig.defaults(defaultRoot()),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                PrivacyConfig.defaults()
        );
    }
}
