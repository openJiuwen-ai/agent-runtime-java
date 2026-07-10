# Redis Checkpointer streaming smoke test (PowerShell). Default: http://localhost:8091
param(
    [string]$BaseUrl = "http://127.0.0.1:8091",
    [string]$ConvId = "redis-stream-c1",
    [string]$CodeName = "REDIS-STREAM-42"
)

$ErrorActionPreference = "Stop"

function Write-Step($n, $msg) { Write-Host ""; Write-Host "[$n] $msg" }
function Write-Pass($msg) { Write-Host "PASS $msg" -ForegroundColor Green }
function Write-Fail($msg) {
    Write-Host "FAIL $msg" -ForegroundColor Red
    exit 1
}

function Parse-SseEvents([string]$Raw) {
    $events = @()
    foreach ($line in ($Raw -split "`n")) {
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith("data:")) {
            $json = $trimmed.Substring(5).Trim()
            if ($json.Length -gt 0) {
                $events += ($json | ConvertFrom-Json)
            }
        }
    }
    return $events
}

function Get-SseAggregatedContent($Events) {
    $builder = New-Object System.Text.StringBuilder
    foreach ($event in $Events) {
        $payload = $event.payload
        if ($null -eq $payload) { continue }
        foreach ($key in @("content", "delta", "output", "response")) {
            $value = $payload.$key
            if ($null -ne $value -and "$value".Length -gt 0) {
                [void]$builder.Append([string]$value)
            }
        }
    }
    return $builder.ToString()
}

function Invoke-QueryStream($bodyObj) {
    $json = $bodyObj | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $resp = Invoke-WebRequest -Uri "$BaseUrl/v1/query" -Method Post `
            -ContentType "application/json; charset=utf-8" -Body $bytes -TimeoutSec 180
        if ($resp.Content.Length -eq 0) {
            throw "empty SSE body (check Redis checkpointer logs)"
        }
        if ($resp.Headers["Content-Type"] -notlike "*text/event-stream*") {
            throw "expected text/event-stream, got $($resp.Headers['Content-Type'])"
        }
        return $resp.Content
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        Write-Fail "stream query failed (HTTP $status): $($_.Exception.Message)"
    }
}

function Assert-ExpectedApp($health, $expectedApp) {
    if ($health.app -ne $expectedApp) {
        Write-Fail "health: expected app=$expectedApp, got $($health.app). Start redis module on 8091, not main demo on 8090."
    }
    if ($health.status -ne "healthy" -or $health.agent_loaded -ne $true) {
        Write-Fail "health: service not ready"
    }
}

function Assert-StreamResponse($raw, $label) {
    $events = Parse-SseEvents $raw
    if ($events.Count -lt 1) {
        Write-Fail "${label}: no SSE data events found"
    }
    $content = Get-SseAggregatedContent $events
    if ([string]::IsNullOrWhiteSpace($content)) {
        Write-Fail "${label}: expected non-empty aggregated SSE content"
    }
    return $content
}

Write-Step "1" "GET /health (demo-redis-agent-service on 8091)"
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/health" -Method Get
    Assert-ExpectedApp $health "demo-redis-agent-service"
    Write-Pass "GET /health"
} catch {
    Write-Fail "GET /health: $($_.Exception.Message). Is redis module running on $BaseUrl ?"
}

Write-Step "2" "Round 1 (stream): store code name (conversation_id=$ConvId, code=$CodeName)"
$round1Raw = Invoke-QueryStream @{
    conversation_id = $ConvId
    message         = "Remember my code name is $CodeName. Reply with received only."
    stream          = $true
}
$round1Content = Assert-StreamResponse $round1Raw "round 1 stream"
Write-Pass "round 1 stream (Core SSE path on redis module, $($round1Content.Length) chars aggregated)"

Write-Step "3" "Round 2 (stream): recall code name (Redis checkpointer + SSE)"
$round2Raw = Invoke-QueryStream @{
    conversation_id = $ConvId
    message         = "What code name did I ask you to remember? Answer with the code only."
    stream          = $true
}
$round2Content = Assert-StreamResponse $round2Raw "round 2 stream"
if ($round2Content -notmatch [regex]::Escape($CodeName) -and $round2Content.ToLower() -notmatch $CodeName.ToLower()) {
    Write-Fail "round 2 stream: model did not recall $CodeName. Aggregated response: $round2Content"
}
Write-Pass "round 2 stream recalls context via SSE (multi-turn / checkpointer)"

Write-Host ""
Write-Host "Redis streaming smoke checks passed against $BaseUrl"
