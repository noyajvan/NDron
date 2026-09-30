# PowerShell-обгортка для gradlew.bat.
# Дозволяє запускати ./gradlew.ps1 <task> з будь-якої директорії,
# не залежачи від того, чи поточна директорія є в PATH.
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradlewBat = Join-Path $scriptDir "gradlew.bat"

if (-not (Test-Path $gradlewBat)) {
    Write-Error "gradlew.bat не знайдено поруч із gradlew.ps1 ($gradlewBat)"
    exit 1
}

& $gradlewBat @args
exit $LASTEXITCODE
