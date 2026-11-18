@echo off
rem Windows version of build_addon.sh
cd /d "%~dp0"
if exist aj_music_assistant\aj rmdir /s /q aj_music_assistant\aj
mkdir aj_music_assistant\aj
xcopy /e /i /q ..\ajsnap-provider\ajsnap aj_music_assistant\aj\ajsnap
xcopy /e /i /q ..\ajcast-provider\ajcast aj_music_assistant\aj\ajcast
xcopy /e /i /q ..\patches aj_music_assistant\aj\patches
copy /y ..\install_ajsnap.sh aj_music_assistant\aj\ >nul
echo Ready. Copy aj_music_assistant to \\homeassistant\addons\  (Samba add-on share "addons")
pause
