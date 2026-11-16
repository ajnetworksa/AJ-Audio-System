# deploy_ajpanel.ps1 - install / update AJ Panel on panels and point it at Music Assistant.
# CHANGES the panels: installs AJ Panel, grants overlay permission, stops the old Snapcast app player,
# tells ControlX Bridge to stop joining a group. Logs -> logs26\<ip>.txt
#
#   deploy_ajpanel.bat                                   (all 3 panels; downloads the newest AJ Panel from GitHub Releases first)
#   deploy_ajpanel.bat -local                            (no download: newest apk already in apps\panel\releases)
#   deploy_ajpanel.bat -apk "C:\path\AJ_Panel_v0.6.0.apk" (exactly this file)
#   deploy_ajpanel.bat -panels "192.168.8.46=Living"     (one panel, custom name)
#   deploy_ajpanel.bat -disableOld                       (also disable the Snapcast app so it can't start again)
#   deploy_ajpanel.bat -uninstall                        (remove AJ Panel, re-enable Snapcast app)
#   deploy_ajpanel.bat -panels "192.168.8.46=dsppa938t" -asHome     (AJ Panel becomes the home screen; Judian panels are skipped)
#   deploy_ajpanel.bat -panels "192.168.8.46=dsppa938t" -restoreHome (vendor home screen again)
param(
  [string]$ma = "192.168.8.5",
  [string]$panels = "192.168.8.194=P8_EN,192.168.8.73=M20_EN,192.168.8.46=dsppa938t",
  [string]$apk = "",              # a specific apk file; empty = newest GitHub release (downloaded) or newest in apps\panel\releases
  [string]$repo = "ajnetworksa/AJ-Audio-System",
  [switch]$local,                 # don't look at GitHub, use the newest apk in apps\panel\releases
  [string]$token = "",
  [string]$user = "",
  [switch]$disableOld,
  [switch]$asHome,          # DSPPA: make AJ Panel the panel's home screen (undo: -asHome:$false -restoreHome)
  [switch]$restoreHome,
  [switch]$uninstall
)
$ErrorActionPreference = "Continue"
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
function Ver([string]$v) { $p = $v.Split('.'); while ($p.Count -lt 3) { $p += '0' }; return [version]($p[0..2] -join '.') }
$relDir = Join-Path $here "..\apps\panel\releases"
New-Item -ItemType Directory -Force $relDir | Out-Null
function NewestLocal { Get-ChildItem (Join-Path $relDir "AJ_Panel_v*.apk") -ErrorAction SilentlyContinue | Sort-Object { Ver ($_.BaseName -replace '^AJ_Panel_v','') } | Select-Object -Last 1 }
if ($apk -eq "" -and -not $local -and -not $uninstall) {
  # newest release from GitHub -> apps\panel\releases (skipped when that version is already there)
  try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/latest" -Headers @{ "User-Agent" = "aj-deploy" } -TimeoutSec 20
    $asset = $rel.assets | Where-Object { $_.name -match '^AJ_Panel_v([0-9.]+)\.apk$' } | Select-Object -First 1
    if ($asset) {
      $remoteVer = $asset.name -replace '^AJ_Panel_v','' -replace '\.apk$',''
      $have = NewestLocal
      $haveVer = if ($have) { $have.BaseName -replace '^AJ_Panel_v','' } else { "0" }
      if ((Ver $remoteVer) -gt (Ver $haveVer)) {
        Write-Host "downloading $($asset.name) from GitHub release $($rel.tag_name) ..."
        $dest = Join-Path $relDir $asset.name
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile "$dest.part" -UseBasicParsing -TimeoutSec 300
        Move-Item -Force "$dest.part" $dest
      } else { Write-Host "newest GitHub release is v$remoteVer - already in apps\panel\releases" }
    } else { Write-Host "GitHub release $($rel.tag_name) has no AJ_Panel apk - using the local one" }
  } catch { Write-Host "could not reach GitHub ($($_.Exception.Message)) - using the newest local apk" }
}
if ($apk -eq "") { $n = NewestLocal; if ($n) { $apk = $n.FullName } }
if (-not $uninstall -and ($apk -eq "" -or -not (Test-Path $apk))) { Write-Host "No AJ Panel apk found (GitHub not reachable and apps\panel\releases is empty)."; exit 1 }
if (-not $uninstall) { Write-Host "AJ Panel apk: $(Split-Path -Leaf $apk)" }
$logs = Join-Path (Get-Location) "logs26"; New-Item -ItemType Directory -Force $logs | Out-Null
$PKG = "com.ajnetwork.ajpanel"

$pass = ""
if (-not $uninstall -and $token -eq "") {
  Write-Host "The panels log in to Music Assistant with their own MA user (works next to your Home Assistant login)."
  Write-Host "Create it once: Music Assistant -> Settings -> Users -> Add user (e.g. 'panels', role User, password 8+ chars)."
  if ($user -eq "") { $user = Read-Host "MA username for the panels (Enter = skip)" }
  if ($user -ne "") {
    $sec = Read-Host "Password for $user" -AsSecureString
    $pass = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
  }
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
  $inst = & $ADB -s $dev shell pm install -r -g /data/local/tmp/ajpanel.apk
  $inst | Tee-Object -Append $log
  if ("$inst" -match "UPDATE_INCOMPATIBLE|signatures do not match") {
    Write-Host "!! $ip has AJ Panel signed with a different key. Run once: deploy_ajpanel.bat -panels `"$ip=$name`" -uninstall  (then deploy again; the panel keeps its player id via its MAC)" -ForegroundColor Yellow
  }
  & $ADB -s $dev shell rm /data/local/tmp/ajpanel.apk

  # permissions: floating card + keep running
  & $ADB -s $dev shell appops set $PKG SYSTEM_ALERT_WINDOW allow | Tee-Object -Append $log
  # allow updates pushed from the AJ Setup app (one tap "Install" on the panel)
  & $ADB -s $dev shell appops set $PKG REQUEST_INSTALL_PACKAGES allow | Tee-Object -Append $log
  & $ADB -s $dev shell dumpsys deviceidle whitelist +$PKG | Tee-Object -Append $log
  & $ADB -s $dev shell cmd appops set $PKG RUN_IN_BACKGROUND allow 2>$null | Out-Null
  # "Share panel sound" (YouTube etc. -> other rooms): no questions on the panel screen
  & $ADB -s $dev shell pm grant $PKG android.permission.RECORD_AUDIO 2>$null | Out-Null
  & $ADB -s $dev shell appops set $PKG PROJECT_MEDIA allow | Tee-Object -Append $log
  & $ADB -s $dev shell cmd notification allow_listener $PKG/$PKG.MediaListener 2>$null | Tee-Object -Append $log

  # old player off (AJ Panel has its own) - otherwise the panel plays twice / orphan process (BUG-015)
  & $ADB -s $dev shell am start-foreground-service -n de.badaix.snapcast/.SnapclientService -a ACTION_STOP 2>$null | Out-Null
  Start-Sleep 1
  & $ADB -s $dev shell am force-stop de.badaix.snapcast | Out-Null
  if ($disableOld) { & $ADB -s $dev shell pm disable-user --user 0 de.badaix.snapcast | Tee-Object -Append $log }
  # ControlX Bridge: keep Control X working, but stop it from (re)joining the Snapcast app to a server
  & $ADB -s $dev shell am broadcast -n com.ajnetwork.cxbridge/.BootReceiver -a com.ajnetwork.cxbridge.START --es join "''" 2>$null | Out-Null

  # configure + start
  # -f 32 = FLAG_INCLUDE_STOPPED_PACKAGES (app was never opened yet)
  $cfg = @("shell", "am", "broadcast", "-f", "32", "-n", "$PKG/.BootReceiver", "-a", "$PKG.CONFIG", "--es", "host", $ma, "--es", "name", "'$name'")
  # stable Music Assistant player id from the Wi-Fi/LAN MAC (used only if the panel has no id yet, e.g. after a reinstall)
  $mac = (& $ADB -s $dev shell "cat /sys/class/net/wlan0/address 2>/dev/null || cat /sys/class/net/eth0/address 2>/dev/null" | Select-Object -First 1)
  if ($mac -and ("$mac".Trim().ToLower() -match '^([0-9a-f]{2}:){5}[0-9a-f]{2}$')) {
    $mac = "$mac".Trim().ToLower().Replace(":", "")
    if ($mac -ne "000000000000" -and $mac -ne "020000000000") { $cfg += @("--es", "host_id_default", "ajpanel_$mac") }
  }
  if ($token -ne "") { $cfg += @("--es", "token", $token) }
  & $ADB -s $dev @cfg | Tee-Object -Append $log
  if ($pass -ne "") {
    # panel logs in once and keeps only a long-lived token (password not stored on the panel, not written to the log)
    $q = "'" + $pass.Replace("'", "'\''") + "'"
    & $ADB -s $dev shell am broadcast -f 32 -n "$PKG/.BootReceiver" -a "$PKG.LOGIN" --es user "'$user'" --es pass $q | Out-Null
  }
  $judian = (& $ADB -s $dev shell pm list packages com.judian.jdmediarender) -match "jdmediarender"
  if ($judian) {
    # Judian keeps its smart-home UI: AJ Panel runs in the background and puts the song into the Judian music player
    Write-Host "Judian panel: AJ Panel runs in background, song shown in the panel's own music player"
    "judian panel: background mode" | Out-File -Append $log -Encoding utf8
  } else {
    if ($asHome) {
      & $ADB -s $dev shell pm enable "$PKG/.HomeAlias" | Tee-Object -Append $log
      & $ADB -s $dev shell cmd package set-home-activity "$PKG/.HomeAlias" | Tee-Object -Append $log
      Write-Host "AJ Panel is now the home screen (Home button opens it). Undo with -restoreHome"
    }
    if ($restoreHome) {
      & $ADB -s $dev shell pm disable "$PKG/.HomeAlias" | Tee-Object -Append $log
      Write-Host "Vendor home screen restored (press Home once and pick it if Android asks)"
    }
    & $ADB -s $dev shell am start -n "$PKG/.MainActivity" | Tee-Object -Append $log
  }
}

Write-Host "`nwaiting 12 s for the players to connect..."
Start-Sleep 12
foreach ($entry in $panels.Split(",")) {
  $ip = $entry.Split("=")[0]; $dev = "$($ip):5555"; $log = Join-Path $logs "$ip.txt"
  if ($uninstall) { continue }
  "--- AJPanel log ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev logcat -d -v time -s AJPanel | Out-File -Append $log -Encoding utf8
  "--- processes ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "ps -A | grep -E 'snapclient|ajpanel|badaix'" | Out-File -Append $log -Encoding utf8
  "--- audio tracks ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "dumpsys media.audio_flinger | grep -E 'Standby|active|Underruns' | head -20" | Out-File -Append $log -Encoding utf8
  "--- media sessions ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "dumpsys media_session | grep -E 'package=|state=|metadata' | head -20" | Out-File -Append $log -Encoding utf8
  "--- connections ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "netstat -tn | grep -E ':1704|:8095'" | Out-File -Append $log -Encoding utf8
  $ok = Select-String -Path $log -Pattern "1704.*ESTABLISHED" -Quiet
  Write-Host ("{0}: sync player {1}" -f $ip, $(if ($ok) { "CONNECTED" } else { "not connected - see $log" }))
}
Write-Host "`nIn Music Assistant the panels now appear as NEW players (ma_ajpanel_...). Re-create your groups with them,"
Write-Host "and disable the old Snapcast-app players in MA -> Settings -> Players."
