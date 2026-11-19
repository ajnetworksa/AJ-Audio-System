# panel_inventory.ps1 - READ ONLY: complete hardware + software inventory of each panel, to design our own
# lightweight smart-home app (replacement for the vendor UI). Nothing on the panels is changed.
#   panel_inventory.bat                                  all panels in panels.txt (else X8, P8, DSPPA)
#   panel_inventory.bat -panels "192.168.8.165,192.168.8.46"
# Output: tools\inventory\<ip>\  (git-ignored): one file per topic + summary.txt, and tools\inventory\summary.txt
# for all panels. Also a screenshot of the panel's current screen (screen.png). No passwords/tokens are read.
param([string]$panels = "")
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$root = Join-Path $PSScriptRoot "inventory"
New-Item -ItemType Directory -Force $root | Out-Null
if (-not $panels) {
  $list = Join-Path $PSScriptRoot "panels.txt"
  if (Test-Path $list) { $panels = ((Get-Content $list | Where-Object { $_ -match '^\s*\d+\.\d+\.\d+\.\d+' } | ForEach-Object { $_.Split("=")[0].Trim() }) -join ",") }
  if (-not $panels) { $panels = "192.168.8.165,192.168.8.194,192.168.8.46" }
}

function Sh([string]$dev, [string]$cmd) { (& $ADB -s $dev shell $cmd 2>&1 | Out-String) }

# topic -> shell commands (all read-only)
$topics = [ordered]@{
  "01_device"     = @("getprop ro.product.manufacturer; getprop ro.product.brand; getprop ro.product.model; getprop ro.product.device; getprop ro.board.platform; getprop ro.hardware; getprop ro.build.fingerprint", "getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.version.security_patch; getprop ro.build.display.id; getprop ro.product.cpu.abilist", "uname -a", "getenforce")
  "02_cpu"        = @("cat /proc/cpuinfo", "for c in /sys/devices/system/cpu/cpu[0-9]*; do echo `$c `$(cat `$c/cpufreq/cpuinfo_min_freq 2>/dev/null) `$(cat `$c/cpufreq/cpuinfo_max_freq 2>/dev/null) `$(cat `$c/cpufreq/scaling_governor 2>/dev/null); done", "cat /sys/class/thermal/thermal_zone*/type /sys/class/thermal/thermal_zone*/temp 2>/dev/null")
  "03_memory"     = @("cat /proc/meminfo", "dumpsys meminfo | head -60", "getprop dalvik.vm.heapsize; getprop dalvik.vm.heapgrowthlimit; getprop ro.config.low_ram; getprop ro.sys.fw.bg_apps_limit")
  "04_storage"    = @("df -h", "cat /proc/partitions")
  "05_display"    = @("wm size; wm density", "dumpsys display | grep -iE 'mBaseDisplayInfo|DisplayModeRecord|density|fps|refreshRate' | head -15", "dumpsys SurfaceFlinger | grep -iE 'GLES|EGL' | head -5", "ls /sys/class/backlight/ && cat /sys/class/backlight/*/max_brightness /sys/class/backlight/*/brightness 2>/dev/null", "settings get system screen_brightness; settings get system screen_off_timeout")
  "06_input"      = @("getevent -lp 2>&1 | head -200", "dumpsys input | grep -iE 'Device [0-9]|Name:|Sources:|KeyLayoutFile|Classes' | head -60")
  "07_sensors"    = @("dumpsys sensorservice | head -60")
  "08_audio"      = @("cat /proc/asound/cards; ls /dev/snd", "dumpsys media.audio_flinger | grep -iE 'Output thread|Input thread|Sample rate|Channel count|HAL frame count|Latency|Standby|devices' | head -40", "dumpsys audio | grep -iE 'stream_|max|Devices|mode' | head -40")
  "09_network"    = @("ip addr", "cat /sys/class/net/*/address", "dumpsys wifi | grep -iE 'mWifiInfo|Supplicant|5GHz|band' | head -8", "dumpsys ethernet 2>/dev/null | head -20", "dumpsys bluetooth_manager 2>/dev/null | head -25", "dumpsys connectivity | grep -iE 'NetworkAgentInfo' | head -5")
  "10_hardware_io"= @("ls -l /dev/tty* /dev/i2c* /dev/spi* /dev/gpio* /dev/hidraw* /dev/input/* /dev/video* 2>/dev/null", "ls /sys/class/", "ls /sys/class/gpio /sys/class/leds /sys/class/pwm 2>/dev/null", "for l in /sys/class/leds/*; do echo `$l `$(cat `$l/brightness 2>/dev/null) `$(cat `$l/max_brightness 2>/dev/null); done", "ls /sys/bus/usb/devices/ 2>/dev/null; for u in /sys/bus/usb/devices/*; do [ -f `$u/product ] && echo `$u `$(cat `$u/idVendor) `$(cat `$u/idProduct) `$(cat `$u/product); done", "dumpsys consumer_ir 2>/dev/null | head -5", "dumpsys media.camera 2>/dev/null | grep -iE 'Number of camera|Device|Facing' | head -10", "ls /vendor/lib/hw /system/lib/hw 2>/dev/null")
  "11_relays_vendor" = @("ls -la /dev | grep -viE 'tty[0-9]|loop|ram|block|pts|binder|socket'", "ls /proc | grep -viE '^[0-9]+`$'", "getprop | grep -iE 'relay|gpio|jd|judian|tuya|zbg|smatek|dsppa|yjy|light|switch|panel' ", "service list | grep -iE 'jd|judian|tuya|smatek|dsppa|yjy|relay|gpio|light|vendor'")
  "12_packages"   = @("pm list packages -f", "pm list packages -3", "pm list packages -d", "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN", "dumpsys package com.google.android.webview | grep -m2 versionName; dumpsys package com.android.webview | grep -m2 versionName", "dumpsys webviewupdate 2>/dev/null | head -12")
  "13_running"    = @("dumpsys activity activities | grep -iE 'mResumedActivity|mFocusedActivity' | head -3", "ps -A -o RSS,PID,USER,NAME | sort -rn | head -45", "top -b -n 1 | head -30", "dumpsys activity services | grep -iE 'ServiceRecord' | head -60")
  "14_vendor_apps"= @("for p in `$(pm list packages | sed 's/package://' | grep -iE 'jd|judian|tuya|smatek|dsppa|yjy|gateway|launcher|panel|zigbee|smart|home|ajnetwork'); do echo == `$p; dumpsys package `$p | grep -E 'versionName|codePath|targetSdk|firstInstallTime' | head -4; dumpsys package `$p | grep -E 'android.permission\.[A-Z_]+' | sed 's/ *granted=true//' | sort -u | head -40; done")
  "15_settings"   = @("settings list system", "settings list global", "settings list secure | grep -viE 'android_id|bluetooth_address|account'")
  "16_power"      = @("dumpsys power | grep -iE 'mWakefulness|mStayOn|mScreenOffTimeout|mIsPowered|mProximity|mDisplayPowerRequest' | head -12", "dumpsys battery", "uptime")
}

$all = @()
foreach ($ip in $panels.Split(",")) {
  $ip = $ip.Trim(); if (-not $ip) { continue }
  $dev = "$($ip):5555"
  $out = Join-Path $root $ip; New-Item -ItemType Directory -Force $out | Out-Null
  Write-Host "=== $ip ==="
  & $ADB connect $dev | Out-Null
  if ((& $ADB -s $dev get-state 2>$null) -ne "device") { "adb not reachable" | Out-File (Join-Path $out "ERROR.txt") -Encoding utf8; Write-Host "  not reachable over adb" -ForegroundColor Yellow; continue }
  foreach ($t in $topics.Keys) {
    $txt = @()
    foreach ($c in $topics[$t]) { $txt += "### $c"; $txt += Sh $dev $c }
    $txt | Out-File (Join-Path $out "$t.txt") -Encoding utf8
    Write-Host "  $t"
  }
  # what the panel shows right now (vendor home screen etc.)
  & $ADB -s $dev shell "screencap -p /sdcard/aj_inv_screen.png" | Out-Null
  & $ADB -s $dev pull /sdcard/aj_inv_screen.png (Join-Path $out "screen.png") 2>&1 | Out-Null
  & $ADB -s $dev shell "rm /sdcard/aj_inv_screen.png" | Out-Null

  # one-line summary for the overview
  $model = (Sh $dev "getprop ro.product.model").Trim()
  $soc = (Sh $dev "getprop ro.board.platform").Trim()
  $android = (Sh $dev "getprop ro.build.version.release").Trim()
  $cores = (Sh $dev "grep -c ^processor /proc/cpuinfo").Trim()
  $maxf = (Sh $dev "cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>/dev/null").Trim()
  $mem = (Sh $dev "grep -E 'MemTotal|MemAvailable' /proc/meminfo").Trim() -replace '\s+', ' '
  $screen = ((Sh $dev "wm size") -replace 'Physical size: ', '').Trim() + " @" + ((Sh $dev "wm density") -replace 'Physical density: ', '').Trim()
  $data = (Sh $dev "df -h /data | tail -1").Trim() -replace '\s+', ' '
  $homeApp = (Sh $dev "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1").Trim()
  $wv = (Sh $dev "dumpsys webviewupdate 2>/dev/null | grep -m1 'Current WebView package'").Trim()
  $line = "{0,-15} {1} | SoC {2} | {3} cores max {4} kHz | {5} | screen {6} | Android {7} | /data {8} | home: {9} | {10}" -f $ip, $model, $soc, $cores, $maxf, $mem, $screen, $android, $data, $homeApp, $wv
  $line | Out-File (Join-Path $out "summary.txt") -Encoding utf8
  $all += $line
  Write-Host "  saved $out"
}
$all | Out-File (Join-Path $root "summary.txt") -Encoding utf8
Write-Host "`nDone - tell Claude the inventory is in tools\inventory."
