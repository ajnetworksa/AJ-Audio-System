@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0collect_logs.ps1" %*
pause
