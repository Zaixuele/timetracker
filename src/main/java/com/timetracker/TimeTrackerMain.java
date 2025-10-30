package com.timetracker;

import com.timetracker.config.FileConfigManager;
import com.timetracker.lifecycle.TimeTrackerApplication;
import com.timetracker.lifecycle.TimeTrackerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class TimeTrackerMain {

    private static final Logger log = LoggerFactory.getLogger(TimeTrackerMain.class);

    private TimeTrackerMain() {
    }

    public static void main(String[] args) {
        Path configPath = resolveConfigPath(args);
        try (FileConfigManager configManager = new FileConfigManager()) {
            TimeTrackerApplication application = new TimeTrackerService(configPath, configManager);
            CountDownLatch latch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    application.stop();
                } catch (Exception ex) {
                    log.error("Error during shutdown", ex);
                } finally {
                    latch.countDown();
                }
            }, "timetracker-shutdown"));

            application.start();
            latch.await();
        } catch (Exception ex) {
            log.error("Failed to start TimeTracker", ex);
            System.exit(1);
        }
    }

    private static Path resolveConfigPath(String[] args) {
        if (args != null && args.length > 0) {
            return Path.of(args[0]).toAbsolutePath().normalize();
        }
        return Path.of("config", "config.json").toAbsolutePath().normalize();
    }
}
