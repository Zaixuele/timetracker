package com.timetracker.config;

import java.io.IOException;
import java.nio.file.Path;

public interface ConfigManager extends AutoCloseable {

    AppConfig load(Path path) throws IOException;

    void save(Path path, AppConfig config) throws IOException;

    void registerListener(ConfigListener listener);

    default void startWatching(Path path) throws IOException {
        // optional
    }

    default void close() {
        // default no-op
    }
}
