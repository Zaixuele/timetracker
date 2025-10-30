# 时间追踪器(TimeTracker)

一款 Windows 前台使用追踪器，以极低开销记录每分钟的专注时间，生成每日 HTML 报告，并提供系统托盘控制。  
Windows foreground usage tracker that captures per-minute focus time with minimal overhead, daily HTML reporting, and a system-tray controller.
 
## 功能特性(Features)
- 使用 Win32 API 每秒采样一次前台进程（可配置），并按分钟聚合，设有 15 秒活跃阈值与空闲检测。  
  Samples the foreground process via Win32 APIs every second (configurable) and aggregates usage into minute buckets with a 15-second active threshold and idle detection.
- 支持 CSV（默认）与 SQLite 存储后端，定期刷新、支持崩溃安全追加。
  Supports CSV (default) and SQLite storage backends with periodic flush and crash-resilient appends.
-  每日生成自包含的 HTML 报告（内嵌 Chart.js），包含饼图、柱状图、时间线视图，并导出对应 JSON。  
  Generates self-contained HTML reports (Chart.js embedded) for each day, with pie/bar charts and a timeline view, plus accompanying JSON exports.
- 系统托盘菜单可暂停/恢复追踪、打开今日报告、或跳转至配置与数据目录。  
  System tray menu allows pausing/resuming tracking, opening today's report, and jumping to config/data directories.
- 支持实时配置热加载：修改 `config/config.json` 后别名、隐私、阈值、报告计划等会即时生效。  
  Live configuration reload: edits to `config/config.json` propagate aliases, privacy, thresholds, and report schedule at runtime.
- 可选启用窗口标题哈希捕获以保护隐私。
  Optional hashed window-title capture for privacy-conscious environments.

## 系统要求(Requirements)
- Windows 10/11
- Java 17 runtime
- Maven 3.9+ for building from source

## 构建与运行(Build & Run)
```powershell
# build shaded jar (targets \target\timetracker-0.1.0-SNAPSHOT-shaded.jar)
mvn clean package

# run (PowerShell helper)
.\scripts\run.ps1

# run (Command Prompt helper)
scripts\run.bat
```
你可以通过命令行参数指定自定义配置路径：
You can pass an alternate config path via CLI arguments:

```powershell
java -jar target\timetracker-0.1.0-SNAPSHOT-shaded.jar D:\custom\config.json
```

## 配置说明(Configuration)
- 默认配置文件路径(Default config): `config/config.json`
- 主要参数(Key options):
  - `samplingIntervalSeconds` — probe cadence (default 1s).采样间隔秒数（默认 1 秒）
  - `minActiveInMinuteSeconds` — seconds of focus required to count a minute (default 15). 每分钟视为“活跃”所需的最少专注秒数（默认 15 秒）
  - `minIdleSeconds` — idle seconds before a minute is classified as Idle (default 60).一分钟内空闲达到该秒数后标记为空闲（默认 60 秒）
  - `storage.type` — `CSV` or `SQLITE`, each with path, flush, and batch settings.存储类型：`CSV` 或 `SQLITE`，各自含路径、刷新与批量参数
  - `report.generateTime` — HH:mm (24h) time to emit yesterday’s report.报告生成时间（24 小时制），用于输出昨日报告
  - `privacy.recordWindowTitle` / `titleHashSalt` — enable hashed title capture.是否记录窗口标题及其哈希盐值
  - `aliases` / `whitelist` / `blacklist` — map executables to friendly names or filter apps.程序别名、白名单、黑名单映射

配置修改会实时生效，仅更改存储类型需重启程序。
Edits trigger live reload; only storage type changes require a restart.

## 输出与日志(Output & Logs)
- CSV data: `%APPDATA%\TimeTracker\data\YYYY\YYYYMMDD.csv`
- SQLite data (if enabled): `%APPDATA%\TimeTracker\data\timetracker.db`
- Reports: `%APPDATA%\TimeTracker\report\daily_report_YYYYMMDD.html` (+ JSON backup)
- Logs: `%APPDATA%\TimeTracker\logs\app.log` with 5×5 MB rotation.

## 托盘控制(Tray Controls)
- 暂停 / 恢复追踪(Pause/Resume tracking)
- 打开今日报告(Open today’s report)
- 打开数据目录(Open data directory)
- 打开配置文件(Open configuration)
- 退出（刷新后安全关闭）(Exit (flushes and shuts down gracefully))

托盘提示显示当前前台应用（或空闲）及今日使用时间最长的应用。
Tooltip shows the current foreground app (or Idle) and today’s top app with minutes.

## 测试(Testing)
单元测试覆盖聚合阈值、别名解析与路径展开，运行：
Unit tests cover aggregation thresholds, alias resolution, and path expansion. Execute:
```powershell
mvn test
```

## 规格说明(Specification)
详细需求与技术设计见`docs/requirements.md`
Detailed requirements and technical design reside in `docs/requirements.md`.
