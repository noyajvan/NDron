@echo off
setlocal

echo === Building and installing debug APK ===
call gradlew.bat :app:installDebug
if errorlevel 1 (
    echo BUILD/INSTALL FAILED
    exit /b 1
)

echo === Launching MainActivity ===
adb shell am start -n com.example.drn_kotlin/.MainActivity
if errorlevel 1 (
    echo LAUNCH FAILED
    exit /b 1
)

echo === Done ===
endlocal
