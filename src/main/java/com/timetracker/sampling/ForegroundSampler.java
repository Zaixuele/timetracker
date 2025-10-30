package com.timetracker.sampling;

public interface ForegroundSampler extends AutoCloseable {

    ForegroundSample sample() throws SamplingException;

    @Override
    default void close() {
        // default noop
    }
}
