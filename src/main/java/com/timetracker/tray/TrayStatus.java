package com.timetracker.tray;

import java.util.Objects;
import java.util.Optional;

public record TrayStatus(
        String currentAppDisplay,
        Optional<AppUsageSummary> topApplication,
        boolean trackingActive
) {

    public TrayStatus {
        Objects.requireNonNull(currentAppDisplay, "currentAppDisplay");
        Objects.requireNonNull(topApplication, "topApplication");
    }

    public record AppUsageSummary(String displayName, int minutes) {
        public AppUsageSummary {
            Objects.requireNonNull(displayName, "displayName");
        }
    }
}
