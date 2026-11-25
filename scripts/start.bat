@echo off
rem AirGesture launcher (double-click friendly). Passes all args to run.ps1.
setlocal
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\run.ps1" %*
endlocal
