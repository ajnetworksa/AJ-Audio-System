package com.ajnetwork.ajpanel;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * Lets AJ Panel see which other app plays on this panel (YouTube, Spotify …) and its song title,
 * for "Play this panel's sound in other rooms". Enabled once by deploy_ajpanel.bat
 * (cmd notification allow_listener); without it the panel still shares, just without the title.
 */
public class MediaListener extends NotificationListenerService {

    public static class Local {
        public String pkg = "", app = "", title = "", artist = "";
        public android.graphics.Bitmap art;
    }

    /** the other app that is playing right now on this panel, or null */
    public static Local playing(Context c) {
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = m.getActiveSessions(new ComponentName(c, MediaListener.class));
            for (MediaController mc : l) {
                if (c.getPackageName().equals(mc.getPackageName())) continue;
                // Judian: while Music Assistant plays here, our DLNA bridge keeps the panel's own music player "playing"
                // a silent track with MA's song title – that is not another app (when MA is idle the bridge stops it,
                // so a song the user starts in that player still counts)
                if ("com.judian.jdmediarender".equals(mc.getPackageName()) && new Cfg(c).vendorBridge()) {
                    PanelService ps = PanelService.instance;
                    NowPlaying np = ps == null ? null : ps.now;
                    if (np != null && np.active()) continue;
                }
                PlaybackState st = mc.getPlaybackState();
                if (st == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                Local x = new Local();
                x.pkg = mc.getPackageName();
                x.app = label(c, x.pkg);
                MediaMetadata md = mc.getMetadata();
                if (md != null) {
                    CharSequence t = md.getText(MediaMetadata.METADATA_KEY_TITLE);
                    CharSequence a = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
                    x.title = t == null ? "" : t.toString();
                    x.artist = a == null ? "" : a.toString();
                    x.art = ShareArt.from(md);
                }
                return x;
            }
        } catch (SecurityException e) {
            // listener not allowed on this panel – fine
        } catch (Exception e) { Log.e("media sessions", e); }
        return null;
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }
}
