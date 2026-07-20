# Generates local TLS material for the security demo (not committed).
# Output: ./tls/server.p12, client.p12, server-trust.p12, client-trust.p12
param(
    [string]$OutputDir = (Join-Path $PSScriptRoot "tls"),
    [string]$Password = "demo-tls-pass"
)

$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
if (-not (Test-Path $keytool)) {
    throw "keytool not found at $keytool; set JAVA_HOME"
}

$serverP12 = Join-Path $OutputDir "server.p12"
$clientP12 = Join-Path $OutputDir "client.p12"
$serverTrust = Join-Path $OutputDir "server-trust.p12"
$clientTrust = Join-Path $OutputDir "client-trust.p12"
$serverCrt = Join-Path $OutputDir "server.crt"
$clientCrt = Join-Path $OutputDir "client.crt"

function Invoke-Keytool {
    param([string[]]$Args)
    & $keytool @Args
    if ($LASTEXITCODE -ne 0) { throw "keytool failed: $($Args -join ' ')" }
}

Invoke-Keytool @(
    "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
    "-keystore", $serverP12, "-storepass", $Password, "-keypass", $Password, "-validity", "365",
    "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost,IP:127.0.0.1"
)
Invoke-Keytool @(
    "-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
    "-keystore", $clientP12, "-storepass", $Password, "-keypass", $Password, "-validity", "365",
    "-dname", "CN=demo-client"
)
Invoke-Keytool @("-exportcert", "-alias", "server", "-keystore", $serverP12, "-storepass", $Password, "-file", $serverCrt)
Invoke-Keytool @("-exportcert", "-alias", "client", "-keystore", $clientP12, "-storepass", $Password, "-file", $clientCrt)
Invoke-Keytool @("-importcert", "-alias", "server", "-file", $serverCrt, "-keystore", $clientTrust,
    "-storepass", $Password, "-storetype", "PKCS12", "-noprompt")
Invoke-Keytool @("-importcert", "-alias", "client", "-file", $clientCrt, "-keystore", $serverTrust,
    "-storepass", $Password, "-storetype", "PKCS12", "-noprompt")

Write-Host "Generated demo TLS material under $OutputDir"
Write-Host "Use password '$Password' in application-security-tls_local.yml (gitignored)."
