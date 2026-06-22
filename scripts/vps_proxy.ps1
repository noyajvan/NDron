$ErrorActionPreference = "Continue"
Add-Type -AssemblyName System.Net

$vps = New-Object Net.Sockets.UdpClient
$vps.Connect("134.209.206.127", 14551)
$vps.Client.ReceiveTimeout = 100

$local = New-Object Net.Sockets.UdpClient(14550)
$local.Client.ReceiveTimeout = 100

$hb = [byte[]]@(0xFE,9,1,0,0,0,1,0,0,0,0,0,0,0,0,0,0)
$mpAddr = $null
$any = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
$lastHb = [DateTime]::Now

try { $vps.Send($hb, $hb.Length) } catch {}
Write-Host "[PROXY] 127.0.0.1:14550 <-> VPS:14551" -ForegroundColor Cyan
Write-Host "[PROXY] MP -> UDP -> 127.0.0.1:14550 -> Connect"
Write-Host "[PROXY] Waiting for MP..."

while ($true) {
    try {
        if ($vps.Available -gt 0) {
            $data = $vps.Receive([ref]$any)
            if ($mpAddr) { 
                $local.Send($data, $data.Length, $mpAddr)
            }
        }
    } catch {}
    try {
        if ($local.Available -gt 0) {
            $from = $any
            $data = $local.Receive([ref]$from)
            if ($mpAddr -eq $null) {
                Write-Host "[MP] Connected!" -ForegroundColor Green
            }
            $mpAddr = $from
            $vps.Send($data, $data.Length)
        }
    } catch {}
    if (([DateTime]::Now - $lastHb).TotalSeconds -gt 20) {
        try { $vps.Send($hb, $hb.Length); $lastHb = [DateTime]::Now } catch {}
    }
    Start-Sleep -Milliseconds 10
}
