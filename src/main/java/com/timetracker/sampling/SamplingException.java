package com.timetracker.sampling;

public class SamplingException extends Exception {

    public SamplingException(String message) {
        super(message);
    }

    public SamplingException(String message, Throwable cause) {
        super(message, cause);
    }
}
