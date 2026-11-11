# ui.md — Screens and behaviour

Design language: dark background, large touch targets (panel screens are 1024×600 / 1280×800 at 160 dpi, used from a wall), accent blue `#40A0FF`, text white/grey. Arabic + English (RTL-ready layouts).

---

## A. Phone app — "AJ Audio" (Android now, iOS later)

### A1. First run / login
- Auto-discovers Music Assistant (`_mass._tcp`); fallback address field `http://192.168.8.5:8095`.
- Username + password (MA user). Token stored; long-press server line = log out.
- Later (R6): only rooms/groups the user is allowed to use are shown.

### A2. Rooms (home screen)
- One **card per panel**: name, state + title, "in <leader>'s group" / "group of N", volume slider (group volume if leader of a group).
- Tick rooms → **Group ticked** (first ticked = leader) / **Ungroup ticked**.
- Transport: ⏮ ⏯ ⏭ ⏹ for the ticked leader.

### A3. Play my phone
- Big button **"Play my phone's audio on …"** → Android capture prompt → plays whatever the phone plays on the ticked room/group.
- Ongoing notification "Playing phone audio on <room>" with **Stop**.
- Planned: Quick Settings tile "Play to <last room>" (one tap).

### A3b. Groups (v0.3)
- Home = **GROUPS** (saved groups with name + temporary groups) above **ROOMS**.
- **New group** button: name, "Save with a name" (saved = permanent Sync group + its own AirPlay receiver "<name> | Music Assistant") or temporary (until ungroup), tick rooms.
- Group card: members, now playing, played by, ⏮ ⏯ ⏭ ⏹, group volume, AirPlay switch (saved), menu: change rooms / ungroup / delete.
- Room card: now playing, "in <group>", leave group, mute, volume.
- ▶ on an empty group explains how to start music (BUG-021).

### A4. Planned (v0.2+)
- Saved groups (create_group_player) shown as "Scenes": *Whole House*, *Ground floor*…
- Now-playing bar with artwork; per-room mute.
- User badge on each room ("Ahmed is playing").
- iOS: same screens; "play my phone" = system AirPlay picker to "<group> | Music Assistant".

---

## B. Panel app — "AJ Panel" (one APK for all panels)

### B1. Background (always)
- Embedded sync player (Snapcast-compatible) connected to the server; starts at boot; reconnects automatically.
- Control X bridge (YJY panels) kept.

### B2. Now-playing overlay (when this panel is playing from the server)
```
┌──────────────────────────────────────────────┐
│ [art]  Song title                             │
│        Artist · in group "Whole House" (3)    │
│        ▶ played by  Ahmed (phone)             │
│   ⏸ Pause here     ⏏ Leave group     🔊 ▬▬▬○  │
└──────────────────────────────────────────────┘
```
- Shows title, artist, artwork, group name & size, **user who started it** (MA queue/`user`).
- **Pause here** = mute/stop only this panel (others keep playing). **Leave group** = remove this panel from the group.
- Collapses to a small floating chip after 10 s; tap to expand. Hidden when nothing plays.
- Permission-aware (R6): if the local user isn't allowed, buttons are hidden/disabled.

### B2b. Implemented in v0.1
- Card bottom-centre (max 760 dp), chip bottom-right; card re-opens for 10 s on new song / new group / new user, not on volume changes.
- Full-screen app view: art left, status + settings + log right. Media notification + MediaSession for launcher widgets / lock screen.
- Saved-group (static) members: "Leave group" only mutes this panel.

### B3. Settings screen (long-press chip, or app icon)
- Server address (auto via mDNS), panel name, zone count (YJY), output stream, buffer/latency, logs export.

---

# v2 redesign (2026-11-11) – mockups: "AJ Audio UI Revamp" design canvas (claude.ai, private to the owner)

Research: Sonos (rooms list with now-playing, "group" sheet with ticks, one home screen), WiiM Home (persistent named groups), in-wall keypads/touchpads (trivum, Russound, Powersoft WM touch: big targets, one zone focus), installer tools (Control4 Composer Express, BluOS configuration utility). Panel look follows the **DSPPA DM938T home** the owner likes: big clock top-left, calm landscape background, icon rail on the right, one now-playing bar at the bottom.

## C. Panel app v1 (AJ Panel)
- **Home**: clock + date + room name; tiles "PLAY HERE OR JOIN": saved groups (state, "you are in this group"), "This room only" (Bluetooth/AirPlay name); now-playing bar (art, title, artist · group (n) · played by, ⏮ ⏯ ⏭, volume).
- **Now playing**: big art, group chip + source chip, played by, progress, transport for the group; box **THIS ROOM**: room volume, *Mute here*, *Take a break from group…*, *Add rooms…*.
- **Take a break** (never permanent): 15 min · 1 h · 2 h · until 07:00 · until I stop my own music (rejoin 5 min after it ends) · until the group's music ends. Option "quiet while away" (else Bluetooth/AirPlay may play here). Other rooms keep playing.
- **Away banner**: "On a break from Whole House · back in 42 min" with *+30 min* and *Rejoin now*; tiles for Bluetooth, AirPlay, sleep timer.
- **Play this on…**: whatever plays on this panel (e.g. AirPlay to the room) can be moved to a group or extra rooms without restarting the song; busy groups show who is using them; rooms on a break / offline are marked.
- Judian panels keep their own UI; only the break/rejoin logic and the song bridge run there.

## D. Installer app (new: "AJ Setup")
For installers only; clients use AirPlay/Bluetooth to named groups (no app).
- Sign in once with an MA **admin**; site overview: server OK, panels online, "needs attention" list.
- **Rooms & panels**: rename (also AirPlay name), model/IP/app version/Wi-Fi quality, AirPlay receiver, show card on panel, max volume, delay trim, test sound, restart player, get panel log, update app.
- **Groups**: name, rooms, AirPlay receiver, "rooms may take a break (max …)", start volume.
- **Users & access**: MA users with allowed rooms/groups (`player_filter`), "may pause other people's music", guest join codes; protect the `panels` user.
- **Logs & diagnostics**: one-tap full report (MA log + diagnostics + every panel log + ping check, no secrets), per-source view/share, live checks.

## E. Client app (AJ Audio, optional)
Rooms tab: active group card with transport, room tiles (who plays, on a break), hint how to play from any app via AirPlay/Bluetooth; tabs Rooms · Groups · Settings.
