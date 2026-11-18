package com.ajnetwork.aj_audio;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Minimal WebSocket client (RFC 6455) for the AriaCast protocol used by Music Assistant's
 * "AriaCast Receiver" plugin: ws://<server>:12889/audio (binary PCM frames) and /metadata (JSON text).
 */
public class AriaWs {
    public interface OnText { void text(String s); }

    private final Socket sock = new Socket();
    private OutputStream out;
    private InputStream in;
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean closed;

    public AriaWs(String host, int port, String path, OnText onText) throws IOException {
        sock.connect(new InetSocketAddress(host, port), 5000);
        sock.setTcpNoDelay(true);
        out = sock.getOutputStream();
        in = new BufferedInputStream(sock.getInputStream());
        byte[] k = new byte[16]; rnd.nextBytes(k);
        String key = Base64.encodeToString(k, Base64.NO_WRAP);
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        StringBuilder head = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) { head.append((char) c); if (head.toString().endsWith("\r\n\r\n")) break; if (head.length() > 8192) break; }
        String h = head.toString();
        if (!h.startsWith("HTTP/1.1 101")) {
            sock.close();
            String first = h.contains("\r\n") ? h.substring(0, h.indexOf("\r\n")) : h;
            throw new IOException(first.contains("403") ? "Music Assistant's AriaCast receiver says a sender is still connected. If nothing is being shared right now, it is a leftover connection: in Music Assistant open Settings → Plugins → AriaCast Receiver → ⋮ → Reload, then try again." : "Server refused: " + first);
        }
        Thread t = new Thread(() -> readLoop(onText), "ws-read" + path);
        t.setDaemon(true);
        t.start();
    }

    private void readLoop(OnText onText) {
        try {
            while (!closed) {
                int b0 = in.read(); if (b0 < 0) break;
                int b1 = in.read(); if (b1 < 0) break;
                int op = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
                byte[] p = new byte[(int) len];
                int off = 0; while (off < len) { int n = in.read(p, off, (int) len - off); if (n < 0) throw new IOException("eof"); off += n; }
                if (op == 0x1 && onText != null) onText.text(new String(p, StandardCharsets.UTF_8));
                else if (op == 0x9) send(0xA, p);          // ping -> pong
                else if (op == 0x8) break;                 // close
            }
        } catch (Exception ignored) { }
        closed = true;
    }

    public boolean isClosed() { return closed || sock.isClosed(); }

    public void sendText(String s) throws IOException { send(0x1, s.getBytes(StandardCharsets.UTF_8)); }
    public void sendBinary(byte[] b) throws IOException { send(0x2, b); }

    private synchronized void send(int op, byte[] p) throws IOException {
        if (closed) throw new IOException("closed");
        int n = p.length;
        byte[] hdr;
        if (n < 126) hdr = new byte[]{(byte) (0x80 | op), (byte) (0x80 | n)};
        else if (n < 65536) hdr = new byte[]{(byte) (0x80 | op), (byte) (0x80 | 126), (byte) (n >> 8), (byte) n};
        else throw new IOException("frame too big");
        byte[] mask = new byte[4]; rnd.nextBytes(mask);
        byte[] f = new byte[hdr.length + 4 + n];
        System.arraycopy(hdr, 0, f, 0, hdr.length);
        System.arraycopy(mask, 0, f, hdr.length, 4);
        for (int i = 0; i < n; i++) f[hdr.length + 4 + i] = (byte) (p[i] ^ mask[i & 3]);
        out.write(f);
        out.flush();
    }

    public void close() {
        if (closed) return;
        try { send(0x8, new byte[]{0x03, (byte) 0xE8}); } catch (Exception ignored) { }
        closed = true;
        try { sock.close(); } catch (Exception ignored) { }
    }
}
