package com.ajnetwork.aj_audio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Optional: with "notification access" the app can see which app plays on this phone and its song title + cover,
 * so the panels show them while "Play my phone" runs. The cover is served from a tiny web server on this phone
 * (port 12890) for Music Assistant's AriaCast plugin to fetch. Without access everything still plays.
 */
public class MediaWatch extends NotificationListenerService {
    static final int ART_PORT = 12890;

    static class Now { String app = "", title = "", artist = ""; Bitmap art; }

    static boolean allowed(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    static void openSettings(Context c) {
        c.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** the other app playing on this phone right now, or null */
    static Now playing(Context c) {
        if (!allowed(c)) return null;
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = m.getActiveSessions(new ComponentName(c, MediaWatch.class));
            for (MediaController mc : l) {
                if (c.getPackageName().equals(mc.getPackageName())) continue;
                PlaybackState st = mc.getPlaybackState();
                if (st == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                Now n = new Now();
                try { PackageManager pm = c.getPackageManager(); n.app = pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)).toString(); }
                catch (Exception e) { n.app = mc.getPackageName(); }
                MediaMetadata md = mc.getMetadata();
                if (md != null) {
                    CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE), a = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
                    n.title = t == null ? "" : t.toString();
                    n.artist = a == null ? "" : a.toString();
                    n.art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    if (n.art == null) n.art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (n.art == null) n.art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                }
                return n;
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---- cover picture web server ----
    private static volatile byte[] jpeg;
    private static Bitmap last;
    private static int version;
    private static ServerSocket server;

    /** remember the picture, start the server once; returns a version that changes with the picture (0 = none) */
    static synchronized int setArt(Bitmap b) {
        if (b == null) return 0;
        if (b != last) {
            try {
                int max = Math.max(b.getWidth(), b.getHeight());
                Bitmap s = max > 600 ? Bitmap.createScaledBitmap(b, b.getWidth() * 600 / max, b.getHeight() * 600 / max, true) : b;
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                s.compress(Bitmap.CompressFormat.JPEG, 85, o);
                jpeg = o.toByteArray();
                last = b;
                version++;
            } catch (Exception e) { return 0; }
        }
        if (server == null) startServer();
        return server == null ? 0 : version;
    }

    static synchronized void stopServer() {
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null; jpeg = null; last = null;
    }

    private static void startServer() {
        try {
            server = new ServerSocket(ART_PORT);
            ServerSocket ss = server;
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(5000);
                        byte[] buf = new byte[2048];
                        s.getInputStream().read(buf);                 // request line + headers (ignored: one picture only)
                        byte[] body = jpeg;
                        OutputStream o = s.getOutputStream();
                        String head = body == null ? "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                : "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                        o.write(head.getBytes(StandardCharsets.US_ASCII));
                        if (body != null) o.write(body);
                        o.flush();
                    } catch (Exception ignored) { }
                }
            }, "aj-art");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) { server = null; }
    }
}
