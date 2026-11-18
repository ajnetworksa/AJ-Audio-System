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
    private String serialProblem = "";
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
            serialProblem = configureTty(tty);
            String bad = serialProblem;
            if (!bad.isEmpty()) Log.i("zigbee bridge: WARNING serial flags still wrong: " + bad);
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
            status = "waiting for Home Assistant on port " + port + (allow.isEmpty() ? "" : " (only " + allow + ")")
                    + (serialProblem.isEmpty() ? "" : " – WARNING serial flags wrong: " + serialProblem);
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
                sessRx = 0; sessTx = 0; firstRx.setLength(0); firstTx.setLength(0);
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
                    rx += n; sessRx += n; hex(firstRx, b, n);
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
                tx += n; sessTx += n; hex(firstTx, b, n);
            }
            if (s == cur) dropped(s, null);
        } catch (Exception e) {
            if (s == cur) dropped(s, e);
        }
    }

    // per-connection diagnostics: how much came back from the radio and the first bytes each way (ASH frames)
    private volatile long sessRx, sessTx;
    private final StringBuffer firstRx = new StringBuffer(), firstTx = new StringBuffer();
    private static void hex(StringBuffer sb, byte[] b, int n) {
        for (int i = 0; i < n && sb.length() < 96; i++) sb.append(String.format("%02x", b[i] & 0xff));
    }

    private void dropped(Socket s, Exception e) {
        if (cur == s) Log.i("zigbee bridge: session ended – HA->radio " + sessTx + " B (" + firstTx + "…), radio->HA " + sessRx + " B (" + firstRx + "…)");
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
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = p.getInputStream().read(buf)) > 0) b.write(buf, 0, n);
            p.waitFor();
            return b.toString("UTF-8");
        } catch (Exception e) { return String.valueOf(e.getMessage()); }
    }

    /*
     * The radio speaks binary ASH frames, so the serial port must pass every byte unchanged.
     * Android's toybox `stty raw` is NOT reliable: on the Judian panels it left istrip/iuclc/inlcr switched ON
     * (0xC1 arrived as 0x61, 0x0A as 0x0D – the radio's reset reply became garbage and ZHA kept retrying),
     * and running it again flipped flags back and forth – which is why ZHA worked once and then stopped.
     * So: never use "raw"; set each flag on its own (one bad/unknown flag can't block the others), then read the
     * settings back and report anything still wrong.
     */
    static final String[] OFF = {"parenb", "cstopb", "ignbrk", "brkint", "ignpar", "parmrk", "inpck", "istrip", "inlcr", "igncr",
            "icrnl", "ixon", "ixoff", "ixany", "iuclc", "imaxbel", "iutf8", "opost", "onlcr", "isig", "icanon", "iexten",
            "echo", "echoe", "echok", "echonl", "echoctl", "echoke"};
    static final String[] ON = {"cs8", "crtscts", "clocal", "cread"};

    static String configureTty(String tty) {
        sh("stty -F " + tty + " 115200");
        for (String f : ON) sh("stty -F " + tty + " " + f);
        for (String f : OFF) sh("stty -F " + tty + " -" + f);
        sh("stty -F " + tty + " min 1 time 0");
        String all = " " + sh("stty -F " + tty + " -a").replace('\n', ' ').replace(';', ' ') + " ";
        StringBuilder bad = new StringBuilder();
        if (!all.contains("115200")) bad.append("speed ");
        for (String f : ON) if (!all.contains(" " + f + " ")) bad.append(f).append("=off ");
        for (String f : OFF) if (all.contains(" " + f + " ")) bad.append(f).append("=on ");
        Log.i("zigbee bridge: serial " + tty + (bad.length() == 0 ? " ok (115200 8N1 RTS/CTS, binary)" : " problems: " + bad));
        return bad.toString().trim();
    }
}
