<#
.SYNOPSIS
    DroneBridge вЂ” РїРѕРґРєР»СЋС‡РµРЅРёРµ Mission Planner Рє РґСЂРѕРЅСѓ С‡РµСЂРµР· Tailscale
.DESCRIPTION
    РўРµР»РµС„РѕРЅ СЂР°Р·РґР°С‘С‚ WiFi (LEO) Рё СЂР°Р±РѕС‚Р°РµС‚ РєР°Рє Tailscale subnet router.
    ESP32 РїРѕРґРєР»СЋС‡С‘РЅ Рє С‚РµР»РµС„РѕРЅСѓ, MP РїРѕРґРєР»СЋС‡Р°РµС‚СЃСЏ Рє ESP32 С‡РµСЂРµР· Tailscale.
.PARAMETER Drone
    РРјСЏ РґСЂРѕРЅР° РёР· СЃРїРёСЃРєР° (РёР»Рё IP-Р°РґСЂРµСЃ РЅР°РїСЂСЏРјСѓСЋ).
    РџРѕ СѓРјРѕР»С‡Р°РЅРёСЋ: "main" (РїРµСЂРІС‹Р№ РґСЂРѕРЅ, 172.24.80.53)
.EXAMPLE
    .\scripts\connect.ps1              # РџРµСЂРІС‹Р№ РґСЂРѕРЅ
    .\scripts\connect.ps1 -Drone 2     # Р’С‚РѕСЂРѕР№ РґСЂРѕРЅ
    .\scripts\connect.ps1 -Drone 172.24.80.55  # РџРѕ IP
#>
param(
    [string]$Drone = "main"
)

$ErrorActionPreference = "Continue"

# --- РљРѕРЅС„РёРіСѓСЂР°С†РёСЏ РґСЂРѕРЅРѕРІ ---
$Drones = @{
    "main"  = @{ Name = "Drone 1"; IP = "172.24.80.53"; Port = 14550 }
    "2"     = @{ Name = "Drone 2"; IP = "172.24.80.54"; Port = 14550 }
    "3"     = @{ Name = "Drone 3"; IP = "172.24.80.55"; Port = 14550 }
}

# РћРїСЂРµРґРµР»СЏРµРј РґСЂРѕРЅ
if ($Drones.ContainsKey($Drone)) {
    $d = $Drones[$Drone]
} elseif ($Drone -match "^\d+\.\d+\.\d+\.\d+$") {
    $d = @{ Name = $Drone; IP = $Drone; Port = 14550 }
} else {
    Write-Host "ERROR: Unknown drone '$Drone'" -ForegroundColor Red
    Write-Host "Available: $($Drones.Keys -join ', ')" -ForegroundColor Yellow
    exit 1
}

$MpPath = "C:\Program Files (x86)\Mission Planner\MissionPlanner.exe"
if (-not (Test-Path $MpPath)) {
    $MpPath = "$env:LOCALAPPDATA\MissionPlanner\MissionPlanner.exe"
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  DroneBridge в†’ $($d.Name) ($($d.IP):$($d.Port))" -ForegroundColor Cyan
Write-Host "  Tailscale subnet: phone hotspot LEO" -ForegroundColor DarkGray
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# --- 1. РџСЂРѕРІРµСЂРєР° СЃРІСЏР·Рё СЃ РґСЂРѕРЅРѕРј ---
Write-Host "[1/4] Pinging drone $($d.IP)..." -ForegroundColor Yellow
$ping = Test-Connection -ComputerName $d.IP -Count 2 -Quiet -ErrorAction SilentlyContinue
if (-not $ping) {
    Write-Host "       FAILED вЂ” drone offline!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Check:" -ForegroundColor Yellow
    Write-Host "  - Phone hotspot 'LEO' is ON"
    Write-Host "  - ESP32 is powered and connected"
    Write-Host "  - Tailscale is running on phone and laptop"
    Read-Host "Press Enter to exit"
    exit 1
}
$rt = (Test-Connection -ComputerName $d.IP -Count 1).ResponseTime
Write-Host "       OK ($rt ms)" -ForegroundColor Green

# --- 2. РЈР±РёС‚СЊ СЃС‚Р°СЂС‹Р№ MP ---
Write-Host "[2/4] Closing old Mission Planner..." -ForegroundColor Yellow
$old = Get-Process MissionPlanner -ErrorAction SilentlyContinue
if ($old) {
    $old | Stop-Process -Force
    Start-Sleep -Seconds 3
    Write-Host "       Closed" -ForegroundColor Green
} else {
    Write-Host "       None running" -ForegroundColor Green
}

# --- 3. РџСЂРѕРїРёСЃР°С‚СЊ РєРѕРЅС„РёРі MP РґР»СЏ Р°РІС‚Рѕ-РїРѕРґРєР»СЋС‡РµРЅРёСЏ ---
Write-Host "[3/4] Configuring MP for UDPCI в†’ $($d.IP):$($d.Port)..." -ForegroundColor Yellow
$cfgPath = "$env:USERPROFILE\Documents\Mission Planner\config.xml"
if (Test-Path $cfgPath) {
    try {
        [xml]$cfg = Get-Content $cfgPath
        $cfg.Config.comport = "UDPCl"
        $cfg.Config.UDP_host = $d.IP
        $cfg.Config.UDP_port = [string]$d.Port
        $cfg.Save($cfgPath)
        Write-Host "       Config updated" -ForegroundColor Green
    } catch {
        Write-Host "       Config write failed, continuing anyway" -ForegroundColor Yellow
    }
} else {
    Write-Host "       Config not found, skipping" -ForegroundColor Yellow
}

# --- 4. Р—Р°РїСѓСЃРє Mission Planner ---
Write-Host "[4/4] Launching Mission Planner..." -ForegroundColor Yellow
Start-Process $MpPath
Start-Sleep -Seconds 8
Write-Host "       Launched" -ForegroundColor Green

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  MISSION PLANNER OPEN вЂ” last step:" -ForegroundColor Green
Write-Host ""
Write-Host "  Top-right dropdown в†’ UDPCI" -ForegroundColor Yellow
Write-Host "  Host: $($d.IP)   Port: $($d.Port)" -ForegroundColor Yellow
Write-Host "  Click CONNECT" -ForegroundColor Yellow
Write-Host ""
Write-Host "  (Host and port are pre-filled from config)" -ForegroundColor DarkGray
Write-Host "============================================" -ForegroundColor Cyan

Write-Host ""
Write-Host "Press Enter to exit this window..." -ForegroundColor DarkGray
Read-Host

