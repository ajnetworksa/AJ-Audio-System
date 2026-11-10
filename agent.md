# agent.md — Rules and context for anyone (human or AI agent) working on this project

Read this file first, then `goal.md`, then the top of `progress.md`.

## 1. Repository layout

```
AJ-Audio-System/
├── goal.md            what we build, requirements, non-goals
├── agent.md           this file — rules & context
├── process.md         how we build, test, release, document
├── progress.md        dated log of what was done + current status + next steps
├── bug.md             every bug found (BUG-###), status open/fixed/wontfix
├── bug_fix.md         how each bug was fixed (FIX-### ↔ BUG-###)
├── troubleshoot.md    how to collect logs, read them, what each message means
├── ui.md              screens & behaviour of phone app and panel app
├── apps/
│   ├── phone-android/   AJ Audio (Android) — com.ajnetwork.ajaudio
│   ├── phone-ios/       AJ Audio (iOS) — needs macOS + Xcode to build
│   └── panel/           AJ Panel — runs on every panel (Android 8.1+, armv7 + arm64)
├── server/
│   └── ajsnap-provider/ our fork of Music Assistant's snapcast provider
├── legacy/              ControlX Bridge (com.ajnetwork.cxbridge) v1.0–v2.0 sources
└── tools/               PowerShell/.bat helpers (install, logs, network tests)
```

The older test scripts `1_…bat` … `25_…bat` and logs live in `D:\Devlopment\Smart Tuya\New folder\` (see troubleshoot.md §2).

## 2. Devices (current lab)

| Name in MA | Model | IP | Android | CPU / ABI | Notes |
|---|---|---|---|---|---|
| P8_EN | Judian P8 (Tuya Zigbee gateway) | **192.168.8.194** (was .210) | 10 (SDK 29) | Allwinner A133, **armeabi-v7a only**, 1 GB | adb :5555, no root, SELinux permissive, root sshd (key-only) |
| M20_EN | Judian M20 | 192.168.8.73 | 10 | Allwinner | adb :5555 |
| smart / dsppa938t | DSPPA DM938T (built by YJY) | 192.168.8.46 | 8.1 (SDK 27) | Rockchip PX30, arm64 + armv7, 2 GB | Google Play Protect active, EShare (licensed) installed, YJY player has AIDL control |
| — | Raspberry Pi (HA OS + Music Assistant 2.10.9) | **192.168.8.5** eth / 192.168.8.6 wlan | — | arm64 | MA web 8095, stream 8097, snapserver 1704/1705 |
| — | Windows PC (build/adb host) | 192.168.8.143 | — | — | Android SDK at `%LOCALAPPDATA%\Android\Sdk` |

## 3. Hard rules

1. **Never** bypass licensing or authentication of other companies (Google Cast device auth, EShare licence, AirFly SDK). Say so and propose a legitimate route.
2. **No root required.** Everything must install with `adb install` / sideload. Don't depend on system signatures.
3. Panels are **32-bit on Judian** → every native lib must ship `armeabi-v7a`; Java-only preferred.
4. Every install on DSPPA may trigger **Play Protect** ("Unsafe app blocked") → target SDK ≥ 29 and tell the user to tap *More details → Install anyway*.
5. Before changing anything on a panel: **back up** the affected app/data (see process.md §4).
6. Every bug you find → `bug.md` (new BUG id). Every fix → `bug_fix.md` with the same id. Update `progress.md` at the end of each work session.
7. Don't guess API shapes: Music Assistant publishes its command list at `GET http://<ma>:8095/api-docs/commands.json` (copy in `New folder\logs25\commands.json`).
8. Keep the panel app **one APK for all panels**; device differences go in a capability table (zones, audio streams, YJY/Judian services) detected at runtime.
9. **Commit after every piece of work** – every change, fix, build, new script or doc update gets its own git commit right away, so the history shows *what* was done and *when*:
   - one logical change per commit; never leave work uncommitted at the end of a session
   - message: `area: what was done` (areas: `panel`, `phone`, `server`, `ci`, `tools`, `docs`, `legacy`), then a short body with **why** and any test result, e.g.
     `panel: show song in Judian player (DLNA bridge)` / body: `M20 needs 127.0.0.1; tested on P8 + M20`
   - mention the bug/decision id when there is one (`FIX-020`, `D-013`)
   - commit releases (`*/releases/*.apk`) together with the source change that produced them
   - never commit tokens, passwords, keystores or logs (see `.gitignore`)
   - the owner pushes to GitHub; tell him when there are commits to push

## 4. Key technical facts (learned the hard way)

- **Music Assistant API**: `POST /api` body `{"message_id","command","args"}`, header `Authorization: Bearer <token>`. Login: `auth/login(username,password,provider_id,device_name)`. Grouping: `players/cmd/set_members(target_player, player_ids_to_add, player_ids_to_remove)`, `players/cmd/group_many`, `players/cmd/ungroup`. Volume: `players/cmd/volume_set`, `players/cmd/group_volume`. Play: `player_queues/play_media(queue_id, media, option, …, user)`.
- **Snapcast wire protocol** (implemented & verified in `legacy/.../SnapServer.java`): 26-byte LE header `type,id,refersTo,sent.sec,sent.usec,recv.sec,recv.usec,size`; types 1 CodecHeader, 2 WireChunk, 3 ServerSettings, 4 Time, 5 Hello, 7 ClientInfo.
- **Snapcast Android app** `de.badaix.snapcast`: `SnapclientService` is exported → `am start-foreground-service -n de.badaix.snapcast/.SnapclientService -a ACTION_START --es EXTRA_HOST <ip> --ei EXTRA_PORT 1704` (ACTION_STOP to stop). It discovers servers named `Snapcast*` on `_snapcast._tcp`.
- **YJY player (DM938T)** AIDL `com.yjy.musicplayer.IMusicplayerInterface` via action `com.yjy.musicplayer.ACTION.musicplayerAidl`, 4 zones; zone volume streams on U6/PX30: zone1 MUSIC(3), zone2 NOTIFICATION(5), zone3 ALARM(4), zone4 NOTIFICATION(5).
- **Control X (Auxdio) protocol**: UDP 40188 (device), broadcast 40189, multicast 234.1.1.1; frame `cmd(2) seq model/devID lang zone room len payload xor`. Full table in Project doc `yjy-controlx-bridge-plan.md`.
- **Network**: panels must reach the server over a **wired** server address; the Pi's Wi-Fi caused 0.6–13 s stalls (BUG-009).
- **Google Cast / Android Media Output**: impossible without Google certification (see goal.md §4).

## 5. Build toolchain (no Gradle needed)

See process.md §2. Cloud: `javac -source 8 -target 8` against `android.jar` + `core-lambda-stubs.jar` → `d8` → `aapt2 link` (from apktool jar) → add `classes.dex` → `uber-apk-signer`. SDK jars come from the owner's PC SDK (build-tools 35.0.0, platform android-35).

## 6. Communication with the owner

- Owner prefers: short, direct status; scripts he can double-click (`.bat`) that save logs to `logsNN\`; then "check the logs".
- Always say what a script changes vs. only reads.
- Timezone Asia/Riyadh.
