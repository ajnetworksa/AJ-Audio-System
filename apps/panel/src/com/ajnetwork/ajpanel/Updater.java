package com.ajnetwork.ajpanel;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * App updates (FIX-032): the panel looks at the GitHub release itself (every 6 h) and offers "Update to vX" on its
 * screen; the Installer app can also push an update or an APK file (PanelHttp). Android always asks once to install.
 */
public final class Updater {
    private Updater() { }
    public static volatile String availableVersion = "", availableUrl = "";
    public static volatile long availablePublished;
    private static volatile long lastCheck;
    public static volatile String state = "";        // "", "downloading", "waiting for Install", error text

    public static String currentVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "0"; }
    }

    public static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }
    private static int parse(String s) { try { return Integer.parseInt(s.replaceAll("\\D.*", "")); } catch (Exception e) { return 0; } }

    /** blocking; called from the poll thread – does the real check at most every hour with automatic updates, else 6 h (or when forced) */
    public static void maybeCheck(Context c, Cfg cfg, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastCheck < (cfg.autoUpdate() ? 3600_000L : 6 * 3600_000L)) return;
        lastCheck = now;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("https://api.github.com/repos/" + cfg.repo() + "/releases/latest").openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(15000);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("User-Agent", "AJ-Panel");
            if (h.getResponseCode() != 200) { Log.i("update check: GitHub said " + h.getResponseCode()); return; }
            JSONObject rel = new JSONObject(read(h.getInputStream()));
            JSONArray assets = rel.optJSONArray("assets");
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                availablePublished = f.parse(rel.optString("published_at")).getTime();
            } catch (Exception e) { availablePublished = 0; }
            Pattern p = Pattern.compile("^AJ_Panel_v([0-9.]+)\\.apk$");
            String ver = "", url = "";
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                Matcher m = p.matcher(a == null ? "" : a.optString("name"));
                if (m.find()) { ver = m.group(1); url = a.optString("browser_download_url"); }
            }
            String cur = currentVersion(c);
            if (!ver.isEmpty() && newer(ver, cur)) {
                if (!ver.equals(availableVersion)) Log.i("update available: v" + ver + " (installed v" + cur + ")");
                availableVersion = ver; availableUrl = url;
            } else { availableVersion = ""; availableUrl = ""; }
        } catch (Exception e) { Log.i("update check: " + e.getMessage()); }
    }

    /** download + install (blocking, run off the main thread); returns null or the error */
    public static String download(Context c, String url) {
        try {
            state = "downloading";
            Log.i("update: downloading " + url);
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(true); h.setConnectTimeout(10000); h.setReadTimeout(60000);
            h.setRequestProperty("User-Agent", "AJ-Panel");
            int code = h.getResponseCode();
            for (int hop = 0; code >= 300 && code < 400 && hop < 5; hop++) {   // GitHub -> objects.githubusercontent.com
                h = (HttpURLConnection) new URL(h.getHeaderField("Location")).openConnection();
                h.setConnectTimeout(10000); h.setReadTimeout(60000);
                code = h.getResponseCode();
            }
            if (code != 200) { state = "download failed (" + code + ")"; return state; }
            try (InputStream in = h.getInputStream()) {
                String err = install(c, in, h.getContentLengthLong(), "download");
                state = err == null ? "waiting for Install" : err;
                return err;
            }
        } catch (Exception e) { Log.e("update", e); state = "update failed: " + e.getMessage(); return state; }
    }

    /** write an APK stream into an install session and ask Android to install it; returns null or the error */
    public static String install(Context c, InputStream in, long len, String what) {
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int id = pi.createSession(p);
            PackageInstaller.Session ses = pi.openSession(id);
            long total = 0;
            try (OutputStream out = ses.openWrite("ajpanel.apk", 0, len)) {
                byte[] buf = new byte[65536];
                while (len < 0 || total < len) {
                    int n = in.read(buf, 0, len < 0 ? buf.length : (int) Math.min(buf.length, len - total));
                    if (n < 0) break;
                    out.write(buf, 0, n); total += n;
                }
                ses.fsync(out);
            }
            if (len >= 0 && total != len) { ses.abandon(); return "only " + total + " of " + len + " bytes arrived"; }
            if (total < 100_000) { ses.abandon(); return "file too small to be an app (" + total + " bytes)"; }
            Log.i("update (" + what + "): " + total + " bytes, asking Android to install");
            Intent i = new Intent(c, UpdateReceiver.class);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            ses.commit(pend.getIntentSender());
            ses.close();
            return null;
        } catch (Exception e) { Log.e("install", e); return String.valueOf(e.getMessage()); }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close(); return b.toString("UTF-8");
    }

    // ---------------- automatic updates (scheduled) ----------------
    private static String autoTried = "";
    private static long autoTriedAt;

    /** is "now" inside the update window ("HH-HH", may wrap midnight) on an allowed day ("1234567", 1 = Monday)? */
    static boolean inWindow(Cfg cfg, java.util.Calendar now) {
        int dow = now.get(java.util.Calendar.DAY_OF_WEEK);           // Sunday = 1
        int day = dow == java.util.Calendar.SUNDAY ? 7 : dow - 1;    // Monday = 1 … Sunday = 7
        if (!cfg.updateDays().contains(String.valueOf(day))) return false;
        String w = cfg.updateWindow();
        if (w.equals("any")) return true;
        try {
            String[] p = w.split("-");
            int from = Integer.parseInt(p[0].trim()), to = Integer.parseInt(p[1].trim()), h = now.get(java.util.Calendar.HOUR_OF_DAY);
            return from <= to ? h >= from && h < to : h >= from || h < to;
        } catch (Exception e) { return false; }
    }

    /**
     * Called every poll (background thread): installs a newer release by itself when automatic updates are on,
     * the panel is allowed to install silently (device owner), it's inside the schedule and nothing plays here.
     * A version that failed is retried after 6 h at the earliest.
     */
    public static void autoInstall(Context c, Cfg cfg, NowPlaying n) {
        if (!cfg.autoUpdate() || availableVersion.isEmpty() || availableUrl.isEmpty()) return;
        if (!AdminReceiver.owner(c)) return;                        // would ask on the screen – the banner does that
        if (n != null && "playing".equals(n.state)) return;         // never interrupt music
        if (ShareService.running) return;
        if (!inWindow(cfg, java.util.Calendar.getInstance())) return;
        long now = System.currentTimeMillis();
        if (availablePublished > 0 && now - availablePublished < cfg.updateDelayH() * 3600_000L) return;   // still in the test period
        if (availableVersion.equals(autoTried) && now - autoTriedAt < 6 * 3600_000L) return;
        autoTried = availableVersion; autoTriedAt = now;
        Log.i("auto update: installing v" + availableVersion + " (window " + cfg.updateWindow() + ", days " + cfg.updateDays() + ")");
        String err = download(c, availableUrl);
        if (err != null) Log.i("auto update failed: " + err);
    }

}
