# aj_tools.ps1 - one menu for all AJ Audio PC tools (troubleshooting from a PC). Start: aj_tools.bat
# Panels are listed in tools\panels.txt (one "address=Room name" per line) - edit it for each site.
$ErrorActionPreference = "Continue"
$here = $PSScriptRoot
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$list = Join-Path $here "panels.txt"
if (-not (Test-Path $list)) {
  "# one panel per line: address=Room name`r`n192.168.8.165=X8`r`n192.168.8.194=P8_EN`r`n192.168.8.46=dsppa938t" | Out-File $list -Encoding ascii
}
function Panels { Get-Content $list | Where-Object { $_ -match '^\s*\d+\.\d+\.\d+\.\d+' } | ForEach-Object { $p = $_.Trim().Split("=", 2); [pscustomobject]@{ ip = $p[0].Trim(); name = $(if ($p.Count -gt 1) { $p[1].Trim() } else { $p[0].Trim() }) } } }
function Pick([string]$what) {
  $ps = @(Panels)
  Write-Host "`n$what"
  for ($i = 0; $i -lt $ps.Count; $i++) { Write-Host ("  {0}) {1}  {2}" -f ($i + 1), $ps[$i].name, $ps[$i].ip) }
  Write-Host "  A) all panels"
  $c = Read-Host "Choose"
  # callers wrap the result in @( ): Windows PowerShell 5.1 turns a one-panel result into a single object without .Count
  if ($c -match '^[aA]$') { return $ps }
  $n = 0; if ([int]::TryParse($c, [ref]$n) -and $n -ge 1 -and $n -le $ps.Count) { return @($ps[$n - 1]) }
  return @()
}
function Run([string]$bat, [string]$params) { $ps1 = Join-Path $here ($bat -replace '\.bat$', '.ps1'); Push-Location $here; try { Invoke-Expression "& '$ps1' $params" } finally { Pop-Location } }

while ($true) {
  Write-Host "`n================ AJ Audio tools ================" -ForegroundColor Green
  Write-Host " 1) Status of all panels (version, server, sync, updates, zigbee)"
  Write-Host " 2) Install / update AJ Panel (deploy)"
  Write-Host " 3) Silent + automatic updates on"
  Write-Host " 4) Collect logs (all panels; add phone on USB)"
  Write-Host " 5) Locate a panel (screen flashes + beeps)"
  Write-Host " 6) Restart AJ Panel on a panel"
  Write-Host " 7) Reboot a panel"
  Write-Host " 8) Zigbee: backup / switch to Home Assistant / back to Tuya / status"
  Write-Host " t) Set time zone + automatic time on panels (wrong clock in logs)"
  Write-Host " w) Wi-Fi panels: fewer background scans, Wi-Fi never sleeps (DSPPA drops)"
  Write-Host " i) Inventory: complete hardware + software info of panels (read only)"
  Write-Host " d) Remove apps panels don't need (dry run first; u = undo)"
  Write-Host " h) Smart-home dashboard: panel sign-in (username + password), look, language, owner name, PIN"
  Write-Host " 9) Edit panel list (panels.txt)"
  Write-Host " 0) Exit"
  switch (Read-Host "Choose") {
    "1" {
      foreach ($p in Panels) {
        try {
          $i = Invoke-RestMethod "http://$($p.ip):8766/api/info" -TimeoutSec 4
          "{0,-12} {1,-15} v{2,-7} server {3,-13} sync {4,-5} silent-upd {5,-5} auto-upd {6} {7}  clock {9}  HA: {10}  zigbee: {8}" -f $p.name, $p.ip, $i.version, $i.server, $i.sync_connected, $i.silent_update, $i.auto_update, $i.update_window, $i.zigbee_bridge, $i.clock, $(if ($i.ha) { $i.ha } else { "-" }) | Write-Host
        } catch { "{0,-12} {1,-15} not reachable (off, other network, or no AJ Panel)" -f $p.name, $p.ip | Write-Host -ForegroundColor Yellow }
      }
    }
    "2" { $ps = @(Pick "Install AJ Panel on:"); if ($ps.Count) { Run "deploy_ajpanel.bat" ("-panels `"" + (($ps | ForEach-Object { "$($_.ip)=$($_.name)" }) -join ",") + "`"") } }
    "3" { $ps = @(Pick "Silent updates on:"); if ($ps.Count) { Run "panel_silent_updates.bat" ("-panels `"" + (($ps | ForEach-Object { $_.ip }) -join ",") + "`"") } }
    "4" { $ph = Read-Host "Phone connected by USB too? (y/n)"; $ps = @(Panels); Run "collect_logs.bat" ("-panels `"" + (($ps | ForEach-Object { $_.ip }) -join ",") + "`"" + $(if ($ph -eq "y") { " -phone" } else { "" })) }
    "5" { foreach ($p in (Pick "Locate:")) { try { Invoke-RestMethod -Method Post "http://$($p.ip):8766/api/locate" -TimeoutSec 5 | Out-Null; Write-Host "  $($p.name) flashes + beeps for 30 s" } catch { Write-Host "  $($p.name): $_" -ForegroundColor Yellow } } }
    "6" { foreach ($p in (Pick "Restart AJ Panel on:")) { & $ADB connect "$($p.ip):5555" | Out-Null; & $ADB -s "$($p.ip):5555" shell "am force-stop com.ajnetwork.ajpanel; sleep 1; am broadcast -f 32 -n com.ajnetwork.ajpanel/.BootReceiver -a com.ajnetwork.ajpanel.START" | Out-Null; Write-Host "  $($p.name) restarted" } }
    "7" { foreach ($p in (Pick "Reboot:")) { & $ADB connect "$($p.ip):5555" | Out-Null; & $ADB -s "$($p.ip):5555" reboot; Write-Host "  $($p.name) rebooting (1-2 min)" } }
    "8" {
      $ps = @(Pick "Zigbee panel:"); if (-not $ps.Count) { continue }
      Write-Host "  b) backup only   o) switch to Home Assistant   f) back to Tuya   s) status"
      $m = Read-Host "Choose"
      $flag = @{ b = ""; o = "-on"; f = "-off"; s = "-status" }[$m]
      if ($null -ne $flag) { foreach ($p in $ps) { Run "zigbee_test.bat" "-panel $($p.ip) $flag" } }
    }
    "t" {
      # Android's own time-zone call (shell may do this, no root): alarm service, setTimeZone. Clock itself comes from the
      # network once automatic time is on. CHANGES: time zone + auto_time on the chosen panels, nothing else.
      $tz = Read-Host "Time zone (Enter = Asia/Riyadh)"; if (-not $tz) { $tz = "Asia/Riyadh" }
      foreach ($p in (Pick "Set time zone $tz on:")) {
        $d = "$($p.ip):5555"; & $ADB connect $d | Out-Null
        & $ADB -s $d shell "settings put global auto_time 1; settings put global auto_time_zone 0; service call alarm 3 s16 $tz" | Out-Null
        Write-Host ("  {0}: {1}  ({2})" -f $p.name, ((& $ADB -s $d shell date) -join ""), ((& $ADB -s $d shell getprop persist.sys.timezone) -join ""))
      }
    }
    "w" {
      # Each Wi-Fi scan takes cheap Wi-Fi chips off the channel for a moment; a >2 s stall drops the sync connection.
      # CHANGES (Android settings only, undo with -u): scanning while Wi-Fi/BT off, "open network" notification scans,
      # Wi-Fi sleep policy = never. Nothing else.
      $undo = (Read-Host "u = undo, Enter = apply") -eq "u"
      foreach ($p in (Pick "Wi-Fi tuning on:")) {
        $d = "$($p.ip):5555"; & $ADB connect $d | Out-Null
        if ($undo) { & $ADB -s $d shell "settings put global wifi_scan_always_enabled 1; settings put global ble_scan_always_enabled 1; settings put global wifi_networks_available_notification_on 1; settings delete global wifi_sleep_policy" | Out-Null; Write-Host "  $($p.name): back to defaults" }
        else {
          & $ADB -s $d shell "settings put global wifi_scan_always_enabled 0; settings put global ble_scan_always_enabled 0; settings put global wifi_networks_available_notification_on 0; settings put global wifi_sleep_policy 2" | Out-Null
          $w = (& $ADB -s $d shell "dumpsys wifi | grep -m1 mWifiInfo") -join ""
          Write-Host ("  {0}: done  {1}" -f $p.name, ($w -replace '.*(RSSI: -?\d+).*(Frequency: \d+).*', '$1 $2'))
        }
      }
    }
    "i" { $ps = @(Pick "Inventory of:"); if ($ps.Count) { Run "panel_inventory.bat" ("-panels `"" + (($ps | ForEach-Object { $_.ip }) -join ",") + "`"") } }
    "d" {
      $ps = @(Pick "Clean up which panel (test ONE first):")
      if ($ps.Count) {
        $ips = ($ps | ForEach-Object { $_.ip }) -join ","
        $m = Read-Host "Enter = dry run (shows the list), a = remove stage 1, 2 = stage 2 (vendor home screen - only when our app is ready), u = undo"
        $args2 = switch ($m) { "a" { "-apply" } "2" { "-apply -stage 2" } "u" { "-restore" } default { "" } }
        if ($m -ne "u" -and (Read-Host "Keep YouTube? (needs Google services, ~400 MB RAM)  Enter = keep, n = remove") -eq "n") { $args2 += " -noYoutube" }
        Run "panel_debloat.bat" ("-panels `"$ips`" " + $args2)
      }
    }
    "h" {
      # CHANGES: AJ Panel settings only (Home Assistant address/token, look, language, owner name, PIN) via adb broadcast.
      # The token is typed here by you, sent to the panel and stored there - this script does not save or print it.
      $ps = @(Pick "Dashboard settings for:"); if (-not $ps.Count) { continue }
      $x = @()
      $url = Read-Host "Home Assistant address (Enter = same Pi as Music Assistant, port 8123)"; if ($url) { $x += "--es ha_url $url" }
      # sign-in: the panel's own username + password (same user in Music Assistant and Home Assistant) - the panel keeps
      # only tokens; labels "AJ <username>" in Home Assistant then decide what that panel shows
      $login = $null
      $usr = Read-Host "Panel username (Enter = skip; t = paste a Home Assistant token instead)"
      if ($usr -eq "t") {
        $sec = Read-Host "Home Assistant long-lived token" -AsSecureString
        $tok = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
        if ($tok) { $x += "--es ha_token $tok" }
      } elseif ($usr) {
        $sec = Read-Host "Password for $usr" -AsSecureString
        $pw = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
        $w = Read-Host "Sign in to: Enter = music + home, m = music only, h = home only"
        $which = @{ m = "music"; h = "home" }[$w]; if (-not $which) { $which = "both" }
        $login = "am broadcast -f 32 -n com.ajnetwork.ajpanel/.BootReceiver -a com.ajnetwork.ajpanel.LOGIN --es user '" + ($usr -replace "'", "") + "' --es pass '" + ($pw -replace "'", "'\''") + "' --es which $which"
      }
      $look = Read-Host "Look: a = auto (pearl by day), g = graphite, p = pearl (Enter = keep)"
      $lk = @{ a = "auto"; g = "graphite"; p = "pearl" }[$look]; if ($lk) { $x += "--es look $lk" }
      $lang = Read-Host "Language: e = English, r = Arabic (Enter = keep)"
      if ($lang -eq "e") { $x += "--es lang en" } elseif ($lang -eq "r") { $x += "--es lang ar" }
      $own = Read-Host "Owner name in English, e.g. Al Rashid Residence (Enter = keep; Arabic name: set it on the panel)"
      if ($own) { $x += "--es owner_en '" + ($own -replace "'", "") + "'" }
      $show = Read-Host "Show the owner name on these panels? y/n (Enter = keep)"
      if ($show -eq "y") { $x += "--ez owner_show true" } elseif ($show -eq "n") { $x += "--ez owner_show false" }
      $pin = Read-Host "Owner PIN, 4-8 digits (Enter = keep, 0 = remove)"
      if ($pin -match '^\d{4,8}$') { $x += "--es pin_owner $pin" } elseif ($pin -eq "0") { $x += "--es pin_owner ''" }
      if (-not $x.Count -and -not $login) { Write-Host "  nothing to change"; continue }
      foreach ($p in $ps) {
        $d = "$($p.ip):5555"; & $ADB connect $d | Out-Null
        if ($x.Count) { & $ADB -s $d shell ("am broadcast -f 32 -n com.ajnetwork.ajpanel/.BootReceiver -a com.ajnetwork.ajpanel.CONFIG " + ($x -join " ")) | Out-Null }
        if ($login) { & $ADB -s $d shell $login | Out-Null; Start-Sleep 6 }
        Start-Sleep 2
        try { $i = Invoke-RestMethod "http://$($p.ip):8766/api/info" -TimeoutSec 4; Write-Host ("  {0}: Home Assistant {1} (user {5}), look {2}, language {3}, {4} tiles" -f $p.name, $i.ha, $i.look, $i.lang, $i.dash_tiles, $(if ($i.ha_user) { $i.ha_user } else { "-" })) }
        catch { Write-Host "  $($p.name): sent (panel did not answer /api/info)" -ForegroundColor Yellow }
      }
      $tok = $null; $pw = $null; $login = $null; $x = $null
    }
    "9" { notepad $list }
    "0" { return }
  }
}
