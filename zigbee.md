# zigbee.md — using the panels' built-in Zigbee radio with Home Assistant (instead of Smart Life)

Status: research + read-only probe (2026-11-16). Nothing changed on the panels yet.

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

## Decision after the probe
- Port 6668 open → try **B** first (keeps Smart Life, local).
- ttyS3 world-accessible or group our app can use + EFR32 found → **C** possible without root (one panel first, after a backup).
- Otherwise **A** works today; C would need root or opening the panel – owner's decision.
