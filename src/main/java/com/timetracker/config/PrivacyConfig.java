package com.timetracker.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.security.SecureRandom;
import java.util.Base64;

public record PrivacyConfig(
        Boolean recordWindowTitle,
        String titleHashSalt
) {

    private static final SecureRandom RANDOM = new SecureRandom();

    @JsonCreator
    public PrivacyConfig(
            @JsonProperty("recordWindowTitle") Boolean recordWindowTitle,
            @JsonProperty("titleHashSalt") String titleHashSalt
    ) {
        this.recordWindowTitle = recordWindowTitle;
        this.titleHashSalt = titleHashSalt;
    }

    public PrivacyConfig withDefaults() {
        boolean record = recordWindowTitle != null && recordWindowTitle;
        String salt = (titleHashSalt == null || titleHashSalt.isBlank())
                ? randomSalt()
                : titleHashSalt;
        return new PrivacyConfig(record, salt);
    }

    private static String randomSalt() {
        byte[] buffer = new byte[16];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    public static PrivacyConfig defaults() {
        return new PrivacyConfig(Boolean.FALSE, randomSalt());
    }
}
