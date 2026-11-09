# AJ Panel — the primary app on every panel (D-006)

One APK for all panels (Judian P8/M20 32-bit Android 10, DSPPA DM938T 64-bit Android 8.1, …), no root.

## v0.1
- **Built-in sync player** – bundled snapclient 0.29 (`lib/`), started and supervised by `PanelService`:
  starts at boot, restarts with back-off, kills leftover players from an earlier run (BUG-015). Holds Wi-Fi/CPU locks.
  hostID `ajpanel_<mac>` → Music Assistant player id **`ma_ajpanel_<mac>`** (shown on the status screen).
- **Now playing on the panel** (R4), polled from Music Assistant every 1 s:
  - floating card over any app (vendor home screen too) → collapses to a chip after 10 s; tap = expand, long-press = open app
  - full-screen view in the app (art, title, artist, group + size, time, volume)
  - **MediaSession + media notification** → the panel's lock screen / launcher media widget / media keys see the song
  - **"played by <user>"** (R5) when the server runs our `ajsnap` provider; otherwise "via AirPlay" etc.
- **Pause here** (mute only this panel, group keeps playing) · **Leave group** · ⏮ ⏯ ⏭ for the group · volume.
  Leaving a *saved* group (static member) only mutes the panel – MA would otherwise stop the whole saved group.
- Settings on the panel (server IP, name, MA login → long-lived token, OpenSL/Oboe, latency trim, overlay/player on/off) or over adb.

## Build / install
`bash tools/build_java_apk.sh apps/panel` → `apps/panel/build/AJ_Panel_v0.1.apk` (prebuilt copy in `releases/`).
Install + configure all panels: `tools\deploy_ajpanel.bat` (see header of the .ps1 for options).

## adb control
```
am broadcast -n com.ajnetwork.ajpanel/.BootReceiver -a com.ajnetwork.ajpanel.CONFIG --es host 192.168.8.5 --es token <MA token> --es name P8_EN
am broadcast -n com.ajnetwork.ajpanel/.BootReceiver -a com.ajnetwork.ajpanel.STOP
logcat -s AJPanel
```
## Next
Merge ControlX Bridge (YJY zones) · push updates from server instead of polling · per-user permissions (R6) · Arabic UI.
