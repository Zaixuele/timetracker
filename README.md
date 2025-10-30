# TimeTracker

Windows foreground usage tracker that captures per-minute focus time with minimal overhead, daily HTML reporting, and a system-tray controller.

## Features
- Samples the foreground process via Win32 APIs every second (configurable) and aggregates usage into minute buckets with a 15-second active threshold and idle detection.
- Supports CSV (default) and SQLite storage backends with periodic flush and crash-resilient appends.
- Generates self-contained HTML reports (Chart.js embedded) for each day, with pie/bar charts and a timeline view, plus accompanying JSON exports.
- System tray menu allows pausing/resuming tracking, opening today's report, and jumping to config/data directories.
- Live configuration reload: edits to `config/config.json` propagate aliases, privacy, thresholds, and report schedule at runtime.
- Optional hashed window-title capture for privacy-conscious environments.

## Requirements
- Windows 10/11
- Java 17 runtime
- Maven 3.9+ for building from source

## Build & Run
```powershell
# build shaded jar (targets \target\timetracker-0.1.0-SNAPSHOT-shaded.jar)
mvn clean package

# run (PowerShell helper)
.\scripts\run.ps1

# run (Command Prompt helper)
scripts\run.bat
```

You can pass an alternate config path via CLI arguments:

```powershell
java -jar target\timetracker-0.1.0-SNAPSHOT-shaded.jar D:\custom\config.json
```

## Configuration
- Default config: `config/config.json`
- Key options:
  - `samplingIntervalSeconds` — probe cadence (default 1s).
  - `minActiveInMinuteSeconds` — seconds of focus required to count a minute (default 15).
  - `minIdleSeconds` — idle seconds before a minute is classified as Idle (default 60).
  - `storage.type` — `CSV` or `SQLITE`, each with path, flush, and batch settings.
  - `report.generateTime` — HH:mm (24h) time to emit yesterday’s report.
  - `privacy.recordWindowTitle` / `titleHashSalt` — enable hashed title capture.
  - `aliases` / `whitelist` / `blacklist` — map executables to friendly names or filter apps.

Edits trigger live reload; only storage type changes require a restart.

## Output & Logs
- CSV data: `%APPDATA%\TimeTracker\data\YYYY\YYYYMMDD.csv`
- SQLite data (if enabled): `%APPDATA%\TimeTracker\data\timetracker.db`
- Reports: `%APPDATA%\TimeTracker\report\daily_report_YYYYMMDD.html` (+ JSON backup)
- Logs: `%APPDATA%\TimeTracker\logs\app.log` with 5×5 MB rotation.

## Tray Controls
- Pause/Resume tracking
- Open today’s report
- Open data directory
- Open configuration
- Exit (flushes and shuts down gracefully)

Tooltip shows the current foreground app (or Idle) and today’s top app with minutes.

## Testing
Unit tests cover aggregation thresholds, alias resolution, and path expansion. Execute:
```powershell
mvn test
```

## Specification
Detailed requirements and technical design reside in `docs/requirements.md`.
