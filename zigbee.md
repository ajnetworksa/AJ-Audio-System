# zigbee.md — using the panels' built-in Zigbee radio with Home Assistant (instead of Smart Life)

Status: probe 1 done (logs30, 2026-11-16). Nothing changed on the panels yet.

## What we know about our panels
- **Judian P8** (and likely X8/M20): Tuya Zigbee gateway inside, module on **/dev/ttyS3** (`tuya-kdql3oqd1xupvuxq`), Android 10, no root via adb, SELinux permissive, a root `sshd` on :22 (key-only, key unknown).
- **DSPPA DM938T**: Tuya Smart app `com.yjy.tuyaiot` (v17); whether it has its own Zigbee radio is unknown → probe.
- Unknown: which chip is in the module (Silicon Labs EFR32 = can become a standard EZSP coordinator; Telink/other = no), who owns /dev/ttyS3, its rights, and whether the Tuya gateway app offers local LAN control.

## The three ways (from easiest to most "pure")

| | How | Smart Life keeps working | Needs | Local (no internet) |
|---|---|---|---|---|
| **A. Cloud** | HA's official **Tuya** integration (log in by scanning a QR code with the Smart Life app). All Zigbee devices paired to the panels appear in HA. | yes | nothing on the panels | no – via Tuya cloud, ~0.5–2 s |
| **B. Local, keep Tuya** | **LocalTuya / Tuya Local** (HACS) talk to the panel's gateway on the LAN with each device's *local key* + sub-device id (cid). | yes | the gateway app must answer on the LAN (port 6668); local keys from a free Tuya IoT developer account | yes |
| **C. Replace Tuya** | Use the radio as a normal **Zigbee coordinator** for **ZHA / Zigbee2MQTT**: (1) flash standard EZSP firmware on the module, (2) a small AJ app passes /dev/ttyS3 to HA over TCP (`socket://panel-ip:6638`), (3) Tuya gateway app disabled with `pm disable-user` (no root). | **no** – Smart Life loses that gateway | EFR32 chip; /dev/ttyS3 readable/writable by an app; firmware flash (on the Moes T6E this needed opening the panel and soldering 4 wires) | yes, fastest |

Precedent for C: the Tuya **Moes T6E** Android panel (Rockchip PX30, Tuya TYZS13 = Silicon Labs EFR32MG13P) runs Zigbee2MQTT after reflashing the module to EZSP 6.9.1 over its serial pads (Elelabs flasher, CH340 adapter) — but that guide used `su` (root) to open the serial port and hide the Tuya launcher. Our rule is **no root**, so C only works if /dev/ttyS3 is accessible to apps (probe shows it) or the owner decides otherwise.

## Probe (read-only)
`tools\zigbee_probe.bat` (optional `-panels "ip1,ip2"`) → `tools\logs30\<ip>.txt`: serial ports with owner/rights/SELinux label, which process holds them (and its user id), the sysfs/USB path of the radio, Tuya/gateway apps and processes, listening ports (6668 = Tuya LAN), Zigbee lines in the Android log.

## Probe 1 results (logs30)
| | Judian X8 (.165) | Judian P8 (.194) | DSPPA DM938T (.46) |
|---|---|---|---|
| Zigbee radio | yes, `/dev/ttyS3` (`persist.sys.jd.zbg.tty`), module `tuya-kdql3oqd1xupvuxq` | same | **none found** – Tuya app `com.yjy.tuyaiot` is only the panel itself as a Wi-Fi Tuya device |
| Port rights | `crw-rw-rw-` **world read/write** → any app can open it, **no root needed** | same | – |
| Gateway app | `com.judian.gateway.c6` (JdZigbee, /vendor/app) with Tuya's **`tuya_z3.c`** host stack = Zigbee 3.0 stack runs on Android, module is a network co-processor (Silicon Labs EZSP style) | same, but `GatewayActive=false` (not added to Smart Life) | – |
| Tuya LAN port **6668** | **open** (LAN protocol 3.4) – has Zigbee sub-devices (e.g. cid `60b763fffe140c8f`) | closed (gateway not activated) | closed |
| Note | | the Judian app also reports our song title to Tuya as a data point | |

**Meaning:** for the Judian panels both B (LocalTuya through port 6668, keep Smart Life) and C (own coordinator, no root because ttyS3 is world-writable) look possible. DSPPA has no Zigbee radio. Next: `tools\zigbee_probe2.bat` reads the serial settings and copies the JdZigbee app (to identify radio firmware / protocol / speed); `-restart` reboots the panel once to capture the gateway's start-up log.

## Probe 2 results (logs31, X8 .165)
- **Serial line:** `/dev/ttyS3` 115200 baud, 8N1, **RTS/CTS hardware flow control** (`crtscts`), raw.
- **Protocol (from `libtuya_android.so` strings):** Silicon Labs **EmberZNet `Z3GatewayHost`** built into Tuya's SDK (`zigbee_host/slabs/v2.2`) → talks **EZSP over ASH** to the module ("ASH connected", "ERROR: NCP EZSP protocol version of 0x%x does not match Host version", options `-f r` = RTS/CTS, `-r` NCP reset by RST frame). So the module is a standard **Silicon Labs EZSP NCP** (EFR32 family) – the same kind of radio ZHA (`bellows`, radio type EZSP) and Zigbee2MQTT (`adapter: ember`) use.
- **Module firmware:** Tuya updates it over the cloud (`/tmp/ota-files/NcpUpgrade.ota`, `/tuya/NcpUpgrade_backup.ota`); the APK carries no firmware file. The **EZSP version is still unknown** – it decides ZHA (works with old EZSP) vs Zigbee2MQTT `ember` (needs EZSP v13+ / EmberZNet 7.4+).
- **Start-up log (`-restart`, logs31\192.168.8.165\startup_logcat.txt):** `ezsp ver: 0x7  stack ver 6.5.0` (EmberZNet 6.5.0, EZSP protocol **v7**), NCP manufacturer 0x0002, Tuya NCP version 0x1005 (`ncp_ver65_1_0_5`), "ncp start success". SELinux on the panel is **permissive** (denials only logged). Two sub-devices are paired (`60b763fffe140c8f`, `a4c1382484fa31c1`); the gateway is live on Tuya EU cloud.
- **What EZSP v7 means:** ZHA (`bellows`) supports EZSP v4 and newer → **ZHA can use this radio as it is**. Zigbee2MQTT's `ember` driver needs EZSP v13+ and its old `ezsp` driver needs v8+ → **Zigbee2MQTT would need new radio firmware** (reflash, risky on a soldered module – not planned).

**Meaning:** option **C** is technically realistic without root and without reflashing: an AJ "Zigbee bridge" in the panel app opens ttyS3 (115200, RTS/CTS) and forwards the bytes to TCP (e.g. port 6638); HA ZHA connects to `socket://192.168.8.165:6638`, radio type EZSP. Costs: the Judian/Tuya gateway app must be switched off (`pm disable-user com.judian.gateway.c6`, undo with `pm enable`), and **every Zigbee device must be re-paired** to the new HA network (they leave Smart Life). Open checks: whether bellows accepts Tuya's NCP build (first test on the X8 with the gateway app disabled), and whether the TCP bridge keeps ASH timing reliable.

## Decision after the probe
- Port 6668 open → try **B** first (keeps Smart Life, local).
- ttyS3 world-accessible or group our app can use + EFR32 found → **C** possible without root (one panel first, after a backup).
- Otherwise **A** works today; C would need root or opening the panel – owner's decision.

## Test build (AJ Panel 0.7.1, X8 only)
AJ Panel has a **Zigbee bridge** (off by default): `/dev/ttyS3` (115200 8N1, RTS/CTS) ↔ TCP port **6638**, one client, only from the music server's address (= HA) unless set to `any`. State in `/api/info` (`zigbee_bridge`, `zigbee_rx`, `zigbee_tx`).

`tools\zigbee_test.bat` (panel `-panel 192.168.8.165` by default):
1. **No switch = backup only, changes nothing.** → `tools\zigbee_backup\<ip>_<date>\`: `.jd-tuya` folder (Tuya Zigbee host files: device list, flags, radio-firmware backups), gateway package info, disabled-app list, getprop, settings, ttyS3 settings, and (if the panel allows it – tap "Back up my data" on the panel) the gateway app's data.
2. **`-on`**: backup again → force-stop + `pm disable-user` the Judian gateway → bridge on → **radio network backup** with zigpy-cli (`zigbee_network.json`: PAN, channel, network key, frame counter – lets us put the Tuya network back) → bridge locked to HA again. Needs Python 3 on the PC (zigpy-cli is installed once into `tools\.zigpy-venv`). If that backup fails the script stops before the HA steps: install Python 3 (python.org, tick "Add to PATH" – the Windows Store "python" stub doesn't count) and run **`-netbackup`**, which repeats only the radio-network backup.
3. In HA: Add integration → **Zigbee Home Automation** → enter manually → **EZSP** → `socket://192.168.8.165:6638`, 115200.
4. **`-off`** (undo): optionally restores `zigbee_network.json` into the radio (needed if ZHA formed its own network), bridge off, `pm enable` the gateway, reboot. Remove/disable ZHA in HA first.
5. **`-status`**: bridge state and byte counters.

`zigbee_backup\` and `.zigpy-venv\` are git-ignored – `zigbee_network.json` contains the Zigbee network key.
