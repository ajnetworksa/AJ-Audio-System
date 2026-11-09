# goal.md — What we are building and why

> Owner: AJ Network Solutions · Started: 2026-11-01 · This file changes only when the goal changes.

## 1. Vision

Turn the Chinese Tuya/Zigbee wall panels (Judian, DSPPA/YJY, Auxdio…) into a **multi-room audio system**:
every panel is a speaker, any set of panels can be grouped and plays **in sync**, people
play from **their own phone with whatever app they like**, and every panel **shows what is
playing and who is playing it**.

## 2. System at a glance

```
 ┌────────────── phones ──────────────┐
 │ AJ Audio (Android)  AJ Audio (iOS) │   discover · group · volume · "play my phone"
 │ iPhone: AirPlay  ·  Android: app / │
 │ AriaCast / Bluetooth "Whole House" │
 └───────────────┬────────────────────┘
                 │  MA API (HTTP/WS, Bearer token per user)
 ┌───────────────▼────────────────────┐
 │ Server (Raspberry Pi, cable 192.168.8.5)
 │  Music Assistant  +  AJ Snap provider (our fork of the snapcast provider)
 │  + AJ policy (users → allowed panels/groups)                       │
 └───────────────┬────────────────────┘
                 │  Snapcast-compatible stream (time-stamped, synced)
 ┌───────────────▼────────────────────┐
 │ AJ Panel app (on every panel)      │  embedded sync player · now playing + user ·
 │  Judian P8/M20 · DSPPA DM938T · …  │  pause/leave for THIS panel · Control X bridge
 └────────────────────────────────────┘
```

## 3. Requirements (from the owner)

| # | Requirement | Status |
|---|---|---|
| R1 | Panels play music **in sync** when grouped; music keeps playing in the background | ✅ works with MA + Snapcast (see progress.md) |
| R2 | **Group / ungroup** panels from the phone (Android **and** iOS) | Android Java v0.1 built (untested) · Flutter v0.2 skeleton + CI (D-007), first build pending |
| R3 | Play **from any phone app** (YouTube, etc.) to a panel or group | iPhone ✅ via AirPlay · Android: app capture v0.1 / AriaCast / Bluetooth bridge |
| R4 | Panel must **show what is playing** (title, artist, art, which group) | AJ Panel v0.1 built (test pending) |
| R5 | Panel shows **which user** is playing; someone at the panel can **pause or close music for that panel only** | AJ Panel v0.1 + ajsnap `now_playing` (D-011), test pending |
| R6 | **User-based access**: who may use which panel / play to which group | planned — MA built-in per-user `player_filter` (D-009) + our additions |
| R7 | Fork the **Snapcast provider** (MA marks it `"stage": "unmaintained"`) → more reliable, optimized for panels, **lower delay** | ajsnap v0.1 fork built (defaults + API), deploy pending |
| R8 | Must run on **all panels** (32-bit Android 10 Judian, 64-bit Android 8.1 PX30 DSPPA, more later), no root | design constraint |
| R9 | Keep **Control X** (DSPPA/Auxdio app) control of the YJY player working | ✅ ControlX Bridge v1.1/v2.0 |
| R10 | Panel protocol work (Tuya/Zigbee, panel-side features) | owner will check later |

## 4. Non-goals / hard limits (do not spend time here)

- **Appearing in Android's Media Output list or the YouTube cast button as a network speaker.** Android only lists Google Cast devices there; receivers need a Google-signed certificate checked by Play Services (cannot be bypassed legitimately). Options instead: Bluetooth "Whole House" receiver, our app, AriaCast. A licensed Google Cast module (e.g. LinkPlay) is the only product-grade path.
- Copying licensed software between devices (EShare receiver, AirFly SDK). Use legit licences.
- Rooting panels unless the owner decides so.

## 5. Success criteria

- Group of N panels starts within **< 2 s** of pressing play; end-to-end delay phone→panels **< 1.5 s** target (Snapcast buffer tuning in R7).
- Drift between panels **< 5 ms** (inaudible).
- A panel reconnects automatically after Wi-Fi drop / reboot, without user action.
- Every panel shows title + user within 2 s of a change.
- No dropouts on a healthy network (panel→server ping < 20 ms).

## 6. Key environment facts

| Item | Value |
|---|---|
| Music Assistant | 2.10.9, HA add-on on Raspberry Pi; **use 192.168.8.5 (Ethernet)**; 192.168.8.6 is the Pi's Wi-Fi — don't use |
| Panels | Judian P8_EN 192.168.8.210 · Judian M20_EN 192.168.8.73 · DSPPA DM938T ("smart") 192.168.8.46 |
| Wi-Fi | COMFAST_4099_2G, 2.4 GHz ch 6 |
| Custom MA providers | built into our own image `FROM ghcr.io/music-assistant/server:<ver>`; deployed as standalone Docker **or** local HA add-on (D-008) |
