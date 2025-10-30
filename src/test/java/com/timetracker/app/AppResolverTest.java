package com.timetracker.app;

import com.timetracker.config.AliasRule;
import com.timetracker.config.PrivacyConfig;
import com.timetracker.sampling.AppIdentity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppResolverTest {

    @Test
    void shouldApplyAliasWhenRuleMatches() {
        AliasRule alias = new AliasRule("chrome.exe", "Chrome");
        AppResolver resolver = new AppResolver(List.of(alias), List.of(), List.of(), new PrivacyConfig(false, ""));
        AppIdentity identity = new AppIdentity("C:/Apps/Chrome/chrome.exe", "chrome", 123);

        Optional<ApplicationSample> resolved = resolver.resolve(identity, Optional.empty());
        assertTrue(resolved.isPresent());
        ResolvedApplication app = resolved.orElseThrow().application();
        assertEquals("Chrome", app.displayName());
        assertTrue(app.aliasApplied());
        assertEquals("chrome", app.id());
    }

    @Test
    void shouldRespectWhitelistWhenProvided() {
        AppResolver resolver = new AppResolver(List.of(), List.of(normalizedPath("C:/Allowed/App.exe")), List.of(), new PrivacyConfig(false, ""));
        AppIdentity blocked = new AppIdentity("C:/Other/app.exe", "App", 1);
        Optional<ApplicationSample> blockedResult = resolver.resolve(blocked, Optional.empty());
        assertTrue(blockedResult.isEmpty());

        AppIdentity allowed = new AppIdentity("C:/Allowed/App.exe", "App", 2);
        Optional<ApplicationSample> allowedResult = resolver.resolve(allowed, Optional.empty());
        assertTrue(allowedResult.isPresent());
    }

    @Test
    void shouldHashWindowTitleWhenEnabled() throws Exception {
        PrivacyConfig privacy = new PrivacyConfig(true, "testsalt");
        AppResolver resolver = new AppResolver(List.of(), List.of(), List.of(), privacy);
        AppIdentity identity = new AppIdentity("C:/Apps/Focus/focus.exe", "Focus", 77);

        Optional<ApplicationSample> sample = resolver.resolve(identity, Optional.of("My Focus Window"));
        assertTrue(sample.isPresent());
        Optional<String> hash = sample.orElseThrow().windowTitleHash();
        assertTrue(hash.isPresent());

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("testsalt".getBytes());
        digest.update("My Focus Window".getBytes());
        String expected = HexFormat.of().formatHex(digest.digest());
        assertEquals(expected, hash.orElseThrow());
    }

    @Test
    void shouldRejectWhenBlacklisted() {
        AppResolver resolver = new AppResolver(List.of(), List.of(), List.of(normalizedPath("C:/Blocked/App.exe")), new PrivacyConfig(false, ""));
        AppIdentity identity = new AppIdentity("C:/Blocked/App.exe", "Blocked", 10);
        Optional<ApplicationSample> resolved = resolver.resolve(identity, Optional.empty());
        assertTrue(resolved.isEmpty());
    }

    private String normalizedPath(String raw) {
        return Path.of(raw).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }
}
