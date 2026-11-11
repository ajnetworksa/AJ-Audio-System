@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0panel_screenshots.ps1" %*
pause
