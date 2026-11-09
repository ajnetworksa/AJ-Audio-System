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
                if (b != null && b.getWidth() > 512) b = Bitmap.createScaledBitmap(b, 512, 512 * b.getHeight() / b.getWidth(), true);
            }
        } catch (Exception e) { Log.e("art " + url, e); }
        lastUrl = url; last = b;
        return b;
    }
}
