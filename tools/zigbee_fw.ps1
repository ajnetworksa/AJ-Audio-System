# zigbee_fw.ps1 - TEST: change the Zigbee radio firmware of ONE Judian panel (for Zigbee2MQTT), with backups and a way back.
# Read zigbee.md "Radio firmware change" first. Start with the X8 (192.168.8.165) only.
#
#   zigbee_fw.bat                       = -probe. READ ONLY on the radio: backs up the radio's Zigbee network again,
#                                         identifies the radio (EZSP/stack version, board, IEEE, BOOTLOADER yes/no) and
#                                         copies any Tuya radio-firmware file it finds on the panel. Changes nothing.
#   zigbee_fw.bat -flash <file.gbl>     WRITES the radio: probe + backup first, asks you to type FLASH, then flashes the
#                                         .gbl through the radio's own bootloader (over AJ Panel's bridge, port 6638).
#                                         The bootloader itself is never touched, so another .gbl can always be flashed
#                                         as long as the new firmware answers on the serial line (see zigbee.md).
#   zigbee_fw.bat -revert <file.gbl>    same as -flash, for going back (older EZSP v7 / Tuya image), then tells you how
#                                         to give the radio back to the Judian gateway (zigbee_test.bat -off)
#   zigbee_fw.bat -restorenet           WRITES the radio's network settings: puts the last zigbee_network.json back
#                                         (keeps PAN/channel/key so paired devices stay on the network)
#   -panel 192.168.8.165
#   -baud 115200 -flow rtscts|none         how the radio talks NOW (default: Tuya firmware = 115200 + rtscts)
#   -newbaud / -newflow                    how the NEW firmware talks after -flash/-revert (default: same as now)
#
# Before you start: the Judian gateway app must be off (zigbee_test.bat -on done) and Home Assistant must NOT use this
# radio (disable ZHA / stop Zigbee2MQTT). Needs AJ Panel 0.9.7+ and Python 3 on this PC (tools\.zigpy-venv).
# Output: tools\zigbee_backup\<ip>_fw_<date>\  (git-ignored; zigbee_network.json holds the network key - keep it private)
param([string]$panel = "192.168.8.165", [string]$flash = "", [string]$revert = "", [switch]$restorenet, [switch]$probe,
      [int]$baud = 115200, [ValidateSet("rtscts", "none")][string]$flow = "rtscts",
      [int]$newbaud = 0, [string]$newflow = "")
if ($newbaud -le 0) { $newbaud = $baud }
if (-not $newflow) { $newflow = $flow }
if ($newflow -notin @("rtscts", "none")) { Write-Host "-newflow must be rtscts or none"; exit 1 }
$ErrorActionPreference = "Continue"
$GW = "com.judian.gateway.c6"
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$dev = "$($panel):5555"
$root = Join-Path $PSScriptRoot "zigbee_backup"
$sock = "socket://$($panel):6638"
$venv = Join-Path $PSScriptRoot ".zigpy-venv"
$vpy = Join-Path $venv "Scripts\python.exe"

function A([string]$cmd) { (& $ADB -s $dev shell $cmd 2>&1 | Out-String).Trim() }
function Cfg([string]$extras) { A "am broadcast -n com.ajnetwork.ajpanel/.BootReceiver -a com.ajnetwork.ajpanel.CONFIG $extras" | Out-Null }
function Info { try { Invoke-RestMethod "http://$($panel):8766/api/info" -TimeoutSec 5 } catch { $null } }
function Stop-Here([string]$msg) { Write-Host $msg -ForegroundColor Red; Cfg "--es zigbee_allow ha --es zigbee_flow $flow --ei zigbee_baud $baud"; exit 1 }

function Py {
  if (-not (Test-Path $vpy)) {
    Write-Host "Python tools not installed yet - run zigbee_test.bat -netbackup once (it installs them), then run this again." -ForegroundColor Yellow
    exit 1
  }
  & $vpy -m pip install -q --upgrade zigpy-cli bellows universal-silabs-flasher 2>&1 | Out-String | Write-Host
  return $vpy
}

function Probe([string]$dir, [int]$b) {
  $py = Py
  Write-Host "  identifying the radio ..."
  & $py (Join-Path $PSScriptRoot "zigbee_fw_probe.py") $sock $b 2>&1 | Tee-Object (Join-Path $dir "radio_probe.txt") | Out-Host
  $r = Get-Content (Join-Path $dir "radio_probe.txt") -Raw -ErrorAction SilentlyContinue
  if ($r -match "RESULT bootloader=yes") { return "yes" }
  if ($r -match "RESULT bootloader=none") { return "none" }
  return "unknown"
}

function NetBackup([string]$dir) {
  $z = Join-Path $venv "Scripts\zigpy.exe"
  $nb = Join-Path $dir "zigbee_network.json"
  Write-Host "  backing up the radio's Zigbee network ..."
  & $z radio --baudrate $baud ezsp $sock backup $nb 2>&1 | Out-File (Join-Path $dir "radio_backup_log.txt") -Encoding utf8
  return (Test-Path $nb)
}

function Flasher([string]$dir, [string]$fw) {
  $exe = Join-Path $venv "Scripts\universal-silabs-flasher.exe"
  $help = (& $exe --help 2>&1 | Out-String)
  $log = Join-Path $dir "flash_log.txt"
  if ($help -match "--probe-methods") {
    & $exe --device $sock --probe-methods "ezsp:$baud" flash --firmware $fw 2>&1 | Tee-Object $log | Out-Host
  } else {
    & $exe --device $sock --probe-method ezsp flash --firmware $fw --allow-cross-flashing 2>&1 | Tee-Object $log | Out-Host
  }
  return $LASTEXITCODE
}

# ---------------------------------------------------------------- checks (read only)
& $ADB connect $dev | Out-Null
if ((& $ADB -s $dev get-state 2>$null) -ne "device") { Write-Host "Cannot reach $dev over adb." -ForegroundColor Red; exit 1 }
$i = Info
if (-not $i) { Write-Host "AJ Panel does not answer on $($panel):8766." -ForegroundColor Red; exit 1 }
if ("$($i.version)" -notmatch "^(0\.9\.([7-9]|\d\d)|0\.\d\d|[1-9]\.)") { Write-Host "AJ Panel 0.9.7 or newer is needed (panel has $($i.version))." -ForegroundColor Red; exit 1 }
if ((A "pm list packages -d | grep -c $GW") -ne "1") { Write-Host "The Judian gateway app still uses the radio - run zigbee_test.bat -on first." -ForegroundColor Red; exit 1 }
if ("$($i.zigbee_bridge)" -like "connected to*") {
  Write-Host "Home Assistant is using this radio now ($($i.zigbee_bridge))." -ForegroundColor Red
  Write-Host "Disable it first: HA -> Settings -> Devices & services -> Zigbee Home Automation -> (three dots) Disable  (or stop the Zigbee2MQTT add-on)."
  exit 1
}

$dir = Join-Path $root ("$($panel)_fw_" + (Get-Date -Format "yyyyMMdd_HHmmss"))
New-Item -ItemType Directory -Force $dir | Out-Null
Write-Host "Panel $panel  AJ Panel $($i.version)  -> $dir"
Cfg "--ez zigbee_bridge true --es zigbee_allow any --es zigbee_flow $flow --ei zigbee_baud $baud"; Start-Sleep 4

# Tuya radio-firmware files the gateway may have kept (the way back to the exact original firmware, if found)
Write-Host "  looking for Tuya radio-firmware files on the panel ..."
$found = A "for d in /sdcard /storage/emulated/0 /data/local/tmp /tuya /tmp /vendor/etc /vendor/firmware /system/etc; do find `$d -maxdepth 4 \( -iname '*ncp*' -o -iname '*.ota' -o -iname '*.gbl' -o -iname '*.ebl' \) 2>/dev/null; done"
$found | Out-File (Join-Path $dir "tuya_firmware_files.txt") -Encoding utf8
foreach ($f in ($found -split "`n" | Where-Object { $_.Trim() -and $_ -notmatch "_flag$" })) {
  & $ADB -s $dev pull $f.Trim() (Join-Path $dir "tuya_fw") 2>&1 | Out-Null
}
if (Test-Path (Join-Path $dir "tuya_fw")) { Write-Host "  copied Tuya firmware file(s) -> $dir\tuya_fw  (keep them: they are the way back)" -ForegroundColor Green }
else { Write-Host "  no Tuya radio-firmware file on the panel (the gateway downloads it from the Tuya cloud only when updating)." }

$bl = Probe $dir $baud
$net = NetBackup $dir
Write-Host ("  network backup: " + $(if ($net) { "OK ($dir\zigbee_network.json)" } else { "FAILED (see radio_backup_log.txt)" }))

if ($restorenet) {
  $last = Get-ChildItem $root -Recurse -Filter zigbee_network.json | Where-Object { $_.DirectoryName -ne $dir } | Sort-Object LastWriteTime | Select-Object -Last 1
  if (-not $last) { Stop-Here "No earlier zigbee_network.json found." }
  $ans = Read-Host "Write the network from $($last.FullName) into the radio? (y/n)"
  if ($ans -eq "y") {
    $z = Join-Path $venv "Scripts\zigpy.exe"
    & $z radio --baudrate $baud ezsp $sock restore $last.FullName 2>&1 | Tee-Object (Join-Path $dir "radio_restore_log.txt") | Out-Host
  }
  Cfg "--es zigbee_allow ha"; exit 0
}

$fw = if ($flash) { $flash } elseif ($revert) { $revert } else { "" }
if (-not $fw) {
  Cfg "--es zigbee_allow ha"
  Write-Host "`nProbe done - nothing was written to the radio. Send me $dir\radio_probe.txt (no secrets in it)."
  if ($bl -eq "none") { Write-Host "This radio has NO serial bootloader: it can only be reflashed with an SWD debugger (panel opened). Do not try -flash." -ForegroundColor Yellow }
  exit 0
}

# ---------------------------------------------------------------- flashing (writes the radio)
if (-not (Test-Path $fw)) { Stop-Here "Firmware file not found: $fw" }
if ($fw -notmatch "\.gbl$") { Stop-Here "Only .gbl files (Gecko bootloader images) are accepted." }
if ($bl -ne "yes") { Stop-Here "The radio did not report a bootloader (result: $bl) - NOT flashing. Send me radio_probe.txt." }
if (-not $net) { Stop-Here "No fresh network backup - NOT flashing. Send me radio_backup_log.txt." }
Copy-Item $fw $dir
Write-Host "`nAbout to flash $(Split-Path $fw -Leaf) into the Zigbee radio of $panel." -ForegroundColor Yellow
Write-Host "Keep the panel powered. Afterwards the radio expects $newbaud baud, flow control $newflow (must match the firmware)."
$ans = Read-Host "Type FLASH to continue"
if ($ans -ne "FLASH") { Stop-Here "Cancelled - nothing was written." }

Write-Host "  bridge: no flow control while flashing (the bootloader does not use RTS/CTS) ..."
Cfg "--es zigbee_flow none --ei zigbee_baud 115200"; Start-Sleep 4
$code = Flasher $dir $fw
Write-Host "  bridge: $newbaud baud, flow $newflow for the new firmware ..."
Cfg "--es zigbee_flow $newflow --ei zigbee_baud $newbaud"; Start-Sleep 6
$after = Join-Path $dir "after"; New-Item -ItemType Directory -Force $after | Out-Null
$bl2 = Probe $after $newbaud
Cfg "--es zigbee_allow ha"
if ($code -ne 0) {
  Write-Host "`nThe flasher reported an error (exit $code) - see $dir\flash_log.txt. Do not unplug the panel; send me the folder." -ForegroundColor Red
  Write-Host "If the radio stays in its bootloader, flashing the same or another .gbl again usually works."
  exit 1
}
if ((Get-Content (Join-Path $after "radio_probe.txt") -Raw) -notmatch "EZSP protocol") {
  Write-Host "`nFlashed, but the new firmware does not answer as EZSP at $newbaud/$newflow. Try the other flow/baud:" -ForegroundColor Yellow
  Write-Host "  zigbee_fw.bat -probe -baud 115200 -flow none      (or -baud 460800). Send me the folder."
  Write-Host "  The bridge now stays on $newbaud/$newflow; the panel app setting is kept until you change it."
  exit 1
}
Write-Host "`nDone - the radio runs the new firmware (see $after\radio_probe.txt)." -ForegroundColor Green
if ($revert) { Write-Host "To give the radio back to the Judian gateway / Smart Life: zigbee_test.bat -off" }
else { Write-Host "Next: zigbee_fw.bat -restorenet (keep the old network) or set up Zigbee2MQTT as in zigbee.md." }
