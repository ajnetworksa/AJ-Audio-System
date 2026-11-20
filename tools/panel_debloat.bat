@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0panel_debloat.ps1" %*
pause
