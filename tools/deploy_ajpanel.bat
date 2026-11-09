@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy_ajpanel.ps1" %*
pause
