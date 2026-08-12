param(
    [string]$BaseUrl = "http://127.0.0.1:8095"
)

$ErrorActionPreference = "Stop"

Write-Host "== Security demo smoke: $BaseUrl =="

$health = Invoke-RestMethod -Uri "$BaseUrl/health" -Method Get
Write-Host "health.app = $($health.app)"

$denyBody = @{
    conversation_id = "smoke-sec-deny"
    message         = "hello"
    stream          = $false
} | ConvertTo-Json -Compress

try {
    Invoke-RestMethod -Uri "$BaseUrl/v1/query" -Method Post -ContentType "application/json" -Body $denyBody
    Write-Error "Expected 403 without X-User-ID"
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status -ne 403) {
        throw
    }
    Write-Host "OK: POST /v1/query without X-User-ID -> 403"
}

Write-Host "Smoke passed (403 contract verified). For allow path, retry with -H X-User-ID and configured LLM."
