# make_release_key.ps1 - creates ONE release signing key for all AJ Android apps (panel + phone).
# Run once on your PC. Keep the .jks file and password safe (backup!) - if lost, apps can't be updated
# without uninstalling. The key never goes into git.
#
# Output:  %USERPROFILE%\.ajnetwork\ajnetwork-release.jks
#          %USERPROFILE%\.ajnetwork\ANDROID_KEYSTORE_B64.txt  -> paste into GitHub secret ANDROID_KEYSTORE_B64
$ErrorActionPreference = "Stop"
$dir = Join-Path $env:USERPROFILE ".ajnetwork"
New-Item -ItemType Directory -Force $dir | Out-Null
$jks = Join-Path $dir "ajnetwork-release.jks"
if (Test-Path $jks) { Write-Host "Key already exists: $jks (delete it yourself if you really want a new one)"; exit 1 }

# find keytool: JAVA_HOME, Android Studio's bundled JDK, or PATH
$cands = @()
if ($env:JAVA_HOME) { $cands += Join-Path $env:JAVA_HOME "bin\keytool.exe" }
$cands += "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
$cands += "C:\Program Files\Android\Android Studio\jre\bin\keytool.exe"
$kt = $cands | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $kt) { $kt = (Get-Command keytool -ErrorAction SilentlyContinue).Source }
if (-not $kt) { Write-Host "keytool not found - install a JDK or Android Studio"; exit 1 }

$sec = Read-Host "Choose a keystore password (6+ chars)" -AsSecureString
$pw = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
& $kt -genkeypair -v -keystore $jks -storetype PKCS12 -alias ajnetwork -keyalg RSA -keysize 2048 -validity 10000 `
      -storepass $pw -keypass $pw -dname "CN=AJ Network Solutions, O=AJ Network Solutions, C=SA"
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
Set-Content -Path (Join-Path $dir "ANDROID_KEYSTORE_B64.txt") -Value $b64 -NoNewline
Write-Host ""
Write-Host "Done. Now on GitHub: repo -> Settings -> Secrets and variables -> Actions -> New repository secret"
Write-Host "  ANDROID_KEYSTORE_B64  = contents of $dir\ANDROID_KEYSTORE_B64.txt"
Write-Host "  ANDROID_KEYSTORE_PASS = the password you just typed"
Write-Host "Back up $jks and the password (e.g. password manager). Do NOT commit it."
