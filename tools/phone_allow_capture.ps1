# phone_allow_capture.ps1 - lets AJ Audio Client start "Play my phone" WITHOUT the
# "Start recording or casting?" question every time. Uses Android's own app-op PROJECT_MEDIA,
# set once over adb (USB cable or Wireless debugging). The phone owner must allow USB / wireless debugging.
# Undo: same script with -undo   (or uninstall the app)
param([switch]$undo, [string]$pair = "", [string]$connect = "")
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$PKG = "com.ajnetwork.aj_audio"
Write-Host "On the phone: Settings > About phone > tap 'Build number' 7x  ->  Developer options > USB debugging ON"
Write-Host "(or Wireless debugging: run this with -pair IP:PORT and -connect IP:PORT shown on the phone)"
if ($pair -ne "") { $code = Read-Host "Pairing code shown on the phone"; & $ADB pair $pair $code }
if ($connect -ne "") { & $ADB connect $connect }
& $ADB devices
$mode = if ($undo) { "default" } else { "allow" }
& $ADB shell appops set $PKG PROJECT_MEDIA $mode
Write-Host "PROJECT_MEDIA now:" (& $ADB shell appops get $PKG PROJECT_MEDIA)
Write-Host "Done. You can switch USB/Wireless debugging off again; the setting stays until the app is uninstalled."
