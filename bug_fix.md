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

## FIX-022
**BUG-022 – app volume doesn't move the panel's volume**
- AJ Panel v0.2.2 starts snapclient with `--mixer none` and `VolumeSync` keeps Android STREAM_MUSIC and the MA player volume equal: MA/app/group volume → panel volume; panel buttons / vendor UI → MA; mute from MA → panel mute. At start the panel's current volume is sent to MA (no jump to MA's default 25 %). Works in the panel's own steps (DSPPA 0–15, Judian 0–100).
- Verify: move the room slider in the phone app → panel volume bar moves; press the panel's volume keys → slider in the app follows within ~1 s. logcat `-s AJPanel` → `volume: MA 40% -> panel 6/15` / `volume: panel 9/15 -> MA 60%`.
- Undo: panel settings → untick "Volume = panel volume", or `--ez system_volume false`.

## FIX-023
**BUG-023 / BUG-017 – play any Android app on the system (network, no Bluetooth)**
- AJ Audio Client v0.4 ("Play my phone" card + Quick Settings tile "Play my phone"): Android 10+ playback capture (MediaProjection consent each time – Android rule) → 48 kHz stereo PCM, 20 ms frames over WebSocket to Music Assistant's **AriaCast Receiver** plugin (`ws://<server>:12889/audio`, metadata on `/metadata`). Before announcing playback the app calls `players/cmd/select_source(target, <ariacast instance>)` so it plays on the chosen room/group.
- Needs once in Music Assistant: Settings → Providers → Add → **AriaCast Receiver** (pick any player; the app chooses the target itself).
- v0.5: the Quick Settings tile opens an AirPlay-style **picker** (groups, then rooms; tap = start or move; Stop). The phone's own speaker is **muted** once sound flows and restored on stop (if a phone's capture goes silent while muted, the app unmutes and remembers not to mute on that phone). The "Start recording or casting?" question can be skipped per phone with `tools\phone_allow_capture.bat` (Android app-op `PROJECT_MEDIA allow`, set once over USB/Wireless debugging).
- Limits: one phone at a time (AriaCast rule); apps that forbid capture (some DRM/video apps) stay silent.
- Verify: start from the app or tile → MA log "AriaCast sender connected from <phone ip>", the group plays.

## FIX-024
**BUG-024 – panel volume changing by itself (M20, DSPPA)**
- AJ Panel v0.3.2 `VolumeSync`: a local volume change goes to MA only after it has stayed the same for 2 s (vendor fades are ignored; a fade that ends where it started sends nothing); never while MA has the room muted (Android then reports 0); echoes of our own volume/mute changes are ignored for 1.5 s; after un-mute the panel comes back at MA's volume. **Fight guard:** 4 local pushes within 60 s → MA is the only source for 10 min and the panel is put back to MA's volume (`volume: panel keeps changing its own volume – MA volume wins for 10 min`).
- At start a muted room now really mutes the panel (before, MA showed "muted" but the panel kept playing). Unmute it from the app/MA if a room is silent after the update.
- Verify: `logcat -s AJPanel | findstr volume` on M20/DSPPA – no more `panel N/100 -> MA` every few seconds; app slider and panel buttons still work.
- Undo: panel settings → untick "Volume = panel volume".

## FIX-025
**BUG-025 – play YouTube (any app) from a panel in other rooms**
- AJ Panel v0.5.0 **Share**: Android 10 playback capture of the other apps on the panel (our own sync player excluded), 48 kHz PCM → Music Assistant **AriaCast Receiver** → `select_source` on the chosen saved group or rooms (first room leads, others join). Title/artist of the app (YouTube video title …) is sent along when the panel's notification listener is allowed.
- The panel itself keeps playing the app directly; if it is part of the chosen saved group it takes a break from it while sharing (no double sound), rejoining when sharing stops.
- Shown on the Playing screen: "YouTube is playing on this panel → Share", while sharing "This panel's sound plays in … · Change · Stop"; also the *Share* button.
- `deploy_ajpanel.bat` pre-grants RECORD_AUDIO, `appops PROJECT_MEDIA allow` and the notification listener, so the panel never asks.
- Limits: the other rooms are ~1 s behind the sharing panel (live capture + MA buffer); one sender at a time (AriaCast, also shared with "Play my phone"); apps that forbid capture stay silent; needs the AriaCast Receiver plugin in MA and Android 10+ on the panel.

## FIX-026
**BUG-026 – sharing panel must play too, in sync with the others**
- AJ Panel v0.5.1 *share mode* while this panel shares: snapclient restarts with `--player file:filename=stdout --logsink stderr --sampleformat 48000:16:2 --latency <trim + queue + hw>`; `PcmRelay` plays that synced PCM with our own AudioTrack on the **ALARM** stream (queue held at 80 ms by dropping/repeating single frames). The panel's **media** stream is set to 0, so the shared app (YouTube …) is not heard directly – only the synced copy, exactly like every other room. Playback capture still gets the app's sound (the capture path has fixed volume).
- The panel is now part of the target: saved group containing it → plays there (a break from it is ended); ticked rooms → this panel leads them.
- Panel keys / vendor slider while sharing → become the room volume in MA, media stream back to 0. Volume from the app/MA → alarm stream.
- Safety: if the capture stays silent for 6 s while an app is playing (a panel whose capture follows its media volume), sharing stops with "this panel can't share while its own speaker is turned down" and everything is restored.
- On stop: snapclient back to oboe, media volume restored to MA's volume.
- Note: the sound (in every room, this one included) comes ~1 s after the video picture on the sharing panel.

## FIX-027
**BUG-027 – AJ Snap fails to load on MA 2.10.9 (provider_access missing)**
- `server/ajsnap-provider/upstream_snapcast/` is now the Snapcast provider of **tag 2.10.9**; `ajsnap/` = that + our small changes (domain/name, buffer 600 ms, chunk 20 ms, AJ API). `aj_api.py` carries its own `resolve_playback_user` (reads the queue's user) instead of importing a dev-only helper. Requirement pins = 2.10.9's (bidict 0.23.1, websocket-client 1.9.0), so nothing is pip-installed at runtime.
- Add-on version **2.10.9-aj3** → HA shows Update.
- CI `addon-check` now clones the MA tag from `build.yaml` + its models version and runs `tools/check_ajsnap_imports.py` – a provider import that doesn't exist in that MA version fails the build.
- Rule: when MA is upgraded, rebase ajsnap on that version's Snapcast provider (diff `upstream_snapcast` → `ajsnap`, apply to the new copy).

## FIX-028
**BUG-028 – Share fails (Android 8 DSPPA; Judian group-then-cancel)**
- Share order now follows the AriaCast plugin: check the plugin exists (clear message if not) → connect → **start sending sound at once** (MA drops a source that sends nothing for 1 s) → group rooms → select the source (if the plugin refuses because it never played since the server started, send "playing" first and select again, up to 4× 0.7 s). Capture runs in its own thread.
- Errors are shown on the panel ("Sharing stopped: <reason>" with *Try again*); grouping done for a failed share is undone.
- Panels whose capture goes silent when their own media volume is 0: instead of stopping, sharing switches to **direct** – the panel plays the app itself (a little ahead of the other rooms) and our synced copy is muted.
- **Android 8/9 panels (DSPPA DM938T = Android 8.1)**: Android has no way for one app to pick up another app's sound before Android 10 (without root/system rights). *Share* there explains this and offers what works in sync: Music page (library + streaming services added in MA), Spotify Connect, phones (AirPlay / Play my phone).
- Tip: in MA → AriaCast Receiver set the "connected player" to the main group/room – on the first share after a server restart that player gets the sound for ~1 s before it moves to the chosen rooms.

## FIX-029
**BUG-029 – Play my phone: connection refused on 12889, consent question every time**
- Port 12889 refused = MA's **AriaCast Receiver** plugin isn't running (after moving to the AJ Music Assistant add-on it has to be added again: Settings → Providers → Add → AriaCast Receiver, choose the main room/group as its player). The Client now checks for the plugin first and says exactly that; the Installer's overview shows a warning while it is missing.
- Client 0.5.1 uses the same order as the panels (FIX-028): send sound first, then select the source (retry after "playing").
- "Start recording or casting?": Android asks every time unless the phone has the app-op set once – `tools\phone_allow_capture.bat` (USB or Wireless debugging). The Client shows a hint while it isn't set; in the question choose **Entire screen** (with "A single app" only that one app is heard).

## FIX-030
**Code review 2026-11-15 (official Music Assistant setup) – BUG-030…033**
- Panel `Ma.ajNowPlaying` / Client `ajPanels`: after the first "invalid command" (400/404) answer they stop asking for 30 min – no more error spam in the official MA log. "Played by" only shows with the AJ server; everything else works the same.
- Stable panel id: `deploy_ajpanel.bat` reads the panel's Wi-Fi/LAN MAC over adb and passes `host_id_default=ajpanel_<mac>`; the panel uses it only when it has no id yet (fresh install / after uninstall), so future reinstalls keep the same MA player. The app also reads `/sys/class/net/*/address` before falling back to ANDROID_ID. Existing panels keep their current id (no new player now).
  - Clean-up once: in MA → Settings → Players remove the old unavailable AJ panel players, and in the Installer (Groups) remove dead members such as `ma_ajpanel_8aef75a3d2e1` from Demo and add the current DSPPA player.
- `MediaListener` ignores the Judian player while MA plays on that panel (it's our silent bridge track); a song started in the Judian player while MA is idle still counts.
- Share to rooms: the panel first leaves any group it is in, then leads the picked rooms.
- Client: "Nothing to play yet" text updated (Play my phone exists now).
- Checked against MA 2.10.9 source: every command/field the apps use exists (players/all, players/get, set_members, group_volume, volume_mute, play_announcement, create_group_player, config/players/save + `||plugin||enabled`, auth/*, music/*, player_queues/play_media, select_source legacy plugin path).

## FIX-031
**BUG-034 + installer tooling (Panel 0.6.0, Installer 0.3.0)**
- **Volume rule (D-020):** the Playing slider and the panel's keys change only this room. A second slider "All" (whole group) appears only on the panel that started the current music (Music page or Share on that panel); music started from a phone → every panel only has its own volume. The origin mark ends 20 s after the music stops or the room leaves the group. In the Group sheet other rooms' sliders are shown only on that panel too.
- **Installer → Find & set up panels:** scans every address of the phone's Wi-Fi network (and the panel addresses MA knows): AJ panels answer on port 8766 with name, IP, model, Android, app version, server, state; devices with only the debug port (5555) are listed as "no AJ Panel" (first install from a PC with `deploy_ajpanel.bat`). *Connect* / *Connect all*: the Installer makes a token for the shared MA user `panels` (created if missing, no password typed anywhere) and sends server address + token + room name to the panel.
  - Panel rule: `/api/settings` with a new `host` is accepted from an admin of that new server only while the panel has no working server connection; otherwise only from an admin of its own server.
- **Installer → Update panels:** tick panels (out-of-date ones are pre-ticked), source = newest GitHub release or an APK file picked on the phone (uploaded to `/api/install`), per-panel result. Android still asks "Install?" once on each panel screen.
- **Logs:** panel log now = state JSON + app log (600 lines) + last crash (stack trace kept on disk) + the app's Android log (warnings); reachable from the scan list even before the panel is in MA.
- **Spotify:** rooms and groups get a "Show in Spotify" switch (MA Spotify Connect plugin, same mechanism as the AirPlay switch); group edit also drops dead panel ids on save.

## FIX-033
**BUG-035 – no way to sign a panel in to Home Assistant from the Installer (Panel 0.9.4, Installer 0.5.9)**
- Panel: tap the "Connect Home Assistant" card (or the red header chip "Connect Home Assistant" / "token rejected") → owner PIN if set → **Sign in to Home Assistant** sheet on the dashboard: address (pre-filled `http://<music server>:8123`), username, password, "also Music Assistant". Shows the result ("Home: signed in as panel-majlis"), closes by itself on success. Settings → Smart home dashboard now starts with the same username/password + **Sign in** button; pasting a token moved down as "Advanced".
- Installer: Rooms → a panel → **Sign in panel** (username, password, Home Assistant + Music Assistant / only one) → `POST /api/login`; the panel's info shows "Home Assistant: connected · panel-majlis".
- Verify: sign in with a non-admin HA user → dashboard fills with rooms; `/api/info` `ha` = connected, `ha_user` = the user. Wrong password → "Home: wrong username or password".
- Undo: Settings → paste a token or clear `ha_token` with the adb CONFIG broadcast.

## FIX-032
**In-app updates for every app (Panel 0.6.1, Client 0.6.0, Installer 0.3.1)**
- All three apps look at the newest GitHub release (`ajnetworksa/AJ-Audio-System`, `releases/latest`) and offer the newer APK of themselves: panel every 6 h (first check 1 min after start), phone apps at start and every 6 h.
- **Panel:** blue card on the Playing screen "AJ Panel vX is available · Update"; Settings has "Check for app update". Download → Android's "Install?" window on the panel (REQUEST_INSTALL_PACKAGES, granted by deploy). The Installer's "Update panels" still works for many panels at once.
- **Client / Installer (Android):** card at the top "… vX is available · Update" with download progress; native `AppUpdater` (PackageInstaller session, no file provider) + `AppUpdater$Result` receiver, added to the generated manifest by `tools/patch_update_manifest.py` in CI. The Installer now has its own native folder (`android/app/src/main/{java,kotlin}` kept in git like the Client).
- iPhone apps can't update themselves – new IPA via Sideloadly/AltStore from the release page.
- Needs: the GitHub repo is public; every release is signed with the same release key (CI secrets) – otherwise Android refuses the update ("signatures do not match") and the app says to uninstall once.
