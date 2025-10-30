package com.timetracker.sampling;

import java.time.Instant;
import java.util.Optional;

public record ForegroundSample(
        Instant timestamp,
        Optional<AppIdentity> app,
        Optional<String> windowTitle
) {

    public ForegroundSample {
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp cannot be null");
        }
        if (app == null) {
            throw new IllegalArgumentException("app optional cannot be null");
        }
        if (windowTitle == null) {
            throw new IllegalArgumentException("windowTitle optional cannot be null");
        }
    }
}
