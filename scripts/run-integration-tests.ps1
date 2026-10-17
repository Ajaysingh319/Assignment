# Runs the full backend test suite with PostgreSQL + Kafka integration tests enabled.
# Without DATABASE_PASSWORD those tests are reported as SKIPPED, never as passed.
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-integration-tests.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\run-integration-tests.ps1 -Test "PostgresOrderConcurrencyIntegrationTest"

param(
    [string]$Test = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not $env:DATABASE_URL) { $env:DATABASE_URL = 'jdbc:postgresql://localhost:5432/fulfilment' }
if (-not $env:DATABASE_USERNAME) { $env:DATABASE_USERNAME = 'postgres' }
if (-not $env:KAFKA_BOOTSTRAP_SERVERS) { $env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092' }
if (-not $env:DATABASE_PASSWORD) {
    $secure = Read-Host "Enter PostgreSQL password for user $($env:DATABASE_USERNAME)" -AsSecureString
    $env:DATABASE_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
}

Set-Location (Join-Path $root 'backend')
if ($Test) {
    mvn "-Dtest=$Test" test
} else {
    mvn test
}
$code = $LASTEXITCODE

Write-Host ''
Write-Host '===== Surefire summary (per class) =====' -ForegroundColor Cyan
Get-ChildItem target\surefire-reports\*.txt | ForEach-Object { (Get-Content $_)[3] }
exit $code
