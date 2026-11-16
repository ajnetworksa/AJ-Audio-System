# zigbee_probe2.ps1 - step 2 for the Judian panels' Zigbee radio (see zigbee.md).
# READ ONLY on the panel: copies the Judian Zigbee gateway app (JdZigbee) to the PC and reads how /dev/ttyS3 is set up.
# With -restart it ALSO reboots the panel once (about 1-2 min, nothing is deleted) to capture the Zigbee
# gateway's start-up log (radio firmware / protocol / speed).
# Output -> logs31\<ip>\   (send the folder)
param([string]$panels = "192.168.8.165", [switch]$restart)
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
foreach ($ip in $panels.Split(",")) {
  $ip = $ip.Trim(); $dev = "$($ip):5555"
  $out = Join-Path (Get-Location) "logs31\$ip"; New-Item -ItemType Directory -Force $out | Out-Null
  $log = Join-Path $out "probe2.txt"
  Write-Host "`n=== $ip ==="
  "=== $ip  $(Get-Date) ===" | Out-File $log -Encoding utf8
  & $ADB connect $dev | Out-File -Append $log -Encoding utf8
  "--- ttyS3 settings ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "stty -F /dev/ttyS3 -a 2>&1; toybox stty -F /dev/ttyS3 2>&1" | Out-File -Append $log -Encoding utf8
  "--- zigbee props ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "getprop | grep -iE 'jd\.zbg|zigbee|has_zigbee'" | Out-File -Append $log -Encoding utf8
  "--- JdZigbee files ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "ls -lR /vendor/app/JdZigbee 2>&1; ls -l /vendor/lib /vendor/lib64 2>/dev/null | grep -iE 'tuya|zig|ezsp|z3' " | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev pull /vendor/app/JdZigbee (Join-Path $out "JdZigbee") 2>&1 | Out-File -Append $log -Encoding utf8
  if ($restart) {
    Write-Host "rebooting $ip once to read the Zigbee start-up log (1-2 min)..."
    & $ADB -s $dev reboot
    Start-Sleep 100
    for ($i = 0; $i -lt 12; $i++) { & $ADB connect $dev | Out-Null; if ((& $ADB -s $dev get-state 2>$null) -eq "device") { break }; Start-Sleep 10 }
    Start-Sleep 20
    & $ADB -s $dev logcat -d -v time 2>&1 | Out-File (Join-Path $out "startup_logcat.txt") -Encoding utf8
    "--- after reboot ---" | Out-File -Append $log -Encoding utf8
    & $ADB -s $dev shell "ps -A | grep -iE 'gateway|ajpanel'; stty -F /dev/ttyS3 -a 2>&1" | Out-File -Append $log -Encoding utf8
  }
  Write-Host "saved $out"
}
Write-Host "`nDone. Send me the logs31 folder."
