# roadmap.md — next big pieces (to do)

Owner's wishes collected 2026-11-18. Not started unless marked.

## 1. Central portal for all sites ("AJ Control")
One web admin (like the AJ Netweb admin panel) where AJ Network sees **every installed site** at a glance and gets **warned before the customer complains**.

**Per site, shown in the portal**
- Server: Music Assistant up/version, AriaCast plugin ok, Home Assistant ok, ZHA ok (if used), disk/CPU/RAM of the Pi.
- Every panel: online, AJ Panel version, sync connected, sync drops/5 min, silent/automatic updates, last crash, Wi-Fi signal, Zigbee bridge state.
- Phones: client versions (optional).
- Last 24 h events (errors, restarts, self-heals done: AriaCast reload, group rejoin, resampler switch …).

**How data gets there (proposal)**
- A small **site agent** on the site's Home Assistant Pi (HA add-on "AJ Site Agent") polls MA + the panels' `/api/info` every minute and pushes a heartbeat + events over **HTTPS outbound** to the portal (no port forwarding at the customer, works behind NAT).
- Panels also report crashes/self-heals to the agent (already in their logs/`/api/info`; add a `/api/events` ring).
- Portal: web app + database (sites, panels, events, licences, users); runs on AJ Network's server (or the existing ajnetweb host).

**Alerts**
- Rules: panel offline > 10 min, sync drops > N/5 min, MA down, AriaCast blocked and not self-healed, update failed, crash, Zigbee radio not answering.
- Channels: portal dashboard + e-mail / WhatsApp / Telegram to AJ staff. Ticket list with "acknowledged / fixed".
- Remote actions from the portal (later, through the agent): restart panel app, reboot panel, push update, collect logs, locate.

## 2. Licensing
- Each site gets a licence (site id + number of panels + expiry/feature flags) issued by the portal, signed (Ed25519).
- Agent fetches/renews it; panels ask the agent. Grace period offline (e.g. 30 days) so a cut internet line never silences a house.
- Without a valid licence: warning in the Installer, then limited features – never a hard stop of playing music.

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
