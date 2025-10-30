package com.timetracker.lifecycle;

import com.timetracker.aggregation.MinuteAggregator;
import com.timetracker.aggregation.MinuteRecord;
import com.timetracker.aggregation.MinuteStatus;
import com.timetracker.aggregation.SampleTick;
import com.timetracker.app.AppResolver;
import com.timetracker.app.ApplicationSample;
import com.timetracker.app.ResolvedApplication;
import com.timetracker.config.AppConfig;
import com.timetracker.config.ConfigManager;
import com.timetracker.config.StorageType;
import com.timetracker.logging.LoggingConfigurator;
import com.timetracker.report.HtmlReportGenerator;
import com.timetracker.report.ReportGenerator;
import com.timetracker.report.loader.UsageDataLoader;
import com.timetracker.sampling.ForegroundSample;
import com.timetracker.sampling.ForegroundSampler;
import com.timetracker.sampling.IdleDetector;
import com.timetracker.sampling.SamplingException;
import com.timetracker.storage.StorageAdapter;
import com.timetracker.storage.StorageException;
import com.timetracker.storage.csv.CsvStorageAdapter;
import com.timetracker.storage.sqlite.SqliteStorageAdapter;
import com.timetracker.tray.SystemTrayController;
import com.timetracker.tray.TrayActions;
import com.timetracker.tray.TrayController;
import com.timetracker.tray.TrayException;
import com.timetracker.tray.TrayMessageType;
import com.timetracker.tray.TrayStatus;
import com.timetracker.win32.Win32ForegroundSampler;
import com.timetracker.win32.Win32IdleDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates sampling, aggregation, persistence, tray updates, and report generation.
 */
public class TimeTrackerService implements TimeTrackerApplication {

    private static final Logger log = LoggerFactory.getLogger(TimeTrackerService.class);

    private final Path configPath;
    private final ConfigManager configManager;

    private AppConfig config;
    private ForegroundSampler sampler;
    private IdleDetector idleDetector;
    private AppResolver appResolver;
    private MinuteAggregator aggregator;
    private StorageAdapter storageAdapter;
    private ReportGenerator reportGenerator;
    private UsageDataLoader usageDataLoader;
    private TrayController trayController;

    private ScheduledExecutorService samplingExecutor;
    private ScheduledExecutorService reportExecutor;
    private ExecutorService shutdownExecutor;

    private final AtomicBoolean trackingActive = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private final Map<String, Integer> todayMinutes = new ConcurrentHashMap<>();
    private final Map<String, ResolvedApplication> todayApps = new ConcurrentHashMap<>();
    private final AtomicInteger todayIdleMinutes = new AtomicInteger(0);

    private volatile String currentAppDisplay = "Idle";
    private volatile LocalDate currentDay;
    private volatile LocalDate lastReportGenerated;

    public TimeTrackerService(Path configPath, ConfigManager configManager) {
        this.configPath = configPath.toAbsolutePath().normalize();
        this.configManager = configManager;
    }

    @Override
    public void start() throws Exception {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        this.config = configManager.load(configPath);
        LoggingConfigurator.apply(config.logging());
        log.info("Starting TimeTracker");

        this.sampler = new Win32ForegroundSampler(Boolean.TRUE.equals(config.privacy().recordWindowTitle()));
        this.idleDetector = new Win32IdleDetector();
        this.appResolver = new AppResolver(config.aliases(), config.whitelist(), config.blacklist(), config.privacy());
        this.aggregator = new MinuteAggregator(
                config.minActiveInMinuteSeconds(),
                config.minIdleSeconds(),
                config.samplingIntervalSeconds());
        this.storageAdapter = createStorageAdapter(config);
        this.reportGenerator = new HtmlReportGenerator(config.report());
        this.usageDataLoader = new UsageDataLoader(config);

        Path reportDir = Path.of(config.report().rootDir());
        Path dataDir = dataRootPath(this.config);
        this.trayController = new SystemTrayController(reportDir, dataDir, configPath);
        TrayActions trayActions = new TrayActions(this::toggleTracking, this::requestStop, trackingActive::get);
        trayController.init(trayActions);

        this.currentDay = LocalDate.now();
        this.lastReportGenerated = null;
        this.trackingActive.set(true);
        this.shutdownExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("shutdown"));

        schedulingSetup();
        configManager.registerListener(newConfig -> {
            if (samplingExecutor != null && !samplingExecutor.isShutdown()) {
                samplingExecutor.execute(() -> applyConfigReload(newConfig));
            }
        });
        try {
            configManager.startWatching(configPath);
        } catch (Exception ex) {
            log.warn("Failed to start configuration watcher", ex);
        }
        trayController.updateStatus(initialStatus());
        maybeGenerateReportForYesterday();
    }

    private void schedulingSetup() {
        scheduleSamplingExecutor();
        scheduleReportExecutor();
    }

    private void scheduleSamplingExecutor() {
        if (samplingExecutor != null) {
            samplingExecutor.shutdownNow();
        }
        this.samplingExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("sampler"));
        long intervalMillis = Math.max(1, config.samplingIntervalSeconds()) * 1000L;
        samplingExecutor.scheduleAtFixedRate(
                () -> safeExecute(this::performSamplingTick, "sampling tick"),
                0,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private void scheduleReportExecutor() {
        if (reportExecutor != null) {
            reportExecutor.shutdownNow();
        }
        this.reportExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("reporter"));
        LocalTime reportTime = LocalTime.parse(config.report().generateTime());
        long initialDelayMillis = computeInitialDelay(reportTime);
        long periodMillis = Duration.ofDays(1).toMillis();
        reportExecutor.scheduleAtFixedRate(
                () -> safeExecute(this::dailyReportTask, "daily report generation"),
                initialDelayMillis,
                periodMillis,
                TimeUnit.MILLISECONDS
        );
    }

    private long computeInitialDelay(LocalTime reportTime) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime firstRun = LocalDateTime.of(now.toLocalDate(), reportTime);
        if (!firstRun.isAfter(now)) {
            firstRun = firstRun.plusDays(1);
        }
        return Duration.between(now, firstRun).toMillis();
    }

    private void performSamplingTick() {
        if (!trackingActive.get()) {
            return;
        }
        try {
            ForegroundSample sample = sampler.sample();
            Duration idleDuration = idleDetector.timeSinceLastInput();
            long idleSecondsTotal = Math.max(0, idleDuration.toSeconds());
            boolean idle = idleSecondsTotal >= config.minIdleSeconds();
            int idleSecondsSnapshot = idleSecondsTotal > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : (int) idleSecondsTotal;

            Optional<ApplicationSample> appSample = Optional.empty();
            if (!idle) {
                appSample = sample.app().flatMap(identity -> appResolver.resolve(identity, sample.windowTitle()));
                currentAppDisplay = appSample
                        .map(app -> app.application().displayName())
                        .orElse(sample.app().map(a -> a.displayName()).orElse("Unknown"));
            } else {
                currentAppDisplay = "Idle";
            }

            SampleTick tick = new SampleTick(sample.timestamp(), idle, appSample, idleSecondsSnapshot);
            aggregator.processSample(tick).ifPresent(this::handleMinuteRecord);
            refreshTrayStatus();
        } catch (SamplingException ex) {
            log.warn("Sampling failed: {}", ex.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected error during sampling tick", ex);
        }
    }

    private void handleMinuteRecord(MinuteRecord record) {
        try {
            storageAdapter.persist(record);
        } catch (StorageException ex) {
            log.error("Failed to persist minute record", ex);
            trayController.displayMessage("TimeTracker", "Failed to persist usage data.", TrayMessageType.ERROR);
        }

        LocalDate recordDay = record.date();
        if (recordDay.isAfter(currentDay)) {
            generateReport(currentDay);
            resetDailyStats(recordDay);
        }

        if (record.status() == MinuteStatus.ACTIVE && record.application().isPresent()) {
            ResolvedApplication app = record.application().orElseThrow();
            todayApps.put(app.id(), app);
            todayMinutes.merge(app.id(), 1, Integer::sum);
        } else if (record.status() == MinuteStatus.IDLE) {
            todayIdleMinutes.incrementAndGet();
        }
    }

    private void refreshTrayStatus() {
        Optional<TrayStatus.AppUsageSummary> top = todayMinutes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .findFirst()
                .map(entry -> {
                    ResolvedApplication app = todayApps.get(entry.getKey());
                    String name = app != null ? app.displayName() : entry.getKey();
                    return new TrayStatus.AppUsageSummary(name, entry.getValue());
                });
        TrayStatus status = new TrayStatus(currentAppDisplay, top, trackingActive.get());
        trayController.updateStatus(status);
    }

    private TrayStatus initialStatus() {
        return new TrayStatus("Initializing", Optional.empty(), true);
    }

    private void toggleTracking() {
        boolean active = trackingActive.get();
        boolean updated = trackingActive.compareAndSet(active, !active);
        if (updated) {
            String message = active ? "Tracking paused" : "Tracking resumed";
            trayController.displayMessage("TimeTracker", message, TrayMessageType.INFO);
        }
        refreshTrayStatus();
    }

    private void dailyReportTask() {
        LocalDate targetDate = LocalDate.now();
        Optional<MinuteRecord> pending = aggregator.flushPendingMinute();
        pending.ifPresent(this::handleMinuteRecord);
        generateReport(targetDate);
    }

    private void generateReport(LocalDate date) {
        if (lastReportGenerated != null && !date.isAfter(lastReportGenerated)) {
            return;
        }
        try {
            storageAdapter.flush();
            List<MinuteRecord> records = usageDataLoader.load(date);
            if (records.isEmpty()) {
                log.debug("No usage data for {}, skipping report generation.", date);
                return;
            }
            reportGenerator.generateDailyReport(date, records);
            lastReportGenerated = date;
            trayController.displayMessage("TimeTracker", "Report generated for " + date, TrayMessageType.INFO);
        } catch (Exception ex) {
            log.error("Failed to generate report for {}", date, ex);
            trayController.displayMessage("TimeTracker", "Report generation failed for " + date, TrayMessageType.ERROR);
        }
    }

    private void applyConfigReload(AppConfig newConfig) {
        AppConfig previous = this.config;
        this.config = newConfig;
        if (previous.equals(newConfig)) {
            log.debug("Configuration reload detected but no changes applied.");
            return;
        }
        log.info("Configuration reloaded from {}", configPath);

        try {
            LoggingConfigurator.apply(newConfig.logging());
        } catch (Exception ex) {
            log.warn("Failed to apply logging configuration after reload", ex);
        }

        this.appResolver = new AppResolver(
                newConfig.aliases(),
                newConfig.whitelist(),
                newConfig.blacklist(),
                newConfig.privacy());

        boolean previousWindowCapture = previous != null && Boolean.TRUE.equals(previous.privacy().recordWindowTitle());
        boolean newWindowCapture = Boolean.TRUE.equals(newConfig.privacy().recordWindowTitle());
        if (previousWindowCapture != newWindowCapture) {
            ForegroundSampler oldSampler = this.sampler;
            this.sampler = new Win32ForegroundSampler(newWindowCapture);
            if (oldSampler != null) {
                try {
                    oldSampler.close();
                } catch (Exception ex) {
                    log.debug("Error closing previous sampler after config reload", ex);
                }
            }
        }

        boolean samplingIntervalChanged = newConfig.samplingIntervalSeconds() != previous.samplingIntervalSeconds();
        boolean thresholdsChanged = samplingIntervalChanged
                || newConfig.minActiveInMinuteSeconds() != previous.minActiveInMinuteSeconds()
                || newConfig.minIdleSeconds() != previous.minIdleSeconds();
        if (thresholdsChanged) {
            if (aggregator != null) {
                Optional<MinuteRecord> pending = aggregator.flushPendingMinute();
                pending.ifPresent(this::handleMinuteRecord);
            }
            this.aggregator = new MinuteAggregator(
                    newConfig.minActiveInMinuteSeconds(),
                    newConfig.minIdleSeconds(),
                    newConfig.samplingIntervalSeconds());
        }

        boolean reportConfigChanged = !previous.report().equals(newConfig.report());
        boolean reportTimeChanged = !previous.report().generateTime().equals(newConfig.report().generateTime());
        if (reportConfigChanged) {
            this.reportGenerator = new HtmlReportGenerator(newConfig.report());
        }
        this.usageDataLoader = new UsageDataLoader(newConfig);

        if (trayController != null) {
            trayController.updatePaths(Path.of(newConfig.report().rootDir()), dataRootPath(newConfig));
        }

        if (samplingIntervalChanged) {
            scheduleSamplingExecutor();
            log.info("Sampling interval updated to {} second(s)", newConfig.samplingIntervalSeconds());
        }

        if (reportTimeChanged) {
            scheduleReportExecutor();
            log.info("Report generation time updated to {}", newConfig.report().generateTime());
        }

        if (!previous.storage().equals(newConfig.storage())) {
            log.warn("Storage configuration changed; please restart the application to apply.");
            if (trayController != null) {
                trayController.displayMessage("TimeTracker", "Storage settings changed. Restart required to apply.", TrayMessageType.WARNING);
            }
        } else if (trayController != null) {
            trayController.displayMessage("TimeTracker", "Configuration reloaded", TrayMessageType.INFO);
        }
    }

    private void maybeGenerateReportForYesterday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        generateReport(yesterday);
    }

    private void resetDailyStats(LocalDate newDay) {
        todayMinutes.clear();
        todayApps.clear();
        todayIdleMinutes.set(0);
        currentDay = newDay;
    }

    private void safeExecute(Runnable runnable, String taskName) {
        try {
            runnable.run();
        } catch (Throwable ex) {
            log.error("Error executing {}", taskName, ex);
        }
    }

    private StorageAdapter createStorageAdapter(AppConfig config) throws StorageException {
        return switch (config.storage().type()) {
            case CSV -> new CsvStorageAdapter(config.storage().csv());
            case SQLITE -> new SqliteStorageAdapter(config.storage().sqlite());
        };
    }

    private Path dataRootPath() {
        return dataRootPath(this.config);
    }

    private Path dataRootPath(AppConfig configuration) {
        if (configuration.storage().type() == StorageType.CSV) {
            return Path.of(configuration.storage().csv().rootDir());
        }
        Path sqlitePath = Path.of(configuration.storage().sqlite().databasePath());
        Path parent = sqlitePath.getParent();
        return parent != null ? parent : sqlitePath;
    }

    private void requestStop() {
        if (shuttingDown.compareAndSet(false, true)) {
            shutdownExecutor.submit(() -> {
                try {
                    stop();
                } catch (Exception ex) {
                    log.error("Error during shutdown", ex);
                }
            });
        }
    }

    @Override
    public void pause() {
        trackingActive.set(false);
        trayController.displayMessage("TimeTracker", "Tracking paused", TrayMessageType.INFO);
        refreshTrayStatus();
    }

    @Override
    public void resume() {
        trackingActive.set(true);
        trayController.displayMessage("TimeTracker", "Tracking resumed", TrayMessageType.INFO);
        refreshTrayStatus();
    }

    @Override
    public void stop() throws Exception {
        if (!started.get()) {
            return;
        }
        log.info("Stopping TimeTracker");
        trackingActive.set(false);
        if (samplingExecutor != null) {
            samplingExecutor.shutdownNow();
            samplingExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (reportExecutor != null) {
            reportExecutor.shutdownNow();
            reportExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }

        Optional<MinuteRecord> pending = aggregator.flushPendingMinute();
        pending.ifPresent(this::handleMinuteRecord);
        storageAdapter.flush();

        generateReport(currentDay);

        if (trayController != null) {
            trayController.shutdown();
        }
        if (sampler != null) {
            sampler.close();
        }
        if (storageAdapter != null) {
            storageAdapter.close();
        }
        configManager.close();

        if (shutdownExecutor != null) {
            shutdownExecutor.shutdownNow();
        }
    }

    @Override
    public void close() throws Exception {
        stop();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private int counter = 0;

        private NamedThreadFactory(String prefix) {
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
