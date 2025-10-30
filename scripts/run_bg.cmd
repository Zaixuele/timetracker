@echo off
setlocal

REM Ensure we are at repo root
cd /d "%~dp0\.."

set "JAR=target\timetracker-0.1.0-SNAPSHOT.jar"

if not exist "%JAR%" (
    echo Building TimeTracker...
    mvn -q -DskipTests package
    if errorlevel 1 (
        echo Build failed.
        pause
        exit /b 1
    )
)

set "JAVA_CMD=javaw"
if defined JAVA_HOME set "JAVA_CMD=%JAVA_HOME%\bin\javaw.exe"

start "" "%JAVA_CMD%" -Djava.awt.headless=false -jar "%JAR%" %*

endlocal
