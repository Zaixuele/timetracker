package com.timetracker.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Locale;
import java.util.Objects;

public record AliasRule(
        String match,
        String name
) {

    @JsonCreator
    public AliasRule(
            @JsonProperty("match") String match,
            @JsonProperty("name") String name
    ) {
        this.match = normalize(match);
        this.name = name == null ? "" : name.trim();
    }

    private static String normalize(String pattern) {
        if (pattern == null) {
            return "";
        }
        String trimmed = pattern.trim();
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public boolean isValid() {
        return !match.isBlank() && !name.isBlank();
    }

    public boolean matches(String exePathLowerCase) {
        Objects.requireNonNull(exePathLowerCase, "exePathLowerCase");
        return !match.isBlank() && exePathLowerCase.contains(match);
    }
}
