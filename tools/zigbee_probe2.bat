@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0zigbee_probe2.ps1" %*
pause
