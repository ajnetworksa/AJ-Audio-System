@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0phone_allow_capture.ps1" %*
pause
