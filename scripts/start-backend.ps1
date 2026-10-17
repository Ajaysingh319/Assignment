# Starts the Spring Boot backend against local PostgreSQL + Kafka.
# The PostgreSQL password is read from DATABASE_PASSWORD if already set in this shell,
# otherwise prompted for (masked). It is never written to disk.
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $root 'backend'

if (-not $env:DATABASE_URL) { $env:DATABASE_URL = 'jdbc:postgresql://localhost:5432/fulfilment' }
if (-not $env:DATABASE_USERNAME) { $env:DATABASE_USERNAME = 'postgres' }
if (-not $env:KAFKA_BOOTSTRAP_SERVERS) { $env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092' }
if (-not $env:DATABASE_PASSWORD) {
    $secure = Read-Host "Enter PostgreSQL password for user $($env:DATABASE_USERNAME)" -AsSecureString
    $env:DATABASE_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
}

# Pre-flight: PostgreSQL and Kafka reachable?
foreach ($svc in @(@{ Name = 'PostgreSQL'; Port = 5432 }, @{ Name = 'Kafka'; Port = 9092 })) {
    $ok = Test-NetConnection localhost -Port $svc.Port -InformationLevel Quiet -WarningAction SilentlyContinue
    if ($ok) { Write-Host "[ok]   $($svc.Name) is listening on port $($svc.Port)" -ForegroundColor Green }
    else { Write-Host "[warn] $($svc.Name) is NOT listening on port $($svc.Port)" -ForegroundColor Yellow }
}

# Pre-flight: port 8080. Only offer to stop the process if it is this application.
$listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    $cmd = [string]$proc.CommandLine
    Write-Host "Port 8080 is in use by PID $($proc.ProcessId) ($($proc.Name))" -ForegroundColor Yellow
    if ($cmd -like '*com.assignment.fulfilment.FulfilmentApplication*' -or $cmd -like '*fulfilment-platform*') {
        $answer = Read-Host 'It is an older instance of this backend. Stop it and continue? [y/N]'
        if ($answer -match '^[yY]') {
            Stop-Process -Id $proc.ProcessId
            Start-Sleep -Seconds 3
        } else {
            Write-Host 'Aborted; port 8080 is still occupied.'; exit 1
        }
    } else {
        Write-Host 'It is not this application; refusing to stop it. Free port 8080 or set SERVER_PORT.' -ForegroundColor Red
        exit 1
    }
}

Set-Location $backend
Write-Host "Starting backend (DATABASE_URL=$($env:DATABASE_URL), KAFKA_BOOTSTRAP_SERVERS=$($env:KAFKA_BOOTSTRAP_SERVERS))" -ForegroundColor Cyan
mvn spring-boot:run
