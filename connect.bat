@echo off
REM ============================================
REM  DroneBridge — ONE-CLICK connect to drone
REM  Usage:  connect.bat          (drone 1)
REM          connect.bat 2        (drone 2)
REM          connect.bat 172.24.80.55  (by IP)
REM ============================================
cd /d "%~dp0"
if "%1"=="" (
    powershell -ExecutionPolicy Bypass -File "scripts\connect.ps1"
) else (
    powershell -ExecutionPolicy Bypass -File "scripts\connect.ps1" -Drone %1
)
