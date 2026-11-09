# ajsnap — our fork of Music Assistant's Snapcast provider

Upstream: `music_assistant/providers/snapcast` from github.com/music-assistant/server
(commit in UPSTREAM_COMMIT.txt). Upstream manifest says `"stage": "unmaintained"`.
Licence: Apache-2.0 (LICENSE-music-assistant) — keep the licence and note our changes in each modified file.

`upstream_snapcast/` = untouched copy for diffing. Our provider will live in `ajsnap/` with domain `ajsnap`.

## How the upstream provider works (read so far)
- Runs the real `snapserver` binary (built-in mode) with `snapserver/snapserver.conf`, or talks to an external one over JSON-RPC (port 1705).
- Music Assistant feeds each queue as a TCP PCM source (`ma_stream.py`, s16/s24, 48/96/192 kHz) into snapserver; `control.py` is the stream control script.
- Config already exposes: built-in **buffer size**, **chunk ms**, **transport codec**, initial volume, send-to-muted, stream idle threshold, sample rate / bit depth.
- Liveness: snapserver has no TCP keepalive → provider polls `lastSeen` every 5 s, marks clients unavailable after 15 s frozen.

## Goals for ajsnap (R7)
1. Lower end-to-end delay: defaults tuned for wired server + Wi-Fi panels (buffer ≤ 400 ms target, chunk 10–20 ms, PCM or FLAC), per-panel latency trim.
2. Faster group start / switch, no stale clients (keepalive, quicker unavailable detection, auto re-attach).
3. Push **now-playing + user** metadata to panels (for AJ Panel overlay, R4/R5).
4. Panel-only actions: pause/mute a single panel, leave group — exposed for AJ Panel.
5. Hooks for per-user access control (R6).

## Deployment requirement
Custom providers need **standalone Music Assistant (Docker)** — mount `ajsnap/` into
`/app/venv/lib/python3.x/site-packages/music_assistant/providers/ajsnap` or build an image
`FROM ghcr.io/music-assistant/server:<version>` + `COPY ajsnap …`. The HA add-on can't load it.
