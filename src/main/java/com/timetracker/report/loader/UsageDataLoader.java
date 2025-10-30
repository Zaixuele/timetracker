package com.timetracker.report.loader;

import com.timetracker.aggregation.MinuteRecord;
import com.timetracker.aggregation.MinuteStatus;
import com.timetracker.app.ResolvedApplication;
import com.timetracker.config.AppConfig;
import com.timetracker.config.StorageType;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public class UsageDataLoader {

    private static final DateTimeFormatter FOLDER_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final AppConfig config;

    public UsageDataLoader(AppConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public List<MinuteRecord> load(LocalDate date) throws IOException {
        Objects.requireNonNull(date, "date");
        return switch (config.storage().type()) {
            case CSV -> loadFromCsv(date);
            case SQLITE -> loadFromSqlite(date);
        };
    }

    private List<MinuteRecord> loadFromCsv(LocalDate date) throws IOException {
        Path root = Path.of(config.storage().csv().rootDir());
        Path directory = root
                .resolve(Integer.toString(date.getYear()))
                .resolve(date.format(FOLDER_FORMAT));
        Path file = directory.resolve(date.format(FOLDER_FORMAT) + ".csv");
        if (Files.notExists(file)) {
            return List.of();
        }

        List<MinuteRecord> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                List<String> columns = parseCsvLine(line);
                records.add(buildRecord(columns));
            }
        }
        return records;
    }

    private List<MinuteRecord> loadFromSqlite(LocalDate date) throws IOException {
        String url = "jdbc:sqlite:" + config.storage().sqlite().databasePath();
        List<MinuteRecord> records = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT date, minute, status, app_id, app_name, exe_path, active_seconds, idle_seconds, title_hash
                     FROM usage_minutes
                     WHERE date = ?
                     ORDER BY minute
                     """)) {
            statement.setString(1, date.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(mapRow(resultSet));
                }
            }
        } catch (SQLException ex) {
            throw new IOException("Failed to read usage data from SQLite", ex);
        }
        return records;
    }

    private MinuteRecord mapRow(ResultSet resultSet) throws SQLException {
        LocalDate date = LocalDate.parse(resultSet.getString("date"));
        LocalTime minute = LocalTime.parse(resultSet.getString("minute"), MINUTE_FORMAT);
        MinuteStatus status = parseStatus(resultSet.getString("status"));
        String appId = resultSet.getString("app_id");
        String appName = resultSet.getString("app_name");
        String exePath = resultSet.getString("exe_path");
        int activeSeconds = resultSet.getInt("active_seconds");
        int idleSeconds = resultSet.getInt("idle_seconds");
        String titleHash = resultSet.getString("title_hash");

        Optional<ResolvedApplication> application = buildApplication(status, appId, appName, exePath);
        Optional<String> title = Optional.ofNullable(titleHash).filter(StringUtils::isNotBlank);

        return new MinuteRecord(date, minute, status, application, activeSeconds, idleSeconds, title);
    }

    private MinuteRecord buildRecord(List<String> columns) {
        String dateValue = getColumn(columns, 0);
        String minuteValue = getColumn(columns, 1);
        String statusValue = getColumn(columns, 2);
        String appId = getColumn(columns, 3);
        String appName = getColumn(columns, 4);
        String exePath = getColumn(columns, 5);
        String activeSecondsValue = getColumn(columns, 7);
        String idleSecondsValue = getColumn(columns, 8);
        String titleHash = getColumn(columns, 9);

        LocalDate date = LocalDate.parse(dateValue);
        LocalTime minute = LocalTime.parse(minuteValue, MINUTE_FORMAT);
        MinuteStatus status = parseStatus(statusValue);
        int activeSeconds = parseInt(activeSecondsValue);
        int idleSeconds = parseInt(idleSecondsValue);
        Optional<ResolvedApplication> application = buildApplication(status, appId, appName, exePath);
        Optional<String> title = Optional.ofNullable(titleHash).filter(StringUtils::isNotBlank);

        return new MinuteRecord(date, minute, status, application, activeSeconds, idleSeconds, title);
    }

    private Optional<ResolvedApplication> buildApplication(MinuteStatus status,
                                                           String appId,
                                                           String appName,
                                                           String exePath) {
        if (status == MinuteStatus.IDLE || StringUtils.isBlank(exePath)) {
            return Optional.empty();
        }
        String normalized = exePath.toLowerCase(Locale.ROOT);
        String resolvedId = StringUtils.isNotBlank(appId) ? appId : normalized;
        String display = StringUtils.isNotBlank(appName) ? appName : deriveDisplayName(exePath);
        boolean aliasApplied = StringUtils.isNotBlank(appId) && !appId.equalsIgnoreCase(normalized);
        ResolvedApplication application = new ResolvedApplication(
                resolvedId,
                display,
                exePath,
                normalized,
                aliasApplied
        );
        return Optional.of(application);
    }

    private String deriveDisplayName(String exePath) {
        if (StringUtils.isBlank(exePath)) {
            return "Unknown";
        }
        Path path = Path.of(exePath);
        Path fileName = path.getFileName();
        if (fileName == null) {
            return exePath;
        }
        String baseName = fileName.toString();
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) {
            baseName = baseName.substring(0, dot);
        }
        return StringUtils.capitalize(baseName.toLowerCase(Locale.ROOT));
    }

    private int parseInt(String value) {
        if (StringUtils.isBlank(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private MinuteStatus parseStatus(String value) {
        if (StringUtils.isBlank(value)) {
            return MinuteStatus.ACTIVE;
        }
        return MinuteStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private String getColumn(List<String> columns, int index) {
        if (index < 0 || index >= columns.size()) {
            return "";
        }
        return columns.get(index);
    }

    private List<String> parseCsvLine(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                columns.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        columns.add(current.toString());
        return columns;
    }
}
