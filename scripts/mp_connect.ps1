<#
.SYNOPSIS
    Quick launch Mission Planner via VPS relay (no firewall, no Tailscale)
.DESCRIPTION
    ESP32 → phone hotspot → 4G → VPS (134.209.206.127:14550) → MP
    Phone is just a hotspot. No Tailscale needed.
#>

$ErrorActionPreference = "Continue"

$VpsIP   = "134.209.206.127"
$VpsPort = 14550

Write-Host "=== DroneBridge VPS Relay ===" -ForegroundColor Cyan

# --- 1. Check VPS ---
$ping = Test-Connection -ComputerName $VpsIP -Count 1 -Quiet -ErrorAction SilentlyContinue
if ($ping) {
    $rt = (Test-Connection -ComputerName $VpsIP -Count 1).ResponseTime
    Write-Host "[VPS]   $VpsIP — ${rt}ms" -ForegroundColor Green
} else {
    Write-Host "[VPS]   $VpsIP — OFFLINE" -ForegroundColor Red
}

# --- 2. Kill old MP ---
$old = Get-Process MissionPlanner -ErrorAction SilentlyContinue
if ($old) {
    Write-Host "[MP]    Killing existing..."
    $old | Stop-Process -Force
    Start-Sleep -Seconds 3
} else {
    Write-Host "[MP]    No existing instance" -ForegroundColor Green
}

# --- 3. Update MP config ---
$cfgPath = "$env:USERPROFILE\OneDrive\Документы\Mission Planner\config.xml"
if (Test-Path $cfgPath) {
    try {
        [xml]$cfg = Get-Content $cfgPath
        $cfg.Config.comport = "UDPCl"
        $cfg.Config.UDP_host = $VpsIP
        $cfg.Config.UDP_port = [string]$VpsPort
        $cfg.Save($cfgPath)
        Write-Host "[CONFIG] MP set to UDPCl $VpsIP`:$VpsPort" -ForegroundColor Green
    } catch {
        Write-Host "[CONFIG] Write failed — connect manually" -ForegroundColor Yellow
    }
} else {
    Write-Host "[CONFIG] Not found — connect manually" -ForegroundColor Yellow
}

# --- 4. Launch MP ---
$mpPath = "C:\Program Files (x86)\Mission Planner\MissionPlanner.exe"
if (-not (Test-Path $mpPath)) {
    $mpPath = "${env:LOCALAPPDATA}\MissionPlanner\MissionPlanner.exe"
}
if (Test-Path $mpPath) {
    Start-Process $mpPath
    Write-Host "[MP]    Launched" -ForegroundColor Green
} else {
    Write-Host "[MP]    NOT FOUND!" -ForegroundColor Red
}

Write-Host ""
Write-Host "=== MANUAL CONNECT ===" -ForegroundColor Cyan
Write-Host "  UDPCI -> $VpsIP`:$VpsPort -> Connect" -ForegroundColor Yellow
Write-Host ""
Write-Host "Phone = hotspot only. No Tailscale, no firewall." -ForegroundColor DarkGray
