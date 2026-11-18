@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0panel_silent_updates.ps1" %*
pause
