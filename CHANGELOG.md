# Changelog

Every push to `main` that changes an app makes a GitHub **Release** automatically (see `.github/workflows/release.yml`).
Its notes are generated from the commit messages since the previous release, grouped by area –
so the commit messages *are* the changelog (rule 9 in `agent.md`).

**Version numbers (tags):**

| Commit message contains | New tag | Use for |
|---|---|---|
| nothing special | `v0.3.0 → v0.3.1` | fixes, small changes |
| `[major]` | `v0.3.1 → v0.4.0` | a big new feature / visible change (e.g. new screen, new server provider) |
| `[breaking]` | `v0.4.0 → v1.0.0` | panels/phones must be reinstalled or reconfigured, old version stops working |

A tag pushed by hand (`git tag v0.5.0 && git push origin v0.5.0`) always wins.

## History before automatic releases

### 2026-11-10
- Phone app v0.3: saved named groups + temporary groups, AirPlay receiver per group, clear message instead of "500" on an empty group.
- Panel app v0.2.1: song shown in the Judian music player also on M20; quieter log.
- Panels log in with their own Music Assistant user (`panels`).
- Tools: remove old Snapcast app, `why_no_play` diagnostics, release signing key.
- CI: fixed Android build, release-key signing, automatic GitHub Releases with changelog.

### 2026-11-09
- Panel app v0.1/v0.2: built-in sync player, now-playing card + MediaSession, Pause here / Leave group, Judian DLNA metadata bridge.
- `ajsnap` server provider fork (lower latency, "who is playing" API), Docker + local Home Assistant add-on.
- Flutter phone app skeleton + CI, documentation set (goal, agent, process, progress, bug, bug_fix, troubleshoot, ui).

### 2026-11-01 → 11-08
- AirPlay receiver fix on Judian, DM858 app port, ControlX Bridge v1.0–v2.0, Music Assistant setup (3 panels in sync, iPhone AirPlay to groups).
