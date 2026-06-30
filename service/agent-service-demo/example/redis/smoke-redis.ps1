# Redis Checkpointer smoke test (PowerShell). Default: http://localhost:8091
param(
    [string]$BaseUrl = "http://127.0.0.1:8091",
    [string]$ConvId = "redis-demo-c1"
)

$ErrorActionPreference = "Stop"

function Write-Step($n, $msg) { Write-Host ""; Write-Host "[$n] $msg" }
function Write-Pass($msg) { Write-Host "PASS $msg" -ForegroundColor Green }
function Write-Fail($msg) {
    Write-Host "FAIL $msg" -ForegroundColor Red
    exit 1
}

function Invoke-QueryJson($bodyObj) {
    $json = $bodyObj | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        $resp = Invoke-WebRequest -Uri "$BaseUrl/v1/query" -Method Post `
            -ContentType "application/json; charset=utf-8" -Body $bytes
        if ($resp.Content.Length -eq 0) {
            throw "empty response body (check Redis checkpointer logs)"
        }
        return $resp.Content | ConvertFrom-Json
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        Write-Fail "query failed (HTTP $status): $($_.Exception.Message)"
    }
}

function Get-AssistantContent($payload) {
    $content = $payload.result.content
    if ($null -eq $content) { return "" }
    if ($content -is [string]) { return $content }
    return ($content | ConvertTo-Json -Compress)
}

function Assert-NotMock($content, $label) {
    if ($content -like "demo:*") {
        Write-Fail "${label}: got mock response. Start redis module on 8091, not main demo on 8090."
    }
}

Write-Step "1" "GET /health"
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/health" -Method Get
    if ($health.status -ne "healthy") { Write-Fail "GET /health: status not healthy" }
    Write-Pass "GET /health"
} catch {
    Write-Fail "GET /health: $($_.Exception.Message). Is redis module running on $BaseUrl ?"
}

Write-Step "2" "Round 1: store a fact (conversation_id=$ConvId)"
$r1 = Invoke-QueryJson @{
    conversation_id = $ConvId
    message         = "Remember my code name is REDIS-DEMO-42. Reply with received only."
    stream          = $false
}
$c1 = Get-AssistantContent $r1
Assert-NotMock $c1 "round 1"
Write-Pass "round 1 (Core path, not mock)"

Write-Step "3" "Round 2: recall the fact (Redis checkpointer)"
$r2 = Invoke-QueryJson @{
    conversation_id = $ConvId
    message         = "What code name did I ask you to remember? Answer with the code only."
    stream          = $false
}
$c2 = Get-AssistantContent $r2
Assert-NotMock $c2 "round 2"
if ($c2 -notmatch "REDIS-DEMO-42" -and $c2.ToLower() -notmatch "redis-demo-42") {
    Write-Fail "round 2: model did not recall REDIS-DEMO-42. Response: $c2"
}
Write-Pass "round 2 recalls context (multi-turn / checkpointer)"

Write-Host ""
Write-Host "Redis example smoke checks passed against $BaseUrl"
