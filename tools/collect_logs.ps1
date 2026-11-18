# collect_logs.ps1 - READ ONLY: gathers everything needed to debug the panels (and optionally the phone) in one go.
#   collect_logs.bat                                   all panels (X8, P8, DSPPA)
#   collect_logs.bat -panels "192.168.8.165,192.168.8.46"
#   collect_logs.bat -phone                            also the Android phone connected by USB (USB debugging on)
# Output: tools\logs32\<ip>\  (git-ignored). Nothing on the panels is changed. No passwords/tokens are collected.
param([string]$panels = "192.168.8.165,192.168.8.194,192.168.8.46", [switch]$phone)
[Console]::OutputEncoding = [Text.Encoding]::UTF8   # adb output is UTF-8 (else dashes come out garbled)
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$root = Join-Path $PSScriptRoot "logs32"
New-Item -ItemType Directory -Force $root | Out-Null
"collected $(Get-Date -Format s) on $env:COMPUTERNAME" | Out-File (Join-Path $root "README.txt") -Encoding utf8

function Sh([string]$dev, [string]$cmd) { (& $ADB -s $dev shell $cmd 2>&1 | Out-String) }

foreach ($ip in $panels.Split(",")) {
  $ip = $ip.Trim(); if (-not $ip) { continue }
  $dev = "$($ip):5555"
  $out = Join-Path $root $ip; New-Item -ItemType Directory -Force $out | Out-Null
  Write-Host "=== $ip ==="
  & $ADB connect $dev | Out-Null
  if ((& $ADB -s $dev get-state 2>$null) -ne "device") { "adb not reachable" | Out-File (Join-Path $out "ERROR.txt") -Encoding utf8; Write-Host "  not reachable over adb" -ForegroundColor Yellow; continue }
  try { [Text.Encoding]::UTF8.GetString((New-Object Net.WebClient).DownloadData("http://$($ip):8766/api/info")) | ConvertFrom-Json | ConvertTo-Json -Depth 5 | Out-File (Join-Path $out "info.json") -Encoding utf8 } catch { "api/info failed: $_" | Out-File (Join-Path $out "info.json") -Encoding utf8 }
  & $ADB -s $dev logcat -d -v time -s AJPanel 2>&1 | Out-File (Join-Path $out "ajpanel.txt") -Encoding utf8
  & $ADB -s $dev logcat -d -v time 2>&1 | Out-File (Join-Path $out "logcat_all.txt") -Encoding utf8
  $sys = @()
  $sys += "--- clock ---";            $sys += Sh $dev "date"
  $sys += "--- timezone / auto time ---"; $sys += Sh $dev "getprop persist.sys.timezone"; $sys += Sh $dev "settings get global auto_time"; $sys += Sh $dev "settings get global ntp_server"
  $sys += "--- uptime ---";           $sys += Sh $dev "uptime"
  $sys += "--- AJ Panel version ---"; $sys += Sh $dev "dumpsys package com.ajnetwork.ajpanel | grep -E 'versionName|lastUpdateTime'"
  $sys += "--- processes ---";        $sys += Sh $dev "ps -A | grep -iE 'ajpanel|snap|gateway|judian'"
  $sys += "--- network ---";          $sys += Sh $dev "ip -4 addr show"; $sys += Sh $dev "ping -c 3 -W 1 192.168.8.5"
  $sys += "--- cpu (top) ---";      $sys += Sh $dev "top -b -n 1 -m 12 | head -20"
  $sys += "--- wifi ---";           $sys += Sh $dev "dumpsys wifi | grep -iE 'mWifiInfo|RSSI|Link speed|Frequency' | head -4"
  $sys += "--- zigbee serial ---";    $sys += Sh $dev "stty -F /dev/ttyS3 -a"
  $sys += "--- disabled apps ---";    $sys += Sh $dev "pm list packages -d"
  $sys += "--- audio outputs ---";    $sys += Sh $dev "dumpsys media.audio_flinger | grep -iE 'Output thread|Sample rate|HAL frame count|Latency' | head -20"
  $sys | Out-File (Join-Path $out "system.txt") -Encoding utf8
  Write-Host "  saved $out"
}

if ($phone) {
  $out = Join-Path $root "phone"; New-Item -ItemType Directory -Force $out | Out-Null
  Write-Host "=== phone (USB) ==="
  & $ADB -d logcat -d -v time 2>&1 | Select-String -Pattern "AJCapture|aj_audio|AriaWs|flutter" | Out-File (Join-Path $out "phone.txt") -Encoding utf8
  & $ADB -d shell "dumpsys package com.ajnetwork.aj_audio | grep versionName" 2>&1 | Out-File -Append (Join-Path $out "phone.txt") -Encoding utf8
  Write-Host "  saved $out"
}
Write-Host "`nDone - tell Claude the logs are in tools\logs32."
