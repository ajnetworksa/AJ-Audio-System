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
    & $ADB -s $dev shell am broadcast -n $PKG/.BootReceiver -a $PKG.RELEASE_OWNER | Out-Null
    Start-Sleep 2
  } else {
    $r = (& $ADB -s $dev shell dpm set-device-owner $PKG/.AdminReceiver 2>&1 | Out-String).Trim()
    Write-Host "  $r"
    if ($r -match "accounts") { Write-Host "  -> this panel has an account signed in (Settings > Accounts). Remove it, then run again." -ForegroundColor Yellow }
    elseif ($r -match "already") { Write-Host "  -> already set" }
    elseif ($r -match "Unknown admin|not found") { Write-Host "  -> update AJ Panel to 0.7.9 or newer first" -ForegroundColor Yellow }
  }
  $o = (& $ADB -s $dev shell dumpsys device_policy 2>&1 | Select-String -Pattern "ajpanel" | Select-Object -First 1)
  Write-Host ("  silent updates: " + $(if ($o) { "ON" } else { "off" }))
}
