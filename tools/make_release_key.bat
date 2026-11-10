@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0make_release_key.ps1" %*
pause
