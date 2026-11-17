# AJ Zigbee Survey (Android, `com.ajnetwork.ajzigbee`)

Site-survey tool for Zigbee installs: plug a **Zigbee coordinator** into the phone (USB-OTG), walk the site,
tap where you stand on the floor plan, and get a **PDF report** with coverage heat map, suggested router spots,
the best channel and the health of the live Home Assistant network(s).

## What it does
| Tab | |
|---|---|
| **Coordinator** | Auto-detects the stick: Silicon Labs **EZSP** (ASH, v4–v13+, 115200/460800/230400) or TI **Z-Stack ZNP** (115200). Shows protocol, firmware/stack version, radio IEEE, maker/board string, USB chip, and whether it suits ZHA / Zigbee2MQTT. *Quick scan*: every network heard on all 16 channels + channel noise (EZSP energy scan). |
| **Survey** | Sites → floors → plan (photo, image or PDF page). Tap = measure here: beacon scan of the target network(s) (3 rounds), noise, phone Wi-Fi scan, and optionally a **test device** read from HA. Points turn green/amber/red, heat map, ★ suggested routers. Long-press = mark coordinator / existing routers. |
| **Network** | **ZHA** through the HA websocket (long-lived token): network settings, devices with LQI/RSSI, neighbour tables, channel use from ZHA diagnostics. **Zigbee2MQTT** through its frontend websocket (`http://host:8080`, token optional, several instances): bridge info, devices, linkquality, *Network map*. Pick a device as test device. |
| **Report** | Summary, coordinator/router advice, channel advice, network health → **PDF** (cover + summary, coordinators, one page per floor with heat map + table, channels/Wi-Fi, device lists) and JSON export. |

## Safety: read-only on the stick
EZSP: only `version`, `getEui64`, `getMfgToken`, `getValue(VERSION_INFO)`, `startScan` (energy/active).
ZNP: only `SYS_PING`, `SYS_VERSION`, `UTIL_GET_DEVICE_INFO`, `ZDO_NWK_DISCOVERY_REQ`.
Never `networkInit/formNetwork/leave`, never NV/token writes → Home Assistant's network on the stick is untouched.
While the stick is in the phone, that HA network is offline – plug it back afterwards.
**With two coordinators:** survey network A with network B's stick in the phone (A's coordinator stays in HA, so it is on air and HA can also read the test device), then swap.

## Grades
RSSI ≥ −70 dBm good · −70…−85 fair · < −85 poor. LQI (TI sticks report LQI only) ≥ 150 good · 80…149 fair · < 80 poor.
A spot that hears only one node near the limit is downgraded one step (no redundancy).

## Build / test
- `bash tools/build_java_apk.sh apps/zigbee-survey` (CI does this; min SDK 24, target 34).
- Protocol tests on a plain JVM against simulated sticks (EZSP v7 legacy frames, EZSP v13 extended frames, ZNP):
  `javac -d /tmp/t apps/zigbee-survey/src/com/ajnetwork/ajzigbee/radio/*.java apps/zigbee-survey/test/RadioTest.java && java -cp /tmp/t RadioTest`

## Limits
- TI sticks: no channel-noise scan without starting a network (not done – read-only), beacons give LQI only (but also the sender's address).
- Phones throttle Wi-Fi scans (4 per 2 min) – then the last results are reused.
- Z2M as HA add-on: map port 8080 in the add-on's network settings so the phone can reach the frontend.
