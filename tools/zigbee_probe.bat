@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0zigbee_probe.ps1" %*
pause
