@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0panel_inventory.ps1" %*
pause
