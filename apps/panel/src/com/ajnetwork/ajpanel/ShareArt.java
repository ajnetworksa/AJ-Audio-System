package com.ajnetwork.ajpanel;

import android.graphics.Bitmap;
import android.media.MediaMetadata;

import java.io.ByteArrayOutputStream;

/**
 * Cover picture of what this device shares (YouTube thumbnail, Spotify cover …), served at /art.jpg so Music
 * Assistant's AriaCast plugin can fetch it ("artworkUrl") and every panel shows it with the song.
 */
final class ShareArt {
    static volatile byte[] jpeg;
    private static Bitmap last;
    private static int n;

    static Bitmap from(MediaMetadata md) {
        if (md == null) return null;
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    /** remember a new picture; returns its version number (changes when the picture changes), 0 = none */
    static synchronized int set(Bitmap b) {
        if (b == null) return 0;
        if (b == last) return n;
        try {
            int max = Math.max(b.getWidth(), b.getHeight());
            Bitmap s = max > 600 ? Bitmap.createScaledBitmap(b, b.getWidth() * 600 / max, b.getHeight() * 600 / max, true) : b;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            s.compress(Bitmap.CompressFormat.JPEG, 85, o);
            jpeg = o.toByteArray();
            last = b;
            return ++n;
        } catch (Exception e) { return 0; }
    }
}
