# why_no_play.ps1 - READ ONLY. Collects everything needed when a group won't play.
# Panel side: AJ Panel log, player process, connection, audio output.  Server side: MA players + groups (API).
# Log -> logs28\  (send the folder)
param(
  [string]$ma = "192.168.8.5",
  [string]$panels = "192.168.8.194,192.168.8.73,192.168.8.46",
  [string]$user = "panels"
)
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$logs = Join-Path (Get-Location) "logs28"; New-Item -ItemType Directory -Force $logs | Out-Null

Write-Host "Try to play to the group NOW (or right before running this), then press Enter."
Read-Host | Out-Null

# ---- Music Assistant (API) ----
$sec = Read-Host "Password of Music Assistant user '$user' (same as for the deploy script)" -AsSecureString
$pw = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
function MaCmd($cmd, $a, $tok) {
  $h = @{ "Content-Type" = "application/json" }
  if ($tok) { $h["Authorization"] = "Bearer $tok" }
  $body = @{ message_id = "$(Get-Random)"; command = $cmd; args = $a } | ConvertTo-Json -Depth 10 -Compress
  try { return Invoke-RestMethod -Uri "http://$($ma):8095/api" -Method Post -Headers $h -Body $body -TimeoutSec 15 }
  catch { return "ERROR $cmd : $($_.Exception.Message) $($_.ErrorDetails.Message)" }
}
$login = MaCmd "auth/login" @{ username = $user; password = $pw; device_name = "why_no_play" } $null
$tok = $login.access_token
if (-not $tok) { "login failed: $($login | ConvertTo-Json -Depth 5)" | Out-File (Join-Path $logs "ma.txt") -Encoding utf8; Write-Host "MA login failed - see logs28\ma.txt" }
else {
  $players = MaCmd "players/all" @{ return_unavailable = $true; return_disabled = $true } $tok
  $players | ConvertTo-Json -Depth 8 | Out-File (Join-Path $logs "ma_players_full.json") -Encoding utf8
  $sum = foreach ($p in $players) {
    "{0,-34} {1,-22} prov={2,-10} type={3,-7} avail={4,-5} enabled={5,-5} state={6,-8} vol={7,-4} muted={8,-5} synced_to={9} group_members=[{10}] active_group={11} active_source={12} media={13}" -f `
      $p.player_id, $p.name, $p.provider, $p.type, $p.available, $p.enabled, $p.playback_state, $p.volume_level, $p.volume_muted, $p.synced_to, ($p.group_members -join ","), $p.active_group, $p.active_source, $p.current_media.title
  }
  $sum | Out-File (Join-Path $logs "ma_players.txt") -Encoding utf8
  $sum | Where-Object { $_ -match "ajpanel|syncgroup|ugp_|group" } | ForEach-Object { Write-Host $_ }
  foreach ($p in $players | Where-Object { $_.player_id -match "ajpanel|syncgroup|ugp_" -or $_.type -eq "group" }) {
    MaCmd "player_queues/get" @{ queue_id = $p.player_id } $tok | ConvertTo-Json -Depth 6 | Out-File -Append (Join-Path $logs "ma_queues.txt") -Encoding utf8
  }
  MaCmd "auth/me" @{} $tok | ConvertTo-Json -Depth 4 | Out-File (Join-Path $logs "ma_me.txt") -Encoding utf8
}

# ---- panels ----
foreach ($ip in $panels.Split(",")) {
  $dev = "$($ip):5555"; $log = Join-Path $logs "$ip.txt"
  & $ADB connect $dev | Out-Null
  "=== $ip $(Get-Date) ===" | Out-File $log -Encoding utf8
  "--- processes ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "ps -A | grep -E 'ajpanel|snapclient|badaix'" | Out-File -Append $log -Encoding utf8
  "--- connections ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "netstat -tn | grep -E ':1704|:8095|:1501'" | Out-File -Append $log -Encoding utf8
  "--- AJPanel log (last 400) ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev logcat -d -v time -s AJPanel -t 400 | Out-File -Append $log -Encoding utf8
  "--- audio_flinger ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "dumpsys media.audio_flinger" | Select-Object -First 120 | Out-File -Append $log -Encoding utf8
  "--- volumes ---" | Out-File -Append $log -Encoding utf8
  & $ADB -s $dev shell "dumpsys audio | grep -A3 'STREAM_MUSIC'" | Out-File -Append $log -Encoding utf8
  Write-Host "$ip done"
}
Write-Host "`nDone. Logs in $logs"
