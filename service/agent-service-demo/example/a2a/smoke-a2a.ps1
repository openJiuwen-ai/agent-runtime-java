#!/usr/bin/env pwsh
param(
    [string]$BaseUrlA = "http://localhost:18090",
    [string]$BaseUrlB = "http://localhost:18091",
    [string]$BaseUrlC = "http://localhost:18092",
    [string]$ConvId = "a2a-demo-c1"
)

$ErrorActionPreference = "Stop"
$tmp = New-Item -ItemType Directory -Force -Path (Join-Path $env:TEMP "a2a-smoke-$(Get-Random)")

try {
    Write-Host "`n[1] GET Agent Cards" -ForegroundColor Cyan
    $cardA = Invoke-RestMethod -Uri "$BaseUrlA/.well-known/agent-card.json" -Method Get
    $cardB = Invoke-RestMethod -Uri "$BaseUrlB/.well-known/agent-card.json" -Method Get
    $cardC = Invoke-RestMethod -Uri "$BaseUrlC/.well-known/agent-card.json" -Method Get
    if (-not $cardA.name) { throw "Agent A card missing name" }
    if (-not $cardB.name) { throw "Agent B card missing name" }
    if (-not $cardC.name) { throw "Agent C card missing name" }
    Write-Host "PASS Agent Cards reachable" -ForegroundColor Green

    $convIdB = "$ConvId-agent-b"
    Write-Host "`n[2a] Round 1: trigger original A->B calc delegation (conversation_id=$convIdB)" -ForegroundColor Cyan
    $msgB1 = "What is 1+1? Use Agent B's ordinary calc path."
    $bodyB1 = @{ conversation_id = $convIdB; message = $msgB1; stream = $false } | ConvertTo-Json
    $rb1 = Invoke-RestMethod -Uri "$BaseUrlA/v1/query" -Method Post -Body $bodyB1 -ContentType "application/json"
    $payloadB1 = ($rb1 | ConvertTo-Json -Depth 20)
    if (-not $rb1.result._interrupt) { throw "A->B Round 1 did not return an INPUT_REQUIRED/_interrupt response: $payloadB1" }
    $interruptMessageB1 = [string]$rb1.result._interrupt.message
    $lowerInterruptB1 = $interruptMessageB1.ToLowerInvariant()
    if ($lowerInterruptB1 -notmatch "confirm" -or $lowerInterruptB1 -match "agent c") {
        throw "A->B Round 1 did not use Agent B's ordinary calc confirmation: $payloadB1"
    }
    Write-Host "A->B Round 1 interrupt: $($interruptMessageB1.Substring(0, [Math]::Min(300, $interruptMessageB1.Length)))"
    Write-Host "PASS Original A->B calc path reached Agent B confirmation" -ForegroundColor Green

    Write-Host "`n[2b] Round 2: resume original A->B calc path" -ForegroundColor Cyan
    $bodyB2 = @{ conversation_id = $convIdB; message = "2"; stream = $false } | ConvertTo-Json
    $rb2 = Invoke-RestMethod -Uri "$BaseUrlA/v1/query" -Method Post -Body $bodyB2 -ContentType "application/json"
    $contentB2 = [string]$rb2.result.content
    if (-not $contentB2) { throw "A->B Round 2 empty response" }
    if ($contentB2 -notmatch "2" -or $contentB2 -match "Agent C") { throw "A->B Round 2 did not stay on the ordinary Agent B calc path: $contentB2" }
    Write-Host "A->B Round 2: $($contentB2.Substring(0, [Math]::Min(300, $contentB2.Length)))"
    Write-Host "PASS Original A->B calc path completed" -ForegroundColor Green

    Write-Host "`n[3a] Round 1: trigger A->B->C DeepAgent delegation (conversation_id=$ConvId)" -ForegroundColor Cyan
    $msg1 = "Recommend a dish for a team lunch. Let Agent C provide the food recommendation after confirmation."
    $body1 = @{ conversation_id = $ConvId; message = $msg1; stream = $false } | ConvertTo-Json
    $r1 = Invoke-RestMethod -Uri "$BaseUrlA/v1/query" -Method Post -Body $body1 -ContentType "application/json"
    $payload1 = ($r1 | ConvertTo-Json -Depth 20)
    if (-not $r1.result._interrupt) { throw "A->B->C Round 1 did not return an INPUT_REQUIRED/_interrupt response: $payload1" }
    $interruptMessage1 = [string]$r1.result._interrupt.message
    $lowerInterrupt1 = $interruptMessage1.ToLowerInvariant()
    if ($lowerInterrupt1 -notmatch "agent c" -or ($lowerInterrupt1 -notmatch "confirm" -and $lowerInterrupt1 -notmatch "确认")) {
        throw "A->B->C Round 1 interrupt message did not come from Agent C confirmation: $payload1"
    }
    Write-Host "A->B->C Round 1 interrupt: $($interruptMessage1.Substring(0, [Math]::Min(300, $interruptMessage1.Length)))"
    Write-Host "PASS A->B->C Round 1 reached Agent C confirmation" -ForegroundColor Green

    Write-Host "`n[3b] Round 2: resume A->B->C path with confirmation through A and B" -ForegroundColor Cyan
    $body2 = @{ conversation_id = $ConvId; message = "ok, confirmed"; stream = $false } | ConvertTo-Json
    $r2 = Invoke-RestMethod -Uri "$BaseUrlA/v1/query" -Method Post -Body $body2 -ContentType "application/json"
    $content2 = [string]$r2.result.content
    if (-not $content2) { throw "A->B->C Round 2 empty response" }
    if ($content2 -notmatch "Agent C" -or ($content2 -notmatch "宫保鸡丁" -and $content2 -notmatch "food" -and $content2 -notmatch "dish" -and $content2 -notmatch "recommend")) { throw "A->B->C Round 2 did not include Agent C food recommendation: $content2" }
    Write-Host "A->B->C Round 2: $($content2.Substring(0, [Math]::Min(300, $content2.Length)))"
    Write-Host "PASS A->B->C Round 2 completed after Agent C confirmation" -ForegroundColor Green

    Write-Host "`nA2A demo smoke checks passed against Agent A=$BaseUrlA Agent B=$BaseUrlB Agent C=$BaseUrlC" -ForegroundColor Green
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
