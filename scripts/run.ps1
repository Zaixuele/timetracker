$jarPath = Join-Path $PSScriptRoot "..\target\timetracker-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path $jarPath)) {
    Write-Host "Jar not found at $jarPath. Build the project first with mvn package." -ForegroundColor Yellow
    exit 1
}
java -jar $jarPath @args
