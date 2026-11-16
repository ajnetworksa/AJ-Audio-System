package com.ajnetwork.aj_audio;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app update (FIX-032): downloads the new APK from the GitHub release and hands it to Android's installer.
 * Android shows one "Install?" window; the app restarts as the new version (same signing key needed).
 */
public final class AppUpdater {
    private AppUpdater() { }
    public static volatile String state = "";   // "", "downloading 42 %", "waiting for Install", error

    public static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "0"; }
    }

    public static void start(Context ctx, String url) {
        final Context c = ctx.getApplicationContext();
        if (state.startsWith("downloading")) return;
        state = "downloading 0 %";
        new Thread(() -> {
            try {
                HttpURLConnection h = open(url);
                int code = h.getResponseCode();
                for (int hop = 0; code >= 300 && code < 400 && hop < 5; hop++) { h = open(h.getHeaderField("Location")); code = h.getResponseCode(); }
                if (code != 200) { state = "Download failed (" + code + ")"; return; }
                long len = h.getContentLengthLong();
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                int id = pi.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
                PackageInstaller.Session ses = pi.openSession(id);
                long total = 0;
                try (InputStream in = h.getInputStream(); OutputStream out = ses.openWrite("app.apk", 0, len)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n); total += n;
                        if (len > 0) state = "downloading " + (total * 100 / len) + " %";
                    }
                    ses.fsync(out);
                }
                if (total < 100_000) { ses.abandon(); state = "Download failed (file too small)"; return; }
                Intent i = new Intent(c, Result.class);
                PendingIntent p = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                ses.commit(p.getIntentSender());
                ses.close();
                state = "waiting for Install";
            } catch (Exception e) { state = "Update failed: " + e.getMessage(); }
        }, "app-update").start();
    }

    private static HttpURLConnection open(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setInstanceFollowRedirects(true); h.setConnectTimeout(10000); h.setReadTimeout(60000);
        h.setRequestProperty("User-Agent", "AJ-Audio-App");
        return h;
    }

    /** answer from Android's installer: open its confirm window, or show what went wrong */
    public static class Result extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(confirm); }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                state = "";
            } else {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                state = st == PackageInstaller.STATUS_FAILURE_ABORTED ? "" : "Update failed: " + msg
                        + (msg != null && msg.contains("INCOMPATIBLE") ? " – uninstall the app once and install the new one" : "");
                if (!state.isEmpty()) new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(c, state, Toast.LENGTH_LONG).show());
            }
        }
    }
}
