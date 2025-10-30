package com.timetracker.aggregation;

import com.timetracker.app.ApplicationSample;
import com.timetracker.app.ResolvedApplication;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinuteAggregatorTest {

    @Test
    void shouldMarkMinuteAsIdleWhenIdleSecondsReachThreshold() {
        MinuteAggregator aggregator = new MinuteAggregator(15, 60, 1, ZoneOffset.UTC);
        Instant base = Instant.parse("2024-01-01T10:00:00Z");
        for (int i = 0; i < 60; i++) {
            int idleDuration = Math.min(i + 1, 60);
            aggregator.processSample(new SampleTick(base.plusSeconds(i), true, Optional.empty(), idleDuration));
        }
        Optional<MinuteRecord> result = aggregator.processSample(new SampleTick(base.plusSeconds(60), true, Optional.empty(), 60));
        assertTrue(result.isPresent(), "Expected a minute record after rollover");
        MinuteRecord record = result.orElseThrow();
        assertEquals(MinuteStatus.IDLE, record.status());
        assertEquals(60, record.idleSeconds());
    }

    @Test
    void shouldSelectSingleApplicationWithMostActiveSeconds() {
        MinuteAggregator aggregator = new MinuteAggregator(15, 60, 1, ZoneOffset.UTC);
        Instant base = Instant.parse("2024-01-01T11:00:00Z");

        ResolvedApplication alpha = new ResolvedApplication("alpha", "Alpha", "C:/alpha.exe", "c:/alpha.exe", false);
        ResolvedApplication bravo = new ResolvedApplication("bravo", "Bravo", "C:/bravo.exe", "c:/bravo.exe", false);

        ApplicationSample alphaSample = new ApplicationSample(alpha, Optional.empty());
        ApplicationSample bravoSample = new ApplicationSample(bravo, Optional.empty());

        Optional<MinuteRecord> result = Optional.empty();
        for (int i = 0; i < 60; i++) {
            ApplicationSample sample = i < 40 ? alphaSample : bravoSample;
            result = aggregator.processSample(new SampleTick(base.plusSeconds(i), false, Optional.of(sample), 0));
        }
        result = aggregator.processSample(new SampleTick(base.plusSeconds(60), false, Optional.of(alphaSample), 0));

        assertTrue(result.isPresent());
        MinuteRecord record = result.orElseThrow();
        assertEquals(MinuteStatus.ACTIVE, record.status());
        assertTrue(record.application().isPresent());
        assertEquals("Alpha", record.application().get().displayName());
        assertEquals(40, record.activeSeconds());
    }

    @Test
    void shouldDropPartialMinuteWhenBelowThresholdOnFlush() {
        MinuteAggregator aggregator = new MinuteAggregator(15, 60, 1, ZoneOffset.UTC);
        Instant base = Instant.parse("2024-01-01T12:00:00Z");
        ResolvedApplication app = new ResolvedApplication("test", "Test", "C:/test.exe", "c:/test.exe", false);
        ApplicationSample sample = new ApplicationSample(app, Optional.empty());

        for (int i = 0; i < 10; i++) {
            aggregator.processSample(new SampleTick(base.plusSeconds(i), false, Optional.of(sample), 0));
        }

        Optional<MinuteRecord> flushed = aggregator.flushPendingMinute();
        assertTrue(flushed.isEmpty());
    }

    @Test
    void shouldAggregateWithCustomSamplingInterval() {
        MinuteAggregator aggregator = new MinuteAggregator(15, 60, 5, ZoneOffset.UTC);
        Instant base = Instant.parse("2024-01-01T13:00:00Z");
        ResolvedApplication app = new ResolvedApplication("app", "App", "C:/app.exe", "c:/app.exe", false);
        ApplicationSample sample = new ApplicationSample(app, Optional.empty());

        Optional<MinuteRecord> result = Optional.empty();
        for (int i = 0; i < 12; i++) {
            result = aggregator.processSample(new SampleTick(base.plusSeconds(i * 5L), false, Optional.of(sample), 0));
        }
        result = aggregator.processSample(new SampleTick(base.plusSeconds(60), false, Optional.of(sample), 0));

        assertTrue(result.isPresent());
        MinuteRecord record = result.orElseThrow();
        assertEquals(MinuteStatus.ACTIVE, record.status());
        assertEquals(60, record.activeSeconds());
    }
}
