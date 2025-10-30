package com.timetracker.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

public class FileConfigManager implements ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(FileConfigManager.class);

    private final ObjectMapper mapper;
    private final List<ConfigListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService watcherExecutor;
    private WatchService watchService;
    private volatile boolean watching;

    public FileConfigManager() {
        this.mapper = new ObjectMapper()
                .registerModule(new ParameterNamesModule())
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule());
        this.mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.mapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
        this.watcherExecutor = Executors.newSingleThreadExecutor(new DaemonThreadFactory("config-watcher"));
    }

    @Override
    public AppConfig load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        ensureParentDirectory(path);
        if (!Files.exists(path)) {
            AppConfig defaults = AppConfig.defaults();
            save(path, defaults);
            return defaults;
        }
        AppConfig config;
        try (var reader = Files.newBufferedReader(path)) {
            config = mapper.readValue(reader, AppConfig.class);
        }
        log.debug("Loaded configuration from {}", path);
        return config;
    }

    @Override
    public void save(Path path, AppConfig config) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(config, "config");
        ensureParentDirectory(path);
        try (var writer = Files.newBufferedWriter(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            mapper.writeValue(writer, config);
        }
        log.info("Configuration saved to {}", path);
    }

    @Override
    public void registerListener(ConfigListener listener) {
        listeners.add(listener);
    }

    @Override
    public void startWatching(Path configFile) throws IOException {
        Objects.requireNonNull(configFile, "configFile");
        if (watching) {
            return;
        }
        ensureParentDirectory(configFile);
        Path dir = configFile.getParent();
        watchService = dir.getFileSystem().newWatchService();
        dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
        watching = true;
        watcherExecutor.submit(() -> runWatcherLoop(configFile));
        log.info("Started watching configuration changes in {}", dir);
    }

    private void runWatcherLoop(Path configFile) {
        while (watching) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.warn("Config watch service interrupted", ex);
                break;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (!(event.context() instanceof Path eventPath)) {
                    continue;
                }
                Path resolved = configFile.getParent().resolve(eventPath);
                if (resolved.equals(configFile)) {
                    try {
                        AppConfig reloaded = load(configFile);
                        listeners.forEach(listener -> listener.onConfigReload(reloaded));
                    } catch (IOException ex) {
                        log.warn("Failed to reload configuration after change", ex);
                    }
                }
            }
            if (!key.reset()) {
                break;
            }
        }
    }

    @Override
    public void close() {
        watching = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ex) {
                log.debug("Error closing config watch service", ex);
            }
        }
        watcherExecutor.shutdownNow();
    }

    private void ensureParentDirectory(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent, new FileAttribute<?>[0]);
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private int counter = 0;

        private DaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + counter++);
            thread.setDaemon(true);
            return thread;
        }
    }
}
