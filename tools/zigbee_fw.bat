@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0zigbee_fw.ps1" %*
pause
