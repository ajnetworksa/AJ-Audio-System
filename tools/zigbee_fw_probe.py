"""READ ONLY: identify a Judian panel's Zigbee radio before a firmware change (see zigbee.md "Radio firmware").

    python zigbee_fw_probe.py socket://192.168.8.165:6638 [baud]

Asks the running EmberZNet NCP for: EZSP protocol + stack version, board/manufacturer strings, the radio's own
IEEE address, and - most important - whether a STANDALONE BOOTLOADER is present (0xFFFF = none -> the module can
NOT be reflashed over the serial line, only with an SWD debugger). Writes nothing to the radio; like zigpy "info"
it resets the radio once (normal start-up) when it connects. Never prints the network key.
"""
import asyncio
import sys

import bellows.ezsp
import bellows.types as t
import zigpy.config
from bellows.zigbee.application import ControllerApplication


def clean(b: bytes) -> str:
    b = bytes(b)
    while b.endswith((b"\xff", b"\x00")):
        b = b.rstrip(b"\xff").rstrip(b"\x00")
    try:
        s = b.decode("ascii")
        if s.isprintable():
            return s or "(empty)"
    except UnicodeDecodeError:
        pass
    return "0x" + b.hex().upper() if b else "(empty)"


async def main(port: str, baud: int) -> int:
    conf = ControllerApplication.SCHEMA({zigpy.config.CONF_DEVICE: {
        zigpy.config.CONF_DEVICE_PATH: port, zigpy.config.CONF_DEVICE_BAUDRATE: baud}})[zigpy.config.CONF_DEVICE]
    ezsp = bellows.ezsp.EZSP(conf)
    await ezsp.connect(use_thread=False)
    ok = True
    try:
        print(f"port                 {port} @ {baud}")
        print(f"EZSP protocol        v{ezsp.ezsp_version}")
        try:
            mfg, board, ver = await ezsp.get_board_info()
            print(f"stack version        {ver}")
            print(f"manufacturer string  {mfg}")
            print(f"board name           {board}")
        except Exception as e:  # noqa: BLE001
            print(f"board info           failed: {e!r}")
        try:
            (eui,) = await ezsp.getEui64()
            print(f"radio IEEE (EUI64)   {eui}")
        except Exception as e:  # noqa: BLE001
            print(f"radio IEEE           failed: {e!r}")
        for tok in ("MFG_CUSTOM_VERSION", "MFG_MANUF_ID", "MFG_PHY_CONFIG", "MFG_STORAGE"):
            try:
                v = await ezsp.get_mfg_token(getattr(t.EzspMfgTokenId, tok))
                print(f"{tok:<20} {clean(v)}")
            except Exception as e:  # noqa: BLE001
                print(f"{tok:<20} failed: {e!r}")
        try:
            r = await ezsp.getStandaloneBootloaderVersionPlatMicroPhy()
            blv, plat, micro, phy = (int(x) for x in r[:4])
            print(f"bootloader version   0x{blv:04X}" + ("   <-- NO standalone bootloader: serial reflash NOT possible"
                                                       if blv == 0xFFFF else "   (bootloader present)"))
            print(f"platform/micro/phy   {plat} / {micro} / {phy}   (raw EmberZNet ids, for the chip family)")
            print(f"RESULT bootloader={'none' if blv == 0xFFFF else 'yes'}")
            ok = blv != 0xFFFF
        except Exception as e:  # noqa: BLE001
            print(f"bootloader query     failed: {e!r}")
            print("RESULT bootloader=unknown")
            ok = False
    finally:
        await ezsp.disconnect()
    return 0 if ok else 2


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    sys.exit(asyncio.run(main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 115200)))
