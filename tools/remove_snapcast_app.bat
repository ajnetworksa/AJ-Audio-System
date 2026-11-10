@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0remove_snapcast_app.ps1" %*
pause
