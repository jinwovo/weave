# dev-up.ps1 — bring up the whole weave stack with one command (idempotent).
#
#   powershell -ExecutionPolicy Bypass -File scripts/dev-up.ps1
#
# Starts, in order, whatever isn't already running:
#   1. Docker Desktop (engine)
#   2. docker compose up -d        -> postgres :5437 · redis :6383 · prometheus :9099 · grafana :3011
#   3. ./gradlew :app:bootRun      -> sync server :8103   (its own terminal window)
#   4. npm run dev (web/)          -> Next.js client :3009 (its own terminal window)
# then waits for health and opens the board. Close the two spawned windows to stop app/web;
# `docker compose down` stops the containers.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Test-PortListening([int]$Port) {
    $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

# --- 1. docker engine -------------------------------------------------------
$engineUp = $false
try { docker info *> $null; $engineUp = ($LASTEXITCODE -eq 0) } catch { $engineUp = $false }
if (-not $engineUp) {
    Write-Host '[dev-up] starting Docker Desktop...' -ForegroundColor Yellow
    Start-Process 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        try { docker info *> $null; if ($LASTEXITCODE -eq 0) { $engineUp = $true; break } } catch {}
    }
    if (-not $engineUp) { throw '[dev-up] Docker engine did not come up within 120s' }
}
Write-Host '[dev-up] docker engine: up' -ForegroundColor Green

# --- 2. infrastructure containers ------------------------------------------
Push-Location $root
try { docker compose up -d } finally { Pop-Location }
Write-Host '[dev-up] compose: postgres/redis/prometheus/grafana up' -ForegroundColor Green

# --- 3. sync server :8103 ---------------------------------------------------
if (Test-PortListening 8103) {
    Write-Host '[dev-up] app already listening on :8103 - skipping' -ForegroundColor Yellow
} else {
    Start-Process powershell -ArgumentList '-NoExit', '-Command', "cd '$root'; ./gradlew :app:bootRun"
    Write-Host '[dev-up] app starting in its own window (:8103)...'
}

# --- 4. web client :3009 -----------------------------------------------------
if (Test-PortListening 3009) {
    Write-Host '[dev-up] web already listening on :3009 - skipping' -ForegroundColor Yellow
} else {
    Start-Process powershell -ArgumentList '-NoExit', '-Command', "cd '$root\web'; npm run dev"
    Write-Host '[dev-up] web starting in its own window (:3009)...'
}

# --- wait for health ---------------------------------------------------------
$deadline = (Get-Date).AddSeconds(120)
$appUp = $false
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8103/actuator/health' -TimeoutSec 2
        if ($r.StatusCode -eq 200) { $appUp = $true; break }
    } catch {}
    Start-Sleep -Seconds 2
}
$webUp = $false
while ((Get-Date) -lt $deadline) {
    if (Test-PortListening 3009) { $webUp = $true; break }
    Start-Sleep -Seconds 2
}

Write-Host ''
if ($appUp) { Write-Host '[dev-up] sync server : http://localhost:8103 (health OK)' -ForegroundColor Green }
else        { Write-Host '[dev-up] sync server : NOT healthy yet - check the gradle window' -ForegroundColor Red }
if ($webUp) { Write-Host '[dev-up] web client  : http://localhost:3009' -ForegroundColor Green }
else        { Write-Host '[dev-up] web client  : not up yet - check the npm window' -ForegroundColor Red }
Write-Host '[dev-up] grafana     : http://localhost:3011  |  prometheus : http://localhost:9099'

if ($appUp -and $webUp) {
    Start-Process 'http://localhost:3009/?room=demo'
}
