package com.ajnetwork.ajzigbee;

import android.util.Base64;

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

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Minimal WebSocket client (RFC 6455) for ws:// and wss:// – Home Assistant API and the Zigbee2MQTT frontend. */
public final class Ws {
    public interface OnText { void text(String s); }
    public interface OnClose { void closed(String why); }

    private Socket sock;
    private OutputStream out;
    private InputStream in;
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean closed;

    /** url: ws://host:port/path?query or wss://… (http/https accepted too) */
    public Ws(String url, OnText onText, OnClose onClose) throws IOException {
        URI u = URI.create(url);
        String sc = u.getScheme() == null ? "ws" : u.getScheme().toLowerCase();
        boolean tls = sc.equals("wss") || sc.equals("https");
        String host = u.getHost();
        if (host == null) throw new IOException("bad address " + url);
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        if (tls) {
            SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            s.connect(new InetSocketAddress(host, port), 6000);
            s.startHandshake();
            sock = s;
        } else {
            sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), 6000);
        }
        sock.setTcpNoDelay(true);
        out = sock.getOutputStream();
        in = new BufferedInputStream(sock.getInputStream(), 65536);
        byte[] k = new byte[16]; rnd.nextBytes(k);
        String key = Base64.encodeToString(k, Base64.NO_WRAP);
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\nUser-Agent: AJ-Zigbee-Survey\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        StringBuilder head = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) { head.append((char) c); if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break; if (head.length() > 8192) break; }
        String h = head.toString();
        if (!h.startsWith("HTTP/1.1 101")) {
            sock.close();
            String first = h.contains("\r\n") ? h.substring(0, h.indexOf("\r\n")) : h;
            throw new IOException("server refused the connection: " + (first.isEmpty() ? "no answer" : first));
        }
        reader = new Thread(() -> readLoop(onText, onClose), "ws-read");
        reader.setDaemon(true);
    }

    private final Thread reader;

    /** start delivering messages (call after the owner has stored this object) */
    public void start() { reader.start(); }

    private int rd() throws IOException { int b = in.read(); if (b < 0) throw new IOException("connection closed"); return b; }

    private void readLoop(OnText onText, OnClose onClose) {
        String why = "closed";
        ByteArrayOutputStream frag = new ByteArrayOutputStream();
        int fragOp = 0;
        try {
            while (!closed) {
                int b0 = rd(), b1 = rd();
                int op = b0 & 0x0F;
                boolean fin = (b0 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = (rd() << 8) | rd();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | rd(); }
                if (len > 64L * 1024 * 1024) throw new IOException("message too big");
                byte[] p = new byte[(int) len];
                int off = 0; while (off < len) { int n = in.read(p, off, (int) len - off); if (n < 0) throw new IOException("eof"); off += n; }
                if (op == 0x9) { send(0xA, p); continue; }
                if (op == 0x8) { why = "server closed the connection"; break; }
                if (op == 0xA) continue;
                if (op == 0x1 || op == 0x2) { frag.reset(); fragOp = op; }
                frag.write(p, 0, p.length);
                if (fin && fragOp == 0x1 && onText != null) onText.text(new String(frag.toByteArray(), StandardCharsets.UTF_8));
                if (fin) frag.reset();
            }
        } catch (Exception e) { why = e.getMessage(); }
        closed = true;
        try { sock.close(); } catch (Exception ignored) { }
        if (onClose != null) onClose.closed(why);
    }

    public boolean isClosed() { return closed; }

    public void sendText(String s) throws IOException { send(0x1, s.getBytes(StandardCharsets.UTF_8)); }

    private synchronized void send(int op, byte[] p) throws IOException {
        if (closed) throw new IOException("closed");
        int n = p.length;
        ByteArrayOutputStream f = new ByteArrayOutputStream(n + 14);
        f.write(0x80 | op);
        if (n < 126) f.write(0x80 | n);
        else if (n < 65536) { f.write(0x80 | 126); f.write(n >> 8); f.write(n); }
        else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write(i >= 4 ? 0 : (n >> (8 * i)) & 0xFF); }
        byte[] mask = new byte[4]; rnd.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < n; i++) f.write(p[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    public void close() {
        if (closed) return;
        try { send(0x8, new byte[]{0x03, (byte) 0xE8}); } catch (Exception ignored) { }
        closed = true;
        try { sock.close(); } catch (Exception ignored) { }
    }
}
