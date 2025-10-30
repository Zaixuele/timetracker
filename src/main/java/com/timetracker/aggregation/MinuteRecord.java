package com.timetracker.aggregation;

import com.timetracker.app.ResolvedApplication;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Optional;

public record MinuteRecord(
        LocalDate date,
        LocalTime minute,
        MinuteStatus status,
        Optional<ResolvedApplication> application,
        int activeSeconds,
        int idleSeconds,
        Optional<String> windowTitleHash
) {

    public MinuteRecord {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(minute, "minute");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(windowTitleHash, "windowTitleHash");
    }
}
