package com.timetracker.app;

import java.util.Objects;

public record ResolvedApplication(
        String id,
        String displayName,
        String executablePath,
        String normalizedPath,
        boolean aliasApplied
) {

    public ResolvedApplication {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(executablePath, "executablePath");
        Objects.requireNonNull(normalizedPath, "normalizedPath");
    }
}
