package com.timetracker.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PathUtils {

    private static final Pattern PERCENT_ENV_PATTERN = Pattern.compile("%([A-Za-z0-9_]+)%");
    private static final Pattern BRACE_ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private PathUtils() {
    }

    public static Path resolve(String path) {
        Objects.requireNonNull(path, "path");
        String expanded = expand(path);
        return Paths.get(expanded).toAbsolutePath().normalize();
    }

    public static Path resolveOrDefault(String candidate, Path defaultPath) {
        Objects.requireNonNull(defaultPath, "defaultPath");
        if (isBlank(candidate)) {
            return defaultPath.toAbsolutePath().normalize();
        }
        return resolve(candidate);
    }

    public static String expand(String path) {
        Objects.requireNonNull(path, "path");
        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        String resolved = replaceEnv(trimmed, PERCENT_ENV_PATTERN);
        resolved = replaceEnv(resolved, BRACE_ENV_PATTERN);
        if (resolved.startsWith("~")) {
            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                resolved = home + resolved.substring(1);
            }
        }
        return resolved;
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String replaceEnv(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = lookupEnv(key);
            if (value == null) {
                value = matcher.group(0);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String lookupEnv(String key) {
        Map<String, String> env = System.getenv();
        String direct = env.get(key);
        if (direct != null) {
            return direct;
        }
        String upper = env.get(key.toUpperCase(Locale.ROOT));
        if (upper != null) {
            return upper;
        }
        return env.get(key.toLowerCase(Locale.ROOT));
    }
}
