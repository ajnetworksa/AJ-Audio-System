# zigbee_test.ps1 - TEST: use a Judian panel's built-in Zigbee radio with Home Assistant ZHA (see zigbee.md).
#
#   zigbee_test.bat                 BACKUP only (changes nothing on the panel)
#   zigbee_test.bat -on             backup (if not done yet) -> stop + disable the Judian gateway app ->
#                                   AJ Panel bridge on (port 6638) -> backup of the radio's Zigbee network (zigpy)
#   zigbee_test.bat -status         bridge state
#   zigbee_test.bat -off            undo: [put the Tuya network back into the radio] -> bridge off ->
#                                   re-enable the Judian gateway app -> reboot the panel
#   -panel 192.168.8.165            which panel (default X8)
#
# Needs AJ Panel 0.7.1+ on the panel. Backups go to tools\zigbee_backup\<ip>_<date>\ (git-ignored:
# zigbee_network.json holds the Zigbee network key - keep it private, do not send it to anyone).
# The radio-network backup/restore uses the open-source zigpy-cli (installed once into tools\.zigpy-venv from PyPI)
# and needs Python 3 on this PC; without Python that step is skipped (the file backup still runs).
param([string]$panel = "192.168.8.165", [switch]$on, [switch]$off, [switch]$status)
$ErrorActionPreference = "Continue"
$GW = "com.judian.gateway.c6"
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$dev = "$($panel):5555"
$root = Join-Path $PSScriptRoot "zigbee_backup"
$sock = "socket://$($panel):6638"

function A([string]$cmd) { (& $ADB -s $dev shell $cmd 2>&1 | Out-String).Trim() }
function Cfg([string]$extras) { A "am broadcast -n com.ajnetwork.ajpanel/.BootReceiver -a com.ajnetwork.ajpanel.CONFIG $extras" | Out-Null }
function Info { try { Invoke-RestMethod "http://$($panel):8766/api/info" -TimeoutSec 5 } catch { $null } }
function LastBackup { Get-ChildItem $root -Directory -Filter "$($panel)_*" -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1 }

function Zigpy {
  $venv = Join-Path $PSScriptRoot ".zigpy-venv"
  $exe = Join-Path $venv "Scripts\zigpy.exe"
  if (Test-Path $exe) { return $exe }
  $py = $null
  foreach ($c in @("py", "python")) { if (Get-Command $c -ErrorAction SilentlyContinue) { $py = $c; break } }
  if (-not $py) { Write-Host "  Python 3 not found on this PC - radio-network backup skipped (install Python from python.org and run again)." -ForegroundColor Yellow; return $null }
  Write-Host "  installing zigpy-cli (once, from PyPI) ..."
  if ($py -eq "py") { & py -3 -m venv $venv 2>&1 | Out-Null } else { & python -m venv $venv 2>&1 | Out-Null }
  & (Join-Path $venv "Scripts\python.exe") -m pip install -q --upgrade pip zigpy-cli bellows 2>&1 | Out-Null
  if (Test-Path $exe) { return $exe }
  Write-Host "  zigpy-cli install failed - radio-network backup skipped." -ForegroundColor Yellow
  return $null
}

function Backup {
  $dir = Join-Path $root ("$($panel)_" + (Get-Date -Format "yyyyMMdd_HHmmss"))
  New-Item -ItemType Directory -Force $dir | Out-Null
  Write-Host "Backup -> $dir"
  # Tuya Zigbee host files (device list, network flags, radio-firmware backups)
  & $ADB -s $dev pull /storage/emulated/0/.jd-tuya (Join-Path $dir "jd-tuya") 2>&1 | Out-Null
  A "ls -laR /storage/emulated/0/.jd-tuya; echo; ls -la /storage/emulated/0/" | Out-File (Join-Path $dir "files.txt") -Encoding utf8
  A "dumpsys package $GW" | Out-File (Join-Path $dir "gateway_package.txt") -Encoding utf8
  A "pm list packages -d; echo; pm list packages -e | grep -i judian" | Out-File (Join-Path $dir "packages.txt") -Encoding utf8
  A "getprop" | Out-File (Join-Path $dir "getprop.txt") -Encoding utf8
  A "stty -F /dev/ttyS3 -a; ls -lZ /dev/ttyS3" | Out-File (Join-Path $dir "ttyS3.txt") -Encoding utf8
  A "settings list global; settings list secure; settings list system" | Out-File (Join-Path $dir "settings.txt") -Encoding utf8
  # app data of the gateway app (Android asks on the PANEL screen: tap "Back up my data", no password)
  Write-Host "  app data: if the panel shows 'Full backup', tap 'Back up my data' on the panel (waits max 60 s)..."
  $ab = Join-Path $dir "gateway_app.ab"
  $j = Start-Job { param($a, $d, $f, $g) & $a -s $d backup -f $f -noapk $g } -ArgumentList $ADB, $dev, $ab, $GW
  Wait-Job $j -Timeout 60 | Out-Null; Stop-Job $j; Remove-Job $j -Force
  $n = (Get-ChildItem (Join-Path $dir "jd-tuya") -Recurse -File -ErrorAction SilentlyContinue).Count
  $abSize = if (Test-Path $ab) { (Get-Item $ab).Length } else { 0 }
  Write-Host ("  jd-tuya files: $n   gateway app data: " + $(if ($abSize -gt 1024) { "$abSize bytes" } else { "not available (app does not allow it - the Tuya cloud keeps that part)" }))
  if ($n -eq 0) { Write-Host "  WARNING: no Tuya Zigbee files copied - stop here and send me files.txt" -ForegroundColor Red; exit 1 }
  return $dir
}

function NetBackup([string]$dir) {
  $z = Zigpy; if (-not $z) { return }
  Write-Host "  reading the radio (zigpy info) ..."
  & $z radio --baudrate 115200 ezsp $sock info 2>&1 | Tee-Object (Join-Path $dir "radio_info.txt")
  Write-Host "  backing up the radio's Zigbee network (PAN, channel, keys) ..."
  $nb = Join-Path $dir "zigbee_network.json"
  & $z radio --baudrate 115200 ezsp $sock backup $nb 2>&1 | Tee-Object (Join-Path $dir "radio_backup_log.txt") | Out-Null
  if (Test-Path $nb) { Write-Host "  radio network saved: $nb" -ForegroundColor Green }
  else { Write-Host "  radio network backup FAILED - see radio_backup_log.txt (send it to me). Do NOT set up ZHA yet." -ForegroundColor Red }
}

& $ADB connect $dev | Out-Null
if ((& $ADB -s $dev get-state 2>$null) -ne "device") { Write-Host "Cannot reach $dev over adb." -ForegroundColor Red; exit 1 }

if ($status) {
  $i = Info
  Write-Host "gateway app: $(A "pm list packages -d | grep -c $GW") (1 = disabled)"
  if ($i) { Write-Host "AJ Panel $($i.version): bridge = $($i.zigbee_bridge)  radio->HA $($i.zigbee_rx) B, HA->radio $($i.zigbee_tx) B" }
  exit 0
}

if ($off) {
  $b = LastBackup
  $nb = if ($b) { Join-Path $b.FullName "zigbee_network.json" } else { "" }
  if ($nb -and (Test-Path $nb)) {
    $ans = Read-Host "Put the original Tuya network back into the radio from $nb ? Needed if Home Assistant created its own network. (y/n)"
    if ($ans -eq "y") {
      Cfg "--ez zigbee_bridge true --es zigbee_allow any"; Start-Sleep 4
      $z = Zigpy
      if ($z) { & $z radio --baudrate 115200 ezsp $sock restore $nb 2>&1 | Tee-Object (Join-Path $b.FullName "radio_restore_log.txt") }
    }
  }
  Cfg "--ez zigbee_bridge false --es zigbee_allow ha"
  A "pm enable $GW" | Write-Host
  Write-Host "Rebooting the panel so the Judian gateway starts normally ..."
  & $ADB -s $dev reboot
  Write-Host "Done. Smart Life should show the gateway online again in 2-3 minutes."
  exit 0
}

# default and -on: backup first
$v = A "dumpsys package com.ajnetwork.ajpanel | grep versionName"
Write-Host "Panel $panel  AJ Panel $v"
$dir = Backup

if (-not $on) { Write-Host "`nBackup done - nothing was changed on the panel. Next: zigbee_test.bat -on"; exit 0 }

if ($v -notmatch "versionName=0\.(7\.[1-9]|[89]\.)|versionName=[1-9]\.") { Write-Host "AJ Panel 0.7.1 or newer is needed (update the panel first)." -ForegroundColor Red; exit 1 }
Write-Host "`nSwitching the radio over to AJ Panel ..."
A "am force-stop $GW" | Out-Null
A "pm disable-user --user 0 $GW" | Write-Host
Cfg "--ez zigbee_bridge true --es zigbee_allow any"     # 'any' only for the zigpy backup from this PC
Start-Sleep 5
$i = Info; Write-Host "bridge: $($i.zigbee_bridge)"
NetBackup $dir
Cfg "--es zigbee_allow ha"                              # back to: only Home Assistant may connect
Start-Sleep 2
$i = Info; Write-Host "bridge: $($i.zigbee_bridge)"
Write-Host @"

Next in Home Assistant: Settings -> Devices & services -> Add integration -> Zigbee Home Automation
  -> 'Enter manually' -> radio type 'EZSP' -> path $sock  baud 115200 (leave flow control as offered)
  -> network: if HA offers to keep/restore, keep this radio's settings; otherwise 'Create a network'.
Then pair your Zigbee devices in ZHA (reset each device). Undo everything: zigbee_test.bat -off
"@
