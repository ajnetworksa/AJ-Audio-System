# panel_screenshots.ps1 - READ ONLY. Takes screenshots of a panel's screen (e.g. the DSPPA DM938T UI) for UI research.
# Navigate on the panel to the screen you want, then press Enter here. "q" + Enter to stop.
# Output -> screens\<ip>\<n>_<time>.png
param([string]$panel = "192.168.8.46")
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }
$dev = "$($panel):5555"
& $ADB connect $dev | Out-Null
$out = Join-Path (Get-Location) "screens\$panel"; New-Item -ItemType Directory -Force $out | Out-Null
"--- screen size / density ---" | Out-File (Join-Path $out "info.txt") -Encoding utf8
& $ADB -s $dev shell wm size | Out-File -Append (Join-Path $out "info.txt") -Encoding utf8
& $ADB -s $dev shell wm density | Out-File -Append (Join-Path $out "info.txt") -Encoding utf8
$n = 1
while ($true) {
  $k = Read-Host "Screen $n : open it on the panel, then Enter (q = quit)"
  if ($k -eq "q") { break }
  $f = Join-Path $out ("{0:D2}_{1}.png" -f $n, (Get-Date -Format "HHmmss"))
  # exec-out keeps the PNG binary intact
  $p = Start-Process -FilePath $ADB -ArgumentList "-s $dev exec-out screencap -p" -RedirectStandardOutput $f -NoNewWindow -Wait -PassThru
  $act = & $ADB -s $dev shell "dumpsys activity activities | grep -m1 -E 'mResumedActivity|ResumedActivity'"
  "{0:D2}: {1}" -f $n, "$act".Trim() | Out-File -Append (Join-Path $out "info.txt") -Encoding utf8
  Write-Host "saved $f  ($((Get-Item $f).Length) bytes)"
  $n++
}
Write-Host "Done. Screenshots in $out"
