# panel_debloat.ps1 - remove apps the panels don't need, to free RAM/CPU for our own smart-home app.
#   panel_debloat.bat -panels 192.168.8.194                 DRY RUN: shows what would go, changes nothing
#   panel_debloat.bat -panels 192.168.8.194 -apply          stage 1: extras, demo/test apps, Google, vendor extras
#   panel_debloat.bat -panels 192.168.8.194 -apply -stage 2 stage 2: vendor home screen + vendor smart-home/music apps
#                                                            (AJ Panel becomes the home screen first) - ONLY when our
#                                                            smart-home UI is ready
#   panel_debloat.bat -panels 192.168.8.194 -restore        puts every app this script removed back
# How: "pm uninstall -k --user 0" (no root). System/vendor apps stay in the firmware and come back with
# "cmd package install-existing" (-restore). Downloaded apps (YouTube, Spotify...) need reinstalling if wanted again.
# Never touched: Android core, Settings, keyboard in use, the WebView provider, the current home app (stage 1),
# hardware services (Judian jdservice, DSPPA yjyservice/smatek, sznaner relay/knob/485), AJ apps.
# Log + list of removed apps: tools\debloat\<ip>\
param([string]$panels = "", [switch]$apply, [int]$stage = 1, [switch]$restore)
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$PKG = "com.ajnetwork.ajpanel"
if (-not $panels) { Write-Host "Give -panels <ip> (test ONE panel first)" -ForegroundColor Yellow; exit 1 }

# ---------------- what goes ----------------
$stage1 = @(
  # extras every panel has
  "com.android.calendar", "com.android.deskclock", "com.android.music", "com.android.traceur", "com.android.bluetoothmidiservice",
  "com.android.dreams.phototable", "com.android.egg", "com.android.printservice.recommendation", "com.android.calculator2",
  "com.android.email", "com.android.gallery3d", "com.android.gl2jni", "com.android.quicksearchbox", "com.android.soundrecorder",
  "com.android.contacts", "com.android.bookmarkprovider", "com.android.mms.service",
  "com.google.android.apps.inputmethod.zhuyin", "com.vng.inputmethod.labankey", "com.svox.pico", "com.radio.fmradio",
  "acr.browser.barebones", "org.chromium.webview_shell",
  # factory / test tools
  "com.ayst.factorytest", "com.cghs.stresstest", "com.DeviceTest", "com.rockchip.devicetest", "com.softwinner.awlogsettings",
  # downloaded media apps (phones play to the panels instead)
  "com.google.android.youtube", "com.spotify.music", "com.pandora.android", "com.appgeneration.itunerfree", "de.yaacc",
  "io.github.jqssun.airplay", "com.amazon.dee.app", "com.ajnetwork.cxbridge",
  # Google services + Play Store (biggest RAM user on the DSPPA: ~460 MB) - nothing of ours needs them
  "com.android.vending", "com.google.android.gms", "com.google.android.gsf", "com.google.android.partnersetup",
  "com.google.android.syncadapters.calendar", "com.google.android.syncadapters.contacts", "com.google.android.backuptransport",
  "com.google.android.configupdater", "com.android.chrome",
  # screen-mirroring receiver (showed up in Music Assistant as a flapping "EShare" player)
  "com.eshare.receiver.tv", "com.eshare.sdk.server",
  # vendor extras: Judian gallery, intercom, vendor OTA updater; DSPPA alarm clock; px30 demo/test
  "com.judian.gallery", "com.judian.indoor", "com.judian.indoorv2", "com.judian.jdupdate",
  "com.yijiayin.alarmclock", "com.sznaner.testdemo"
)
$stage2 = @(
  # vendor home screens + their smart-home / music / Tuya apps - replaced by AJ Panel
  "com.judian.bmlauncher", "com.judian.jdSkin", "com.judian.jdmediarender", "com.judian.jdsmart",
  "com.yjy.yjyhomeui", "com.yjy.musicplayer", "com.yjy.tuyaiot",
  "com.sznaner.bgmz9", "com.smartpanel.dlna"
  # NOT here on purpose: com.judian.gateway.c6 (Tuya Zigbee - remove only where Zigbee runs via HA or isn't used),
  # com.judian.jdservice / jdcustom / setupwizard, yijiayin.yjyservice, yjy.kgxuanfu, smatek, sznaner relay/knob/485/settings
)
$never = "^(android|com\.android\.(systemui|settings|shell|phone|providers\..*|packageinstaller|permissioncontroller|webview|inputmethod\.latin|bluetooth|networkstack.*|externalstorage|documentsui|location\.fused)|com\.google\.android\.webview|com\.ajnetwork\..*|com\.ajnetweb\..*|com\.judian\.(jdservice|jdcustom|setupwizard|gateway\.c6)|com\.yijiayin\.yjyservice|com\.smatek\..*|com\.sznaner\.(relay|rotaryknob|a485set|airconditioning485|settings|powermenu|pulldownmenu|volumedialog))$"

function Sh([string]$dev, [string]$cmd) { (& $ADB -s $dev shell $cmd 2>&1 | Out-String).Trim() }
function FreeMB([string]$dev) { [int]((Sh $dev "grep MemAvailable /proc/meminfo") -replace '\D', '') / 1024 }

foreach ($ip in $panels.Split(",")) {
  $ip = $ip.Trim(); if (-not $ip) { continue }
  $dev = "$($ip):5555"
  $out = Join-Path $PSScriptRoot "debloat\$ip"; New-Item -ItemType Directory -Force $out | Out-Null
  $log = Join-Path $out "debloat.log"
  Write-Host "=== $ip ==="
  & $ADB connect $dev | Out-Null
  if ((& $ADB -s $dev get-state 2>$null) -ne "device") { Write-Host "  not reachable over adb" -ForegroundColor Yellow; continue }
  "`n==== $(Get-Date -Format s) stage $stage apply=$apply restore=$restore" | Out-File -Append $log -Encoding utf8

  if ($restore) {
    $files = Get-ChildItem $out -Filter "removed_*.txt" -ErrorAction SilentlyContinue
    $all = @($files | ForEach-Object { Get-Content $_.FullName }) | Where-Object { $_ } | Sort-Object -Unique
    foreach ($p in $all) { $r = Sh $dev "cmd package install-existing $p"; Write-Host "  $p : $r"; "restore $p : $r" | Out-File -Append $log -Encoding utf8 }
    Write-Host "  restored $($all.Count) apps (downloaded apps that failed must be reinstalled by hand)"
    continue
  }

  $installed = (Sh $dev "pm list packages") -split "`n" | ForEach-Object { $_.Trim() -replace '^package:', '' } | Where-Object { $_ }
  $homeApp = ((Sh $dev "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN") -split "`n")[-1].Split("/")[0].Trim()
  $webview = ([regex]::Match((Sh $dev "dumpsys webviewupdate"), "Current WebView package \(name, version\): \(([^,]+),")).Groups[1].Value
  $ime = (Sh $dev "settings get secure default_input_method").Split("/")[0]
  Write-Host "  home: $homeApp   webview: $webview   keyboard: $ime   free RAM: $(FreeMB $dev) MB"
  $want = if ($stage -ge 2) { $stage2 } else { $stage1 }
  $go = @()
  foreach ($p in $want) {
    if ($installed -notcontains $p) { continue }
    $why = ""
    if ($p -match $never) { $why = "protected" }
    elseif ($p -eq $webview) { $why = "is the WebView provider" }
    elseif ($p -eq $ime) { $why = "is the keyboard in use" }
    elseif ($stage -lt 2 -and $p -eq $homeApp) { $why = "is the home screen (stage 2)" }
    if ($why) { Write-Host "  keep   $p ($why)" -ForegroundColor DarkGray } else { $go += $p; Write-Host "  remove $p" }
  }
  if (-not $apply) { Write-Host "  DRY RUN - nothing changed. Add -apply to remove these $($go.Count) apps." -ForegroundColor Cyan; continue }

  if ($stage -ge 2) {
    # AJ Panel must be the home screen BEFORE the vendor launcher goes, or the panel has no home screen
    Sh $dev "pm enable $PKG/.HomeAlias" | Out-Null
    $r = Sh $dev "cmd package set-home-activity $PKG/.HomeAlias"
    $now = ((Sh $dev "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN") -split "`n")[-1]
    if ($now -notmatch [regex]::Escape($PKG)) { Write-Host "  AJ Panel could not become the home screen ($r) - stage 2 stopped, nothing removed" -ForegroundColor Red; continue }
    Write-Host "  AJ Panel is the home screen"
  }
  $before = FreeMB $dev
  $done = @()
  foreach ($p in $go) {
    Sh $dev "am force-stop $p" | Out-Null
    $r = Sh $dev "pm uninstall -k --user 0 $p"
    "uninstall $p : $r" | Out-File -Append $log -Encoding utf8
    if ($r -match "Success") { $done += $p; Write-Host "  removed $p" } else { Write-Host "  $p : $r" -ForegroundColor Yellow }
  }
  $done | Out-File (Join-Path $out ("removed_{0}_stage{1}.txt" -f (Get-Date -Format yyyyMMdd_HHmmss), $stage)) -Encoding ascii
  Start-Sleep 5
  $after = FreeMB $dev
  Write-Host ("  removed {0} apps - free RAM {1} MB -> {2} MB (reboot the panel for the full effect)" -f $done.Count, $before, $after) -ForegroundColor Green
  "free RAM $before -> $after MB" | Out-File -Append $log -Encoding utf8
}
