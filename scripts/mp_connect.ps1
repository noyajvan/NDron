<#
.SYNOPSIS
    Автоподключение Mission Planner к дрону ESP32 через Tailscale subnet routing
.DESCRIPTION
    1. Убивает старый Mission Planner
    2. Меняет config.xml на нужный IP дрона
    3. Отправляет heartbeat с порта 14550 чтобы ESP32 зарегистрировал эту машину
    4. Запускает Mission Planner - он сразу получает MAVLink поток
.PARAMETER DroneIP
    IP дрона через Tailscale subnet router (например 172.24.80.237)
.PARAMETER DronePort
    UDP порт дрона (по умолчанию 14550)
.PARAMETER MPConfigPath
    Путь к config.xml Mission Planner
.PARAMETER NoLaunch
    Только подготовить конфиг и heartbeat, не запускать Mission Planner
.EXAMPLE
    .\scripts\mp_connect.ps1 -DroneIP 172.24.80.237
.EXAMPLE
    .\scripts\mp_connect.ps1 -DroneIP 172.24.88.100 -DronePort 14550
#>

param(
    [Parameter(Mandatory = $true, HelpMessage = "Tailscale IP дрона (суброутинг)")]
    [string]$DroneIP,

    [Parameter(HelpMessage = "UDP порт дрона")]
    [int]$DronePort = 14550,

    [Parameter(HelpMessage = "Путь к config.xml")]
    [string]$MPConfigPath = "$env:USERPROFILE\OneDrive\Документы\Mission Planner\config.xml",

    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"

Write-Host "=== DroneBridge Mission Planner Connect ===" -ForegroundColor Cyan
Write-Host "  Drone: ${DroneIP}:${DronePort}" -ForegroundColor Yellow
Write-Host "  MP Config: $MPConfigPath" -ForegroundColor Yellow

# --- 1. Kill existing Mission Planner ---
$existing = Get-Process MissionPlanner -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "[1/5] Killing existing Mission Planner (PID $($existing.Id))..."
    $existing | Stop-Process -Force
    Start-Sleep -Seconds 3
    Write-Host "       Done" -ForegroundColor Green
} else {
    Write-Host "[1/5] No existing Mission Planner" -ForegroundColor Green
}

# --- 2. Update Mission Planner config ---
if (Test-Path $MPConfigPath) {
    Write-Host "[2/5] Updating MP config: UDP_host=$DroneIP..."
    $xml = [xml](Get-Content $MPConfigPath)
    $node = $xml.Config.UDP_host
    if (-not $node) {
        $node = $xml.CreateElement("UDP_host")
        $xml.Config.AppendChild($node)
    }
    $node.InnerText = $DroneIP
    $xml.Save($MPConfigPath)
    Write-Host "       Saved" -ForegroundColor Green
} else {
    Write-Host "[2/5] WARNING: MP config not found at $MPConfigPath" -ForegroundColor Red
    Write-Host "       Will launch MP with default connection" -ForegroundColor Yellow
}

# --- 3. Ping drone ---
Write-Host "[3/5] Pinging drone at $DroneIP..."
$ping = Test-Connection -ComputerName $DroneIP -Count 1 -Quiet -ErrorAction SilentlyContinue
if ($ping) {
    Write-Host "       Reachable" -ForegroundColor Green
} else {
    Write-Host "       WARNING: Ping failed - Tailscale may need time to establish" -ForegroundColor Yellow
}

# --- 4. Send heartbeat to trigger ESP32 registration ---
Write-Host "[4/5] Sending MAVLink heartbeat from :${DronePort} to trigger ESP32 registration..."
$hbScript = @"
import socket, time
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    sock.bind(('0.0.0.0', $DronePort))
    # MAVLink v1 heartbeat: type=0, autopilot=0, base_mode=0, custom_mode=0, system_status=0, mavlink_version=3
    hb = bytes([0xFD,0x09,0x00,0xFF,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x03,0x05,0x79])
    sock.sendto(hb, ('$DroneIP', $DronePort))
    sock.settimeout(2.0)
    try:
        data, addr = sock.recvfrom(4096)
        print(f'RESPONSE: {len(data)} bytes from {addr[0]}:{addr[1]}')
        # Check for more packets
        count = 1
        t0 = time.time()
        while time.time() - t0 < 1.5:
            try:
                data, addr = sock.recvfrom(4096)
                count += 1
            except socket.timeout:
                break
        print(f'MAVLink stream active: {count} packets received')
    except socket.timeout:
        print('WARNING: No response - FC not connected or ESP32 not ready')
finally:
    sock.close()
"@
$result = python -c $hbScript 2>&1
Write-Host "       $result" -ForegroundColor Green

# Allow port to release
Start-Sleep -Seconds 2

# --- 5. Launch Mission Planner ---
if (-not $NoLaunch) {
    Write-Host "[5/5] Launching Mission Planner..."
    Start-Process "C:\Program Files (x86)\Mission Planner\MissionPlanner.exe"
    Write-Host "       Done" -ForegroundColor Green
} else {
    Write-Host "[5/5] Skipping launch (--NoLaunch)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=== READY ===" -ForegroundColor Cyan
Write-Host "Mission Planner should now receive MAVLink from ${DroneIP}:${DronePort}"
Write-Host "Open HUD / Flight Data to verify telemetry" -ForegroundColor Yellow
Write-Host ""
Write-Host "Next time:  .\scripts\mp_connect.ps1 -DroneIP $DroneIP" -ForegroundColor White
