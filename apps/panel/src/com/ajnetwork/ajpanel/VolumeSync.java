package com.ajnetwork.ajpanel;

import android.content.Context;
import android.media.AudioManager;

/**
 * Keeps the panel's real (Android media) volume and the Music Assistant volume the same – both ways.
 *
 * Before: snapclient used a *software* mixer, so MA's volume only scaled the audio data; the panel's own
 * volume (keys, vendor slider, Judian/YJY UI) never moved, and moving it never reached MA.
 * Now: snapclient runs with "--mixer none" and this class
 *  - MA volume / mute changed (phone app, MA web, group volume)  -> set STREAM_MUSIC on the panel
 *  - panel volume changed locally (buttons, vendor UI)           -> players/cmd/volume_set in MA
 *  - first contact after start: the panel's current volume wins (no sudden jump to MA's default 25)
 * Works in steps of the panel's own range (DSPPA 0..15, Judian 0..100), so no ping-pong on rounding.
 */
public class VolumeSync {
    private final AudioManager am;
    private final Ma ma;
    private final Cfg cfg;
    private boolean first = true;
    private int lastMa = -1;          // MA volume we last saw / set (0..100)
    private int lastIndex = -1;       // stream index we last saw / set
    private Boolean lastMuted = null;

    public VolumeSync(Context c, Ma ma, Cfg cfg) {
        this.am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        this.ma = ma; this.cfg = cfg;
    }

    private int max() { return Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)); }
    private int toPercent(int idx) { return Math.round(idx * 100f / max()); }
    private int toIndex(int pct) { return Math.round(Math.max(0, Math.min(100, pct)) * max() / 100f); }

    /** call from the poll thread with the latest MA state */
    public void update(NowPlaying n) {
        if (!cfg.systemVolume() || !n.registered || n.volume < 0) return;
        try {
            int idx = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (first) {
                // panel volume wins at start
                first = false;
                int pct = toPercent(idx);
                lastIndex = idx; lastMa = pct; lastMuted = n.muted;
                if (Math.abs(pct - n.volume) > 1) { ma.volume(cfg.maPlayerId(), pct); Log.i("volume: panel " + pct + "% -> MA"); }
                return;
            }
            if (n.volume != lastMa) {
                // changed in MA (app, web, group volume) -> panel
                int target = toIndex(n.volume);
                if (target != idx) am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                Log.i("volume: MA " + n.volume + "% -> panel " + target + "/" + max());
                lastMa = n.volume; lastIndex = target;
            } else if (idx != lastIndex) {
                // changed on the panel (buttons / vendor UI) -> MA
                int pct = toPercent(idx);
                ma.volume(cfg.maPlayerId(), pct);
                Log.i("volume: panel " + idx + "/" + max() + " -> MA " + pct + "%");
                lastIndex = idx; lastMa = pct;
            }
            if (lastMuted == null || n.muted != lastMuted) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, n.muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
                Log.i("volume: " + (n.muted ? "muted" : "unmuted") + " from MA");
                lastMuted = n.muted;
            }
        } catch (Exception e) {
            Log.e("volume sync", e);
        }
    }

    /** settings / server changed: take the panel volume as truth again */
    public void reset() { first = true; lastMuted = null; }
}
