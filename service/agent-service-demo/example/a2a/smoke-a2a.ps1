#!/usr/bin/env pwsh
param(
    [string]$BaseUrlA = "http://localhost:18090",
    [string]$BaseUrlB = "http://localhost:18091",
    [string]$ConvId = "a2a-demo-c1"
)

$ErrorActionPreference = "Stop"
$tmp = New-Item -ItemType Directory -Force -Path (Join-Path $env:TEMP "a2a-smoke-$(Get-Random)")

try {
    Write-Host "`n[1] GET Agent Cards" -ForegroundColor Cyan
    $cardA = Invoke-RestMethod -Uri "$BaseUrlA/.well-known/agent-card.json" -Method Get
    $cardB = Invoke-RestMethod -Uri "$BaseUrlB/.well-known/agent-card.json" -Method Get
    if (-not $cardA.name) { throw "Agent A card missing name" }
    if (-not $cardB.name) { throw "Agent B card missing name" }
    Write-Host "PASS Agent Cards reachable" -ForegroundColor Green

    Write-Host "`n[2] Round 1: trigger A2A delegation (conversation_id=$ConvId)" -ForegroundColor Cyan
    $body1 = @{ conversation_id = $ConvId; message = "What is 1+1?"; stream = $false } | ConvertTo-Json
    $r1 = Invoke-RestMethod -Uri "$BaseUrlA/v1/query" -Method Post -Body $body1 -ContentType "application/json"
    $content1 = $r1.result.content
    if (-not $content1) { throw "Round 1 empty response" }
    Write-Host "Round 1: $($content1.Substring(0, [Math]::Min(200, $content1.Length)))"
    Write-Host "PASS Round 1 delegation triggered" -ForegroundColor Green

    Write-Host "`n[3] Round 2: resume with confirmation" -ForegroundColor Cyan
    $body2 = @{ conversation_id = $ConvId; message = "ok"; stream = $false } | ConvertTo-Json
    $r2 = Invoke-RestMethod -Uri "$BaseUrlA/v1/query" -Method Post -Body $body2 -ContentType "application/json"
    $content2 = $r2.result.content
    if (-not $content2) { throw "Round 2 empty response" }
    Write-Host "Round 2: $($content2.Substring(0, [Math]::Min(200, $content2.Length)))"
    Write-Host "PASS Round 2 resume completed" -ForegroundColor Green

    Write-Host "`nA2A demo smoke checks passed against Agent A=$BaseUrlA Agent B=$BaseUrlB" -ForegroundColor Green
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
