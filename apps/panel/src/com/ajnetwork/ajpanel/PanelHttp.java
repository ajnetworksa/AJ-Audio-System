package com.ajnetwork.ajpanel;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Small LAN API for the installer app "AJ Setup" (port 8766).
 *  GET  /api/info              public: name, version, player id, connection state
 *  GET  /chime.wav             public: test sound (MA plays it through the sync player = full path test)
 *  GET  /api/log               admin
 *  POST /api/restart           admin: restart the sync player
 *  POST /api/settings?name=&latency=&overlay=&system_volume=   admin
 *  POST /api/update?url=       admin: download an APK and ask to install it (one tap on the panel)
 * "admin" = header "Authorization: Bearer <Music Assistant token>" of an MA user with role admin;
 * the panel checks the token with MA (auth/me) – no extra passwords.
 */
public class PanelHttp {
    public static final int PORT = 8766;
    private final PanelService svc;
    private final Cfg cfg;
    private ServerSocket server;
    private final Map<String, Long> okTokens = new HashMap<>();
    private final long started = System.currentTimeMillis();

    PanelHttp(PanelService svc, Cfg cfg) { this.svc = svc; this.cfg = cfg; }

    void start() {
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket(PORT, 8, InetAddress.getByName("0.0.0.0"));
                Log.i("setup api on :" + PORT);
                while (!server.isClosed()) {
                    Socket c = server.accept();
                    new Thread(() -> handle(c), "setup-api").start();
                }
            } catch (Exception e) { if (server == null || !server.isClosed()) Log.e("setup api", e); }
        }, "setup-api-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() { try { if (server != null) server.close(); } catch (Exception ignored) { } }

    private boolean admin(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return false;
        String tok = auth.substring(7).trim();
        synchronized (okTokens) {
            Long t = okTokens.get(tok);
            if (t != null && System.currentTimeMillis() - t < 10 * 60_000L) return true;
        }
        try {
            Object me = new Ma(cfg.maBase(), tok).cmd("auth/me", null);
            boolean ok = me instanceof JSONObject && "admin".equals(((JSONObject) me).optString("role"));
            if (ok) synchronized (okTokens) { okTokens.put(tok, System.currentTimeMillis()); }
            return ok;
        } catch (Exception e) { return false; }
    }

    private void handle(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            StringBuilder head = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1) { head.append((char) ch); if (head.length() > 3 && head.toString().endsWith("\r\n\r\n")) break; if (head.length() > 16384) break; }
            String[] lines = head.toString().split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) return;
            String method = first[0], target = first[1], path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            String auth = null;
            for (String l : lines) if (l.toLowerCase().startsWith("authorization:")) auth = l.substring(14).trim();
            Map<String, String> args = new HashMap<>();
            for (String kv : query.split("&")) {
                int e = kv.indexOf('='); if (e <= 0) continue;
                args.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            }
            OutputStream o = s.getOutputStream();
            if (method.equals("OPTIONS")) { reply(o, 204, "text/plain", new byte[0]); return; }
            switch (path) {
                case "/api/info": reply(o, 200, "application/json", info().toString().getBytes(StandardCharsets.UTF_8)); return;
                case "/chime.wav": reply(o, 200, "audio/wav", chime()); return;
            }
            if (!admin(auth)) { reply(o, 401, "application/json", "{\"error\":\"Music Assistant admin token needed\"}".getBytes(StandardCharsets.UTF_8)); return; }
            switch (path) {
                case "/api/log":
                    reply(o, 200, "text/plain; charset=utf-8", ("AJ Panel " + version() + " · " + cfg.name() + " · " + cfg.maPlayerId() + "\n\n" + Log.dump()).getBytes(StandardCharsets.UTF_8));
                    return;
                case "/api/restart":
                    Log.i("setup: restart sync player");
                    svc.snap.restart();
                    ok(o); return;
                case "/api/settings": {
                    android.content.SharedPreferences.Editor ed = cfg.edit();
                    if (args.containsKey("name")) ed.putString("name", args.get("name"));
                    if (args.containsKey("latency")) ed.putInt("latency", Integer.parseInt(args.get("latency")));
                    if (args.containsKey("overlay")) ed.putBoolean("overlay", Boolean.parseBoolean(args.get("overlay")));
                    if (args.containsKey("system_volume")) ed.putBoolean("system_volume", Boolean.parseBoolean(args.get("system_volume")));
                    ed.commit();
                    Log.i("setup: settings " + args.keySet());
                    svc.reloadFromAnyThread();
                    ok(o); return;
                }
                case "/api/update": {
                    String url = args.get("url");
                    if (url == null || !url.startsWith("http")) { reply(o, 400, "text/plain", "url missing".getBytes()); return; }
                    ok(o);
                    new Thread(() -> update(url), "update").start();
                    return;
                }
                default: reply(o, 404, "text/plain", "not found".getBytes());
            }
        } catch (Exception e) { Log.e("setup api request", e); }
    }

    private JSONObject info() throws Exception {
        NowPlaying n = svc.now;
        return new JSONObject().put("app", "AJ Panel").put("version", version()).put("name", cfg.name())
                .put("player_id", cfg.maPlayerId()).put("host_id", cfg.hostId())
                .put("model", Build.MANUFACTURER + " " + Build.MODEL).put("android", Build.VERSION.RELEASE)
                .put("sync_connected", svc.snap != null && svc.snap.connected).put("sync_restarts", svc.snap == null ? 0 : svc.snap.restarts)
                .put("server", cfg.host()).put("logged_in", !cfg.token().isEmpty())
                .put("latency", cfg.latencyMs()).put("overlay", cfg.overlay()).put("system_volume", cfg.systemVolume())
                .put("playing", n != null && n.active() ? n.title : "")
                .put("uptime_s", (System.currentTimeMillis() - started) / 1000);
    }

    private String version() {
        try { return svc.getPackageManager().getPackageInfo(svc.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private static void ok(OutputStream o) throws Exception { reply(o, 200, "application/json", "{\"ok\":true}".getBytes()); }

    private static void reply(OutputStream o, int code, String type, byte[] body) throws Exception {
        String h = "HTTP/1.1 " + code + (code == 200 ? " OK" : code == 204 ? " No Content" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization\r\nConnection: close\r\n\r\n";
        o.write(h.getBytes(StandardCharsets.US_ASCII));
        o.write(body);
        o.flush();
    }

    /** two soft tones (A5 then E6), 44.1 kHz mono 16 bit WAV */
    private static byte[] chime() {
        int rate = 44100;
        double[][] tones = {{880, 0.28}, {1318.5, 0.45}};
        int total = 0; for (double[] t : tones) total += (int) (t[1] * rate);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int dataLen = total * 2;
        writeStr(b, "RIFF"); writeInt(b, 36 + dataLen); writeStr(b, "WAVE");
        writeStr(b, "fmt "); writeInt(b, 16); writeShort(b, 1); writeShort(b, 1); writeInt(b, rate); writeInt(b, rate * 2); writeShort(b, 2); writeShort(b, 16);
        writeStr(b, "data"); writeInt(b, dataLen);
        for (double[] t : tones) {
            int len = (int) (t[1] * rate);
            for (int i = 0; i < len; i++) {
                double env = Math.min(1, i / (rate * 0.01)) * Math.exp(-3.0 * i / len);
                writeShort(b, (int) (Math.sin(2 * Math.PI * t[0] * i / rate) * env * 0.6 * 32767));
            }
        }
        return b.toByteArray();
    }
    private static void writeStr(ByteArrayOutputStream b, String s) { b.write(s.getBytes(StandardCharsets.US_ASCII), 0, 4); }
    private static void writeInt(ByteArrayOutputStream b, int v) { b.write(v); b.write(v >> 8); b.write(v >> 16); b.write(v >> 24); }
    private static void writeShort(ByteArrayOutputStream b, int v) { b.write(v); b.write(v >> 8); }

    /** download the APK and hand it to Android's installer (shows one confirm dialog on the panel) */
    private void update(String url) {
        try {
            Log.i("update: downloading " + url);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true); c.setConnectTimeout(10000); c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { c = (HttpURLConnection) new URL(c.getHeaderField("Location")).openConnection(); }
            PackageInstaller pi = svc.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int id = pi.createSession(p);
            try (PackageInstaller.Session ses = pi.openSession(id);
                 InputStream in = c.getInputStream();
                 OutputStream out = ses.openWrite("ajpanel.apk", 0, -1)) {
                byte[] buf = new byte[65536]; int n; long total = 0;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
                ses.fsync(out);
                Log.i("update: " + total + " bytes, asking to install");
                Intent i = new Intent(svc, UpdateReceiver.class);
                PendingIntent pend = PendingIntent.getBroadcast(svc, id, i, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                out.close();
                ses.commit(pend.getIntentSender());
            }
        } catch (Exception e) { Log.e("update", e); }
    }
}
