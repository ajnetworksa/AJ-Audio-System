package com.ajnetwork.ajpanel;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;

/**
 * Keeps the panel's real (Android media) volume and the Music Assistant volume the same – both ways.
 *
 *  - MA volume / mute changed (phone app, MA web, group volume)  -> set STREAM_MUSIC on the panel
 *  - panel volume changed locally (buttons, vendor UI)           -> players/cmd/volume_set in MA
 *
 * v0.3.2 (BUG-024): on M20 and DSPPA the vendor firmware moves STREAM_MUSIC by itself (a drop to a low
 * value, then a ~3 s fade back up, every ~12 s). v0.3.1 forwarded every one of those steps to MA, so the
 * whole room/group volume jumped around. Now:
 *  - a local change is only sent to MA when it has stayed put for SETTLE_MS (fades are ignored; when the
 *    fade ends where it started nothing is sent at all)
 *  - nothing is sent to MA while MA has the room muted
 *  - local changes right after we set the volume ourselves are ignored
 *  - "fight guard": if the panel changes its volume on its own too often, MA becomes the only source of
 *    truth for GUARD_MS and the MA volume is put back on the panel
 */
public class VolumeSync {
    private static final long SETTLE_MS = 2000;      // local value must be stable this long
    private static final long OWN_SET_MS = 1500;     // ignore echoes of our own setStreamVolume
    private static final long WINDOW_MS = 60_000;    // fight detection window
    private static final int FIGHT_COUNT = 4;        // this many local pushes in the window = fight
    private static final long GUARD_MS = 10 * 60_000;

    private final AudioManager am;
    private final Ma ma;
    private final Cfg cfg;
    private boolean first = true;
    private int lastMa = -1;          // MA volume we last saw / set (0..100)
    private int lastIndex = -1;       // stream index that matches lastMa
    private Boolean lastMuted = null;
    private volatile int stream = AudioManager.STREAM_MUSIC;  // ALARM while sharing (our PcmRelay plays there)
    private volatile boolean maWins;
    private int savedMusic = -1;
    private boolean shareOn;

    private int pendingIdx = -1;      // local value waiting to settle
    private long pendingSince = 0;
    private long ownSetAt = 0;
    private final long[] pushes = new long[FIGHT_COUNT];
    private int pushPos = 0;
    private long guardUntil = 0;

    public VolumeSync(Context c, Ma ma, Cfg cfg) {
        this.am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        this.ma = ma; this.cfg = cfg;
    }

    private int max() { return Math.max(1, am.getStreamMaxVolume(stream)); }
    private int toPercent(int idx) { return Math.round(idx * 100f / max()); }
    private int toIndex(int pct) { return Math.round(Math.max(0, Math.min(100, pct)) * max() / 100f); }

    private void setPanel(int target) {
        am.setStreamVolume(stream, target, 0);
        ownSetAt = SystemClock.elapsedRealtime();
    }

    /** true when too many local changes reached MA lately */
    private boolean fighting(long now) {
        pushes[pushPos] = now;
        pushPos = (pushPos + 1) % FIGHT_COUNT;
        long oldest = pushes[pushPos];
        return oldest > 0 && now - oldest < WINDOW_MS;
    }

    /** call from the poll thread with the latest MA state */
    public void update(NowPlaying n) {
        if (!cfg.systemVolume() || !n.registered || n.volume < 0) return;
        try {
            long now = SystemClock.elapsedRealtime();
            if (stream == AudioManager.STREAM_ALARM && now - ownSetAt > OWN_SET_MS) {
                // share mode: someone moved the panel's own volume (keys / vendor slider) – that would make the
                // shared app audible un-synced again. Take it as the room volume and turn the media stream back to 0.
                int m = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (m > 0) {
                    int pct = Math.round(m * 100f / Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                    ownSetAt = now;
                    ma.volume(cfg.maPlayerId(), pct);
                    Log.i("volume: share mode, panel keys -> MA " + pct + "%");
                }
            }
            int idx = am.getStreamVolume(stream);
            if (first) {
                first = false;
                lastMuted = null;                       // apply MA mute state below
                int pct = toPercent(idx);
                if (n.muted || maWins || Math.abs(pct - n.volume) <= 1) {
                    // MA value is the truth (or already equal)
                    lastMa = n.volume; lastIndex = toIndex(n.volume);
                    if (lastIndex != idx && !n.muted) setPanel(lastIndex);
                } else {
                    // panel volume wins at start (no jump to MA's default)
                    lastIndex = idx; lastMa = pct;
                    ma.volume(cfg.maPlayerId(), pct);
                    Log.i("volume: panel " + pct + "% -> MA (start)");
                }
                applyMute(n, now);
                return;
            }

            if (n.volume != lastMa) {
                // changed in MA (app, web, group volume) -> panel
                int target = toIndex(n.volume);
                if (target != idx && !n.muted) setPanel(target);   // while muted it is applied on unmute
                Log.i("volume: MA " + n.volume + "% -> panel " + target + "/" + max());
                lastMa = n.volume; lastIndex = target; pendingIdx = -1;
            } else if (n.muted) {
                pendingIdx = -1;                        // muted: Android reports 0 – never send that to MA
            } else if (idx == lastIndex) {
                pendingIdx = -1;                        // back where it was (end of a vendor fade)
            } else if (now - ownSetAt < OWN_SET_MS) {
                // echo of our own change – ignore
            } else if (idx != pendingIdx) {
                pendingIdx = idx; pendingSince = now;   // wait until it settles
            } else if (now - pendingSince >= SETTLE_MS) {
                pendingIdx = -1;
                if (now < guardUntil) {
                    // the panel keeps moving by itself: MA wins
                    setPanel(lastIndex);
                    Log.i("volume: panel moved itself to " + idx + "/" + max() + " – put back " + lastIndex + " (guard)");
                } else if (fighting(now)) {
                    guardUntil = now + GUARD_MS;
                    setPanel(lastIndex);
                    Log.i("volume: panel keeps changing its own volume – MA volume wins for 10 min");
                } else {
                    // a real local change (buttons / vendor slider) -> MA
                    int pct = toPercent(idx);
                    ma.volume(cfg.maPlayerId(), pct);
                    Log.i("volume: panel " + idx + "/" + max() + " -> MA " + pct + "%");
                    lastIndex = idx; lastMa = pct;
                }
            }

            applyMute(n, now);
        } catch (Exception e) {
            Log.e("volume sync", e);
        }
    }

    private void applyMute(NowPlaying n, long now) {
            if (lastMuted == null || n.muted != lastMuted) {
                am.adjustStreamVolume(stream, n.muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
                ownSetAt = now;
                if (!n.muted && lastIndex >= 0) setPanel(lastIndex);  // come back at MA's volume
                Log.i("volume: " + (n.muted ? "muted" : "unmuted") + " from MA");
                lastMuted = n.muted;
            }
    }

    /** settings / server changed: start over */
    public void reset() { first = true; lastMuted = null; pendingIdx = -1; guardUntil = 0; maWins = false; }

    /**
     * Share mode (FIX-026): the synced music plays on the ALARM stream, the media stream (the shared app, e.g.
     * YouTube, playing directly on this panel) is turned down to 0 so this room hears only the synced copy.
     */
    /** share fallback: the media stream (the app itself) is this room's sound again, MA volume drives it */
    public synchronized void shareDirect() {
        stream = AudioManager.STREAM_MUSIC;
        if (savedMusic >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0);
        ownSetAt = SystemClock.elapsedRealtime();
        first = true; lastMuted = null; pendingIdx = -1; maWins = true;
        Log.i("volume: share direct – media stream back to " + savedMusic);
    }

    public synchronized void shareMode(boolean on) {
        if (on == shareOn) return;
        shareOn = on;
        if (on) {
            savedMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            stream = AudioManager.STREAM_ALARM;
            int amax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM), mmax = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            am.setStreamVolume(AudioManager.STREAM_ALARM, Math.max(1, Math.round(savedMusic * amax / (float) mmax)), 0);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            Log.i("volume: share mode – media stream 0 (was " + savedMusic + "), music on alarm stream");
        } else {
            stream = AudioManager.STREAM_MUSIC;
            if (savedMusic >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0);
            Log.i("volume: share mode off – media stream back to " + savedMusic);
        }
        ownSetAt = SystemClock.elapsedRealtime();
        first = true; lastMuted = null; pendingIdx = -1; maWins = true;   // MA's volume is applied to the new stream
    }
}
