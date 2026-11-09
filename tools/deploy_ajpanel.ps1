# deploy_ajpanel.ps1 - install / update AJ Panel on panels and point it at Music Assistant.
# CHANGES the panels: installs AJ Panel, grants overlay permission, stops the old Snapcast app player,
# tells ControlX Bridge to stop joining a group. Logs -> logs26\<ip>.txt
#
#   deploy_ajpanel.bat                                   (all 3 panels, prompts for token)
#   deploy_ajpanel.bat -panels "192.168.8.46=Living"     (one panel, custom name)
#   deploy_ajpanel.bat -disableOld                       (also disable the Snapcast app so it can't start again)
#   deploy_ajpanel.bat -uninstall                        (remove AJ Panel, re-enable Snapcast app)
param(
  [string]$ma = "192.168.8.5",
  [string]$panels = "192.168.8.210=P8_EN,192.168.8.73=M20_EN,192.168.8.46=smart",
  [string]$apk = "",
  [string]$token = "",
  [switch]$disableOld,
  [switch]$uninstall
)
$ErrorActionPreference = "Continue"
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($apk -eq "") { $apk = Get-ChildItem (Join-Path $here "..\apps\panel\releases\AJ_Panel_v*.apk") | Sort-Object Name | Select-Object -Last 1 -ExpandProperty FullName }
$logs = Join-Path (Get-Location) "logs26"; New-Item -ItemType Directory -Force $logs | Out-Null
$PKG = "com.ajnetwork.ajpanel"

if (-not $uninstall -and $token -eq "") {
  Write-Host "Music Assistant long-lived token for the panels (MA -> Settings -> your profile -> Long-lived tokens)."
  Write-Host "Tip: make a separate MA user 'panel' for the panels. Press Enter to skip and log in on each panel instead."
  $token = Read-Host "Token"
}

& $ADB kill-server | Out-Null
& $ADB start-server | Out-Null
foreach ($entry in $panels.Split(",")) {
  $ip, $name = $entry.Split("=")
  $dev = "$($ip):5555"
  $log = Join-Path $logs "$ip.txt"
  "=== $ip ($name) $(Get-Date) ===" | Out-File $log -Encoding utf8
  Write-Host "`n=== $ip ($name) ==="
  & $ADB connect $dev | Tee-Object -Append $log
  if ($uninstall) {
    & $ADB -s $dev shell am broadcast -n "$PKG/.BootReceiver" -a "$PKG.STOP" | Out-Null
    & $ADB -s $dev uninstall $PKG | Tee-Object -Append $log
    & $ADB -s $dev shell pm enable de.badaix.snapcast | Tee-Object -Append $log
    continue
  }
  Write-Host "installing $(Split-Path -Leaf $apk) (watch the panel screen for Play Protect: More details -> Install anyway)"
  & $ADB -s $dev push $apk /data/local/tmp/ajpanel.apk | Tee-Object -Append $log
  & $ADB -s $dev shell pm install -r -g /data/local/tmp/ajpanel.apk | Tee-Object -Append $log
  & $ADB -s $dev shell rm /data/local/tmp/ajpanel.apk

  # permissions: floating card + keep running
  & $ADB -s $dev shell appops set $PKG SYSTEM_ALERT_WINDOW allow | Tee-Object -Append $log
  & $ADB -s $dev shell dumpsys deviceidle whitelist +$PKG | Tee-Object -Append $log
  & $ADB -s $dev shell cmd appops set $PKG RUN_IN_BACKGROUND allow 2>$null | Out-Null

  # old player off (AJ Panel has its own) - otherwise the panel plays twice / orphan process (BUG-015)
  & $ADB -s $dev shell am start-foreground-service -n de.badaix.snapcast/.SnapclientService -a ACTION_STOP 2>$null | Out-Null
  Start-Sleep 1
  & $ADB -s $dev shell am force-stop de.badaix.snapcast | Out-Null
  if ($disableOld) { & $ADB -s $dev shell pm disable-user --user 0 de.badaix.snapcast | Tee-Object -Append $log }
  # ControlX Bridge: keep Control X working, but stop it from (re)joining the Snapcast app to a server
  & $ADB -s $dev shell am broadcast -n com.ajnetwork.cxbridge/.BootReceiver -a com.ajnetwork.cxbridge.START --es join "''" 2>$null | Out-Null

  # configure + start
  $cfg = @("shell", "am", "broadcast", "-n", "$PKG/.BootReceiver", "-a", "$PKG.CONFIG", "--es", "host", $ma, "--es", "name", "'$name'")
  if ($token -ne "") { $cfg += @("--es", "token", $token) }
  & $ADB -s $dev @cfg | Out-Null
  & $ADB -s $dev shell am start -n "$PKG/.MainActivity" | Tee-Object -Append $log
}

Write-Host "`nwaiting 12 s for the players to connect..."
Start-Sleep 12
foreach ($entry in $panels.Split(",")) {
  $ip = $entry.Split("=")[0]; $dev = "$($ip):5555"; $log = Join-Path $logs "$ip.txt"
  if ($uninstall) { continue }
  "--- AJPanel log ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev logcat -d -v time -s AJPanel | Out-File -Append $log -Encoding utf8
  "--- connections ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "netstat -tn | grep -E ':1704|:8095'" | Out-File -Append $log -Encoding utf8
  $ok = Select-String -Path $log -Pattern "1704.*ESTABLISHED" -Quiet
  Write-Host ("{0}: sync player {1}" -f $ip, $(if ($ok) { "CONNECTED" } else { "not connected - see $log" }))
}
Write-Host "`nIn Music Assistant the panels now appear as NEW players (ma_ajpanel_...). Re-create your groups with them,"
Write-Host "and disable the old Snapcast-app players in MA -> Settings -> Players."
