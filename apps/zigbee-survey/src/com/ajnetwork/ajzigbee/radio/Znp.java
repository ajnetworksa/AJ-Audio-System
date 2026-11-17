package com.ajnetwork.ajzigbee.radio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Texas Instruments Z-Stack ZNP over UART (MT protocol: FE len cmd0 cmd1 data fcs).
 * READ-ONLY: SYS_PING, SYS_VERSION, UTIL_GET_DEVICE_INFO and ZDO_NWK_DISCOVERY_REQ (beacon scan).
 * We never send ZDO_STARTUP_FROM_APP or write NV items, so Home Assistant's network on the stick stays as it is.
 */
public final class Znp {
    private final Transport t;
    private final Transport.Log log;
    private final byte[] rbuf = new byte[512];
    private final ByteArrayOutputStream acc = new ByteArrayOutputStream();
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();   // [cmd0, cmd1, data...]

    public Znp(Transport t, Transport.Log log) { this.t = t; this.log = log; }

    public static byte[] encode(int cmd0, int cmd1, byte[] data) {
        byte[] f = new byte[data.length + 5];
        f[0] = (byte) 0xFE; f[1] = (byte) data.length; f[2] = (byte) cmd0; f[3] = (byte) cmd1;
        System.arraycopy(data, 0, f, 4, data.length);
        int x = 0; for (int i = 1; i < f.length - 1; i++) x ^= f[i];
        f[f.length - 1] = (byte) x;
        return f;
    }

    private void feed(byte[] b, int n) {
        acc.write(b, 0, n);
        byte[] a = acc.toByteArray();
        int i = 0;
        while (true) {
            while (i < a.length && (a[i] & 0xFF) != 0xFE) i++;
            if (a.length - i < 5) break;
            int len = a[i + 1] & 0xFF;
            if (a.length - i < len + 5) break;
            int x = 0; for (int k = i + 1; k < i + 4 + len; k++) x ^= a[k];
            if ((byte) x == a[i + 4 + len]) {
                byte[] f = new byte[len + 2]; System.arraycopy(a, i + 2, f, 0, len + 2);
                frames.add(f);
                i += len + 5;
            } else i++;                              // bad FCS -> resync on next 0xFE
        }
        acc.reset();
        if (i < a.length) acc.write(a, i, a.length - i);
    }

    private byte[] next(long deadline) throws IOException {
        while (frames.isEmpty()) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return null;
            int n = t.read(rbuf, (int) Math.min(left, 200));
            if (n > 0) feed(rbuf, n);
        }
        return frames.poll();
    }

    public interface Areq { void frame(int cmd0, int cmd1, byte[] data); }

    /** SREQ -> SRSP; AREQs that arrive meanwhile go to areq (may be null) */
    public byte[] request(int cmd0, int cmd1, byte[] data, int timeoutMs, Areq areq) throws IOException {
        t.write(encode(cmd0, cmd1, data));
        long dl = System.currentTimeMillis() + timeoutMs;
        int want0 = (cmd0 & 0x1F) | 0x60;
        while (true) {
            byte[] f = next(dl);
            if (f == null) throw new IOException(String.format(Locale.US, "no answer to ZNP %02X %02X", cmd0, cmd1));
            int c0 = f[0] & 0xFF, c1 = f[1] & 0xFF;
            byte[] d = new byte[f.length - 2]; System.arraycopy(f, 2, d, 0, d.length);
            if (c0 == want0 && c1 == cmd1) return d;
            if (c0 == 0x60 && c1 == 0x00) throw new IOException("ZNP: command not known (RPC error)");
            if ((c0 & 0xE0) == 0x40 && areq != null) areq.frame(c0, c1, d);
        }
    }

    public void pump(int ms, Areq areq, java.util.function.BooleanSupplier done) throws IOException {
        long dl = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < dl && !done.getAsBoolean()) {
            byte[] f = next(Math.min(dl, System.currentTimeMillis() + 300));
            if (f == null) continue;
            byte[] d = new byte[f.length - 2]; System.arraycopy(f, 2, d, 0, d.length);
            areq.frame(f[0] & 0xFF, f[1] & 0xFF, d);
        }
    }

    /**
     * Wakes the stick like Home Assistant (zigpy-znp) does: DTR/RTS low, RTS pulse (reset into the app, not the
     * bootloader), bootloader-skip byte 0xEF, then SYS_PING.
     */
    public boolean start() throws IOException {
        t.drain(); frames.clear(); acc.reset();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt == 1) {
                t.setLines(false, false); sleep(150);
                t.setLines(false, true); sleep(150);
                t.setLines(false, false); sleep(1200);
            }
            t.write(new byte[]{(byte) 0xEF});
            sleep(attempt == 0 ? 100 : 500);
            t.drain(); frames.clear(); acc.reset();
            for (int i = 0; i < 2; i++) {
                try { request(0x21, 0x01, new byte[0], 1000, null); return true; } catch (IOException ignored) { }
            }
        }
        return false;
    }

    private static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    public void fillInfo(Model.CoordInfo ci) {
        ci.protocol = "Z-Stack ZNP (Texas Instruments)";
        ci.canEnergyScan = false;
        ci.beaconHasRssi = false;
        ci.beaconHasSource = true;
        try {
            byte[] v = request(0x21, 0x02, new byte[0], 2000, null);
            if (v.length >= 5) {
                int product = v[1] & 0xFF;
                long rev = v.length >= 9 ? ((v[5] & 0xFFL) | (v[6] & 0xFFL) << 8 | (v[7] & 0xFFL) << 16 | (v[8] & 0xFFL) << 24) : 0;
                // product ids as zigpy-znp names them: 0 = Z-Stack 1.2, 1 = Z-Stack 3.x.0 (SimpleLink CC26x2/CC13x2), 2 = Z-Stack 3.0.x
                boolean modern = product == 1;
                String z = product == 0 ? "Z-Stack 1.2" : product == 1 ? "Z-Stack 3.x.0" : product == 2 ? "Z-Stack 3.0.x" : "Z-Stack (product " + product + ")";
                ci.family = modern ? "TI CC26x2 / CC13x2 (e.g. CC2652P/R/RB, CC1352P)" : "TI CC2530 / CC2531 / CC2538 class";
                ci.firmware = z + String.format(Locale.US, " %d.%d.%d", v[2] & 0xFF, v[3] & 0xFF, v[4] & 0xFF) + (rev > 0 ? " · build " + rev : "");
                ci.extra.put("Transport revision", String.valueOf(v[0] & 0xFF));
                if (modern && rev > 0) ci.extra.put("Advice", rev >= 20230507 ? "firmware is current enough for ZHA and Zigbee2MQTT"
                        : "old firmware – Zigbee2MQTT recommends 20230507 or newer");
                else if (!modern) ci.extra.put("Advice", "old CC253x-class stick: small device limit (~20–40 direct children); fine for tests, weak as main coordinator");
            }
        } catch (IOException e) { log.i("znp: version " + e.getMessage()); }
        try {
            byte[] d = request(0x27, 0x00, new byte[0], 2000, null);
            if (d.length >= 14 && d[0] == 0) {
                ci.ieee = Model.hex(d, 1, 8, true);
                int state = d[12] & 0xFF;
                String[] st = {"not started (hold)", "init", "searching", "joining", "rejoining", "end device unauthenticated", "end device", "router", "starting coordinator", "coordinator started", "orphan"};
                ci.extra.put("Radio state", state < st.length ? st[state] : String.valueOf(state));
            }
        } catch (IOException e) { log.i("znp: device info " + e.getMessage()); }
    }

    /** beacon scan via ZDO_NWK_DISCOVERY_REQ (works on a stick that is not running a network) */
    public List<Model.Beacon> activeScan(int channelMask, int duration) throws IOException {
        final List<Model.Beacon> res = new ArrayList<>();
        final boolean[] done = {false};
        Areq a = (c0, c1, d) -> {
            if (c0 == 0x45 && c1 == 0xC5 && d.length >= 1) {          // ZDO_BEACON_NOTIFY_IND
                int n = d[0] & 0xFF;
                for (int i = 0; i < n && 1 + (i + 1) * 21 <= d.length; i++) {
                    int o = 1 + i * 21;
                    Model.Beacon b = new Model.Beacon();
                    b.src = (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8;
                    b.pan = (d[o + 2] & 0xFF) | (d[o + 3] & 0xFF) << 8;
                    b.channel = d[o + 4] & 0xFF;
                    b.permitJoin = d[o + 5] != 0;
                    b.routerCapacity = d[o + 6] != 0;
                    b.lqi = d[o + 10] & 0xFF;
                    b.depth = d[o + 11] & 0xFF;
                    b.extPan = Model.hex(d, o + 13, 8, true);
                    res.add(b);
                }
            } else if (c0 == 0x45 && c1 == 0xC7) done[0] = true;          // ZDO_NWK_DISCOVERY_CNF
        };
        byte[] r = request(0x25, 0x26, new byte[]{(byte) channelMask, (byte) (channelMask >> 8), (byte) (channelMask >> 16), (byte) (channelMask >> 24), (byte) duration}, 3000, a);
        if (r.length > 0 && r[0] != 0) throw new IOException("beacon scan refused by the stick, status 0x" + Integer.toHexString(r[0] & 0xFF)
                + " (it may be busy running a network)");
        pump(20000, a, () -> done[0]);
        return res;
    }
}
