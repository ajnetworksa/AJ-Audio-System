package com.ajnetwork.ajpanel;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import android.util.Base64;

import javax.crypto.Cipher;

/**
 * Minimal Android Debug Bridge client over the network (port 5555) – lets the Installer app do what the PC tools
 * (deploy_ajpanel, panel_silent_updates, collect_logs …) do, without a PC.
 *
 * Protocol (AOSP system/core/adb/protocol.txt): 24-byte little-endian headers CNXN/AUTH/OPEN/OKAY/WRTE/CLSE.
 * Auth: the panel sends a 20-byte token; we sign it with this app's RSA key. The first time the panel doesn't know
 * the key yet → we send our public key and the panel shows "Allow debugging?" once (tick "Always allow").
 */
public final class Adb implements java.io.Closeable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Socket sock = new Socket();
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;
    public String banner = "";

    static final class Msg { int cmd, a0, a1; byte[] data; }

    /** @param keyDir app-private folder for the RSA key; @param waitAllowMs how long to wait for "Allow" on the panel */
    public Adb(String host, int port, File keyDir, long waitAllowMs) throws Exception {
        sock.connect(new InetSocketAddress(host, port), 4000);
        sock.setTcpNoDelay(true);
        in = new DataInputStream(sock.getInputStream());
        out = sock.getOutputStream();
        KeyPair kp = key(keyDir);
        send(CNXN, VERSION, MAX, "host::AJ-Panel\0".getBytes(StandardCharsets.US_ASCII));
        boolean sentSig = false, sentKey = false;
        sock.setSoTimeout(10_000);
        while (true) {
            Msg m = read();
            if (m.cmd == CNXN) { banner = new String(m.data, StandardCharsets.US_ASCII).trim(); break; }
            if (m.cmd != AUTH || m.a0 != AUTH_TOKEN) throw new IOException("unexpected adb reply " + Integer.toHexString(m.cmd));
            if (!sentSig) { send(AUTH, AUTH_SIGNATURE, 0, sign(kp.getPrivate(), m.data)); sentSig = true; continue; }
            if (!sentKey) {
                send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) kp.getPublic()));
                sentKey = true;
                sock.setSoTimeout((int) Math.max(15_000, waitAllowMs));   // person taps "Allow" on the panel
                continue;
            }
            throw new IOException("the panel did not allow debugging (tap Allow on its screen)");
        }
        sock.setSoTimeout(30_000);
    }

    // ---------------- services ----------------

    /** run a shell command, return everything it printed */
    public String shell(String cmd) throws IOException {
        return service("shell:" + cmd, null, 120_000);
    }

    /** stream an APK into "cmd package install" (no file on the panel needed) */
    public String install(byte[] apk, String flags) throws IOException {
        return service("exec:cmd package install " + flags + " -S " + apk.length, apk, 300_000);
    }

    private String service(String name, byte[] stdin, int timeoutMs) throws IOException {
        int local = nextId++;
        send(OPEN, local, 0, (name + "\0").getBytes(StandardCharsets.UTF_8));
        sock.setSoTimeout(timeoutMs);
        int remote = 0;
        ByteArrayOutputStream res = new ByteArrayOutputStream();
        int sent = 0;
        boolean waitingOkay = false;
        while (true) {
            if (remote != 0 && stdin != null && sent < stdin.length && !waitingOkay) {
                int n = Math.min(MAX, stdin.length - sent);
                byte[] chunk = new byte[n];
                System.arraycopy(stdin, sent, chunk, 0, n);
                send(WRTE, local, remote, chunk);
                sent += n;
                waitingOkay = true;
            }
            Msg m = read();
            if (m.a1 != local && m.cmd != CNXN) continue;           // other stream
            if (m.cmd == OKAY) { remote = m.a0; waitingOkay = false; }
            else if (m.cmd == WRTE) { res.write(m.data); send(OKAY, local, m.a0, new byte[0]); }
            else if (m.cmd == CLSE) {
                if (remote == 0) throw new IOException("the panel refused \"" + name.split(" ")[0] + "\"");
                send(CLSE, local, remote, new byte[0]);
                break;
            }
        }
        sock.setSoTimeout(30_000);
        return res.toString("UTF-8");
    }

    @Override public void close() { try { sock.close(); } catch (Exception ignored) { } }

    // ---------------- wire ----------------

    private void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0; for (byte b : data) sum += b & 0xff;
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd);
        synchronized (out) { out.write(h.array()); out.write(data); out.flush(); }
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt(); m.a0 = h.getInt(); m.a1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > 1 << 20) throw new IOException("bad adb packet");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---------------- keys ----------------

    static KeyPair key(File dir) throws Exception {
        File pri = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub.der");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pri.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))), kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(pri))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(pri)) { o.write(kp.getPrivate().getEncoded()); }
        try (FileOutputStream o = new FileOutputStream(pub)) { o.write(kp.getPublic().getEncoded()); }
        return kp;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream i = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    /** PKCS#1 v1.5 signature of the token as if it were a SHA-1 hash (what adbd verifies) */
    static byte[] sign(PrivateKey k, byte[] token) throws Exception {
        int size = 256;
        byte[] em = new byte[size];
        em[0] = 0; em[1] = 1;
        int ps = size - 3 - SHA1_PREFIX.length - token.length;
        for (int i = 0; i < ps; i++) em[2 + i] = (byte) 0xff;
        em[2 + ps] = 0;
        System.arraycopy(SHA1_PREFIX, 0, em, 3 + ps, SHA1_PREFIX.length);
        System.arraycopy(token, 0, em, 3 + ps + SHA1_PREFIX.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k);
        return c.doFinal(em);
    }

    /** Android's own public key format (mincrypt RSAPublicKey struct, base64) + " name" + NUL */
    static byte[] publicKey(RSAPublicKey k) {
        int words = 64;
        BigInteger n = k.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " AJ-Panel@self\0";
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
