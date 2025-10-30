package com.timetracker.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PathUtilsTest {

    @Test
    void shouldExpandPercentEnvironmentVariables() {
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            return;
        }
        Path resolved = PathUtils.resolve("%APPDATA%/TimeTracker");
        assertTrue(resolved.toString().contains("TimeTracker"));
        assertTrue(resolved.startsWith(Path.of(appData).toAbsolutePath().normalize()));
    }
}
