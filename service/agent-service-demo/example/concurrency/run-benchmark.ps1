param(
    [string]$BaseUrl = "http://localhost:8096",
    [int]$Sessions = 6,
    [int]$Concurrency = 3,
    [bool]$Stream = $false
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ServiceDir = Resolve-Path (Join-Path $ScriptDir "..\..\..")

Write-Host "`n[1/2] Checking health at $BaseUrl"
try {
    $response = Invoke-WebRequest -Uri "$BaseUrl/health" -UseBasicParsing -TimeoutSec 10
    if ($response.StatusCode -ne 200) {
        throw "HTTP $($response.StatusCode)"
    }
} catch {
    Write-Error "FAIL health check. Start: mvn -pl agent-service-demo/example/concurrency -am spring-boot:run"
}

Write-Host "`n[2/2] Running concurrent benchmark (sessions=$Sessions concurrency=$Concurrency stream=$Stream)"
Push-Location $ServiceDir
try {
    & mvn -pl agent-service-demo/example/concurrency -am -q exec:java `
        "-Ddemo.concurrency.mode=query" `
        "-Ddemo.concurrency.base-url=$BaseUrl" `
        "-Ddemo.concurrency.sessions=$Sessions" `
        "-Ddemo.concurrency.concurrency=$Concurrency" `
        "-Ddemo.concurrency.stream=$Stream" `
        "-Ddemo.concurrency.min-success-rate=0.80"
    if ($LASTEXITCODE -ne 0) {
        throw "benchmark exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

Write-Host "`nPASS concurrency benchmark"
