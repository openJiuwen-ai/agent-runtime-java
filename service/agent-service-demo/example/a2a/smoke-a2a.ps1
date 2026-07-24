#!/usr/bin/env pwsh
param(
    [string]$BaseUrlA = "http://localhost:18090",
    [string]$BaseUrlB = "http://localhost:18091",
    [string]$BaseUrlC = "http://localhost:18092",
    [string]$ConvId = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$callerDirectory = (Get-Location).ProviderPath
$serviceDirectory = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "../../..")).ProviderPath
$defaultApiConfig = Join-Path (Join-Path $serviceDirectory "agent-service-demo") "apiconfig.json"
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) "a2a-smoke-$([guid]::NewGuid().ToString('N'))"
$null = New-Item -ItemType Directory -Force -Path $tmp

if ([string]::IsNullOrWhiteSpace($ConvId)) {
    $ConvId = "a2a-demo-$((Get-Date).ToString('yyyyMMddHHmmss'))-$PID"
}

function Write-Step {
    param([string]$Number, [string]$Message)
    Write-Host "`n[$Number] $Message" -ForegroundColor Cyan
}

function Write-Pass {
    param([string]$Message)
    Write-Host "PASS $Message" -ForegroundColor Green
}

function Invoke-Utf8JsonRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Uri,
        [ValidateSet("GET", "POST")]
        [string]$Method = "GET",
        [AllowNull()]
        [string]$Body = $null,
        [ValidateRange(1, 2147483647)]
        [int]$TimeoutSec = 30
    )

    $client = $null
    $request = $null
    $response = $null
    try {
        $client = [System.Net.Http.HttpClient]::new()
        $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::new($Method),
            $Uri
        )
        $request.Headers.Accept.Add(
            [System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("application/json")
        )
        if ($null -ne $Body) {
            $request.Content = [System.Net.Http.StringContent]::new(
                $Body,
                [System.Text.Encoding]::UTF8,
                "application/json"
            )
        }

        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        # Windows PowerShell 5.1 misdecodes charset-less UTF-8 JSON when Invoke-RestMethod is used.
        $responseText = [System.Text.Encoding]::UTF8.GetString($bytes)
        if (-not $response.IsSuccessStatusCode) {
            throw "HTTP $([int]$response.StatusCode) $($response.ReasonPhrase) from ${Uri}: $responseText"
        }
        if ([string]::IsNullOrWhiteSpace($responseText)) {
            return $null
        }
        return $responseText | ConvertFrom-Json
    } finally {
        if ($null -ne $response) {
            $response.Dispose()
        }
        if ($null -ne $request) {
            $request.Dispose()
        }
        if ($null -ne $client) {
            $client.Dispose()
        }
    }
}

function Save-JsonResponse {
    param([object]$Value, [string]$FileName)
    $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $tmp $FileName) -Encoding utf8
}

function Resolve-ApiConfig {
    param([string]$Configured, [string]$BaseDirectory)
    $candidate = if ([System.IO.Path]::IsPathRooted($Configured)) {
        $Configured
    } else {
        Join-Path $BaseDirectory $Configured
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "OPENJIUWEN_API_CONFIG does not reference a readable regular file: $candidate"
    }
    $resolved = (Resolve-Path -LiteralPath $candidate).ProviderPath
    try {
        $stream = [System.IO.File]::Open(
            $resolved,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite
        )
        $stream.Dispose()
    } catch {
        throw "OPENJIUWEN_API_CONFIG does not reference a readable regular file: $resolved"
    }
    return $resolved
}

function Start-Agent {
    param(
        [string]$Label,
        [string]$MainClass,
        [string]$LogPrefix,
        [string]$MavenCommand
    )
    $stdoutLog = Join-Path $tmp "$LogPrefix.log"
    $stderrLog = Join-Path $tmp "$LogPrefix.err.log"
    $arguments = @(
        "-pl",
        "agent-service-demo/example/a2a",
        "-am",
        "spring-boot:run",
        "-q",
        "-Dspring-boot.run.main-class=$MainClass"
    )
    $process = Start-Process -FilePath $MavenCommand -ArgumentList $arguments `
        -WorkingDirectory $serviceDirectory -NoNewWindow -PassThru `
        -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog
    return [pscustomobject]@{
        Label = $Label
        Process = $process
        StdoutLog = $stdoutLog
        StderrLog = $stderrLog
    }
}

function Wait-ForHealth {
    param(
        [string]$Url,
        [pscustomobject]$AgentRun
    )
    $max = 45
    for ($i = 1; $i -le $max; $i++) {
        $AgentRun.Process.Refresh()
        if ($AgentRun.Process.HasExited) {
            throw "$($AgentRun.Label) exited before becoming healthy (exit code $($AgentRun.Process.ExitCode)); logs: $($AgentRun.StdoutLog), $($AgentRun.StderrLog)"
        }
        try {
            $health = Invoke-Utf8JsonRequest -Uri "$Url/health" -Method GET -TimeoutSec 2
            if ($health.status -eq "healthy") {
                return
            }
        } catch {
            # Service is still starting.
        }
        Start-Sleep -Seconds 2
    }
    throw "$($AgentRun.Label) did not become healthy within $($max * 2)s"
}

function Stop-Agent {
    param([pscustomobject]$AgentRun)
    if ($null -eq $AgentRun) {
        return
    }
    $AgentRun.Process.Refresh()
    if ($AgentRun.Process.HasExited) {
        return
    }
    if ($env:OS -eq "Windows_NT" -and (Get-Command taskkill -ErrorAction SilentlyContinue)) {
        & taskkill /PID $AgentRun.Process.Id /T /F *> $null
    } else {
        Stop-Process -Id $AgentRun.Process.Id -Force -ErrorAction SilentlyContinue
    }
}

$apiConfigWasSet = Test-Path Env:OPENJIUWEN_API_CONFIG
$originalApiConfig = $env:OPENJIUWEN_API_CONFIG
$agentA = $null
$agentB = $null
$agentC = $null
$succeeded = $false

try {
    if ($env:OPENJIUWEN_API_CONFIG) {
        $env:OPENJIUWEN_API_CONFIG = Resolve-ApiConfig $env:OPENJIUWEN_API_CONFIG $callerDirectory
    } elseif (Test-Path -LiteralPath $defaultApiConfig -PathType Leaf) {
        $env:OPENJIUWEN_API_CONFIG = $defaultApiConfig
    }

    $mavenCommand = (Get-Command mvn -ErrorAction Stop).Source
    Set-Location $serviceDirectory

    Write-Step "0a" "Starting Agent C (DeepAgent, port 18092) ..."
    $agentC = Start-Agent "Agent C" "com.openjiuwen.service.demo.example.a2a.A2aAgentCDemoApplication" `
        "agent-c" $mavenCommand
    Wait-ForHealth $BaseUrlC $agentC
    Write-Pass "Agent C healthy on $BaseUrlC"

    Write-Step "0b" "Starting Agent B (port 18091) ..."
    $agentB = Start-Agent "Agent B" "com.openjiuwen.service.demo.example.a2a.A2aAgentBDemoApplication" `
        "agent-b" $mavenCommand
    Wait-ForHealth $BaseUrlB $agentB
    Write-Pass "Agent B healthy on $BaseUrlB"

    Write-Step "0c" "Starting Agent A (port 18090) ..."
    $agentA = Start-Agent "Agent A" "com.openjiuwen.service.demo.example.a2a.A2aAgentADemoApplication" `
        "agent-a" $mavenCommand
    Wait-ForHealth $BaseUrlA $agentA
    Write-Pass "Agent A healthy on $BaseUrlA"

    Write-Step "1" "GET Agent Cards"
    $cardA = Invoke-Utf8JsonRequest -Uri "$BaseUrlA/.well-known/agent-card.json" -Method GET
    $cardB = Invoke-Utf8JsonRequest -Uri "$BaseUrlB/.well-known/agent-card.json" -Method GET
    $cardC = Invoke-Utf8JsonRequest -Uri "$BaseUrlC/.well-known/agent-card.json" -Method GET
    Save-JsonResponse $cardA "card-a.json"
    Save-JsonResponse $cardB "card-b.json"
    Save-JsonResponse $cardC "card-c.json"
    if (-not $cardA.name) { throw "Agent A card missing name" }
    if (-not $cardB.name) { throw "Agent B card missing name" }
    if (-not $cardC.name) { throw "Agent C card missing name" }
    Write-Pass "Agent Cards reachable"

    $convIdB = "$ConvId-agent-b"
    Write-Step "2a" "Round 1: trigger original A->B calc delegation (conversation_id=$convIdB)"
    $bodyB1 = @{
        conversation_id = $convIdB
        message = "What is 1+1? Use Agent B's ordinary calc path."
        stream = $false
    } | ConvertTo-Json -Compress
    $rb1 = Invoke-Utf8JsonRequest -Uri "$BaseUrlA/v1/query" -Method POST -Body $bodyB1
    Save-JsonResponse $rb1 "round-b-1.json"
    $payloadB1 = $rb1 | ConvertTo-Json -Depth 20
    if (-not $rb1.result._interrupt) {
        throw "A->B Round 1 did not return an INPUT_REQUIRED/_interrupt response: $payloadB1"
    }
    $interruptMessageB1 = [string]$rb1.result._interrupt.message
    $lowerInterruptB1 = $interruptMessageB1.ToLowerInvariant()
    if ($lowerInterruptB1 -notmatch "confirm" -or $lowerInterruptB1 -match "agent c") {
        throw "A->B Round 1 did not use Agent B's ordinary calc confirmation: $payloadB1"
    }
    Write-Host "A->B Round 1 interrupt: $($interruptMessageB1.Substring(0, [Math]::Min(300, $interruptMessageB1.Length)))"
    Write-Pass "Original A->B calc path reached Agent B confirmation"

    Write-Step "2b" "Round 2: resume original A->B calc path"
    $bodyB2 = @{ conversation_id = $convIdB; message = "2"; stream = $false } | ConvertTo-Json -Compress
    $rb2 = Invoke-Utf8JsonRequest -Uri "$BaseUrlA/v1/query" -Method POST -Body $bodyB2
    Save-JsonResponse $rb2 "round-b-2.json"
    $contentB2 = [string]$rb2.result.content
    if (-not $contentB2) { throw "A->B Round 2 empty response" }
    if ($contentB2 -notmatch "2" -or $contentB2 -match "Agent C") {
        throw "A->B Round 2 did not stay on the ordinary Agent B calc path: $contentB2"
    }
    Write-Host "A->B Round 2: $($contentB2.Substring(0, [Math]::Min(300, $contentB2.Length)))"
    Write-Pass "Original A->B calc path completed"

    Write-Step "3a" "Round 1: trigger A->B->C delegation (conversation_id=$ConvId)"
    $body1 = @{
        conversation_id = $ConvId
        message = "Recommend a dish for a team lunch. Let Agent C provide the food recommendation after confirmation."
        stream = $false
    } | ConvertTo-Json -Compress
    $r1 = Invoke-Utf8JsonRequest -Uri "$BaseUrlA/v1/query" -Method POST -Body $body1
    Save-JsonResponse $r1 "round1.json"
    $payload1 = $r1 | ConvertTo-Json -Depth 20
    if (-not $r1.result._interrupt) {
        throw "A->B->C Round 1 did not return an INPUT_REQUIRED/_interrupt response: $payload1"
    }
    $interruptMessage1 = [string]$r1.result._interrupt.message
    $lowerInterrupt1 = $interruptMessage1.ToLowerInvariant()
    if ($lowerInterrupt1 -notmatch "agent c" -or `
            ($lowerInterrupt1 -notmatch "confirm" -and $lowerInterrupt1 -notmatch "确认")) {
        throw "A->B->C Round 1 interrupt message did not come from Agent C confirmation: $payload1"
    }
    Write-Host "A->B->C Round 1 interrupt: $($interruptMessage1.Substring(0, [Math]::Min(300, $interruptMessage1.Length)))"
    Write-Pass "A->B->C path reached Agent C confirmation"

    Write-Step "3b" "Round 2: resume A->B->C delegation"
    $body2 = @{ conversation_id = $ConvId; message = "ok, confirmed"; stream = $false } | ConvertTo-Json -Compress
    $r2 = Invoke-Utf8JsonRequest -Uri "$BaseUrlA/v1/query" -Method POST -Body $body2
    Save-JsonResponse $r2 "round2.json"
    $payload2 = $r2 | ConvertTo-Json -Depth 20
    if ($r2.result._interrupt) {
        throw "A->B->C Round 2 remained interrupted after confirmation: $payload2"
    }
    $content2 = [string]$r2.result.content
    if (-not $content2) { throw "A->B->C Round 2 empty response" }
    $lowerContent2 = $content2.ToLowerInvariant()
    $hasRecommendation = $content2.Contains("宫保鸡丁") -or $lowerContent2.Contains("kung pao")
    $failurePattern = "unavailable|unable|failed|failure|error|remote_"
    if (-not $lowerContent2.Contains("agent c") -or -not $hasRecommendation -or `
            $lowerContent2 -match $failurePattern) {
        throw "A->B->C Round 2 did not return a successful Agent C result: $content2"
    }
    Write-Host "A->B->C Round 2: $($content2.Substring(0, [Math]::Min(300, $content2.Length)))"
    Write-Pass "A->B->C path resumed and completed"

    Write-Host "`nA2A demo smoke checks passed against Agent A=$BaseUrlA Agent B=$BaseUrlB Agent C=$BaseUrlC" `
        -ForegroundColor Green
    $succeeded = $true
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Logs and responses are in $tmp" -ForegroundColor Red
} finally {
    Stop-Agent $agentA
    Stop-Agent $agentB
    Stop-Agent $agentC
    Set-Location $callerDirectory
    if ($apiConfigWasSet) {
        $env:OPENJIUWEN_API_CONFIG = $originalApiConfig
    } else {
        Remove-Item Env:OPENJIUWEN_API_CONFIG -ErrorAction SilentlyContinue
    }
    if ($succeeded) {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    } else {
        Write-Host "`nLogs and responses retained in $tmp" -ForegroundColor Red
    }
}

if (-not $succeeded) {
    exit 1
}
