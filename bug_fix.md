# bug_fix.md — How each bug was fixed

Each section's id matches the bug in `bug.md` (FIX-001 ↔ BUG-001). Include: what was changed, where, how to verify, and how to undo.

---

## FIX-001
**BUG-001 – AirPlay Server crash (libcrypto name clash) on Judian Android 10**
- Patched the APK: renamed bundled `lib/armeabi-v7a/libcrypto.so` → `libcryptx.so`; binary-patched the `NEEDED` entry in `libairplay_native.so` and the `SONAME` of the renamed lib (same-length string `libcrypto.so\0` → `libcryptx.so\0`); dropped non-armv7 ABIs; zip-aligned (libs stored, 4 KB aligned); re-signed (uber-apk-signer).
- File: `New folder\downloads\airplay-server-panel-fixed.apk`. Installed with `5_install_fixed_airplay.bat` (uninstalls original first — different signature).
- Verify: `netstat -tln` shows `:7000` LISTEN; logcat `AirPlayNative: AirPlay server started on port 7000`.
- Undo: `adb uninstall io.github.jqssun.airplay`.

## FIX-002
**BUG-002 – monkey can't launch apps**
- Use `am start -n <pkg>/<activity>` (find activity with `cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>`).

## FIX-003
**BUG-003 – Play Protect blocks installs on DM938T**
- On the panel screen: *More details → Install anyway* (never "Got it").
- Prevention: build our apps with `targetSdkVersion` ≥ 29; install with `adb push` then `pm install` so the result is visible.
- Optional (owner decides): Play Store → Play Protect → turn off "Scan apps".

## FIX-004
**BUG-004 – DM858 app crashes (missing firmware libs)**
- Built no-op stub `.so` files for all 9 missing libs (adau1701, serial_port, Func, SpeexDsp, WebRtcNs, mp3lame, openh264, ut_c_api, ztspeech) by generating JNI functions from the decompiled `native` declarations (return 0/null), compiled with clang `--target=armv5te-linux-androideabi`, added to `lib/armeabi/`.
- File: `downloads\DM858_slim_v2.apk` (also: 56 MB voice pack replaced by empty zip, 120 JPGs downscaled → 28 MB).
- Verify: app opens, `netstat -uln` shows `40188`.

## FIX-005
**BUG-005 – two adb versions fight**
- Scripts always use `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`, run `adb kill-server` once at start, and reconnect before each step (`10_adb_dsppa.bat` pattern).

## FIX-006
**BUG-006 – slow/failed large installs**
- `adb push -p <apk> /data/local/tmp/x.apk` (progress bar) then `pm install -r -g /data/local/tmp/x.apk` (shows the real error). Keep APKs small.

## FIX-007
**BUG-007 – ControlX Bridge broadcast storm**
- v1.1: normalise state (0→2) **before** comparing; idle zones polled every 5 s; playlist refresh 30 s.
- File: `downloads\ControlX_Bridge_v1.1.apk` (and v2.0).

## FIX-008
**BUG-008 – MA port 8097 taken**
- Find the owner: `curl -si http://127.0.0.1:8097/ | head` (→ `SimpleHTTP/0.6 Python/3.14.8`), then per-add-on check (troubleshoot.md §6).
- Fix A: stop that add-on → start MA → MA *Settings → System → Streams* → port 8098 → restart MA → start the add-on again.
- Fix B: move the AJ Netweb add-on's web server off 8097.

## FIX-009
**BUG-009 – panels used the Pi's Wi-Fi**
- Pi has two addresses: `192.168.8.5` (eth, MAC …ff:ed) and `192.168.8.6` (wlan, …ff:ee). Use .5 everywhere (`20_join_music_assistant.bat -ma 192.168.8.5`), set MA published IP to .5, disable wlan0 in HA *Settings → System → Network*.
- Verify: `22_network_isolate.bat` → panel→Pi avg < 20 ms (before: 76 ms avg / 647 ms max; earlier 2.4 s avg / 12.9 s max).

## FIX-010
**BUG-010 – no sound after server change**
- Clean restart of the player on each panel: `SnapclientService ACTION_STOP` → `pkill -f libsnapclient.so` → `am force-stop de.badaix.snapcast` → `ACTION_START --es EXTRA_HOST 192.168.8.5`. (`24_sound_test.bat` does this.)
- Long-term: AJ Panel embeds and supervises its own player (no orphan processes).

## FIX-011
**BUG-011 – AirPlay → group stalls**
- MA *Settings → Players*: disable `EShare-8965` and `Android AirPlay`; panels' output protocol = Snapcast/Native. (`25_ma_disable_dupes.bat` automates via API.)
- Verify: iPhone AirPlay to "<group> | Music Assistant" plays on all panels ✅ (2026-11-09).

## FIX-015
**BUG-015 – orphan snapclient process (DSPPA)**
- AJ Panel v0.1 runs `libsnapclient.so` itself (`SnapProcess`): one child only, pid kept in `files/snapclient.pid`, a leftover from an earlier run is killed at start, restart with 1→15 s back-off.
- `deploy_ajpanel.bat` stops the Snapcast app (`ACTION_STOP` + `force-stop`; `-disableOld` disables it) and tells ControlX Bridge `join ''`.
- Verify: `adb shell "ps -A | grep snapclient"` → exactly one process, owner = AJ Panel's uid; `netstat -tn | grep 1704` one ESTABLISHED.
- Undo: `deploy_ajpanel.bat -uninstall` (re-enables the Snapcast app).

## FIX-019
**BUG-019 – panels don't show what is playing**
- AJ Panel polls MA (`players/get` for `ma_ajpanel_<mac>`, its sync leader / group player) every 1 s and shows a floating card (needs `appops set com.ajnetwork.ajpanel SYSTEM_ALERT_WINDOW allow`, done by the deploy script), its own full screen, and a MediaSession + media notification.
- User name ("played by …") comes from `ajsnap/now_playing` once the AJ server (ajsnap provider) runs.
- Verify: play to a group → every panel shows the title within ~2 s; logcat `-s AJPanel`.

## FIX-020
**BUG-020 – Judian music player doesn't show the song**
- Found in JdLauncher (`com.judian.launcher.cc.d` "MusicHelper"): the home screen gets title/artist/cover only from `com.judian.jdmediarender` (AIDL `JDMusicPlayerService`, callback 20007 SONG_CHANGE). That app is also a DLNA renderer: a DLNA push creates its song from the DIDL (`dc:title`, `upnp:artist`, `upnp:albumArtURI`) – `i.h.j.j.j.d.onSetDataSource`.
- AJ Panel v0.2 `DlnaMetaBridge` (on when `com.judian.jdmediarender` is installed): SSDP-finds the panel's own renderer, sends `SetAVTransportURI` with MA's title/artist/cover and a **silent MP3** served by AJ Panel on 127.0.0.1, then `Play`/`Pause`/`Stop` following MA. Real audio still = our snapclient.
- Pause / play on the Judian screen is read back (`GetTransportInfo`) → **pause here / resume here**.
- M20_EN: HTTP to its own LAN IP (port 1501) times out → description/control are fetched via 127.0.0.1 (v0.2.1).
- Note: Judian's parser turns every `&` into `&amp;` before parsing, so metadata is sent with raw `&`.
- Judian panels: floating card off by default, AJ Panel runs in background (deploy script doesn't open it).
- Verify: play to the group → Judian home music card shows the song within ~2 s; logcat `-s AJPanel` → `dlna bridge: pushed '…'`.
- Undo: AJ Panel settings → untick "Show song in the panel's own music player", or `--ez vendor_bridge false`.

## FIX-021
**BUG-021 – 500 error on ▶ for a new group**
- Flutter v0.3: if the player/group is idle with no current media, ▶ shows "Nothing to play yet" (AirPlay to "<group> | Music Assistant", or pick music in MA) instead of sending `play_pause`. Other API errors show MA's message; 403 says an admin user is needed.
