#!/usr/bin/env pwsh
param(
    [string]$BaseUrlA = "http://localhost:18090",
    [string]$BaseUrlB = "http://localhost:18091",
    [string]$BaseUrlC = "http://localhost:18092",
    [string]$BaseUrlD = "http://localhost:18093",
    [ValidateRange(1, 2147483647)]
    [int]$A2aRequestTimeoutSec = 300,
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
        if ($Method -eq "POST" -and $PSBoundParameters.ContainsKey("Body")) {
            $request.Content = [System.Net.Http.StringContent]::new(
                $Body,
                [System.Text.Encoding]::UTF8,
                "application/json"
            )
        }

        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        # Windows PowerShell 5.1 may misdecode charset-less UTF-8 JSON.
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

function Invoke-Utf8TextRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Uri,
        [Parameter(Mandatory = $true)]
        [string]$Body,
        [string]$Accept = "text/event-stream",
        [ValidateRange(1, 2147483647)]
        [int]$TimeoutSec = 300
    )

    $client = $null
    $request = $null
    $response = $null
    try {
        $client = [System.Net.Http.HttpClient]::new()
        $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Post,
            $Uri
        )
        $request.Headers.Accept.Add(
            [System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new($Accept)
        )
        $request.Content = [System.Net.Http.StringContent]::new(
            $Body,
            [System.Text.Encoding]::UTF8,
            "application/json"
        )
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $responseText = [System.Text.Encoding]::UTF8.GetString($bytes)
        if (-not $response.IsSuccessStatusCode) {
            throw "HTTP $([int]$response.StatusCode) $($response.ReasonPhrase) from ${Uri}: $responseText"
        }
        return $responseText
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

function New-A2aRequestBody {
    param(
        [string]$Method,
        [string]$RequestId,
        [string]$ContextId,
        [string]$TaskId,
        [string]$Message
    )
    $requestMessage = [ordered]@{
        role = "ROLE_USER"
        contextId = $ContextId
        parts = @(@{ text = $Message })
    }
    if (-not [string]::IsNullOrWhiteSpace($TaskId)) {
        $requestMessage.taskId = $TaskId
    }
    return [ordered]@{
        jsonrpc = "2.0"
        id = $RequestId
        method = $Method
        params = @{ message = $requestMessage }
    } | ConvertTo-Json -Depth 10 -Compress
}

function Read-SseTask {
    param(
        [string]$Content,
        [string]$ExpectedState,
        [string]$ExpectedText = "",
        [string]$SecondExpectedText = "",
        [string]$ThirdExpectedText = ""
    )
    $events = @()
    foreach ($line in ($Content -split "`r?`n")) {
        if ($line.StartsWith("data:")) {
            $events += ($line.Substring(5).Trim() | ConvertFrom-Json)
        }
    }
    if ($events.Count -eq 0) {
        throw "SSE response contained no JSON-RPC data events"
    }
    $states = @()
    $taskIds = @()
    foreach ($event in $events) {
        if ($event.result.statusUpdate) {
            $states += [string]$event.result.statusUpdate.status.state
            $taskIds += [string]$event.result.statusUpdate.taskId
        } elseif ($event.result.artifactUpdate) {
            $taskIds += [string]$event.result.artifactUpdate.taskId
        }
    }
    if ($states -notcontains $ExpectedState) {
        throw "SSE response did not reach $ExpectedState; states=$($states -join ',')"
    }
    $stableTaskIds = @($taskIds | Where-Object { $_ } | Select-Object -Unique)
    if ($stableTaskIds.Count -ne 1) {
        throw "SSE response did not contain one stable taskId: $($taskIds -join ',')"
    }
    $payload = $events | ConvertTo-Json -Depth 50 -Compress
    $lowerPayload = $payload.ToLowerInvariant()
    if (-not $lowerPayload.Contains("_remote_invocation")) {
        throw "SSE response did not contain remote-agent progress"
    }
    foreach ($expected in @($ExpectedText, $SecondExpectedText, $ThirdExpectedText)) {
        if ($expected -and -not $lowerPayload.Contains($expected.ToLowerInvariant())) {
            throw "SSE response did not contain expected text: $expected"
        }
    }
    return [pscustomobject]@{ TaskId = $stableTaskIds[0]; State = $ExpectedState; Payload = $payload }
}

function Read-SyncTask {
    param(
        [object]$Response,
        [string]$ExpectedState,
        [string]$ExpectedText = "",
        [string]$SecondExpectedText = "",
        [string]$ThirdExpectedText = ""
    )
    if ($Response.error) {
        throw "JSON-RPC error: $($Response.error | ConvertTo-Json -Depth 10 -Compress)"
    }
    $task = $Response.result.task
    if ([string]$task.status.state -ne $ExpectedState) {
        throw "synchronous response state was $($task.status.state), expected $ExpectedState"
    }
    if (-not $task.id) {
        throw "synchronous response did not contain task.id"
    }
    $payload = $Response | ConvertTo-Json -Depth 50 -Compress
    $lowerPayload = $payload.ToLowerInvariant()
    if ($lowerPayload.Contains("_remote_invocation")) {
        throw "synchronous response unexpectedly contained remote-agent progress"
    }
    foreach ($expected in @($ExpectedText, $SecondExpectedText, $ThirdExpectedText)) {
        if ($expected -and -not $lowerPayload.Contains($expected.ToLowerInvariant())) {
            throw "synchronous response did not contain expected text: $expected"
        }
    }
    return [pscustomobject]@{ TaskId = [string]$task.id; State = $ExpectedState; Payload = $payload }
}

function Assert-PlainTerminalArtifacts {
    param(
        [object[]]$Artifacts,
        [string]$RawResponse = ""
    )
    if ($RawResponse.Contains("\u003d")) {
        throw "response contains HTML-escaped equals signs (\u003d)"
    }
    foreach ($artifact in $Artifacts) {
        foreach ($part in @($artifact.parts)) {
            if ($null -eq $part.text) {
                continue
            }
            try {
                $envelope = [string]$part.text | ConvertFrom-Json -ErrorAction Stop
            } catch {
                continue
            }
            if ($envelope -is [System.Management.Automation.PSCustomObject] -or $envelope -is [array]) {
                throw "structured JSON leaked into parts.text instead of parts.data"
            }
        }
    }
}

function Assert-CalculationResult {
    param(
        [object]$Response,
        [string]$ExpectedResult
    )
    $texts = @(
        foreach ($artifact in @($Response.result.task.artifacts)) {
            foreach ($part in @($artifact.parts)) {
                if ($null -ne $part.text) { [string]$part.text }
            }
        }
    )
    if ($texts | Where-Object { $_.Trim().ToLowerInvariant() -eq "ok" }) {
        throw "calculator returned the confirmation text instead of a result"
    }
    $escapedResult = [regex]::Escape($ExpectedResult)
    if (-not ($texts | Where-Object { $_ -match "(?:^|\D)$escapedResult(?:\D|$)" })) {
        throw "calculator artifacts did not contain result $ExpectedResult"
    }
}

function Assert-LogContains {
    param([string]$LogFile, [string]$Expected)
    if (-not (Select-String -LiteralPath $LogFile -SimpleMatch $Expected -Quiet)) {
        throw "log $LogFile did not contain: $Expected"
    }
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
$agentD = $null
$succeeded = $false

try {
    if ($env:OPENJIUWEN_API_CONFIG) {
        $env:OPENJIUWEN_API_CONFIG = Resolve-ApiConfig $env:OPENJIUWEN_API_CONFIG $callerDirectory
    } elseif (Test-Path -LiteralPath $defaultApiConfig -PathType Leaf) {
        $env:OPENJIUWEN_API_CONFIG = $defaultApiConfig
    }

    $mavenCommand = (Get-Command mvn -ErrorAction Stop).Source
    Set-Location $serviceDirectory

    Write-Step "0a" "Starting Agent D (expense WorkflowAgent, port 18093) ..."
    $agentD = Start-Agent "Agent D" "com.openjiuwen.service.demo.example.a2a.A2aAgentDDemoApplication" `
        "agent-d" $mavenCommand
    Wait-ForHealth $BaseUrlD $agentD
    Write-Pass "Agent D healthy on $BaseUrlD"

    Write-Step "0b" "Starting Agent C (DeepAgent, port 18092) ..."
    $agentC = Start-Agent "Agent C" "com.openjiuwen.service.demo.example.a2a.A2aAgentCDemoApplication" `
        "agent-c" $mavenCommand
    Wait-ForHealth $BaseUrlC $agentC
    Write-Pass "Agent C healthy on $BaseUrlC"

    Write-Step "0c" "Starting Agent B (port 18091) ..."
    $agentB = Start-Agent "Agent B" "com.openjiuwen.service.demo.example.a2a.A2aAgentBDemoApplication" `
        "agent-b" $mavenCommand
    Wait-ForHealth $BaseUrlB $agentB
    Write-Pass "Agent B healthy on $BaseUrlB"

    Write-Step "0d" "Starting Agent A (port 18090) ..."
    $agentA = Start-Agent "Agent A" "com.openjiuwen.service.demo.example.a2a.A2aAgentADemoApplication" `
        "agent-a" $mavenCommand
    Wait-ForHealth $BaseUrlA $agentA
    Write-Pass "Agent A healthy on $BaseUrlA"

    Write-Step "1" "GET Agent Cards"
    $cardA = Invoke-Utf8JsonRequest -Uri "$BaseUrlA/.well-known/agent-card.json" -Method GET
    $cardB = Invoke-Utf8JsonRequest -Uri "$BaseUrlB/.well-known/agent-card.json" -Method GET
    $cardC = Invoke-Utf8JsonRequest -Uri "$BaseUrlC/.well-known/agent-card.json" -Method GET
    $cardD = Invoke-Utf8JsonRequest -Uri "$BaseUrlD/.well-known/agent-card.json" -Method GET
    Save-JsonResponse $cardA "card-a.json"
    Save-JsonResponse $cardB "card-b.json"
    Save-JsonResponse $cardC "card-c.json"
    Save-JsonResponse $cardD "card-d.json"
    if (-not $cardA.name) { throw "Agent A card missing name" }
    if (-not $cardB.name) { throw "Agent B card missing name" }
    if (-not $cardC.name) { throw "Agent C card missing name" }
    if (-not $cardD.name) { throw "Agent D card missing name" }
    Write-Pass "Agent Cards reachable"

    $calcContext = "$ConvId-calc"
    Write-Step "2a" "Round 1: trigger A->B calculator through SendMessage"
    $calcBody1 = New-A2aRequestBody "SendMessage" "calc-1" $calcContext "" `
        "Please calculate 1+1 through Agent B."
    $calcResponse1 = Invoke-Utf8JsonRequest -Uri "$BaseUrlA/a2a/" -Method POST `
        -Body $calcBody1 -TimeoutSec $A2aRequestTimeoutSec
    Save-JsonResponse $calcResponse1 "calc-response-1.json"
    $calcTask1 = Read-SyncTask $calcResponse1 "TASK_STATE_INPUT_REQUIRED" "reply yes or no"
    Write-Pass "A->B calculator reached confirmation (taskId=$($calcTask1.TaskId))"

    Write-Step "2b" "Round 2: resume the same A->B calculator task"
    $calcBody2 = New-A2aRequestBody "SendMessage" "calc-2" $calcContext $calcTask1.TaskId "ok"
    $calcResponse2 = Invoke-Utf8JsonRequest -Uri "$BaseUrlA/a2a/" -Method POST `
        -Body $calcBody2 -TimeoutSec $A2aRequestTimeoutSec
    Save-JsonResponse $calcResponse2 "calc-response-2.json"
    $calcTask2 = Read-SyncTask $calcResponse2 "TASK_STATE_COMPLETED"
    Assert-CalculationResult $calcResponse2 "2"
    if ($calcTask2.TaskId -ne $calcTask1.TaskId) {
        throw "A->B calculator resume changed taskId from $($calcTask1.TaskId) to $($calcTask2.TaskId)"
    }
    Write-Pass "A->B calculator resumed and completed"

    $cStreamContext = "$ConvId-c-stream"
    $cStreamMessage = "Recommend a team lunch dish through Agent C in streaming mode. " + `
        "Agent C must ask for confirmation."
    Write-Step "3a" "Round 1: trigger Agent C through the streaming route"
    $cStreamBody1 = New-A2aRequestBody "SendStreamingMessage" "c-stream-1" `
        $cStreamContext "" $cStreamMessage
    $cStreamResponse1 = Invoke-Utf8TextRequest -Uri "$BaseUrlA/a2a/" -Body $cStreamBody1 `
        -TimeoutSec $A2aRequestTimeoutSec
    $cStreamResponse1 | Set-Content -LiteralPath (Join-Path $tmp "c-stream-response-1.txt") -Encoding utf8
    $cStreamTask1 = Read-SseTask $cStreamResponse1 "TASK_STATE_INPUT_REQUIRED" "agent c" "confirm"
    Write-Pass "Agent C streaming route reached confirmation (taskId=$($cStreamTask1.TaskId))"

    Write-Step "3b" "Round 2: resume the same Agent C streaming task"
    $cStreamBody2 = New-A2aRequestBody "SendStreamingMessage" "c-stream-2" `
        $cStreamContext $cStreamTask1.TaskId "approved"
    $cStreamResponse2 = Invoke-Utf8TextRequest -Uri "$BaseUrlA/a2a/" -Body $cStreamBody2 `
        -TimeoutSec $A2aRequestTimeoutSec
    $cStreamResponse2 | Set-Content -LiteralPath (Join-Path $tmp "c-stream-response-2.txt") -Encoding utf8
    $cStreamTask2 = Read-SseTask $cStreamResponse2 "TASK_STATE_COMPLETED" "agent c" "kung pao chicken"
    if ($cStreamTask2.TaskId -ne $cStreamTask1.TaskId) {
        throw "Agent C streaming resume changed taskId from $($cStreamTask1.TaskId) to $($cStreamTask2.TaskId)"
    }
    Write-Pass "Agent C streaming route resumed and completed"

    $cNonstreamContext = "$ConvId-c-nonstream"
    $cNonstreamMessage = "Recommend a team lunch dish through Agent C in non-streaming mode. " + `
        "Agent C must ask for confirmation."
    Write-Step "4a" "Round 1: trigger Agent C through the non-streaming route"
    $cNonstreamBody1 = New-A2aRequestBody "SendMessage" "c-nonstream-1" `
        $cNonstreamContext "" $cNonstreamMessage
    $cNonstreamResponse1 = Invoke-Utf8JsonRequest -Uri "$BaseUrlA/a2a/" -Method POST `
        -Body $cNonstreamBody1 -TimeoutSec $A2aRequestTimeoutSec
    Save-JsonResponse $cNonstreamResponse1 "c-nonstream-response-1.json"
    $cNonstreamTask1 = Read-SyncTask $cNonstreamResponse1 "TASK_STATE_INPUT_REQUIRED" "agent c" "confirm"
    Write-Pass "Agent C non-streaming route reached confirmation (taskId=$($cNonstreamTask1.TaskId))"

    Write-Step "4b" "Round 2: resume the same Agent C non-streaming task"
    $cNonstreamBody2 = New-A2aRequestBody "SendMessage" "c-nonstream-2" `
        $cNonstreamContext $cNonstreamTask1.TaskId "approved"
    $cNonstreamResponse2 = Invoke-Utf8JsonRequest -Uri "$BaseUrlA/a2a/" -Method POST `
        -Body $cNonstreamBody2 -TimeoutSec $A2aRequestTimeoutSec
    Save-JsonResponse $cNonstreamResponse2 "c-nonstream-response-2.json"
    $cNonstreamTask2 = Read-SyncTask $cNonstreamResponse2 "TASK_STATE_COMPLETED" `
        "agent c" "kung pao chicken"
    if ($cNonstreamTask2.TaskId -ne $cNonstreamTask1.TaskId) {
        throw "Agent C non-streaming resume changed taskId from $($cNonstreamTask1.TaskId) to $($cNonstreamTask2.TaskId)"
    }
    Write-Pass "Agent C non-streaming route resumed and completed"

    $dStreamContext = "$ConvId-d-stream"
    $dStreamClaim = "WF-STREAM-001"
    $dStreamMessage = "Review expense claim $dStreamClaim through Agent D in streaming mode: category hotel, " + `
        "3 nights, unit_price 1000 CNY, total 3000 CNY, currency CNY. Preserve every value exactly."
    Write-Step "5a" "Round 1: trigger Agent D through the streaming route"
    $dStreamBody1 = New-A2aRequestBody "SendStreamingMessage" "d-stream-1" `
        $dStreamContext "" $dStreamMessage
    $dStreamResponse1 = Invoke-Utf8TextRequest -Uri "$BaseUrlA/a2a/" -Body $dStreamBody1 `
        -TimeoutSec $A2aRequestTimeoutSec
    $dStreamResponse1 | Set-Content -LiteralPath (Join-Path $tmp "d-stream-response-1.txt") -Encoding utf8
    $dStreamTask1 = Read-SseTask $dStreamResponse1 "TASK_STATE_INPUT_REQUIRED" `
        "manual approval" $dStreamClaim
    Write-Pass "Agent D streaming route reached manual approval (taskId=$($dStreamTask1.TaskId))"

    Write-Step "5b" "Round 2: approve and resume the same Agent D streaming task"
    $dStreamBody2 = New-A2aRequestBody "SendStreamingMessage" "d-stream-2" `
        $dStreamContext $dStreamTask1.TaskId "approved"
    $dStreamResponse2 = Invoke-Utf8TextRequest -Uri "$BaseUrlA/a2a/" -Body $dStreamBody2 `
        -TimeoutSec $A2aRequestTimeoutSec
    $dStreamResponse2 | Set-Content -LiteralPath (Join-Path $tmp "d-stream-response-2.txt") -Encoding utf8
    $dStreamTask2 = Read-SseTask $dStreamResponse2 "TASK_STATE_COMPLETED" `
        "agent d expense review completed" $dStreamClaim "llm_report="
    $dStreamArtifacts = @(
        foreach ($line in ($dStreamResponse2 -split "`r?`n")) {
            if ($line.StartsWith("data:")) {
                $event = $line.Substring(5).Trim() | ConvertFrom-Json
                if ($event.result.artifactUpdate) { $event.result.artifactUpdate.artifact }
            }
        }
    )
    Assert-PlainTerminalArtifacts $dStreamArtifacts $dStreamResponse2
    if ($dStreamTask2.TaskId -ne $dStreamTask1.TaskId) {
        throw "Agent D streaming resume changed taskId from $($dStreamTask1.TaskId) to $($dStreamTask2.TaskId)"
    }
    Write-Pass "Agent D streaming route resumed through the final LLM and completed"

    $dNonstreamContext = "$ConvId-d-nonstream"
    $dNonstreamClaim = "WF-NONSTREAM-001"
    $dNonstreamMessage = "Review expense claim $dNonstreamClaim through Agent D in non-streaming mode: category hotel, " + `
        "3 nights, unit_price 1000 CNY, total 3000 CNY, currency CNY. Preserve every value exactly."
    Write-Step "6a" "Round 1: trigger Agent D through the non-streaming route"
    $dNonstreamBody1 = New-A2aRequestBody "SendMessage" "d-nonstream-1" `
        $dNonstreamContext "" $dNonstreamMessage
    $dNonstreamResponse1 = Invoke-Utf8JsonRequest -Uri "$BaseUrlA/a2a/" -Method POST `
        -Body $dNonstreamBody1 -TimeoutSec $A2aRequestTimeoutSec
    Save-JsonResponse $dNonstreamResponse1 "d-nonstream-response-1.json"
    $dNonstreamTask1 = Read-SyncTask $dNonstreamResponse1 "TASK_STATE_INPUT_REQUIRED" `
        "manual approval" $dNonstreamClaim
    Write-Pass "Agent D non-streaming route reached manual approval (taskId=$($dNonstreamTask1.TaskId))"

    Write-Step "6b" "Round 2: approve and resume the same Agent D non-streaming task"
    $dNonstreamBody2 = New-A2aRequestBody "SendMessage" "d-nonstream-2" `
        $dNonstreamContext $dNonstreamTask1.TaskId "approved"
    $dNonstreamRawResponse2 = Invoke-Utf8TextRequest -Uri "$BaseUrlA/a2a/" `
        -Body $dNonstreamBody2 -Accept "application/json" -TimeoutSec $A2aRequestTimeoutSec
    $dNonstreamResponse2 = $dNonstreamRawResponse2 | ConvertFrom-Json
    Save-JsonResponse $dNonstreamResponse2 "d-nonstream-response-2.json"
    $dNonstreamTask2 = Read-SyncTask $dNonstreamResponse2 "TASK_STATE_COMPLETED" `
        "agent d expense review completed" $dNonstreamClaim "llm_report="
    Assert-PlainTerminalArtifacts @($dNonstreamResponse2.result.task.artifacts) $dNonstreamRawResponse2
    if ($dNonstreamTask2.TaskId -ne $dNonstreamTask1.TaskId) {
        throw "Agent D non-streaming resume changed taskId from $($dNonstreamTask1.TaskId) to $($dNonstreamTask2.TaskId)"
    }
    Write-Pass "Agent D non-streaming route resumed through the final LLM and completed"

    Assert-LogContains $agentA.StdoutLog "A2A call agent=agentb streaming=true"
    Assert-LogContains $agentA.StdoutLog "A2A call agent=agentb streaming=false"
    Assert-LogContains $agentB.StdoutLog "A2A call agent=agentc-streaming streaming=true"
    Assert-LogContains $agentB.StdoutLog "A2A call agent=agentc-nonstreaming streaming=false"
    Assert-LogContains $agentB.StdoutLog "A2A call agent=agentd-streaming streaming=true"
    Assert-LogContains $agentB.StdoutLog "A2A call agent=agentd-nonstreaming streaming=false"
    Assert-LogContains $agentD.StdoutLog "Begin to call node [final_response]"
    Write-Pass "Configured streaming and non-streaming remote routes were exercised"

    $summary = "`nA2A demo smoke checks passed against Agent A=$BaseUrlA Agent B=$BaseUrlB " + `
        "Agent C=$BaseUrlC Agent D=$BaseUrlD"
    Write-Host $summary -ForegroundColor Green
    $succeeded = $true
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Logs and responses are in $tmp" -ForegroundColor Red
} finally {
    Stop-Agent $agentA
    Stop-Agent $agentB
    Stop-Agent $agentC
    Stop-Agent $agentD
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
