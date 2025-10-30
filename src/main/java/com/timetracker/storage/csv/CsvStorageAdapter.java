package com.timetracker.storage.csv;

import com.timetracker.aggregation.MinuteRecord;
import com.timetracker.aggregation.MinuteStatus;
import com.timetracker.app.ResolvedApplication;
import com.timetracker.config.CsvStorageConfig;
import com.timetracker.storage.StorageAdapter;
import com.timetracker.storage.StorageException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class CsvStorageAdapter implements StorageAdapter {

    private static final Logger log = LoggerFactory.getLogger(CsvStorageAdapter.class);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final Path rootDir;
    private final int flushIntervalMinutes;
    private final int maxBatchSize;
    private final List<MinuteRecord> buffer = new ArrayList<>();
    private long lastFlushEpochMinute = -1;

    public CsvStorageAdapter(CsvStorageConfig config) {
        Objects.requireNonNull(config, "config");
        this.rootDir = Path.of(config.rootDir()).toAbsolutePath();
        this.flushIntervalMinutes = config.flushIntervalMinutes();
        this.maxBatchSize = config.maxBatchSize();
    }

    @Override
    public synchronized void persist(MinuteRecord record) throws StorageException {
        Objects.requireNonNull(record, "record");
        buffer.add(record);

        long minuteValue = record.date().toEpochDay() * 1440L + record.minute().getHour() * 60L + record.minute().getMinute();
        if (lastFlushEpochMinute < 0) {
            lastFlushEpochMinute = minuteValue;
        }

        boolean sizeExceeded = buffer.size() >= maxBatchSize;
        boolean intervalExceeded = minuteValue - lastFlushEpochMinute >= flushIntervalMinutes;

        if (sizeExceeded || intervalExceeded) {
            flush();
            lastFlushEpochMinute = minuteValue;
        }
    }

    @Override
    public synchronized void flush() throws StorageException {
        if (buffer.isEmpty()) {
            return;
        }
        try {
            for (MinuteRecord record : buffer) {
                writeRecord(record);
            }
            buffer.clear();
        } catch (IOException ex) {
            throw new StorageException("Failed to write CSV records", ex);
        }
    }

    private void writeRecord(MinuteRecord record) throws IOException {
        Path datedDir = rootDir
                .resolve(Integer.toString(record.date().getYear()))
                .resolve(record.date().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        Files.createDirectories(datedDir);

        Path file = datedDir.resolve(record.date().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".csv");
        boolean newFile = Files.notExists(file);

        try (BufferedWriter writer = Files.newBufferedWriter(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            if (newFile) {
                writer.write(header());
                writer.newLine();
            }
            writer.write(toCsv(record));
            writer.newLine();
        }
    }

    private String header() {
        return String.join(",",
                "date",
                "minute",
                "status",
                "app_id",
                "app_name",
                "exe_path",
                "minutes",
                "active_seconds",
                "idle_seconds",
                "title_hash");
    }

    private String toCsv(MinuteRecord record) {
        String status = record.status() == MinuteStatus.IDLE ? "Idle" : "Active";
        String appId = record.application().map(ResolvedApplication::id).orElse("");
        String appName = record.application().map(ResolvedApplication::displayName).orElse("");
        String exePath = record.application().map(ResolvedApplication::executablePath).orElse("");
        String titleHash = record.windowTitleHash().orElse("");

        return String.join(",",
                escape(record.date().format(DATE_FORMAT)),
                escape(record.minute().format(MINUTE_FORMAT)),
                escape(status),
                escape(appId),
                escape(appName),
                escape(exePath),
                "1",
                Integer.toString(record.activeSeconds()),
                Integer.toString(record.idleSeconds()),
                escape(titleHash));
    }

    private String escape(String value) {
        if (StringUtils.isEmpty(value)) {
            return "";
        }
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
