# roadmap.md — next big pieces (to do)

Owner's wishes collected 2026-11-18. Not started unless marked.

## 1. Central portal + alerts → build it INTO AJ Netweb (decided 2026-11-18 after reading D:\Devlopment\HA\AJ Netweb)
AJ Netweb already is the fleet portal: FastAPI control plane + React dashboard (Sites, Map, Alerts, Tickets,
Campaigns, Billing), the `ajnetweb_tunnel` HA add-on as edge agent (heartbeat + telemetry every 60 s, remote
commands, owner consent), notification rules (Telegram, WhatsApp, e-mail, webhooks), health issues → alerts,
campaigns with maintenance windows, licensing (signed "green signal" per Install ID). So AJ Audio becomes one more
**product/module** there instead of a second portal.

**a) Agent (`addon/ajnetweb_tunnel/agent.py`, new `collect_audio()` in `collect()`)**
- Music Assistant: add-on state/version (Supervisor API), `players/all` (needs an MA long-lived token in the add-on
  options), AriaCast plugin loaded, Snapcast buffer.
- Every AJ panel: from MA players `ma_ajpanel_*` → its IP → `http://<ip>:8766/api/info` (no login needed): version,
  sync connected, sync drops/5 min, silent/auto updates + state, crashed, zigbee bridge, resample, repair_ready.
- → `telemetry["audio"] = {ma: {...}, panels: [...]}`; panel `/api/events` ring (to add) for self-heals/crashes.

**b) Control plane (`control/app/management.py`)**
- `summarize()`: audio counts (panels total/online/outdated, worst sync drops, aria ok).
- `health_issues()` new keys → existing alerts + notification rules: `audio_panel_offline` (high, > 10 min),
  `audio_sync_unstable` (medium, drops ≥ 3/5 min), `audio_ma_down` (high), `audio_aria_missing/blocked` (medium),
  `audio_update_failed` (medium), `audio_panel_crash` (medium), `zigbee_bridge_error` (medium).
- Dashboard: site side panel gets an **Audio** tab (panels table, versions, buttons).

**c) Remote actions (agent `execute()` new commands)** — panel restart, update now, locate, fetch panel log,
switch auto-updates; via the panel API (admin calls need the MA token the agent holds).

**d) Campaigns** — AJ Panel updates per site/tag inside the maintenance window (panels already self-update on a
schedule; the campaign just sets window/test period through `/api/settings`).

## 2. Licensing → AJ Netweb's existing scheme
- Add product `audio` to the signed green signal (`/config/.ajnetweb/remote_access.json`).
- The agent exposes the signal to panels on the LAN (small read-only endpoint); AJ Panel and the Installer show
  "licensed / grace / expired". Grace period offline; expired = warning + limited features, never silence music.

## 3. Our own Music Assistant build? (decision pending — see answer 2026-11-18)
Recommendation: **official MA + our add-ons, not a full fork.**
- Build "AJ Music Assistant" = the official image + our extra providers/plugins (ajsnap, AJ API, AriaCast fixes) + as few core patches as possible (`server/` already does this: `FROM ghcr.io/music-assistant/server:<ver>` + CI import check).
- CI rebuilds it automatically for every official release; we only touch our own folders. Fixes useful for everyone (e.g. AriaCast heartbeat → no stale senders) go upstream as PRs, then disappear from our patch list.
- Full fork = we merge MA's ~weekly releases ourselves forever → high effort, risk of falling behind on security and provider fixes.
- Switch sites to the AJ build only once it gives real value (AriaCast keep-alive + jitter buffer, "who plays" per panel, central-portal hooks).

## 4. Smaller to-dos
- Web version of the Installer's read-only parts for PC troubleshooting (status, logs, locate) — today: `tools\aj_tools.bat` menu.
- DSPPA: cable or better Wi-Fi if sync drops continue after Panel 0.8.4's lighter resampling.
- Zigbee: confirm the stty inversion fix (Panel 0.8.5) brings ZHA back on the X8.
