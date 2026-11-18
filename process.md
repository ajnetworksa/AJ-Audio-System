# process.md — How we work

## 1. Workflow for every change

1. **Pick work** from `progress.md` → "Next".
2. **Check bugs**: is there an open BUG in `bug.md` touching this area? Read its notes first.
3. **Back up** anything on a panel you will change (§4).
4. **Build** (§2) → bump `versionCode`/`versionName`.
5. **Test** on at least one Judian (32-bit, Android 10) **and** the DSPPA (64-bit, Android 8.1) (§3).
6. **Document**: new bugs → `bug.md`; fixes → `bug_fix.md`; session summary → `progress.md`; UI changes → `ui.md`.
7. **Deliver**: APK + source zip into the right `apps/…/releases/` folder; scripts into `tools/`.

## 2. Building Android apps (panel + phone) without Android Studio

Inputs (copied from the owner's SDK `%LOCALAPPDATA%\Android\Sdk`):
- `platforms/android-35/android.jar`
- `build-tools/35.0.0/lib/d8.jar`, `build-tools/35.0.0/core-lambda-stubs.jar`
- `aapt2` (Linux) from `apktool_2.11.1.jar` → `prebuilt/linux/aapt2_64`
- `uber-apk-signer-1.3.0.jar`

One command does all steps below (also used by CI): `bash tools/build_java_apk.sh apps/panel` (or `apps/zigbee-survey`); copies `lib/` (native libs) into the APK. Output `build/<Label>_v<version>.apk`; copy finished versions to the app's `releases/`.

```bash
javac -source 8 -target 8 -encoding UTF-8 -bootclasspath android.jar:core-lambda-stubs.jar -d build/classes $(find src -name '*.java')
java -cp d8.jar com.android.tools.r8.D8 --release --min-api <21|29> --lib android.jar --output build/dex $(find build/classes -name '*.class')
aapt2_64 link --manifest AndroidManifest.xml -I android.jar -o build/unsigned.apk      # add -R compiled res if any
(cd build/dex && zip -q ../unsigned.apk classes.dex)
java -jar uber-apk-signer.jar -a build/unsigned.apk --allowResign -o build/signed
```

Notes:
- **Signing (D-014):** one release key for all our Android apps, made once with `tools\make_release_key.bat` (stored in `%USERPROFILE%\.ajnetwork\`, never in git). GitHub secrets `ANDROID_KEYSTORE_B64` + `ANDROID_KEYSTORE_PASS` → CI signs AJ Panel, AJ Audio (Java + Flutter) and ControlX Bridge with it. Without the secrets: Java apps get the uber-apk-signer debug key, Flutter gets a random CI key (warning in the run).
  Local build with the release key: `KEYSTORE=<jks> KEYSTORE_PASS=<pw> bash tools/build_java_apk.sh apps/panel`.
  Switching an installed app from debug key to release key needs **one uninstall** (signatures differ).
- iOS needs no key in CI: the `.ipa` is unsigned; Sideloadly/AltStore signs it with your Apple ID when installing.
- Re-signed third-party APKs can't update over the original (different signature) → uninstall first.
- Native libs that are stored uncompressed must be 4 KB-aligned (uber-apk-signer's zipalign handles it).

Phone app (Flutter, D-007): built only by GitHub Actions (`.github/workflows/phone-flutter.yml`) – Android APK + unsigned iOS IPA (install with Sideloadly/AltStore). No Flutter/macOS needed on the PC.

Server (D-008): `server/README.md` – same `ajsnap` provider + `patches/` packaged as Docker image and as local HA add-on.

## 2a. Releases (GitHub)
- Every push builds **artifacts** (Actions → the run → bottom "Artifacts" → zip). They expire after 90 days.
- **Releases** (repo → Releases) are made by `release.yml`:
  - every push to `main` that changes an app → new release with an automatic version: patch by default (v0.3.1), `[major]` in a commit → v0.4.0, `[breaking]` → v1.0.0
  - release notes = changelog of the commits since the previous release, grouped by area (see `CHANGELOG.md`)
  - a hand-pushed tag (`git tag v0.5.0` + `git push origin v0.5.0`) or Actions → release → Run workflow with a tag overrides the number
- `android-java.yml` / `phone-flutter.yml` only run inside the release (and for pull requests / manual runs), so each push builds once.

## 2b. Git
- Repo root = `AJ-Audio-System`. Branch `main`. **Commit after every piece of work** (rule 9 in agent.md); message `area: what` + body with why/test result.
- Never commit tokens/passwords (`.gitignore` blocks `tokens*.txt`, logs, keystores).
- The owner pushes manually to GitHub.

## 3. Testing checklist

| Area | Test |
|---|---|
| Install | `adb push` + `pm install -r -g` (not streamed install — slow Wi-Fi, BUG-006). DSPPA: Play Protect popup → *Install anyway* |
| Start | app starts at boot (BootReceiver) and after `am force-stop` + launcher |
| Sync | 2+ panels grouped, same song, listen for echo; `tools/…sound_test` |
| Network | `22_network_isolate.bat`: panel→server < 20 ms avg, no > 200 ms spikes |
| Recovery | pull Wi-Fi for 30 s / reboot server → panels reconnect within 15 s |
| Logs | `21_snapcast_logs.bat`, `23_why_no_sound.bat` produce clean summaries |

## 4. Backups before touching a panel

- `16_backup_and_check_yjy.bat` pattern: `pm path <pkg>` → `adb pull` APK into `backup_<device>\`, plus `getprop`, `settings list *`.
- Existing: `New folder\backup_yjy_dm938t\` (DM938T system apps), `downloads\dsppa_backup\` (DM858 apps), `New folder\apks\` (Judian system apps).

## 5. IDs and naming

- Bugs: `BUG-###` (bug.md) · Fixes: `FIX-###` with the **same number** as the bug (bug_fix.md).
- Decisions: `D-###` (progress.md).
- Versions: `MAJOR.MINOR` per app; versionCode always increases.
- Logs from helper scripts: `logsNN\` where NN = script number.

## 6. Release & deployment (target)

1. Server: standalone Music Assistant (Docker on Raspberry Pi OS 64-bit) + `ajsnap` provider mounted (needed for R7).
2. Panels: `tools/deploy_panel.bat <ip>` installs AJ Panel, sets server address, removes old test apps.
3. Phones: Android APK sideload (later Play Store), iOS TestFlight.
