<#
.DESCRIPTION
    ESP32 sends telemetry to VPS (134.209.206.127:14550).
    VPS forwards to all connected GCS. All drones use same port.
.PARAMETER Drone
    Drone label for display (1, 2, 3). Default: 1
#>
param([string]$Drone = "1")
$ErrorActionPreference = "Continue"

$VpsIP   = "134.209.206.127"
$VpsPort = 14551

$Labels = @{ "1" = "Drone 1"; "2" = "Drone 2"; "3" = "Drone 3" }
$d = @{
    Name = if ($Labels[$Drone]) { $Labels[$Drone] } else { "Drone $Drone" }
    IP   = $VpsIP
    Port = $VpsPort
}

$MpPath = "C:\Program Files (x86)\Mission Planner\MissionPlanner.exe"
if (-not (Test-Path $MpPath)) {
    $MpPath = "$env:LOCALAPPDATA\MissionPlanner\MissionPlanner.exe"
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  DroneBridge -> $($d.Name) ($($d.IP):$($d.Port))" -ForegroundColor Cyan
Write-Host "  VPS relay: DO Amsterdam" -ForegroundColor DarkGray
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# --- 1. Ping VPS ---
Write-Host "[1/5] Checking VPS relay $($d.IP)..." -ForegroundColor Yellow
$ping = Test-Connection -ComputerName $d.IP -Count 1 -Quiet -ErrorAction SilentlyContinue
if (-not $ping) {
    Write-Host "       VPS offline!" -ForegroundColor Red
    Write-Host "Check internet connection" -ForegroundColor Yellow
    exit 1
}
$rt = (Test-Connection -ComputerName $d.IP -Count 1).ResponseTime
Write-Host "       OK (${rt}ms)" -ForegroundColor Green

# --- Announce to relay (must send first packet so relay forwards drone data back) ---
Write-Host "       Announcing to relay..."
try {
    $a = New-Object Net.Sockets.UdpClient
    $a.Connect($d.IP, $d.Port)
    $a.Send(@(0xFE,1,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0), 17)
    $a.Close()
    Write-Host "       Relay registered" -ForegroundColor Green
} catch {
    Write-Host "       Announce failed, continuing" -ForegroundColor Yellow
}

# --- 2. Kill old MP ---
Write-Host "[2/5] Closing old Mission Planner..." -ForegroundColor Yellow
$old = Get-Process MissionPlanner -ErrorAction SilentlyContinue
if ($old) {
    $old | Stop-Process -Force
    Start-Sleep -Seconds 3
    Write-Host "       Closed" -ForegroundColor Green
} else {
    Write-Host "       None running" -ForegroundColor Green
}

# --- 3. Update MP config for auto-connect ---
Write-Host "[3/5] Configuring MP for UDPCI -> $($d.IP):$($d.Port)..." -ForegroundColor Yellow
$cfgFile = Get-ChildItem -Path "$env:USERPROFILE" -Recurse -Filter config.xml -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -like "*Mission Planner*" } |
    Select-Object -First 1 -ExpandProperty FullName
if ($cfgFile -and (Test-Path $cfgFile)) {
    try {
        [xml]$cfg = Get-Content $cfgFile
        $cfg.Config.comport = "UDPCl"
        $cfg.Config.UDP_host = $d.IP
        $cfg.Config.UDP_port = [string]$d.Port
        $cfg.Save($cfgFile)
        Write-Host "       Config updated" -ForegroundColor Green
    } catch {
        Write-Host "       Config write failed, continuing anyway" -ForegroundColor Yellow
    }
} else {
    Write-Host "       Config not found, skipping" -ForegroundColor Yellow
}

# --- 4. Launch Mission Planner ---
Write-Host "[4/5] Launching Mission Planner..." -ForegroundColor Yellow
Start-Process $MpPath

# --- 5. Auto-connect MP ---
Write-Host "[5/5] Waiting for MP window and auto-connecting..." -ForegroundColor Yellow
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class MPHelper {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(System.IntPtr hWnd);
}
"@

$connected = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 2
    $mpWin = (Get-Process MissionPlanner -ErrorAction SilentlyContinue).MainWindowHandle
    if ($mpWin -and $mpWin -ne [IntPtr]::Zero) {
        [MPHelper]::SetForegroundWindow($mpWin)
        Start-Sleep -Milliseconds 500

        # Alt+C opens Connect menu, tabs navigate, Enter confirms
        [System.Windows.Forms.SendKeys]::SendWait("%(C)")
        Start-Sleep -Milliseconds 800
        [System.Windows.Forms.SendKeys]::SendWait("{TAB}{TAB}{TAB}{TAB}")
        Start-Sleep -Milliseconds 300
        [System.Windows.Forms.SendKeys]::SendWait("{DOWN}")
        Start-Sleep -Milliseconds 200
        [System.Windows.Forms.SendKeys]::SendWait("{TAB}")
        Start-Sleep -Milliseconds 200
        [System.Windows.Forms.SendKeys]::SendWait($d.IP)
        Start-Sleep -Milliseconds 100
        [System.Windows.Forms.SendKeys]::SendWait("{TAB}")
        Start-Sleep -Milliseconds 100
        [System.Windows.Forms.SendKeys]::SendWait([string]$d.Port)
        Start-Sleep -Milliseconds 300
        [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")

        Write-Host "       Auto-connect sent!" -ForegroundColor Green
        $connected = $true
        break
    }
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
if ($connected) {
    Write-Host "  MP LAUNCHED & AUTO-CONNECTED" -ForegroundColor Green
    Write-Host "  Green bar should appear in 3-5 seconds" -ForegroundColor Green
} else {
    Write-Host "  MP launched. If not connected:" -ForegroundColor Yellow
    Write-Host "  In MP: UDPCI -> $($d.IP):$($d.Port) -> Connect" -ForegroundColor Yellow
}
Write-Host "============================================" -ForegroundColor Cyan
