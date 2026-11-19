# panel_silent_updates.ps1 - make AJ Panel updates install WITHOUT tapping "Install" on every panel.
# Sets AJ Panel as the panel's Android "device owner" (one adb command per panel, AJ Panel 0.7.9+ needed).
# AJ Panel uses this role only for installing its own updates.
#   panel_silent_updates.bat                     X8, P8, DSPPA
#   panel_silent_updates.bat -panels "192.168.8.165"
#   panel_silent_updates.bat -off                give the role back (needed before uninstalling AJ Panel)
param([string]$panels = "192.168.8.165,192.168.8.194,192.168.8.46", [switch]$off)
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$PKG = "com.ajnetwork.ajpanel"
foreach ($ip in $panels.Split(",")) {
  $ip = $ip.Trim(); if (-not $ip) { continue }
  $dev = "$($ip):5555"
  Write-Host "=== $ip ==="
  & $ADB connect $dev | Out-Null
  if ((& $ADB -s $dev get-state 2>$null) -ne "device") { Write-Host "  not reachable over adb" -ForegroundColor Yellow; continue }
  if ($off) {
    & $ADB -s $dev shell am broadcast -n "$PKG/.BootReceiver" -a "$PKG.RELEASE_OWNER" | Out-Null
    Start-Sleep 2
  } else {
    # dpm answers on stderr; keep only its first line (no PowerShell error block)
    $r = (& $ADB -s $dev shell "dpm set-device-owner $PKG/.AdminReceiver 2>&1" | Out-String).Trim()
    Write-Host ("  " + ($r -split "`n")[0].Trim())
    if ($r -match "accounts") {
      Write-Host "  -> panel has system accounts (Judian): using the panel's own adb instead - tap ALLOW on the panel (tick 'Always allow')" -ForegroundColor Yellow
      # quoted: unquoted $PKG.ADB_PAIR is read by PowerShell as a property of $PKG (empty) -> "Argument expected after -a"
      & $ADB -s $dev shell am broadcast -f 32 -n "$PKG/.BootReceiver" -a "$PKG.ADB_PAIR" | Out-Null
      for ($i = 0; $i -lt 30; $i++) {          # up to 60 s for the tap on the panel
        Start-Sleep 2
        try { $st = [string](Invoke-RestMethod "http://$($ip):8766/api/info" -TimeoutSec 3).adb_pair } catch { $st = "" }
        if ($st -match "allowed|not allowed|unexpected") { break }
      }
      Write-Host "  panel's own adb: $(if ($st) { $st } else { 'no answer (AJ Panel 0.8.6+ needed)' })"
    }
    elseif ($r -match "already") { Write-Host "  -> already set" }
    elseif ($r -match "Unknown admin|not found") { Write-Host "  -> update AJ Panel to 0.7.9 or newer first" -ForegroundColor Yellow }
  }
  # ask the panel itself: device owner OR its own adb (Judian) both count
  try { $inf = Invoke-RestMethod "http://$($ip):8766/api/info" -TimeoutSec 4; $on = [bool]$inf.silent_update; $via = [string]$inf.silent_via } catch {
    $on = [bool](& $ADB -s $dev shell dumpsys device_policy 2>&1 | Select-String -Pattern "ajpanel" | Select-Object -First 1); $via = "device owner" }
  Write-Host ("  silent updates: " + $(if ($on) { "ON ($via)" } else { "off" })) -ForegroundColor $(if ($on) { "Green" } else { "Yellow" })
}
