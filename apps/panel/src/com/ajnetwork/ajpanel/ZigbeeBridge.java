package com.ajnetwork.ajpanel;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TEST: serial -> TCP bridge for the panel's built-in Zigbee radio (Judian: /dev/ttyS3, Silicon Labs EZSP v7 NCP).
 * Home Assistant ZHA connects to socket://<panel>:6638, radio type EZSP. Bytes are passed through unchanged.
 * Off by default. Only one client, and only from the allowed address (default: the music server = HA).
 * The Judian gateway app must be disabled first (tools\zigbee_test.bat -on), otherwise two programs share the radio.
 */
public class ZigbeeBridge {
    public static volatile String status = "off";
    public static volatile String client = "";
    public static volatile long rx, tx;   // bytes radio->HA, HA->radio

    private final String tty, allow;
    private final int port;
    private volatile boolean run;
    private ServerSocket server;
    private volatile Socket cur;
    private FileInputStream in;
    private FileOutputStream out;

    public ZigbeeBridge(String tty, int port, String allow) { this.tty = tty; this.port = port; this.allow = allow; }

    public String sig() { return tty + port + allow; }

    public void start() {
        run = true;
        new Thread(this::serve, "zb-bridge").start();
    }

    public void stop() {
        run = false;
        status = "off"; client = "";
        closeQuiet(cur);
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        try { if (in != null) in.close(); } catch (Exception ignored) { }
        try { if (out != null) out.close(); } catch (Exception ignored) { }
    }

    private void serve() {
        try {
            in = new FileInputStream(tty);
            out = new FileOutputStream(tty);
            // 115200 8N1, RTS/CTS, raw – what the Tuya host used (probe 2)
            // proven base first (this worked with ZHA); then switch off the stray input flags toybox's "raw" leaves on
            String r = sh("stty -F " + tty + " 115200 cs8 -parenb -cstopb crtscts raw -echo -echoe -echok -echoctl -echoke -onlcr -icrnl -ixon -ixoff");
            String r2 = sh("stty -F " + tty + " -ignpar -inpck -iuclc -ixany -iutf8 -imaxbel -istrip -brkint");
            if (!r2.isEmpty()) r += " / extra: " + r2.trim();
            Log.i("zigbee bridge: " + tty + " opened, stty " + (r.isEmpty() ? "ok" : r.trim()) + " | " + sh("stty -F " + tty).replace('\n', ' ').trim());
        } catch (Exception e) {
            status = "error: cannot open " + tty + " (" + e.getMessage() + ")";
            Log.i("zigbee bridge: " + status);
            return;
        }
        new Thread(this::radioToClient, "zb-radio").start();
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port));
            status = "waiting for Home Assistant on port " + port + (allow.isEmpty() ? "" : " (only " + allow + ")");
            Log.i("zigbee bridge: " + status);
            while (run) {
                Socket s = server.accept();
                String from = s.getInetAddress().getHostAddress();
                if (!allow.isEmpty() && !allow.equals(from)) {
                    Log.i("zigbee bridge: refused " + from + " (allowed: " + allow + ")");
                    closeQuiet(s);
                    continue;
                }
                closeQuiet(cur);      // one client only – the newest wins
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                cur = s;
                client = from;
                status = "connected to " + from;
                Log.i("zigbee bridge: " + status);
                new Thread(() -> clientToRadio(s), "zb-client").start();
            }
        } catch (Exception e) {
            if (run) { status = "error: " + e.getMessage(); Log.i("zigbee bridge: " + status); }
        }
    }

    private void radioToClient() {
        byte[] b = new byte[512];
        try {
            int n;
            while (run && (n = in.read(b)) >= 0) {
                if (n == 0) continue;
                Socket s = cur;
                if (s == null) continue;      // nobody listening: drop (ASH resends)
                try {
                    s.getOutputStream().write(b, 0, n);
                    rx += n;
                } catch (Exception e) {
                    if (s == cur) dropped(s, e);
                }
            }
        } catch (Exception e) {
            if (run) { status = "error: radio read " + e.getMessage(); Log.i("zigbee bridge: " + status); }
        }
    }

    private void clientToRadio(Socket s) {
        byte[] b = new byte[512];
        try {
            InputStream i = s.getInputStream();
            int n;
            while (run && (n = i.read(b)) >= 0) {
                out.write(b, 0, n);
                out.flush();
                tx += n;
            }
            if (s == cur) dropped(s, null);
        } catch (Exception e) {
            if (s == cur) dropped(s, e);
        }
    }

    private void dropped(Socket s, Exception e) {
        closeQuiet(s);
        if (cur == s) { cur = null; client = ""; }
        if (run) {
            status = "waiting for Home Assistant on port " + port + " (last client left" + (e == null ? "" : ": " + e.getMessage()) + ")";
            Log.i("zigbee bridge: " + status);
        }
    }

    private static void closeQuiet(Socket s) { try { if (s != null) s.close(); } catch (Exception ignored) { } }

    private static String sh(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd + " 2>&1").start();
            byte[] all = new byte[4096];
            int n = p.getInputStream().read(all);
            p.waitFor();
            return n > 0 ? new String(all, 0, n) : "";
        } catch (Exception e) { return e.getMessage(); }
    }
}
