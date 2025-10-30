package com.timetracker.app;

import com.timetracker.config.AliasRule;
import com.timetracker.config.PrivacyConfig;
import com.timetracker.sampling.AppIdentity;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class AppResolver {

    private final List<AliasRule> aliasRules;
    private final Set<String> whitelist;
    private final Set<String> blacklist;
    private final boolean recordWindowTitle;
    private final String titleHashSalt;

    private static final HexFormat HEX = HexFormat.of();

    public AppResolver(List<AliasRule> aliasRules,
                       List<String> whitelist,
                       List<String> blacklist,
                       PrivacyConfig privacyConfig) {
        this.aliasRules = new CopyOnWriteArrayList<>(aliasRules == null ? List.of() : aliasRules);
        this.whitelist = Set.copyOf(normalizeList(whitelist));
        this.blacklist = Set.copyOf(normalizeList(blacklist));
        this.recordWindowTitle = privacyConfig != null && Boolean.TRUE.equals(privacyConfig.recordWindowTitle());
        this.titleHashSalt = (privacyConfig == null || privacyConfig.titleHashSalt() == null)
                ? ""
                : privacyConfig.titleHashSalt();
    }

    public Optional<ApplicationSample> resolve(AppIdentity identity, Optional<String> windowTitle) {
        Objects.requireNonNull(identity, "identity");

        String normalizedPath = normalizePath(identity.executablePath());
        String normalizedLower = normalizedPath.toLowerCase(Locale.ROOT);

        if (!whitelist.isEmpty() && !whitelist.contains(normalizedLower)) {
            return Optional.empty();
        }
        if (blacklist.contains(normalizedLower)) {
            return Optional.empty();
        }

        AliasRule matchedAlias = findAlias(normalizedLower);
        boolean aliasApplied = matchedAlias != null;
        String displayName = aliasApplied ? matchedAlias.name() : identity.displayName();
        if (StringUtils.isBlank(displayName)) {
            displayName = deriveDisplayName(normalizedPath);
        }

        String id = aliasApplied
                ? matchedAlias.name().toLowerCase(Locale.ROOT)
                : normalizedLower;

        ResolvedApplication application = new ResolvedApplication(
                id,
                displayName,
                normalizedPath,
                normalizedLower,
                aliasApplied
        );

        Optional<String> windowTitleHash = recordWindowTitle
                ? hashWindowTitle(windowTitle)
                : Optional.empty();

        return Optional.of(new ApplicationSample(application, windowTitleHash));
    }

    public void updateAliases(List<AliasRule> aliases) {
        aliasRules.clear();
        aliasRules.addAll(aliases == null ? List.of() : aliases);
    }

    private AliasRule findAlias(String normalizedLower) {
        for (AliasRule rule : aliasRules) {
            if (rule.isValid() && rule.matches(normalizedLower)) {
                return rule;
            }
        }
        return null;
    }

    private Optional<String> hashWindowTitle(Optional<String> windowTitle) {
        return windowTitle
                .filter(StringUtils::isNotBlank)
                .map(title -> {
                    try {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        digest.update(titleHashSalt.getBytes(StandardCharsets.UTF_8));
                        digest.update(title.strip().getBytes(StandardCharsets.UTF_8));
                        return HEX.formatHex(digest.digest());
                    } catch (NoSuchAlgorithmException e) {
                        throw new IllegalStateException("SHA-256 not available", e);
                    }
                });
    }

    private List<String> normalizeList(List<String> entries) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String normalizePath(String rawPath) {
        if (StringUtils.isBlank(rawPath)) {
            return "";
        }
        try {
            return Path.of(rawPath).toAbsolutePath().normalize().toString();
        } catch (Exception ex) {
            return rawPath;
        }
    }

    private String deriveDisplayName(String normalizedPath) {
        Path path = Path.of(normalizedPath);
        Path fileName = path.getFileName();
        if (fileName == null) {
            return normalizedPath;
        }
        String baseName = fileName.toString();
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }
        return StringUtils.capitalize(baseName.toLowerCase(Locale.ROOT));
    }
}
