# AJ Audio System

Multi-room audio for Tuya/Zigbee wall panels (Judian, DSPPA/YJY, Auxdio…): every panel is a speaker,
panels group and play in sync, phones play from any app, panels show what's playing and who.

| Start here | |
|---|---|
| [goal.md](goal.md) | what we build, requirements R1–R10, non-goals |
| [agent.md](agent.md) | rules, devices, key technical facts — read before working |
| [process.md](process.md) | build / test / release / documentation workflow |
| [progress.md](progress.md) | status, next steps, decisions, dated log |
| [bug.md](bug.md) → [bug_fix.md](bug_fix.md) | bugs (BUG-###) and their fixes (FIX-###, same number) |
| [troubleshoot.md](troubleshoot.md) | how to collect and read logs |
| [ui.md](ui.md) | phone app + panel app screens |

## Repository layout

| Folder | What |
|---|---|
| `apps/panel/` | **AJ Panel** – primary app on every panel: sync player + now playing + pause here / leave group |
| `apps/phone-flutter/` | **AJ Audio** phone app, Flutter (Android + iOS), built by GitHub Actions |
| `apps/phone-android/` | AJ Audio v0.1 (Java) – reference client, has "play my phone" capture |
| `server/` | AJ Music Assistant: `ajsnap` provider fork + patches → Docker **and** local HA add-on |
| `legacy/controlx-bridge/` | ControlX Bridge v2.0 (Control X ↔ YJY player), to be merged into AJ Panel |
| `tools/` | `build_java_apk.sh`, `deploy_ajpanel.bat/.ps1` |
| `.github/workflows/` | CI: Flutter APK + unsigned IPA, Java APKs |

## Git
Local repo, pushed manually: `git remote add origin https://github.com/<you>/AJ-Audio-System.git` then `git push -u origin main`.
Releases (`*/releases/*.apk`) are committed; other build output is ignored.
