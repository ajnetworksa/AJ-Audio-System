package com.ajnetwork.ajpanel;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Small blocking WebSocket client (RFC 6455) for the Home Assistant API: text frames, fragmentation,
 * ping/pong, close. ws:// and wss://. One reader thread (the caller of {@link #read()}), sends are synchronized.
 * No compression extension (HA does not need it). Messages up to 32 MB (a big entity registry is ~1–3 MB).
 */
public class Ws implements AutoCloseable {
    private static final int MAX = 32 << 20;
    private final Socket s;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean closed;

    public Ws(String url, int timeoutMs) throws IOException {
        URI u = URI.create(url);
        boolean tls = "wss".equals(u.getScheme()) || "https".equals(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        String host = u.getHost();
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setTcpNoDelay(true);
        raw.setKeepAlive(true);
        if (tls) raw = ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
        s = raw;
        s.setSoTimeout(timeoutMs);
        in = new BufferedInputStream(s.getInputStream(), 65536);
        out = s.getOutputStream();
        byte[] k = new byte[16]; rnd.nextBytes(k);
        String key = java.util.Base64.getEncoder().encodeToString(k);
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\nUser-Agent: AJPanel\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII)); out.flush();
        String status = readLine();
        if (status == null || !status.contains(" 101")) throw new IOException("websocket refused: " + status);
        String l;
        while ((l = readLine()) != null && !l.isEmpty()) { /* headers */ }
    }

    private String readLine() throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') { int n = b.length(); if (n > 0 && b.charAt(n - 1) == '\r') b.setLength(n - 1); return b.toString(); }
            b.append((char) c);
            if (b.length() > 8192) throw new IOException("header too long");
        }
        return b.length() == 0 ? null : b.toString();
    }

    /** read timeout for {@link #read()} (0 = forever) */
    public void timeout(int ms) throws IOException { s.setSoTimeout(ms); }

    /** next text message; answers pings itself; null when the server closed the connection */
    public String read() throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        boolean inText = false;
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) return null;
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = ((long) rd() << 8) | rd();
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | rd(); }
            if (len > MAX) throw new IOException("message too big: " + len);
            byte[] mask = null;
            if ((b1 & 0x80) != 0) { mask = new byte[4]; full(mask); }
            byte[] p = new byte[(int) len];
            full(p);
            if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
            switch (op) {
                case 0x9: send(0xA, p); break;                                   // ping -> pong
                case 0xA: break;                                                 // pong
                case 0x8: closed = true; try { send(0x8, new byte[0]); } catch (Exception ignored) { } return null;
                case 0x1: case 0x2: case 0x0:
                    if (op != 0) { msg.reset(); inText = true; }
                    if (!inText) break;
                    msg.write(p, 0, p.length);
                    if (msg.size() > MAX) throw new IOException("message too big");
                    if (fin) return new String(msg.toByteArray(), StandardCharsets.UTF_8);
                    break;
                default: break;
            }
        }
    }

    private int rd() throws IOException { int c = in.read(); if (c < 0) throw new IOException("eof"); return c; }
    private void full(byte[] b) throws IOException {
        int o = 0;
        while (o < b.length) { int n = in.read(b, o, b.length - o); if (n < 0) throw new IOException("eof"); o += n; }
    }

    public void sendText(String t) throws IOException { send(0x1, t.getBytes(StandardCharsets.UTF_8)); }
    public void ping() throws IOException { send(0x9, new byte[0]); }

    /** tests: a thread that must never write (stands in for Android's main thread, where network I/O throws) */
    static volatile Thread forbidden;

    /** client frames are always masked */
    private synchronized void send(int op, byte[] p) throws IOException {
        if (Thread.currentThread() == forbidden) throw new IllegalStateException("network I/O on the main thread");
        if (closed && op != 0x8) throw new IOException("closed");
        ByteArrayOutputStream f = new ByteArrayOutputStream(p.length + 14);
        f.write(0x80 | op);
        if (p.length < 126) f.write(0x80 | p.length);
        else if (p.length < 65536) { f.write(0x80 | 126); f.write(p.length >> 8); f.write(p.length); }
        else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write((int) (((long) p.length) >> (8 * i))); }
        byte[] m = new byte[4]; rnd.nextBytes(m);
        f.write(m, 0, 4);
        byte[] q = p.clone();
        for (int i = 0; i < q.length; i++) q[i] ^= m[i & 3];
        f.write(q, 0, q.length);
        out.write(f.toByteArray());
        out.flush();
    }

    public boolean isClosed() { return closed || s.isClosed(); }

    @Override public void close() {
        closed = true;
        try { send(0x8, new byte[0]); } catch (Exception ignored) { }
        try { s.close(); } catch (Exception ignored) { }
    }
}
