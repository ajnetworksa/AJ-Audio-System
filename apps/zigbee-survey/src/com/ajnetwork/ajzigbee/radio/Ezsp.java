package com.ajnetwork.ajzigbee.radio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Silicon Labs EmberZNet NCP over UART: ASH link layer + EZSP commands (legacy v4–v7 and extended v8+ frames).
 * READ-ONLY use: version, EUI64, manufacturing strings, energy scan and active (beacon) scan.
 * We never call networkInit / formNetwork / leaveNetwork / setToken, so the network stored on the stick
 * (Home Assistant's network) is untouched.
 */
public final class Ezsp {
    // ---------------- ASH ----------------
    static final int FLAG = 0x7E, ESC = 0x7D, XON = 0x11, XOFF = 0x13, SUB = 0x18, CAN = 0x1A;

    private final Transport t;
    private final Transport.Log log;
    private int txFrm, rxAck;                 // our next frame number, next frame number we expect from the NCP
    private final ByteArrayOutputStream acc = new ByteArrayOutputStream();
    private boolean escaped, bad;
    private final byte[] rbuf = new byte[512];

    // ---------------- EZSP ----------------
    public int protocol = -1, stackType, stackVersion;
    private int seq;

    public interface Callback { void frame(int frameId, byte[] params); }
    private Callback callback;

    public Ezsp(Transport t, Transport.Log log) { this.t = t; this.log = log; }

    public static int crc(byte[] d, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (d[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        return crc;
    }

    /** XOR mask of ASH data fields (pseudo-random sequence, seed 0x42) */
    public static void randomize(byte[] d, int off, int len) {
        int r = 0x42;
        for (int i = off; i < off + len; i++) {
            d[i] ^= (byte) r;
            r = (r & 1) != 0 ? ((r >> 1) ^ 0xB8) : (r >> 1);
        }
    }

    static boolean reserved(int b) { return b == FLAG || b == ESC || b == XON || b == XOFF || b == SUB || b == CAN; }

    /** control + data -> stuffed frame with CRC and flag */
    public static byte[] encode(int control, byte[] data) {
        byte[] raw = new byte[1 + data.length + 2];
        raw[0] = (byte) control;
        System.arraycopy(data, 0, raw, 1, data.length);
        int c = crc(raw, 0, 1 + data.length);
        raw[raw.length - 2] = (byte) (c >> 8);
        raw[raw.length - 1] = (byte) c;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte b : raw) {
            int v = b & 0xFF;
            if (reserved(v)) { o.write(ESC); o.write(v ^ 0x20); } else o.write(v);
        }
        o.write(FLAG);
        return o.toByteArray();
    }

    /** decoded frames not handed out yet */
    private final java.util.ArrayDeque<byte[]> frames = new java.util.ArrayDeque<>();

    /** next un-stuffed, CRC-checked frame (control + data), or null on timeout */
    private byte[] nextFrame(long deadline) throws IOException {
        while (frames.isEmpty()) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return null;
            int n = t.read(rbuf, (int) Math.min(left, 200));
            for (int i = 0; i < n; i++) { byte[] f = feed(rbuf[i] & 0xFF); if (f != null) frames.add(f); }
        }
        return frames.poll();
    }

    private byte[] feed(int v) {
        if (v == XON || v == XOFF) return null;
        if (v == CAN) { acc.reset(); escaped = false; bad = false; return null; }
        if (v == SUB) { bad = true; return null; }
        if (v == FLAG) {
            byte[] f = acc.toByteArray(); acc.reset(); boolean wasBad = bad; bad = false; escaped = false;
            if (wasBad || f.length < 3) return null;
            int c = crc(f, 0, f.length - 2);
            if (((f[f.length - 2] & 0xFF) << 8 | (f[f.length - 1] & 0xFF)) != c) { log.i("ash: bad crc"); return null; }
            byte[] out = new byte[f.length - 2]; System.arraycopy(f, 0, out, 0, out.length);
            return out;
        }
        if (v == ESC) { escaped = true; return null; }
        if (escaped) { v ^= 0x20; escaped = false; }
        acc.write(v);
        return null;
    }

    /** ASH reset; returns the reset code from RSTACK or -1 when nothing answered */
    public int reset(int timeoutMs) throws IOException {
        t.drain(); frames.clear(); acc.reset(); escaped = false; bad = false;
        t.write(new byte[]{(byte) CAN});
        t.write(encode(0xC0, new byte[0]));
        long dl = System.currentTimeMillis() + timeoutMs;
        while (true) {
            byte[] f = nextFrame(dl);
            if (f == null) return -1;
            int c = f[0] & 0xFF;
            if (c == 0xC1 && f.length >= 3) { txFrm = 0; rxAck = 0; return f[2] & 0xFF; }
            if (c == 0xC2) log.i("ash: NCP error frame code " + (f.length > 2 ? (f[2] & 0xFF) : -1));
        }
    }

    private byte[] lastData; private int lastFrm;

    private void sendData(byte[] ezsp, boolean retx) throws IOException {
        byte[] d = ezsp.clone();
        randomize(d, 0, d.length);
        int ctrl = ((retx ? lastFrm : txFrm) << 4) | (retx ? 0x08 : 0) | rxAck;
        if (!retx) { lastFrm = txFrm; lastData = ezsp; txFrm = (txFrm + 1) & 7; }
        t.write(encode(ctrl, d));
    }

    private void ack() throws IOException { t.write(encode(0x80 | rxAck, new byte[0])); }

    /**
     * Next EZSP frame from the NCP (already ACKed, de-randomized); handles ASH ACK/NAK.
     * returns null on timeout
     */
    private byte[] receiveEzsp(long deadline) throws IOException {
        while (true) {
            byte[] f = nextFrame(deadline);
            if (f == null) return null;
            int c = f[0] & 0xFF;
            if ((c & 0x80) == 0) {                  // DATA
                int frm = (c >> 4) & 7;
                if (frm == rxAck) {
                    rxAck = (rxAck + 1) & 7;
                    ack();
                    byte[] d = new byte[f.length - 1]; System.arraycopy(f, 1, d, 0, d.length);
                    randomize(d, 0, d.length);
                    return d;
                }
                ack();                              // duplicate / out of order -> just ack what we have
            } else if ((c & 0xE0) == 0xA0) {        // NAK -> resend our last frame
                if (lastData != null) sendData(lastData, true);
            } else if (c == 0xC1) {
                throw new IOException("radio reset itself (RSTACK)");
            } else if (c == 0xC2) {
                throw new IOException("radio reported ASH error " + (f.length > 2 ? (f[2] & 0xFF) : -1));
            }
            // ACK frames: nothing to do (one frame in flight)
        }
    }

    // ---------------- EZSP frames ----------------
    private byte[] frame(int id, byte[] params) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(seq);
        o.write(0x00);
        if (protocol >= 8) { o.write(0x01); o.write(id & 0xFF); o.write(id >> 8); }
        else if (protocol >= 5) { o.write(0xFF); o.write(0x00); o.write(id); }
        else o.write(id);
        o.write(params, 0, params.length);
        return o.toByteArray();
    }

    /** parsed EZSP frame: [0]=seq [1]=fc [2]=id, params */
    private static final class Frame { int seq, fc, id; byte[] p; }

    private Frame parse(byte[] d) {
        if (d.length < 3) return null;
        Frame f = new Frame();
        f.seq = d[0] & 0xFF; f.fc = d[1] & 0xFF;
        int off;
        if (protocol >= 8 && d.length >= 5) { f.id = (d[3] & 0xFF) | (d[4] & 0xFF) << 8; off = 5; }
        else if ((d[2] & 0xFF) == 0xFF && d.length >= 5) { f.id = d[4] & 0xFF; off = 5; }
        else { f.id = d[2] & 0xFF; off = 3; }
        f.p = new byte[d.length - off]; System.arraycopy(d, off, f.p, 0, f.p.length);
        return f;
    }

    /** sends a command and waits for its response; callbacks that arrive meanwhile go to the listener */
    public byte[] command(int id, byte[] params, int timeoutMs) throws IOException {
        byte[] fr = frame(id, params);
        int mySeq = seq; seq = (seq + 1) & 0xFF;
        sendData(fr, false);
        int tries = 0;
        long dl = System.currentTimeMillis() + Math.min(timeoutMs, 1600);
        long hard = System.currentTimeMillis() + timeoutMs;
        while (true) {
            byte[] d = receiveEzsp(Math.min(dl, hard));
            if (d == null) {
                if (System.currentTimeMillis() >= hard) throw new IOException(String.format(Locale.US, "no answer to EZSP 0x%04X", id));
                if (++tries > 3) throw new IOException(String.format(Locale.US, "no answer to EZSP 0x%04X", id));
                sendData(fr, true);
                dl = System.currentTimeMillis() + 1600;
                continue;
            }
            Frame f = parse(d);
            if (f == null) continue;
            int cbType = (f.fc >> 3) & 3;
            if (cbType == 0 && f.seq == mySeq && f.id == id) return f.p;
            if (callback != null && (cbType != 0 || f.id != id)) callback.frame(f.id, f.p);
        }
    }

    /** waits for callbacks for up to ms (used while a scan is running) */
    public void pump(int ms, Callback cb, java.util.function.BooleanSupplier done) throws IOException {
        long dl = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < dl && !done.getAsBoolean()) {
            byte[] d = receiveEzsp(Math.min(dl, System.currentTimeMillis() + 300));
            if (d == null) continue;
            Frame f = parse(d);
            if (f != null) cb.frame(f.id, f.p);
        }
    }

    /** ASH reset + EZSP version handshake. Returns false if nothing that speaks ASH answered. */
    public boolean start(int resetTimeoutMs) throws IOException {
        int code = reset(resetTimeoutMs);
        if (code < 0) code = reset(resetTimeoutMs);
        if (code < 0) return false;
        log.i("ash: RSTACK reset code 0x" + Integer.toHexString(code));
        protocol = 4;                 // the first version command always uses the short legacy frame
        byte[] r = versionCmd(4);
        int ver = r[0] & 0xFF;
        if (ver != 4) {
            protocol = ver < 5 ? 4 : ver;
            r = versionCmd(ver);
        }
        protocol = r[0] & 0xFF;
        stackType = r[1] & 0xFF;
        stackVersion = (r[2] & 0xFF) | (r[3] & 0xFF) << 8;
        log.i("ezsp: protocol v" + protocol + " stack " + stackString());
        return true;
    }

    private byte[] versionCmd(int v) throws IOException {
        byte[] fr;
        if (protocol >= 8) fr = new byte[]{(byte) seq, 0x00, 0x01, 0x00, 0x00, (byte) v};
        else fr = new byte[]{(byte) seq, 0x00, 0x00, (byte) v};
        int mySeq = seq; seq = (seq + 1) & 0xFF;
        sendData(fr, false);
        for (int tries = 0; tries < 3; tries++) {
            long dl = System.currentTimeMillis() + 1600;
            while (true) {
                byte[] d = receiveEzsp(dl);
                if (d == null) break;
                // version response: seq, fc, [ext...], id 0, protocol, stackType, stackVersion
                if (d.length >= 7 && (d[0] & 0xFF) == mySeq) {
                    int off = (d[2] & 0xFF) == 0x01 && d.length >= 9 ? 5 : 3;
                    if (d.length - off >= 4) { byte[] r = new byte[4]; System.arraycopy(d, off, r, 0, 4); return r; }
                }
            }
            sendData(fr, true);
        }
        throw new IOException("no answer to EZSP version");
    }

    public String stackString() {
        int v = stackVersion;
        return String.format(Locale.US, "%d.%d.%d.%d", (v >> 12) & 15, (v >> 8) & 15, (v >> 4) & 15, v & 15);
    }

    private int statusLen() { return protocol >= 13 ? 4 : 1; }

    // ---------------- read-only queries ----------------
    public String eui64() throws IOException {
        byte[] r = command(0x26, new byte[0], 3000);
        return r.length >= 8 ? Model.hex(r, 0, 8, true) : "";
    }

    /** EZSP_MFG_STRING = 0x01, EZSP_MFG_BOARD_NAME = 0x02 */
    public String mfgString(int token) {
        try {
            byte[] r = command(0x0B, new byte[]{(byte) token}, 3000);
            if (r.length < 1) return "";
            int n = Math.min(r[0] & 0xFF, r.length - 1);
            StringBuilder s = new StringBuilder();
            for (int i = 1; i <= n; i++) { int c = r[i] & 0xFF; if (c == 0 || c == 0xFF) break; if (c >= 32 && c < 127) s.append((char) c); }
            return s.toString().trim();
        } catch (IOException e) { return ""; }
    }

    /** EZSP_VALUE_VERSION_INFO (0x11): build, major, minor, patch, special, type */
    public String versionInfo() {
        try {
            byte[] r = command(0xAA, new byte[]{0x11}, 3000);
            int off = statusLen();
            if (r.length < off + 1 || (r[0] & 0xFF) != 0) return "";
            int len = r[off] & 0xFF;
            if (len < 7 || r.length < off + 1 + 7) return "";
            int p = off + 1;
            int build = (r[p] & 0xFF) | (r[p + 1] & 0xFF) << 8;
            return String.format(Locale.US, "%d.%d.%d.%d build %d", r[p + 2] & 0xFF, r[p + 3] & 0xFF, r[p + 4] & 0xFF, r[p + 5] & 0xFF, build);
        } catch (IOException e) { return ""; }
    }

    /** energy scan of the channels in mask; channel -> max RSSI dBm */
    public java.util.Map<Integer, Integer> energyScan(int channelMask, int duration) throws IOException {
        final java.util.Map<Integer, Integer> res = new java.util.TreeMap<>();
        final boolean[] done = {false};
        Callback cb = (id, p) -> {
            if (id == 0x48 && p.length >= 2) res.put(p[0] & 0xFF, (int) p[1]);
            else if (id == 0x1C) done[0] = true;
        };
        callback = cb;
        try {
            byte[] r = command(0x1A, new byte[]{0x00, (byte) channelMask, (byte) (channelMask >> 8), (byte) (channelMask >> 16), (byte) (channelMask >> 24), (byte) duration}, 4000);
            if (r.length > 0 && (r[0] & 0xFF) != 0) throw new IOException("energy scan refused, status 0x" + Integer.toHexString(r[0] & 0xFF));
            pump(20000, cb, () -> done[0]);
        } finally { callback = null; }
        return res;
    }

    /** active scan (beacon requests) on the channels in mask */
    public List<Model.Beacon> activeScan(int channelMask, int duration) throws IOException {
        final List<Model.Beacon> res = new ArrayList<>();
        final boolean[] done = {false};
        Callback cb = (id, p) -> {
            if (id == 0x1B && p.length >= 16) {
                Model.Beacon b = new Model.Beacon();
                b.channel = p[0] & 0xFF;
                b.pan = (p[1] & 0xFF) | (p[2] & 0xFF) << 8;
                b.extPan = Model.hex(p, 3, 8, true);
                b.permitJoin = p[11] != 0;
                b.lqi = p[14] & 0xFF;
                b.rssi = p[15];
                res.add(b);
            } else if (id == 0x1C) done[0] = true;
        };
        callback = cb;
        try {
            byte[] r = command(0x1A, new byte[]{0x01, (byte) channelMask, (byte) (channelMask >> 8), (byte) (channelMask >> 16), (byte) (channelMask >> 24), (byte) duration}, 4000);
            if (r.length > 0 && (r[0] & 0xFF) != 0) throw new IOException("beacon scan refused, status 0x" + Integer.toHexString(r[0] & 0xFF));
            pump(20000, cb, () -> done[0]);
        } finally { callback = null; }
        return res;
    }
}
