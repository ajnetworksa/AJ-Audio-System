package com.ajnetwork.ajpanel;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Album art loader with a one-entry cache (the panel only ever shows one track). */
public final class Art {
    private static String lastUrl;
    private static Bitmap last;
    private Art() { }

    public static synchronized Bitmap get(Cfg cfg, String url) {
        if (url == null || url.isEmpty()) return null;
        if (url.equals(lastUrl)) return last;
        Bitmap b = download(cfg, url, 512);
        lastUrl = url; last = b;
        return b;
    }

    // ---- small covers for the room cards (loaded in the background, kept in a little cache) ----
    public interface Done { void got(Bitmap b); }
    private static final android.util.LruCache<String, Bitmap> thumbs = new android.util.LruCache<>(24);
    private static final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    public static Bitmap cached(String url) { return url == null || url.isEmpty() ? null : thumbs.get(url); }
    public static void thumb(Cfg cfg, String url, Done d) {
        if (url == null || url.isEmpty()) { d.got(null); return; }
        Bitmap b = thumbs.get(url);
        if (b != null) { d.got(b); return; }
        pool.execute(() -> {
            Bitmap x = download(cfg, url, 160);
            if (x != null) thumbs.put(url, x);
            main.post(() -> d.got(x));
        });
    }

    static Bitmap download(Cfg cfg, String url, int max) {
        Bitmap b = null;
        try {
            String u = url.startsWith("http") ? url : cfg.maBase() + (url.startsWith("/") ? "" : "/") + url;
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(4000); c.setReadTimeout(8000);
            if (u.contains(cfg.host()) && !cfg.token().isEmpty()) c.setRequestProperty("Authorization", "Bearer " + cfg.token());
            if (c.getResponseCode() == 200) {
                try (InputStream in = c.getInputStream()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    b = BitmapFactory.decodeStream(in, null, o);
                }
                if (b != null && b.getWidth() > max) b = Bitmap.createScaledBitmap(b, max, max * b.getHeight() / b.getWidth(), true);
            }
        } catch (Exception e) { Log.e("art " + url, e); }
        return b;
    }
}
