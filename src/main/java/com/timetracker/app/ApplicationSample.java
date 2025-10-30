package com.timetracker.app;

import java.util.Objects;
import java.util.Optional;

public record ApplicationSample(
        ResolvedApplication application,
        Optional<String> windowTitleHash
) {

    public ApplicationSample {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(windowTitleHash, "windowTitleHash");
    }
}
