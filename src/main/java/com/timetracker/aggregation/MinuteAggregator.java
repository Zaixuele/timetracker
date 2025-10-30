package com.timetracker.aggregation;

import com.timetracker.app.ApplicationSample;
import com.timetracker.app.ResolvedApplication;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class MinuteAggregator {

    private final int minActiveSeconds;
    private final int minIdleSeconds;
    private final int samplingIntervalSeconds;
    private final ZoneId zoneId;

    private LocalDateTime currentMinuteStart;
    private final Map<String, AppAccumulation> appSeconds = new HashMap<>();
    private int idleSeconds;
    private boolean idleThresholdReached;

    public MinuteAggregator(int minActiveSeconds, int minIdleSeconds) {
        this(minActiveSeconds, minIdleSeconds, 1, ZoneId.systemDefault());
    }

    public MinuteAggregator(int minActiveSeconds, int minIdleSeconds, int samplingIntervalSeconds) {
        this(minActiveSeconds, minIdleSeconds, samplingIntervalSeconds, ZoneId.systemDefault());
    }

    public MinuteAggregator(int minActiveSeconds, int minIdleSeconds, int samplingIntervalSeconds, ZoneId zoneId) {
        if (minActiveSeconds <= 0) {
            throw new IllegalArgumentException("minActiveSeconds must be > 0");
        }
        if (minIdleSeconds <= 0) {
            throw new IllegalArgumentException("minIdleSeconds must be > 0");
        }
        if (samplingIntervalSeconds <= 0) {
            throw new IllegalArgumentException("samplingIntervalSeconds must be > 0");
        }
        this.minActiveSeconds = minActiveSeconds;
        this.minIdleSeconds = minIdleSeconds;
        this.samplingIntervalSeconds = samplingIntervalSeconds;
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }

    /**
     * Process a single second sample and optionally emit a completed minute record.
     */
    public Optional<MinuteRecord> processSample(SampleTick tick) {
        Objects.requireNonNull(tick, "tick");
        LocalDateTime minute = toMinuteStart(tick.timestamp());

        Optional<MinuteRecord> flushed = Optional.empty();

        if (currentMinuteStart == null) {
            startNewMinute(minute);
        } else if (minute.isAfter(currentMinuteStart)) {
            flushed = finalizeCurrentMinute();
            startNewMinute(minute);
        } else if (minute.isBefore(currentMinuteStart)) {
            // Time went backwards (clock adjustment) - restart aggregation.
            startNewMinute(minute);
        }

        accumulate(tick);
        return flushed;
    }

    /**
     * Flush the current minute immediately, typically on shutdown or day rollover.
     */
    public Optional<MinuteRecord> flushPendingMinute() {
        if (currentMinuteStart == null) {
            return Optional.empty();
        }
        Optional<MinuteRecord> record = finalizeCurrentMinute();
        clearState();
        return record;
    }

    private void accumulate(SampleTick tick) {
        if (tick.idle()) {
            int rawDuration = Math.max(0, tick.idleDurationSeconds());
            int cappedDuration = Math.min(rawDuration, 60);
            idleSeconds = Math.max(idleSeconds, cappedDuration);
            if (rawDuration >= minIdleSeconds) {
                idleThresholdReached = true;
            }
            return;
        }

        int currentIdle = Math.max(0, Math.min(tick.idleDurationSeconds(), 60));
        idleSeconds = currentIdle;

        tick.application().ifPresent(sample -> {
            AppAccumulation accumulation = appSeconds.computeIfAbsent(sample.application().id(),
                    id -> new AppAccumulation(sample.application()));
            accumulation.increment(samplingIntervalSeconds);
            sample.windowTitleHash()
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(accumulation::setWindowTitleHash);
        });
    }

    private Optional<MinuteRecord> finalizeCurrentMinute() {
        if (currentMinuteStart == null) {
            return Optional.empty();
        }
        LocalDateTime minuteStart = currentMinuteStart;

        MinuteRecord record = null;
        if (idleThresholdReached) {
            int reportedIdleSeconds = Math.min(Math.max(idleSeconds, minIdleSeconds), 60);
            record = new MinuteRecord(
                    minuteStart.toLocalDate(),
                    minuteStart.toLocalTime(),
                    MinuteStatus.IDLE,
                    Optional.empty(),
                    0,
                    reportedIdleSeconds,
                    Optional.empty()
            );
        } else {
            AppAccumulation winner = selectWinner();
            if (winner != null && winner.activeSeconds >= minActiveSeconds) {
                int reportedActiveSeconds = Math.min(winner.activeSeconds, 60);
                int reportedIdleSeconds = Math.min(idleSeconds, 60);
                record = new MinuteRecord(
                        minuteStart.toLocalDate(),
                        minuteStart.toLocalTime(),
                        MinuteStatus.ACTIVE,
                        Optional.of(winner.application),
                        reportedActiveSeconds,
                        reportedIdleSeconds,
                        Optional.ofNullable(winner.windowTitleHash).filter(StringUtils::isNotBlank)
                );
            }
        }

        appSeconds.clear();
        idleSeconds = 0;
        idleThresholdReached = false;
        currentMinuteStart = null;

        return Optional.ofNullable(record);
    }

    private void startNewMinute(LocalDateTime minuteStart) {
        appSeconds.clear();
        idleSeconds = 0;
        idleThresholdReached = false;
        currentMinuteStart = minuteStart;
    }

    private void clearState() {
        appSeconds.clear();
        idleSeconds = 0;
        idleThresholdReached = false;
        currentMinuteStart = null;
    }

    private AppAccumulation selectWinner() {
        return appSeconds.values().stream()
                .max(Comparator
                        .comparingInt(AppAccumulation::activeSeconds)
                        .thenComparing(acc -> acc.application.displayName()))
                .orElse(null);
    }

    private LocalDateTime toMinuteStart(Instant timestamp) {
        return LocalDateTime.ofInstant(timestamp, zoneId)
                .truncatedTo(ChronoUnit.MINUTES);
    }

    private static final class AppAccumulation {
        private final ResolvedApplication application;
        private int activeSeconds;
        private String windowTitleHash;

        private AppAccumulation(ResolvedApplication application) {
            this.application = application;
        }

        private void increment(int samplingIntervalSeconds) {
            activeSeconds += samplingIntervalSeconds;
        }

        private void setWindowTitleHash(String hash) {
            this.windowTitleHash = hash;
        }

        private int activeSeconds() {
            return activeSeconds;
        }
    }
}
