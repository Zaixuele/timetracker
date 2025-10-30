package com.timetracker.tray;

import java.nio.file.Path;

public interface TrayController extends AutoCloseable {

    void init(TrayActions actions) throws TrayException;

    void updateStatus(TrayStatus status);

    default void updatePaths(Path reportDirectory, Path dataDirectory) {
        // optional
    }

    void displayMessage(String caption, String text, TrayMessageType type);

    void shutdown();

    @Override
    default void close() {
        shutdown();
    }
}
