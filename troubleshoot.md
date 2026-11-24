# troubleshoot.md — How to find out what's wrong

Start at §1 (symptom → first check). Collect logs with §2. Read them with §3–§7.

## 1. Symptom → first check

| Symptom | First check | Likely bug |
|---|---|---|
| Panel not in Music Assistant player list | Is the panel's player connected? `netstat -tn \| grep 1704` on the panel → `ESTABLISHED` to **192.168.8.5** | BUG-010 |
| MA says playing, no sound | `23_why_no_sound.bat` → is there an **active** audio track for the player? Volume? | BUG-010, BUG-015 |
| Stutter / dropouts | `22_network_isolate.bat` → panel→server ping | BUG-009, BUG-016 |
| Nothing works, MA page down | HA → Add-ons → Music Assistant → Log → "address already in use"? | BUG-008 |
| App won't install on DSPPA | Look at the panel screen for Play Protect | BUG-003 |
| Panel shows nothing while music plays | AJ Panel status screen (open app) → "Login needed" / "Waiting for MA…"; `logcat -s AJPanel` | BUG-019 |
| Panel missing from old MA group after AJ Panel install | expected: new player id `ma_ajpanel_<mac>` → re-create group, disable old Snapcast-app player | — |
| App opens and closes immediately | `adb logcat -d \| findstr "FATAL UnsatisfiedLink"` | BUG-001, BUG-004 |
| AirPlay connects but group silent | Duplicate protocol players in MA | BUG-011 |

## 2. Collecting logs (old numbered scripts: `D:\Devlopment\AJ\vendor\panel-system-apks\`; current: `tools\collect_logs.bat`)

| Script | What it collects | Output |
|---|---|---|
| `1_check_panel.bat` | full panel inventory (props, packages, audio, ports, processes) | `logs\` |
| `2_deep_check.bat` | pulls system APKs, init scripts, sshd | `logs2\`, `apks\` |
| `16_backup_and_check_yjy.bat` | backup YJY apps + YJY player status + 90 s Control X capture | `backup_yjy_dm938t\`, `logs16\` |
| `20_join_music_assistant.bat [-ma ip] [-panels ip=zones,…]` | (changes) points panels at MA; verifies connection | `logs20\` |
| `21_snapcast_logs.bat` | 60 s Wi-Fi, ping-to-MA, Snapcast/bridge log from every panel | `logs21\<ip>\` |
| `22_network_isolate.bat` | simultaneous pings PC/panels → Pi/router | `logs22\summary.txt` |
| `23_why_no_sound.bat` | volumes, active audio tracks, output device, Snapcast screen | `logs23\<ip>\` |
| `24_sound_test.bat` | (changes) restarts players, interactive "do you hear it?" test | `logs24\` |
| `25_ma_disable_dupes.bat` | (changes) MA API: list players, disable duplicates, dump configs | `logs25\` (incl. `commands.json`) |
| `AJ-Audio-System\tools\why_no_play.bat` | read-only: MA players/groups/queues via API (asks MA password) + AJ Panel log, connections, audio output per panel | `logs28\` |
| `AJ-Audio-System\tools\deploy_ajpanel.bat` | (changes) installs/configures AJ Panel, stops Snapcast app; collects AJPanel log + 1704 connections | `logs26\<ip>.txt` |

Manual essentials:
```bat
set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
%ADB% connect 192.168.8.210:5555
%ADB% -s 192.168.8.210:5555 logcat -d -v time > panel.txt
%ADB% -s 192.168.8.210:5555 shell dumpsys media.audio_flinger > af.txt
%ADB% -s 192.168.8.210:5555 shell dumpsys audio > audio.txt
%ADB% -s 192.168.8.210:5555 shell "netstat -tun"
```
Music Assistant log: Home Assistant → Add-ons → Music Assistant → **Log** (set provider log level to *Debug* in MA → Settings → Providers → <provider>).

**Log files are UTF-16** when written by PowerShell `Out-File` — open in Notepad, or strip NULs before grepping.

## 3. Reading logcat — our tags

| Tag | App | Meaning of common lines |
|---|---|---|
| `AJPanel` | AJ Panel | `start snapclient [...]` = player launched · `snap: …` = snapclient output (`Connected to` good, `Failed to connect` = server/port) · `snapclient exited N` then restart (look at the line before) · `killed leftover snapclient` = orphan from earlier run cleaned · `overlay add (overlay permission?)` = run deploy script / allow "display over other apps" · `login` errors = wrong MA user/password · status screen "Waiting for Music Assistant to see this panel" = player not registered in MA yet (snapcast/ajsnap provider disabled?) |
| `CXBridge` | ControlX Bridge | `search from <ip>` = Control X looking for devices · `cmd N zoneZ query/set V` = Control X command (N see Control X table) · `bind YJY player: false` = YJY player service not found (normal on Judian, BUG-013) · `joined group server <ip>` = told Snapcast player to connect |
| `AJAudio` | AJ Audio phone app | `streaming http://…/live.aac` = capture running · `listener connected: /192.168.8.5` = MA is pulling the phone stream · `Music Assistant refused: …` = play_media failed |
| `AndroidRuntime` | any | `FATAL EXCEPTION` = crash; next lines show why |
| `ActivityManager` | system | `Start proc` / `Killing … crash` / `has died` |
| `PackageManager` | system | install / permission issues |

## 4. Reading `dumpsys media.audio_flinger`

```
Output thread … name AudioOut_D …
  Standby: no                      ← output active (yes = idle, nothing playing)
  Output device: 0x2 (AUDIO_DEVICE_OUT_SPEAKER)
  ... Tracks of which 1 are active
  Id Active Client Session … SRate … G db … Server FrmCnt FrmRdy F Underruns
  197  yes  16398 …           48000   -12   023BF800 2052   1028  A    0
```
- **Client** = process id (`pidof libsnapclient.so` to match).
- **Active yes** = audio is being played right now.
- **G db** = stream gain (volume). `-inf` = muted/zero.
- **Server** increasing between two dumps = data is flowing.
- **Underruns** > 0 and growing = player starving (network or CPU). A few on start are normal.
- `removeTrack_l … Underruns 1024` lines repeating = player keeps restarting (BUG-016).
- Judian has a second output `AUDIO_DEVICE_OUT_JD_SECOND_CODEC` (zone 2); `Standby: yes` there is normal.

## 5. Reading `dumpsys audio`

- `- STREAM_MUSIC: Muted: false … Current: 2 (speaker): 37` → media volume 37 (range shown as Max).
- DM938T range 0–15, Judian 0–100.
- "Audio playback (lastly played comes first)" → which apps played recently.

## 6. Network checks

- Ping meaning: < 10 ms good · 10–50 ok · spikes > 200 ms cause audio gaps · > 1000 ms = stall (BUG-009).
- Same spikes on **all** panels at the same moment → shared cause (AP or server link), not the panel.
- Ports: AJ Panel setup API **8766** (`/api/info` open, rest needs MA admin token) · MA web **8095**, MA stream **8097**, snapserver **1704** (audio) / **1705** (control) / 1780 (http), Control X **40188/40189 UDP**, our DLNA **49494**, AJ Audio phone stream **8765**, AirPlay **7000**, adb **5555**.
- Who uses a port on the Pi (from the SSH add-on):
  ```bash
  curl -si --max-time 3 http://127.0.0.1:8097/ | head -8
  for s in $(ha apps --raw-json | jq -r '.data.addons[]|select(.state=="started")|.slug'); do
    ha apps info "$s" --raw-json | jq -r --arg s "$s" '.data as $d|"\($s) host_network=\($d.host_network) ingress=\($d.ingress_port) ports=\($d.network//{}|tostring)"'
  done | grep -E '8097|host_network=true'
  ```

## 7. Music Assistant log lines

| Line | Meaning |
|---|---|
| `Player (type protocol) registered: ap…/EShare-8965` | MA found an AirPlay device and linked it to a panel (can hijack output → BUG-011) |
| `Setting active output protocol on X to Native` | MA will use Snapcast (good) for panel X |
| `AirPlay Receiver … Playback started (via sessioncontrol hook)` | a phone started AirPlay into MA |
| `Starting AirPlay playback on player syncgroup_…` | AirPlay audio sent to a saved group |
| `Calling set_members on native player …` | MA is (re)building the group |
| `OSError … 8097 … address already in use` | BUG-008 |
| `Player unavailable: …` | device dropped off the network |
