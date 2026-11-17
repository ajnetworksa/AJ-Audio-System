import com.ajnetwork.ajzigbee.radio.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/** Plain-JVM tests: the radio code against simulated EZSP (v7 legacy + v13 extended) and ZNP sticks. */
public class RadioTest {
    static final Transport.Log LOG = s -> System.out.println("   log: " + s);

    /** base: bytes out of the "stick" queue */
    static abstract class Fake implements Transport {
        final ArrayDeque<Byte> out = new ArrayDeque<>();
        int baud; boolean dtr, rts;
        public void setBaud(int b) { baud = b; }
        public void setLines(boolean d, boolean r) { dtr = d; rts = r; }
        public synchronized int read(byte[] buf, int timeoutMs) {
            long dl = System.currentTimeMillis() + timeoutMs;
            while (out.isEmpty() && System.currentTimeMillis() < dl) { try { wait(5); } catch (InterruptedException e) { } }
            int n = 0; while (!out.isEmpty() && n < buf.length) buf[n++] = out.poll();
            return n;
        }
        public synchronized void drain() { out.clear(); }
        public String describe() { return "fake"; }
        synchronized void emit(byte[] b) { for (byte x : b) out.add(x); notifyAll(); }
    }

    /** simulated Silicon Labs NCP */
    static class FakeEzsp extends Fake {
        final int proto, okBaud; int ncpFrm, expect; final ByteArrayOutputStream acc = new ByteArrayOutputStream(); boolean esc;
        int noiseCh = 15;
        FakeEzsp(int proto, int okBaud) { this.proto = proto; this.okBaud = okBaud; }
        public synchronized void write(byte[] b) {
            if (baud != okBaud) return;                       // wrong speed: garbage, no answer
            for (byte x : b) {
                int v = x & 0xFF;
                if (v == 0x1A) { acc.reset(); continue; }
                if (v == 0x7E) { handle(acc.toByteArray()); acc.reset(); continue; }
                if (v == 0x7D) { esc = true; continue; }
                if (esc) { v ^= 0x20; esc = false; }
                acc.write(v);
            }
        }
        void handle(byte[] f) {
            if (f.length < 3) return;
            int c = Ezsp.crc(f, 0, f.length - 2);
            if ((((f[f.length - 2] & 0xFF) << 8) | (f[f.length - 1] & 0xFF)) != c) throw new AssertionError("host sent bad crc");
            int ctrl = f[0] & 0xFF;
            if (ctrl == 0xC0) { ncpFrm = 0; expect = 0; emit(Ezsp.encode(0xC1, new byte[]{2, 0x0B})); return; }
            if ((ctrl & 0x80) != 0) return;                   // ack from host
            int frm = (ctrl >> 4) & 7;
            if (frm != expect) { emit(Ezsp.encode(0x80 | expect, new byte[0])); return; }
            expect = (expect + 1) & 7;
            emit(Ezsp.encode(0x80 | expect, new byte[0]));
            byte[] d = new byte[f.length - 3]; System.arraycopy(f, 1, d, 0, d.length);
            Ezsp.randomize(d, 0, d.length);
            command(d);
        }
        void send(byte[] ezsp) {
            byte[] d = ezsp.clone(); Ezsp.randomize(d, 0, d.length);
            emit(Ezsp.encode((ncpFrm << 4) | expect, d)); ncpFrm = (ncpFrm + 1) & 7;
        }
        int curProto = 4;
        void command(byte[] d) {
            int seq = d[0] & 0xFF; int id; int off;
            boolean ext = (d[2] & 0xFF) == 0x01 && d.length >= 5;
            if (d.length == 4 && d[2] == 0) { id = 0; off = 3; }
            else if (ext) { id = (d[3] & 0xFF) | (d[4] & 0xFF) << 8; off = 5; }
            else if ((d[2] & 0xFF) == 0xFF) { id = d[4] & 0xFF; off = 5; }
            else { id = d[2] & 0xFF; off = 3; }
            if (id == 0) {
                int req = d[off] & 0xFF;
                byte[] p = {(byte) proto, 2, 0x10, 0x74};      // stack 7.4.1.0
                if (req == proto && proto >= 8 && ext) { curProto = proto; resp(seq, 0, p, true); }
                else resp(seq, 0, p, false);
                if (req == proto) curProto = proto;
                return;
            }
            boolean e = curProto >= 8;
            int sl = curProto >= 13 ? 4 : 1;
            switch (id) {
                case 0x26: resp(seq, id, new byte[]{8, 7, 6, 5, 4, 3, 2, 1}, e); break;
                case 0x0B: { byte[] s = (d[off] == 1 ? "SONOFF" : "ZBDongle-E").getBytes(); byte[] p = new byte[s.length + 1]; p[0] = (byte) s.length; System.arraycopy(s, 0, p, 1, s.length); resp(seq, id, p, e); break; }
                case 0xAA: { byte[] p = new byte[sl + 1 + 7]; p[sl] = 7; p[sl + 1] = 0x10; p[sl + 2] = 0; p[sl + 3] = 7; p[sl + 4] = 4; p[sl + 5] = 1; p[sl + 6] = 0; p[sl + 7] = (byte) 0xAA; resp(seq, id, p, e); break; }
                case 0x1A: {
                    resp(seq, id, new byte[sl], e);
                    int type = d[off];
                    if (type == 0) for (int ch = 11; ch <= 26; ch++) cb(0x48, new byte[]{(byte) ch, (byte) (ch == noiseCh ? -40 : -92)}, e);
                    else {
                        byte[] n = new byte[16];
                        n[0] = 15; n[1] = 0x34; n[2] = 0x12;
                        for (int i = 0; i < 8; i++) n[3 + i] = (byte) (0xD0 + i);
                        n[14] = (byte) 200; n[15] = (byte) -63;
                        cb(0x1B, n, e);
                        n = n.clone(); n[14] = (byte) 120; n[15] = (byte) -81; cb(0x1B, n, e);
                    }
                    byte[] done = new byte[1 + sl]; done[0] = 26; cb(0x1C, done, e);
                    break;
                }
                default: throw new AssertionError("unexpected cmd " + id);
            }
        }
        void resp(int seq, int id, byte[] p, boolean ext) { send(hdr(seq, 0x80, id, p, ext)); }
        void cb(int id, byte[] p, boolean ext) { send(hdr(0, 0x90, id, p, ext)); }   // async callback
        byte[] hdr(int seq, int fc, int id, byte[] p, boolean ext) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(seq); o.write(fc);
            if (ext) { o.write(0x01); o.write(id); o.write(id >> 8); }
            else if (curProto >= 5 && id != 0) { o.write(0xFF); o.write(0); o.write(id); }
            else o.write(id);
            o.write(p, 0, p.length); return o.toByteArray();
        }
    }

    /** simulated TI ZNP */
    static class FakeZnp extends Fake {
        final ByteArrayOutputStream acc = new ByteArrayOutputStream();
        public synchronized void write(byte[] b) {
            if (baud != 115200) return;
            acc.write(b, 0, b.length);
            byte[] a = acc.toByteArray(); int i = 0;
            while (true) {
                while (i < a.length && (a[i] & 0xFF) != 0xFE) i++;
                if (a.length - i < 5) break;
                int len = a[i + 1] & 0xFF; if (a.length - i < len + 5) break;
                byte[] d = new byte[len]; System.arraycopy(a, i + 4, d, 0, len);
                handle(a[i + 2] & 0xFF, a[i + 3] & 0xFF, d); i += len + 5;
            }
            acc.reset(); if (i < a.length) acc.write(a, i, a.length - i);
        }
        void handle(int c0, int c1, byte[] d) {
            if (c0 == 0x21 && c1 == 0x01) emit(Znp.encode(0x61, 0x01, new byte[]{0x79, 0x01}));
            else if (c0 == 0x21 && c1 == 0x02) emit(Znp.encode(0x61, 0x02, new byte[]{2, 1, 2, 7, 1, (byte) 0x1B, (byte) 0x9D, 0x34, 0x01}));   // rev 20225307? -> check below
            else if (c0 == 0x27 && c1 == 0x00) { byte[] r = new byte[15]; for (int i = 0; i < 8; i++) r[1 + i] = (byte) (0x11 * (i + 1)); r[12] = 0; emit(Znp.encode(0x67, 0x00, r)); }
            else if (c0 == 0x25 && c1 == 0x26) {
                emit(Znp.encode(0x65, 0x26, new byte[]{0}));
                byte[] b = new byte[1 + 21 * 2]; b[0] = 2;
                for (int k = 0; k < 2; k++) { int o = 1 + k * 21; b[o] = (byte) (k == 0 ? 0 : 0x31); b[o + 1] = (byte) (k == 0 ? 0 : 0x4A); b[o + 2] = 0x34; b[o + 3] = 0x12; b[o + 4] = 20; b[o + 6] = 1; b[o + 10] = (byte) (k == 0 ? 180 : 90); for (int i = 0; i < 8; i++) b[o + 13 + i] = (byte) (0xD0 + i); }
                emit(Znp.encode(0x45, 0xC5, b));
                emit(Znp.encode(0x45, 0xC7, new byte[]{0}));
            }
        }
    }

    static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) failed++; }
    static int failed;

    public static void main(String[] a) throws Exception {
        // ASH reference vector from the Silicon Labs UG101: RST frame = C0 38 BC 7E
        byte[] rst = Ezsp.encode(0xC0, new byte[0]);
        check(rst.length == 4 && (rst[1] & 0xFF) == 0x38 && (rst[2] & 0xFF) == 0xBC && (rst[3] & 0xFF) == 0x7E, "ASH RST frame = C0 38 BC 7E");
        // UG101 randomization: first bytes of the pseudo-random sequence are 42 21 A8 54 2A
        byte[] z = new byte[5]; Ezsp.randomize(z, 0, 5);
        check((z[0] & 0xFF) == 0x42 && (z[1] & 0xFF) == 0x21 && (z[2] & 0xFF) == 0xA8 && (z[3] & 0xFF) == 0x54 && (z[4] & 0xFF) == 0x2A, "ASH randomization sequence 42 21 A8 54 2A");
        // ZNP SYS_PING = FE 00 21 01 20
        byte[] ping = Znp.encode(0x21, 0x01, new byte[0]);
        check((ping[4] & 0xFF) == 0x20, "ZNP SYS_PING FCS 0x20");

        for (int proto : new int[]{7, 13}) {
            System.out.println("--- fake EFR32, EZSP v" + proto + " at 460800 (detector must step through 115200 + ZNP first)");
            FakeEzsp f = new FakeEzsp(proto, 460800);
            Model.CoordInfo ci = new Model.CoordInfo();
            Radio r = Radio.detect(f, "SONOFF Zigbee 3.0 USB Dongle Plus V2", ci, LOG);
            check(r != null && r.isEzsp(), "detected EZSP v" + proto);
            if (r == null) continue;
            check(ci.baud == 460800, "baud 460800");
            check(ci.ieee.equals("01:02:03:04:05:06:07:08"), "EUI64 " + ci.ieee);
            check(ci.firmware.contains("7.4.1.0"), "firmware " + ci.firmware);
            check(ci.board.contains("SONOFF") && ci.board.contains("ZBDongle-E"), "board " + ci.board);
            Model.ScanResult s = r.measure(Radio.mask(15, 20, 25), 2, true);
            check(s.energy.size() == 16 && s.energy.get(15) == -40, "energy scan 16 ch, ch15 = -40 dBm");
            check(s.beacons.size() == 4, "4 beacons over 2 rounds (" + s.beacons.size() + ")");
            Model.Beacon b = s.beacons.get(0);
            check(b.channel == 15 && b.pan == 0x1234 && b.rssi == -63 && b.lqi == 200, "beacon " + b);
            check(b.extPan.equals("d7d6d5d4d3d2d1d0"), "ext pan MSB first " + b.extPan);
            for (String[] row : ci.rows()) System.out.println("     " + row[0] + ": " + row[1]);
        }

        System.out.println("--- fake CC2652 ZNP");
        FakeZnp zf = new FakeZnp();
        Model.CoordInfo ci = new Model.CoordInfo();
        Radio r = Radio.detect(zf, "", ci, LOG);
        check(r != null && !r.isEzsp(), "detected ZNP");
        if (r != null) {
            check(ci.ieee.equals("88:77:66:55:44:33:22:11"), "IEEE " + ci.ieee);
            check(ci.firmware.startsWith("Z-Stack 3.x.0 2.7.1"), "firmware " + ci.firmware);
            Model.ScanResult s = r.measure(Radio.mask(20), 1, true);
            check(s.beacons.size() == 2 && s.beacons.get(1).src == 0x4A31 && s.beacons.get(1).lqi == 90 && s.beacons.get(0).channel == 20, "2 TI beacons with source address");
            check(s.energy.isEmpty(), "no energy scan on TI");
            for (String[] row : ci.rows()) System.out.println("     " + row[0] + ": " + row[1]);
        }
        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }
}
