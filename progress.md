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
- **D-012** (2026-11-09) Git repo local in `AJ-Audio-System`, owner pushes manually to GitHub.

## Log

### 2026-11-09
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
