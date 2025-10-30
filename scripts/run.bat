@echo off
setlocal
set JAR=%~dp0..\target\timetracker-0.1.0-SNAPSHOT.jar
if not exist "%JAR%" (
    echo Jar not found at %JAR%. Build the project first with mvn package.
    exit /b 1
)
java -jar "%JAR%" %*
