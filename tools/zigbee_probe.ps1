# zigbee_probe.ps1 - READ ONLY. Finds out how each panel's built-in Zigbee radio is wired (chip port, who uses it,
# rights, Tuya gateway ports), to decide how to use it with Home Assistant instead of Smart Life.
# Changes NOTHING on the panels (it copies a read-only script to /data/local/tmp and runs it).
# Log -> logs30\<ip>.txt  (send the folder)
param([string]$panels = "192.168.8.194,192.168.8.165,192.168.8.46")
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$logs = Join-Path (Get-Location) "logs30"; New-Item -ItemType Directory -Force $logs | Out-Null
foreach ($ip in $panels.Split(",")) {
  $ip = $ip.Trim(); $dev = "$($ip):5555"
  $log = Join-Path $logs "$ip.txt"
  Write-Host "`n=== $ip ==="
  "=== $ip  $(Get-Date) ===" | Out-File $log -Encoding utf8
  & $ADB connect $dev | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev push (Join-Path $here "zigbee_probe.sh") /data/local/tmp/zigbee_probe.sh | Out-Null
  & $ADB -s $dev shell sh /data/local/tmp/zigbee_probe.sh 2>&1 | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell rm /data/local/tmp/zigbee_probe.sh | Out-Null
  Write-Host "saved $log"
}
Write-Host "`nDone. Send me the logs30 folder."
