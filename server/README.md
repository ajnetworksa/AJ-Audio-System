# server/ — AJ Music Assistant (D-008: one patch set, two ways to run)

```
server/
  ajsnap-provider/ajsnap/   our fork of MA's snapcast provider (domain "ajsnap")
  ajsnap-provider/upstream_snapcast/   untouched upstream copy, for diffing on MA updates
  ajcast-provider/ajcast/   AJ Cast plugin (domain "ajcast", port 12899) – based on MA's AriaCast Receiver
  patches/                  core MA patches (none yet)
  install_ajsnap.sh         copies ajsnap + applies patches inside the image (used by both builds)
  docker/                   standalone: Dockerfile + docker-compose.yml
  ha-addon/                 local Home Assistant add-on (aj_music_assistant)
```

## What ajsnap adds (v0.1)
- Drop-in for the built-in Snapcast provider (same player ids `ma_<hostID>` → existing groups keep working).
- Lower-latency defaults: buffer **600 ms** (was 1000, min now 100), chunk **20 ms** (was 26).
- API for AJ Panel: `ajsnap/now_playing {player_id}` → `{queue_id, group_id, source, user_id, username, user_display_name}`
  and `ajsnap/panels` (all panels at once). This is how the panel shows **who** is playing (R5).
- AJ Panel works on stock MA too — it just shows no user name until ajsnap runs.

## What AJ Cast adds (plugin, v0.1)
Same protocol as MA's AriaCast Receiver (phones and panels share "any app" sound), so our apps work with either.
AJ Panel ≥ 0.8.7 and AJ Audio Client ≥ 0.7.6 use AJ Cast when the server has it, else the official AriaCast (12889).
- **Dead senders don't block it any more**: websocket heartbeat (4 s) closes vanished phones; a sender that sent
  nothing for 2 s, or the same device reconnecting, is replaced instead of answered with 403. No more reloads.
- **Jitter buffer**: 160 ms cushion before playing, real-time pacing, silence instead of stopping on a Wi-Fi gap,
  backlog trimmed above 500 ms → no stutter on short hiccups, latency stays low.
- Own port 12899 (official one stays free), no UDP discovery (our apps ask MA for the plugin).
- Add it: MA → Settings → Plugins → Add → **AJ Cast** → Finish ("No default – AJ apps choose the room" is preselected; a default player is only needed for third-party AriaCast apps). Then remove the AriaCast Receiver.

## A) Local Home Assistant add-on (keeps HA for other projects)
1. On the PC: `server\ha-addon\build_addon.bat` (fills `aj_music_assistant\aj\`).
2. Copy the folder `aj_music_assistant` to the HA share **addons** (Samba add-on: `\\homeassistant\addons\`).
3. HA → Settings → Add-ons → Add-on store → ⋮ → **Check for updates** → "Local add-ons" → **AJ Music Assistant** → Install (builds on the Pi, takes a few minutes).
4. **Backup first**: official Music Assistant add-on → Backup. Then **stop** it (both use 8095/1704) and start AJ Music Assistant.
   Its data folder is separate: either set up again (players appear automatically) or restore settings from the MA backup (MA → Settings → Backup).
5. MA → Settings → Providers: **disable "Snapcast"**, add **"AJ Snap (panels)"**.

## B) Standalone Docker (no Home Assistant)
```bash
cd server
docker compose -f docker/docker-compose.yml up -d --build
```
Data in `server/docker/data`. Then step 5 above.

## On every MA update
1. Bump `2.10.9` in: `docker/Dockerfile`, `docker/docker-compose.yml`, `ha-addon/aj_music_assistant/build.yaml`, `config.yaml` (`version: "<ma>-ajN"`).
2. Diff upstream snapcast vs `upstream_snapcast/`; port fixes into `ajsnap/`; refresh `upstream_snapcast/` + `UPSTREAM_COMMIT.txt`.
3. Rebuild, run the test checklist in `process.md`.
