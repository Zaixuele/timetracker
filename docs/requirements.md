# TimeTracker Requirements & Technical Specification

## 1. Overview
- **Product goal**: On Windows, measure foreground application usage per minute with minimal resource usage, produce daily HTML summaries, and expose core controls via system tray.
- **Target users**: Individual productivity tracking (work/study); team internal tool; local storage only.
- **Out of scope (v1)**: Cross-platform support, account/sync, sub-minute analytics, remote telemetry.

## 2. Success Criteria
- Minute-level usage accuracy (≤15s tolerated drift per minute).
- Daily HTML report with pie + bar charts available by 00:05 each day.
- Background service footprint: RSS < 40 MB, average CPU < 1%, disk I/O < 1 MB/day (excluding reports).
- Stable after crashes/sleep; no data loss beyond last flush interval (≤1 minute).

### 2.1 MVP Decisions & Defaults
- **Minute ownership**: At most one outcome per minute (Idle or single app). Idle wins if both Idle and any app exceed their thresholds.
- **Thresholds**: `minActiveInMinuteSeconds=15`, `minIdleSeconds=60`, `samplingIntervalSeconds=1`.
- **Directories**: Default root `%APPDATA%\TimeTracker` with subfolders `data`, `logs`, `report`, `config`.
- **Listings**: Whitelist/blacklist disabled by default; matching performed against lowercase executable full path.
- **Aliases**: Applied post whitelist/blacklist checks; default name derived from executable filename when no alias matches.
- **Reports**: Chart.js embedded; bar chart top N default 10 excluding Idle while pie includes Idle.
- **Privacy**: Window titles not recorded by default; optional hashed titles via SHA-256 with per-install salt.

## 3. High-Level Architecture

```
┌────────────┐   ┌────────────────┐   ┌────────────────┐   ┌──────────────┐   ┌─────────────┐
│ Win32 APIs │→ │ ForegroundPoller│→│ MinuteAggregator │→│ StorageAdapter │→│ ReportEngine │
└────────────┘   └────────────────┘   └────────────────┘   └──────────────┘   └─────────────┘
                        │                    │                       ↑                  │
                        ↓                    ↓                       │                  ↓
                   IdleDetector         AliasResolver           ConfigManager        TrayUI
```

- Single `ScheduledExecutorService` (1 s period) drives sampling and aggregation.
- Background threads: sampler, flush writer (optional), report scheduler.
- Configuration + logs stored under `%APPDATA%\TimeTracker`.

## 4. Functional Requirements

### 4.1 Sampling & Detection
- Poll every 1 s (`samplingIntervalSeconds`, default 1).
- Resolve foreground window → process PID → executable path via `GetForegroundWindow`, `GetWindowThreadProcessId`, `OpenProcess`, `QueryFullProcessImageName`.
- Detect idle via `GetLastInputInfo`; idle threshold `minIdleSeconds` (default 60).
- Lock detection MVP: infer via idle threshold; extendable to `WTSGetSessionInformation`.
- Track “active seconds” within current wall-clock minute for:
  - Each foreground application (per normalized exe path).
  - Idle bucket (if idle threshold met within minute).

### 4.2 Minute Aggregation
- On minute boundary (`HH:mm` change), evaluate buckets:
  - Primary rule: application with max active seconds in that minute wins if `activeSeconds ≥ minActiveInMinuteSeconds` (default 15). Others drop.
  - If Idle active seconds ≥ threshold, minute recorded as Idle; applications skipped.
  - Result: at most one minute per bucket (Idle or one app).
- Store per-minute record: `{date, minute, status (Idle|Active), appId, appName, exePath, minutes=1, optional titleHash}`.
- After aggregation: reset counters for new minute.

### 4.3 Storage
- Pluggable storage via `StorageAdapter` interface (see §7).
- Implement CSV first, SQLite second (both MVP deliverables).
- Default: CSV files `data/YYYY/YYYYMMDD.csv`.
- Flush policy: write aggregated minutes immediately or batch up to 100 records / 1 minute (configurable).
- Handle crash recovery by appending on restart; avoid duplicate minute entries.

### 4.4 Reporting
- Daily report generation at 23:59 or on next launch if missed.
- Generate `report/daily_report_YYYYMMDD.html` (embedded Chart.js to avoid network dependency).
- Produce optional `report/data_YYYYMMDD.json` mirroring aggregated data.
- Visualizations:
  - Pie chart: share of minutes per application + Idle.
  - Bar chart: Top N apps (default 10) by minutes, excluding Idle by default; Idle toggle available.
  - Timeline (stretch goal): 1440-minute stacked segments (deferred unless time permits).
- Include daily summary stats (total focus minutes, Idle, top app).

### 4.5 Tray UI
- System tray icon & menu:
  - Start/Pause tracking (reflects current state).
  - Open today’s report.
  - Open data directory.
  - Open config file in editor.
  - Exit (graceful shutdown + flush).
- Tooltip shows current foreground app and today’s cumulative top app minutes.

### 4.6 Configuration
- `config/config.json` loaded on startup; optional manual reload via tray.
- Default structure:

```json
{
  "samplingIntervalSeconds": 1,
  "minActiveInMinuteSeconds": 15,
  "minIdleSeconds": 60,
  "storage": {
    "type": "csv",
    "csv": {
      "rootDir": "%APPDATA%/TimeTracker/data",
      "flushIntervalMinutes": 1,
      "maxBatchSize": 100
    },
    "sqlite": {
      "databasePath": "%APPDATA%/TimeTracker/data/timetracker.db",
      "journalMode": "WAL"
    }
  },
  "report": {
    "rootDir": "%APPDATA%/TimeTracker/report",
    "generateTime": "23:59",
    "topN": 10,
    "includeIdleInBar": false
  },
  "logging": {
    "level": "INFO",
    "file": "%APPDATA%/TimeTracker/logs/app.log",
    "maxSizeMB": 5,
    "rotationCount": 5
  },
  "aliases": [
    { "match": ".*chrome.exe", "name": "Chrome" }
  ],
  "whitelist": [],
  "blacklist": [],
  "privacy": {
    "recordWindowTitle": false,
    "titleHashSalt": ""
  }
}
```

- Whitelist/blacklist disabled by default; match on lowercase exe full path; whitelist mode and blacklist mode mutually exclusive.

### 4.7 Logging & Diagnostics
- Rolling file appender; log start/stop, sampling errors, storage failures, report generation status.
- Health metrics (optional): last flush time, queue sizes.

## 5. Non-Functional Requirements
- **Performance**: CPU < 1%, memory < 40 MB RSS; minimize Syscalls by reusing handles.
- **Reliability**: Handle API failures gracefully with retries/backoff; ensure flush on shutdown; guard against duplicate tray icons.
- **Security & Privacy**: No remote transmission; optional hashed title recording with per-install salt (SHA-256).
- **Maintainability**: Modular interfaces, structured logging, configuration comments in documentation.
- **Deployability**: Build as self-contained JAR + runtime (future packaging as MSI/EXE).

## 6. Data Specifications

### 6.1 CSV Schema
File: `YYYY/MM/DD.csv`

| Column          | Type    | Notes                                  |
|-----------------|---------|----------------------------------------|
| date            | string  | `YYYY-MM-DD`                           |
| minute          | string  | `HH:mm`                                |
| status          | string  | `Active` or `Idle`                     |
| app_id          | string  | Normalized exe path hash               |
| app_name        | string  | Resolved alias name                    |
| exe_path        | string  | Full executable path                   |
| minutes         | int     | Always `1`                             |
| idle_seconds    | int     | Idle seconds in minute (if Idle)       |
| active_seconds  | int     | Active seconds for winning app         |
| title_hash      | string  | Optional; blank if disabled            |

### 6.2 SQLite Schema (planned)

```sql
CREATE TABLE usage_minutes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    minute TEXT NOT NULL,
    status TEXT NOT NULL,
    app_id TEXT,
    app_name TEXT,
    exe_path TEXT,
    active_seconds INTEGER,
    idle_seconds INTEGER,
    minutes INTEGER NOT NULL DEFAULT 1,
    title_hash TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_usage_unique ON usage_minutes(date, minute);
```

## 7. Component Interfaces (Java)

### 7.1 Configuration
```java
interface ConfigManager {
    AppConfig load(Path path) throws IOException;
    void save(Path path, AppConfig config) throws IOException;
    void registerListener(ConfigListener listener);
}

interface ConfigListener {
    void onConfigReload(AppConfig newConfig);
}
```

### 7.2 Sampling
```java
interface ForegroundSampler extends AutoCloseable {
    SampledApp sampleForeground() throws SamplingException;
}

record SampledApp(
    Instant timestamp,
    Optional<AppIdentity> app,
    boolean idle
) {}

record AppIdentity(String exePath, String displayName, int processId) {}
```

### 7.3 Idle Detection
```java
interface IdleDetector {
    Duration timeSinceLastInput() throws SamplingException;
}
```

### 7.4 Aggregation
```java
interface MinuteAggregator {
    void recordSample(SampledApp sample);
    Optional<MinuteRecord> flushIfReady(Instant currentTime);
}

record MinuteRecord(
    LocalDate date,
    LocalTime minute,
    MinuteStatus status,
    Optional<AppIdentity> app,
    int activeSeconds,
    int idleSeconds
) {}

enum MinuteStatus { ACTIVE, IDLE }
```

### 7.5 Storage
```java
interface StorageAdapter extends AutoCloseable {
    void persist(MinuteRecord record) throws StorageException;
    void flush() throws StorageException;
}
```

### 7.6 Reporting
```java
interface ReportGenerator {
    void generateDailyReport(LocalDate date, List<MinuteRecord> records) throws IOException;
}
```

### 7.7 Tray Control
```java
interface TrayController {
    void init(TrayActions actions) throws TrayException;
    void updateStatus(TrayStatus status);
    void shutdown();
}

record TrayStatus(String currentApp, Optional<AppUsageSummary> topApp) {}
record AppUsageSummary(String appName, int minutes) {}
```

### 7.8 Application Lifecycle
```java
interface TimeTrackerApplication {
    void start() throws Exception;
    void pause();
    void resume();
    void stop() throws Exception;
}
```

## 8. Error Handling & Logging
- Wrap Win32 calls with descriptive exceptions; categorize into recoverable (retry) vs fatal.
- Log WARN when sampling fails occasionally; escalate to ERROR when consecutive failures > configurable threshold (default 5).
- Storage failures trigger retry queue; if persistent, pause sampling and prompt user via tray notification.

## 9. Deployment & Packaging (Future)
- Produce runnable fat JAR + bundled JRE (Java 17).
- Optional MSI/EXE installer with autostart registry entry.
- Configuration & data directories created on first run.

## 10. Open Items / Future Enhancements
1. Precise lock detection via `WTSRegisterSessionNotification`.
2. Real-time timeline visualization (1440-minute heatmap).
3. Advanced aliasing (regex groups), team configuration sync.
4. Additional storage (cloud, REST) — explicitly out of scope for v1.
5. Integration tests with mocked Win32 API using JNA test harness.
