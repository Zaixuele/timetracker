package com.timetracker.storage.sqlite;

import com.timetracker.aggregation.MinuteRecord;
import com.timetracker.aggregation.MinuteStatus;
import com.timetracker.app.ResolvedApplication;
import com.timetracker.config.SqliteStorageConfig;
import com.timetracker.storage.StorageAdapter;
import com.timetracker.storage.StorageException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class SqliteStorageAdapter implements StorageAdapter {

    private static final Logger log = LoggerFactory.getLogger(SqliteStorageAdapter.class);

    private static final int DEFAULT_FLUSH_INTERVAL_MINUTES = 1;
    private static final int DEFAULT_MAX_BATCH_SIZE = 100;

    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final Path databasePath;
    private final int flushIntervalMinutes;
    private final int maxBatchSize;
    private final Connection connection;
    private final PreparedStatement upsertStatement;

    private final List<MinuteRecord> buffer = new ArrayList<>();
    private long lastFlushEpochMinute = -1;

    public SqliteStorageAdapter(SqliteStorageConfig config) throws StorageException {
        Objects.requireNonNull(config, "config");
        try {
            this.databasePath = Path.of(config.databasePath()).toAbsolutePath();
            if (databasePath.getParent() != null) {
                Files.createDirectories(databasePath.getParent());
            }
            this.flushIntervalMinutes = DEFAULT_FLUSH_INTERVAL_MINUTES;
            this.maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
            String url = "jdbc:sqlite:" + databasePath;
            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(false);
            configurePragma(connection, config.journalMode());
            createSchema(connection);
            this.connection.commit();
            this.upsertStatement = connection.prepareStatement("""
                    INSERT INTO usage_minutes
                        (date, minute, status, app_id, app_name, exe_path, active_seconds, idle_seconds, minutes, title_hash)
                    VALUES
                        (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                    ON CONFLICT(date, minute) DO UPDATE SET
                        status=excluded.status,
                        app_id=excluded.app_id,
                        app_name=excluded.app_name,
                        exe_path=excluded.exe_path,
                        active_seconds=excluded.active_seconds,
                        idle_seconds=excluded.idle_seconds,
                        minutes=excluded.minutes,
                        title_hash=excluded.title_hash,
                        updated_at=CURRENT_TIMESTAMP
                    """);
        } catch (Exception ex) {
            throw new StorageException("Failed to initialise SQLite storage", ex);
        }
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
                bindRecord(upsertStatement, record);
                upsertStatement.addBatch();
            }
            upsertStatement.executeBatch();
            connection.commit();
            upsertStatement.clearBatch();
            buffer.clear();
        } catch (SQLException ex) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                log.warn("SQLite rollback failed", rollbackEx);
            }
            throw new StorageException("Failed to persist minute records to SQLite", ex);
        }
    }

    @Override
    public synchronized void close() throws StorageException {
        try {
            flush();
        } finally {
            try {
                if (upsertStatement != null) {
                    upsertStatement.close();
                }
            } catch (SQLException ex) {
                log.debug("Failed to close SQLite prepared statement", ex);
            }
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException ex) {
                log.debug("Failed to close SQLite connection", ex);
            }
        }
    }

    private void bindRecord(PreparedStatement statement, MinuteRecord record) throws SQLException {
        statement.setString(1, record.date().toString());
        statement.setString(2, MINUTE_FORMAT.format(record.minute()));
        statement.setString(3, record.status().name());

        if (record.status() == MinuteStatus.IDLE || record.application().isEmpty()) {
            statement.setNull(4, java.sql.Types.VARCHAR);
            statement.setNull(5, java.sql.Types.VARCHAR);
            statement.setNull(6, java.sql.Types.VARCHAR);
        } else {
            ResolvedApplication app = record.application().orElseThrow();
            statement.setString(4, app.id());
            statement.setString(5, app.displayName());
            statement.setString(6, app.executablePath());
        }

        statement.setInt(7, record.activeSeconds());
        statement.setInt(8, record.idleSeconds());

        if (record.windowTitleHash().isPresent() && StringUtils.isNotBlank(record.windowTitleHash().get())) {
            statement.setString(9, record.windowTitleHash().get());
        } else {
            statement.setNull(9, java.sql.Types.VARCHAR);
        }
    }

    private void configurePragma(Connection connection, String journalMode) throws SQLException {
        if (StringUtils.isNotBlank(journalMode)) {
            String mode = journalMode.trim().toUpperCase(Locale.ROOT);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=" + mode);
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA synchronous=NORMAL");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS usage_minutes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        date TEXT NOT NULL,
                        minute TEXT NOT NULL,
                        status TEXT NOT NULL,
                        app_id TEXT,
                        app_name TEXT,
                        exe_path TEXT,
                        active_seconds INTEGER NOT NULL DEFAULT 0,
                        idle_seconds INTEGER NOT NULL DEFAULT 0,
                        minutes INTEGER NOT NULL DEFAULT 1,
                        title_hash TEXT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        updated_at TEXT NOT NULL DEFAULT (datetime('now'))
                    )
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_usage_unique
                    ON usage_minutes(date, minute)
                    """);
        }
    }
}
