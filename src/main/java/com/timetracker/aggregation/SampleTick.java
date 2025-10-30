package com.timetracker.aggregation;

import com.timetracker.app.ApplicationSample;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record SampleTick(
        Instant timestamp,
        boolean idle,
        Optional<ApplicationSample> application,
        int idleDurationSeconds
) {

    public SampleTick {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(application, "application");
        if (idleDurationSeconds < 0) {
            throw new IllegalArgumentException("idleDurationSeconds must be >= 0");
        }
    }
}
