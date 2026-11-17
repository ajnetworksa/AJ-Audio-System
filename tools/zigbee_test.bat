@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0zigbee_test.ps1" %*
pause
