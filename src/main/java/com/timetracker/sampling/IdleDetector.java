package com.timetracker.sampling;

import java.time.Duration;

public interface IdleDetector {

    Duration timeSinceLastInput() throws SamplingException;
}
