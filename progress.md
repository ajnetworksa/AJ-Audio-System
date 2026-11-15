# progress.md — What's done, what's next

Newest entries on top. Decisions are `D-###`.

## Current status (2026-11-09, evening)

- ✅ Music Assistant (HA add-on, Pi, **192.168.8.5**) groups 3 panels (P8_EN, M20_EN, smart) and plays in sync, audible.
- ✅ iPhone → AirPlay → MA group works.
- 🆕 **AJ Panel v0.1 built** (`apps/panel/releases/AJ_Panel_v0.1.apk`) – own sync player + now-playing card/screen/MediaSession + Pause here / Leave group. **Not yet installed** → run `tools\deploy_ajpanel.bat`.
- 🆕 **ajsnap provider v0.1** (fork, lower-latency defaults, `ajsnap/now_playing` with user) + Docker + local HA add-on packaging – **not yet built/deployed**.
- 🆕 Flutter phone app skeleton + GitHub Actions (APK + unsigned IPA) – **not yet compiled** (first CI run after push).
- 🆕 Local git repo created; owner pushes to GitHub.
- ⚠️ Android "any app": AJ Audio v0.1 (Java) untested (BUG-018); AriaCast receiver to try.

- 🆕 **AJ Panel v0.2**: Judian panels keep their smart-home UI – the song is pushed into the Judian music player (DLNA metadata bridge, FIX-020). DSPPA uses AJ Panel's own screen/card.

## Next (in order)

1. **Test AJ Panel v0.1** on .210/.73/.46: deploy script → re-create the MA group with the new `ma_ajpanel_…` players → check sound, sync, card, Pause here, Leave group, reboot recovery. Log bugs.
2. **Install AJ Music Assistant** (local HA add-on, `server/README.md` A) → disable Snapcast provider, enable AJ Snap → panel shows "played by <user>". Then tune buffer (600 → 400 ms).
3. Push repo → check CI → install Flutter APK on Android + IPA via Sideloadly on iPhone.
4. AJ Panel v0.2: merge ControlX Bridge (YJY zones), Arabic UI, push updates from server (no polling), per-user permissions (R6).
5. Android "play my phone" → Flutter (platform channel, port of CaptureService) / try AriaCast.
6. ajsnap v0.2: keepalive / faster unavailable detection, per-panel latency trim from MA UI, scenes.

## Decisions

- **D-001** (2026-11-05) Music Assistant is the server/brain; panels join via Snapcast protocol. Reason: perfect sync, groups, AirPlay receiver, maintained.
- **D-002** (2026-11-05) No Spotify provider; users play from their own apps.
- **D-003** (2026-11-09) No attempt to appear as Google Cast (licensing). Android paths: own app capture, AriaCast, Bluetooth bridge.
- **D-004** (2026-11-09) Fork MA snapcast provider (`ajsnap`). ~~requires standalone Docker~~ → superseded by D-008.
- ~~D-005~~ superseded by D-007.
- **D-007** (2026-11-09) Phone app = **one cross-platform codebase (Flutter)** for Android + iOS. **No App Store**: Android = APK sideload; iOS = build `.ipa` on a macOS machine (GitHub Actions macOS runner or Codemagic — Flutter/iOS can't be compiled on Windows or in our cloud box) and install with Sideloadly/AltStore from Windows using an Apple ID (free ID = re-sign every 7 days; paid developer account = 1 year). Current Java app v0.1 stays as the reference/test client until the Flutter app replaces it.
- **D-008** (2026-11-09) Server runs **both ways from the same patch set**: (a) standalone Docker image and (b) a **local Home Assistant add-on** — both built `FROM ghcr.io/music-assistant/server:<version>` + our `ajsnap` provider + patches. On every MA update: bump the base tag, re-apply patches, run the test checklist. HA stays usable for other projects.
- **D-009** (2026-11-09) **User access (R6) uses Music Assistant's built-in users**: each MA user has a `player_filter` (allowed player ids; empty = all) enforced by `has_player_access()`; queues store the `userid` of who started playback → panel shows the user (R5). We only add what MA lacks (e.g. "may pause this panel" for guests at the panel).
- **D-006** (2026-11-09) One panel APK for all panel models; capabilities detected at runtime.
- **D-010** (2026-11-09) **AJ Panel is the primary panel app** – it replaces the Snapcast app and (later) ControlX Bridge. Panels get new MA player ids `ma_ajpanel_<mac>`.
- **D-011** (2026-11-09) "Who is playing" is served by our provider API (`ajsnap/now_playing`), not a core MA patch – fewer patches to re-apply on MA updates.
- **D-015** (2026-11-11) Phone side splits in two: **AJ Setup** (installer/admin app: rooms, groups, users, logs) and **AJ Audio** (optional client rooms remote). Same Flutter repo, two app targets. Clients mainly use AirPlay/Bluetooth to named groups.
- **D-016** (2026-11-11) "Take a break": saved groups are MA **dynamic sync groups** (members = preset pulled in on play, removable per session, dissolve on stop – checked in sync_group/player.py). Break = `set_members(remove=[me])` + panel stores {group, until/mode}; at expiry `set_members(add=[me])` if the session still runs; while on a break the panel removes itself again if a new session pulls it in. Works on stock MA; moves server-side into ajsnap later so it survives panel reboots/offline.
- **D-018** (2026-11-12) Android "any app → group" = AJ Audio Client capture → MA AriaCast Receiver (WebSocket PCM) + `select_source`; Media output stays Cast/Bluetooth-only (BUG-017). Apps renamed **AJ Audio Client** / **AJ Audio Installer**.
- **D-019** (2026-11-15) Panel UI v2: Sonos-style player (rail, Playing, Rooms with Scenes + zone cards, Group sheet); owner found v1 looked like a web page. Supersedes D-017's layout.
- **D-017** (2026-11-11) Panel UI follows the DSPPA home look (clock, landscape, right rail, bottom now-playing bar); one APK, Judian keeps vendor UI + bridge.
- **D-014** (2026-11-10) One release signing key for all Android apps (CI secrets); iOS stays unsigned in CI (signed at install by Sideloadly/AltStore).
- **D-013** (2026-11-09) DSPPA panels use AJ Panel as their UI; Judian panels keep their smart-home UI and AJ Panel feeds the song into the Judian music player (DLNA metadata bridge).
- **D-012** (2026-11-09) Git repo local in `AJ-Audio-System`, owner pushes manually to GitHub.

## Log

### 2026-11-15
- AJ Panel v0.5.0: Share – YouTube/any app playing on a panel can be played in other rooms or a saved group (BUG-025/FIX-025).
- AJ Panel v0.4.1: Music page – browse/search the MA library and play on this room, its group or any saved group straight from the panel (no phone needed).
- AJ Panel v0.4.0: new Sonos-style UI and group handling (scenes + tick-to-group sheet, Join/Add on room cards, group volume, per-room volume).

### 2026-11-12
- AJ Panel v0.3.2: volume no longer jumps on M20/DSPPA (BUG-024/FIX-024 – vendor fades ignored, mute respected, fight guard).
- **AJ Audio Client v0.5.0**: Quick Settings tile → AirPlay-style picker (groups/rooms, move while playing, Stop); phone auto-muted while playing and restored on stop (self-check if a phone's capture follows the volume); consent prompt can be skipped per phone with `tools/phone_allow_capture.bat` (app-op PROJECT_MEDIA; SystemUI's MediaProjectionPermissionActivity grants immediately when `hasProjectionPermission` is true – checked in AOSP main).
- **Android Media output re-researched** (AOSP MediaRoute2ProviderService/MediaRoute2Info main branch, Samsung Audio Mirroring, androidx/media#3500): only Bluetooth, certified Google Cast and system-privileged providers (`MODIFY_AUDIO_ROUTING`) can appear; owner rejected Bluetooth → network solution = "Play my phone".
- **AJ Audio Client v0.4.0** (renamed from AJ Audio): "Play my phone" card + Quick Settings tile; native capture service streams to MA's AriaCast Receiver and selects it on the chosen room/group (FIX-023). CI patches the generated manifest (`tools/patch_client_manifest.py`).
- **AJ Audio Installer v0.2.0** (renamed from AJ Setup).
- D-018: Android "any app" path = capture → AriaCast (MA-native, live PCM) instead of HTTP AAC + play_media.

### 2026-11-11
- **AJ Setup v0.1 (installer app, Flutter, `apps/setup-flutter`)**: admin sign-in, overview (server, rooms online, needs attention, test sound everywhere), Rooms (rename = AirPlay name, AirPlay switch, max volume, delay trim, now-playing card, test sound, restart player, panel log, update panel app from GitHub release), Groups (create/rename/rooms/AirPlay/delete), Users & access (add, role, allowed rooms, password, delete, `panels` protected), Logs (checks, MA log, panel logs, one-tap full report – tokens scrubbed). CI builds AJ Audio + AJ Setup (APK + IPA) via a matrix. Not compiled locally (no Flutter here).
- **AJ Panel v0.3.1**: LAN API on port 8766 for AJ Setup (info, chime.wav, log, restart, settings, update via PackageInstaller); admin-only calls checked with MA `auth/me`. Deploy script grants REQUEST_INSTALL_PACKAGES.
- **AJ Panel v0.3.0 – new look built** (mockup → code): Home (clock, gradient + landscape, group tiles "play here or join", break banner, now-playing bar with volume), Now playing (chips, transport, THIS ROOM: volume, mute here, take a break, add rooms), Play this on… (groups + rooms), Take-a-break dialog (15 min/1 h/2 h/until 07:00/until my music stops/until group ends) with auto-rejoin (`Breaks`, D-016), old screen kept as Settings. Optional home-screen mode for DSPPA (`deploy_ajpanel.bat -asHome`). Not yet tested on a panel.
- AJ Panel v0.2.2: real panel volume synced with MA both ways (BUG-022 / FIX-022).
- UI research + revamp mockups (design canvas "AJ Audio UI Revamp": 5 panel screens, 5 installer screens, 1 client screen); spec in ui.md §C–E, R11–R14, D-015…D-017. `tools/panel_screenshots.bat` to capture more DSPPA/Judian screens.

### 2026-11-10
- Flutter app built by CI and running on the owner's Samsung; temporary grouping works (P8 leads M20 + smart). ▶ gave 500 (BUG-021). **Flutter v0.3**: Groups section (saved named groups via Sync group provider + temporary groups), "New group" sheet (name / save or temporary / rooms), change rooms, ungroup, delete, per-group AirPlay receiver switch, AirPlay on by default for new saved groups + "AirPlay for all groups" button, leave-group per room. Saving groups / AirPlay switch need an MA **admin** user (scope config.players.write).
- **First install (logs26):** AJ Panel installed on P8_EN (now **192.168.8.194**), M20_EN (.73), DSPPA (.46); all logged in as MA user `panels`, snapclient connected to 192.168.8.5:1704 (FLAC 48 kHz, oboe/AAudio; DSPPA resamples to 44.1 kHz). P8: Judian DLNA renderer found (127.0.0.1:1501). M20: own-IP fetch of the renderer description timed out → v0.2.1 fetches via 127.0.0.1. v0.2.1 also hides the per-second "No chunks available" idle lines. Not yet verified: sound + song shown while playing.
- AJ Panel confirmed running on P8_EN + M20_EN (new Snapcast-protocol players online in MA); old Snapcast-app players show as offline duplicates → `tools/remove_snapcast_app.bat` uninstalls the old app, then remove the grey players in MA.
- CI fix: android-java used `setup-android` + `${{ env.ANDROID_SDK_ROOT }}` (empty in env context) → now uses the runner's preinstalled SDK via `$ANDROID_HOME`; actions bumped to checkout/setup-java v5 (Node 24). Release-key signing added to both workflows; `tools/make_release_key.bat`.

### 2026-11-09
- Panels log in with a **built-in MA user** (e.g. `panels`), independent of the owner's Home Assistant login: deploy script asks username/password, panel keeps only a long-lived token (`.LOGIN` broadcast).
- AJ Panel v0.2: `DlnaMetaBridge` for Judian (JdLauncher/JdReceiver decompiled: launcher shows only jdmediarender's songs; DLNA push carries DIDL title/artist/cover). Silent MP3 server verified (valid 128 kbit/s, -91 dB). Deploy script: Judian = background mode, `-f 32` broadcasts, more logs (processes, audio tracks, media sessions).
- D-013: DSPPA → AJ Panel is the UI; Judian → keep vendor UI, AJ Panel only plays + feeds the song to the vendor player.
- Built **AJ Panel v0.1** (com.ajnetwork.ajpanel, min SDK 26, bundles snapclient 0.29 armv7+arm64): supervised player, MA poll 1 s, overlay card/chip, full screen, MediaSession, Pause here/Leave group/volume, adb CONFIG broadcast, boot start. NowPlaying logic tested against a mock MA server.
- `tools/deploy_ajpanel.bat` (install, overlay permission, stop Snapcast app, configure, collect logs26) and `tools/build_java_apk.sh` (all three Java apps build).
- **ajsnap** fork created (domain ajsnap, buffer 600 / chunk 20 defaults, `ajsnap/now_playing`, `ajsnap/panels`), `server/docker`, `server/ha-addon`, `install_ajsnap.sh`.
- Flutter app skeleton (sign-in, rooms, group/ungroup, volume, mute, transport, user badge) + CI workflows.
- Local git repo initialised.
- Decisions D-007 (Flutter, no App Store), D-008 (dual Docker + HA add-on from one patch set), D-009 (MA built-in user access: `player_filter`, queue `userid`).
- Found in MA source: `controllers/webserver/helpers/auth_middleware.py: has_player_access()` and `auth.py` user `player_filter`; `player_queues` stores `userid`.
- Researched Open Screen / Google Cast / Android Output Switcher → only Google Cast or Bluetooth appear system-wide (BUG-017 WONTFIX).
- Built **AJ Audio v0.1** (Android): discovery, login, rooms, grouping, volume, "play my phone" (capture → AAC live stream → MA play_media).
- Read MA server API (307 commands) from the live server (`logs25\commands.json`).
- Cloned MA server source (commit efe0496…, 2026-11-09): `snapcast` provider manifest `"stage": "unmaintained"`; `ariacast_receiver` plugin exists (alpha).
- Created AJ-Audio-System folder + docs (goal, agent, process, progress, bug, bug_fix, troubleshoot, ui).

### 2026-11-08
- iPhone AirPlay → MA group works after disabling duplicate players (FIX-011).
- Found panels on Pi Wi-Fi .6 → moved to Ethernet .5 (FIX-009); no-sound fixed by clean player restart (FIX-010).
- MA crash on port 8097 traced to an AJ Netweb add-on (BUG-008).

### 2026-11-03 → 11-05
- ControlX Bridge v1.0–v2.0 (Control X ↔ YJY player, 4 zones; v2.0 adds own group server + DLNA "All Rooms", verified against official snapclient).
- Music Assistant chosen (D-001); panels joined MA's built-in snapserver.

### 2026-11-01 → 11-02
- Panel inventory (Judian P8, DSPPA DM938T, DM858 backup); AirPlay Server patched (FIX-001); Snapcast app installed; DM858 app ported (FIX-004).
