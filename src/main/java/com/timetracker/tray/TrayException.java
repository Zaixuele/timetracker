package com.timetracker.tray;

public class TrayException extends Exception {
    public TrayException(String message) {
        super(message);
    }

    public TrayException(String message, Throwable cause) {
        super(message, cause);
    }
}
