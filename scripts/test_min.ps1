$ErrorActionPreference = "Continue"
$Drones = @{
    "main" = @{ Name = "Drone 1"; IP = "172.24.80.53"; Port = 14550 }
}
$Drone = "main"
$d = $Drones[$Drone]
Write-Host "Test" -ForegroundColor Cyan
Write-Host "[1/4] Pinging drone $($d.IP)..." -ForegroundColor Yellow
$ping = Test-Connection -ComputerName $d.IP -Count 2 -Quiet -ErrorAction SilentlyContinue
if (-not $ping) {
    Write-Host "       FAILED - drone offline!" -ForegroundColor Red
    exit 1
}
$rt = (Test-Connection -ComputerName $d.IP -Count 1).ResponseTime
Write-Host "       OK ($rt ms)" -ForegroundColor Green
Write-Host "[2/4] Closing old Mission Planner..." -ForegroundColor Yellow
