<#
.SYNOPSIS
    UDP relay: localhost <-> ESP32 through Tailscale
    Run before Mission Planner: .\scripts\udp_relay.ps1
    In MP: UDP -> Connect -> port 14551
#>
param(
    [string]$EspIP = "172.24.80.53",
    [int]$EspPort = 14550,
    [int]$LocalPort = 14551
)

$ErrorActionPreference = "Stop"
Write-Host "=== UDP Relay: 0.0.0.0:$LocalPort <-> ${EspIP}:$EspPort ===" -ForegroundColor Cyan

$espEP = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Parse($EspIP), $EspPort)
$localEP = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, $LocalPort)

$udpLocal = New-Object System.Net.Sockets.UdpClient($localEP)
$udpEsp   = New-Object System.Net.Sockets.UdpClient
$udpLocal.Client.ReceiveTimeout = 200
$udpEsp.Client.ReceiveTimeout = 200

# Wake ESP32 so it learns our IP:port
$wake = [System.Text.Encoding]::ASCII.GetBytes("RELAY_WAKE")
$udpEsp.Send($wake, $wake.Length, $espEP)
Write-Host "Wake-up sent to ESP32" -ForegroundColor DarkGray

# Keep track of MP's endpoint
$mpEP = $null

Write-Host "LISTENING on UDP :$LocalPort" -ForegroundColor Green
Write-Host "In MP: select UDP -> Connect -> 127.0.0.1:$LocalPort" -ForegroundColor Yellow

# ESP32->MP telemetry counter
$lastLog = [DateTime]::Now

while ($true) {
    # Check MP (local) -> ESP32
    try {
        if ($udpLocal.Available -gt 0) {
            $remote = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            $data = $udpLocal.Receive([ref]$remote)
            $mpEP = $remote
            $udpEsp.Send($data, $data.Length, $espEP)
        }
    } catch {}

    # Check ESP32 -> MP
    try {
        if ($udpEsp.Available -gt 0) {
            $remote = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
            $data = $udpEsp.Receive([ref]$remote)
            if ($mpEP) {
                $udpLocal.Send($data, $data.Length, $mpEP)
            }
        }
    } catch {}

    $elapsed = ([DateTime]::Now - $lastLog).TotalSeconds
    if ($mpEP -and $elapsed -ge 5) {
        Write-Host "[RELAY] ESP: $($udpEsp.Available) queued, MP: $($mpEP.Address):$($mpEP.Port)" -ForegroundColor DarkGray
        $lastLog = [DateTime]::Now
    }

    Start-Sleep -Milliseconds 10
}
