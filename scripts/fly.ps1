# OBSOLETE: use connect.ps1 instead
<# 
.SYNOPSIS
    ONE-CLICK: DroneBridge + Mission Planner через Tailscale
    Запускать: powershell -ExecutionPolicy Bypass -File .\scripts\fly.ps1
#>

$ErrorActionPreference = "Continue"

$EspIP = "172.24.80.53"
$EspPort = 14550
$LocalPort = 14551
$MpPath = "C:\Program Files (x86)\Mission Planner\MissionPlanner.exe"

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  DroneBridge ONE-CLICK via Tailscale" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

# 1. Kill old MP
$old = Get-Process MissionPlanner -ErrorAction SilentlyContinue
if ($old) { $old | Stop-Process -Force; Start-Sleep -Seconds 2; Write-Host "[OK] Old MP killed" -ForegroundColor Green }

# 2. Check ESP32 reachable
$ping = Test-Connection -ComputerName $EspIP -Count 1 -Quiet -ErrorAction SilentlyContinue
if (-not $ping) {
    Write-Host "[ERR] ESP32 $EspIP not reachable! Check hotspot LEO." -ForegroundColor Red
    Write-Host "Press any key to exit..."; $null = $host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
    exit 1
}
Write-Host "[OK] ESP32 $EspIP ping OK" -ForegroundColor Green

# 3. Start UDP relay (background)
$relayScript = Join-Path $PSScriptRoot "udp_relay.ps1"
$relayJob = Start-Job -ScriptBlock {
    param($ip, $port, $local)
    $eep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Parse($ip), $port)
    $lep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, $local)
    $ul = New-Object System.Net.Sockets.UdpClient($lep); $ul.Client.ReceiveTimeout = 200
    $ue = New-Object System.Net.Sockets.UdpClient; $ue.Client.ReceiveTimeout = 200
    $w = [System.Text.Encoding]::ASCII.GetBytes("WAKE"); $ue.Send($w, $w.Length, $eep)
    $mp = $null
    while ($true) {
        try { if ($ul.Available -gt 0) { $r = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0); $d = $ul.Receive([ref]$r); $mp = $r; $ue.Send($d, $d.Length, $eep) } } catch {}
        try { if ($ue.Available -gt 0) { $r = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0); $d = $ue.Receive([ref]$r); if ($mp) { $ul.Send($d, $d.Length, $mp) } } } catch {}
        Start-Sleep -Milliseconds 10
    }
} -ArgumentList $EspIP, $EspPort, $LocalPort
Start-Sleep -Seconds 2
Write-Host "[OK] Relay running on 127.0.0.1:$LocalPort <-> $EspIP`:$EspPort" -ForegroundColor Green

# 4. Launch MP
$mpProc = Start-Process $MpPath -PassThru
Start-Sleep -Seconds 10

# 5. Auto-click Connect in MP
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32 {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);
}
"@
$mpHWnd = (Get-Process -Id $mpProc.Id -ErrorAction SilentlyContinue).MainWindowHandle
if ($mpHWnd -and $mpHWnd -ne [IntPtr]::Zero) {
    Start-Sleep -Seconds 2
    [Win32]::SetForegroundWindow($mpHWnd)
    Start-Sleep -Milliseconds 500
    [System.Windows.Forms.SendKeys]::SendWait('%(C)')
    Start-Sleep -Milliseconds 500
    [System.Windows.Forms.SendKeys]::SendWait('{TAB}{TAB}{TAB}')
    Start-Sleep -Milliseconds 200
    [System.Windows.Forms.SendKeys]::SendWait('{DOWN}{DOWN}')
    Start-Sleep -Milliseconds 200
    [System.Windows.Forms.SendKeys]::SendWait('{TAB}')
    Start-Sleep -Milliseconds 200
    [System.Windows.Forms.SendKeys]::SendWait("127.0.0.1`:$LocalPort")
    Start-Sleep -Milliseconds 200
    [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
    Write-Host "[OK] Auto-connect sent to MP" -ForegroundColor Green
} else {
    Write-Host "[WARN] MP window not found. Connect manually:" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  GREEN BAR should appear in MP!" -ForegroundColor Green
Write-Host "  If not: In MP top-right -> UDPCI" -ForegroundColor Yellow
Write-Host "  Host: 127.0.0.1  Port: $LocalPort" -ForegroundColor Yellow
Write-Host "============================================" -ForegroundColor Cyan

Read-Host "Press Enter to stop relay and close"
$relayJob | Stop-Job -ErrorAction SilentlyContinue
$mpProc | Stop-Process -Force -ErrorAction SilentlyContinue
