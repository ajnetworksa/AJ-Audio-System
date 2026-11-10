# remove_snapcast_app.ps1 - uninstalls the old Snapcast app (de.badaix.snapcast) from the panels.
# AJ Panel has its own built-in player, so the old app only creates duplicate/offline players in Music Assistant.
# CHANGES the panels. Undo: install downloads\de.badaix.snapcast_2902.apk again.   Log -> logs27\
param([string]$panels = "192.168.8.210,192.168.8.73,192.168.8.46")
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$logs = Join-Path (Get-Location) "logs27"; New-Item -ItemType Directory -Force $logs | Out-Null
foreach ($ip in $panels.Split(",")) {
  $dev = "$($ip):5555"; $log = Join-Path $logs "$ip.txt"
  Write-Host "`n=== $ip ==="
  & $ADB connect $dev | Out-Null
  "=== $ip $(Get-Date) ===" | Out-File $log -Encoding utf8
  & $ADB -s $dev shell am force-stop de.badaix.snapcast | Out-Null
  $r = & $ADB -s $dev uninstall de.badaix.snapcast 2>&1
  if ("$r" -notmatch "Success") { $r = & $ADB -s $dev shell pm uninstall --user 0 de.badaix.snapcast 2>&1 }
  "uninstall: $r" | Tee-Object -Append $log
  # AJ Panel must still be running and connected
  "--- AJ Panel / snapclient ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "ps -A | grep -E 'ajpanel|snapclient|badaix'" | Tee-Object -Append $log
  & $ADB -s $dev shell "netstat -tn | grep ':1704'" | Tee-Object -Append $log
}
Write-Host "`nNow in Music Assistant -> Players: on each GREY card (hourglass) -> menu (3 dots) -> Remove."
Write-Host "Keep the WHITE cards: those are the panels' AJ Panel players."
