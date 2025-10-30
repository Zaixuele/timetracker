package com.timetracker.tray;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public record TrayActions(
        Runnable onToggleTracking,
        Runnable onExit,
        BooleanSupplier isTrackingActive
) {
    public TrayActions {
        Objects.requireNonNull(onToggleTracking, "onToggleTracking");
        Objects.requireNonNull(onExit, "onExit");
        Objects.requireNonNull(isTrackingActive, "isTrackingActive");
    }
}
