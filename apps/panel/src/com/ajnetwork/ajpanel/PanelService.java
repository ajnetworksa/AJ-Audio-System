package com.ajnetwork.ajpanel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The always-on part of AJ Panel:
 *  1. runs the embedded sync player (SnapProcess)
 *  2. polls Music Assistant every second for what this panel plays (NowPlaying)
 *  3. publishes it as a MediaSession + media notification (so launchers / lock screen / media keys see it)
 *  4. shows the floating now-playing overlay
 */
public class PanelService extends Service implements SnapProcess.Listener {
    public interface Listener { void onUpdate(NowPlaying n, Bitmap art); }

    static volatile PanelService instance;
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    public static void addListener(Listener l) { listeners.add(l); PanelService s = instance; if (s != null && s.now != null) l.onUpdate(s.now, s.art); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    private static final String CH = "ajpanel";
    private static final int NID = 1;

    private Cfg cfg;
    private Ma ma;
    SnapProcess snap;
    private MediaSession session;
    private Overlay overlay;
    private DlnaMetaBridge bridge;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService actions = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private Thread poller;
    volatile NowPlaying now;
    volatile Bitmap art;
    private String lastKey = "";
    private String snapSig = "";
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock cpuLock;

    public static void start(Context c) {
        Intent i = new Intent(c, PanelService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        cfg = new Cfg(this);
        ma = new Ma(cfg.maBase(), cfg.token());
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CH, "AJ Panel", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "AJPanel");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { playPause(); }
            @Override public void onPause() { playPause(); }
            @Override public void onSkipToNext() { groupCmd("next"); }
            @Override public void onSkipToPrevious() { groupCmd("previous"); }
            @Override public void onStop() { groupCmd("stop"); }
        });
        startForeground(NID, buildNotification(null));
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ajpanel");
        wifiLock.acquire();
        cpuLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ajpanel:player");
        cpuLock.acquire();
        snap = new SnapProcess(this, cfg, this);
        overlay = new Overlay(this, cfg);
        bridge = new DlnaMetaBridge(this, paused -> {
            NowPlaying n = now;
            if (n == null) return;
            if (paused) { if (!n.muted) pauseHere(); }          // pause on the panel's own screen = pause here
            else if (n.muted) pauseHere();                      // play on the panel's screen = resume here
            else if (!"playing".equals(n.state)) playPause();   // group was paused -> play the group
        });
        Log.i("service created, hostID " + cfg.hostId() + " → MA player " + cfg.maPlayerId());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        reload();
        return START_STICKY;
    }

    /** (re)apply settings: server, token, player on/off */
    public void reload() {
        ma.base = cfg.maBase();
        ma.token = cfg.token();
        String sig = cfg.player() + cfg.host() + cfg.snapPort() + cfg.hostId() + cfg.engine() + cfg.resample() + cfg.latencyMs();
        if (!sig.equals(snapSig)) {   // only restart audio when its settings changed
            snapSig = sig;
            snap.stop();
            if (cfg.player()) snap.start();
        }
        if (!running) {
            running = true;
            poller = new Thread(this::pollLoop, "ma-poll");
            poller.start();
        }
        lastKey = "";
    }

    private void pollLoop() {
        while (running) {
            NowPlaying n = NowPlaying.fetch(ma, cfg.maPlayerId());
            Bitmap a = n.active() ? Art.get(cfg, n.imageUrl) : null;
            if (cfg.vendorBridge()) bridge.update(n);
            now = n; art = a;
            String key = n.key() + "|" + n.serverOk + n.registered + n.error;
            final boolean changed = !key.equals(lastKey);
            lastKey = key;
            main.post(() -> publish(n, a, changed));
            try { Thread.sleep(n.serverOk ? 1000 : 3000); } catch (InterruptedException e) { break; }
        }
    }

    private void publish(NowPlaying n, Bitmap a, boolean changed) {
        for (Listener l : listeners) l.onUpdate(n, a);
        if (cfg.overlay() && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))) overlay.update(n, a, changed);
        if (!changed) return;
        if (n.active()) {
            MediaMetadata.Builder md = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, n.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, n.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, n.album)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle(n))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (n.duration * 1000));
            if (a != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, a);
            session.setMetadata(md.build());
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                    .setState("playing".equals(n.state) ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            (long) (n.elapsed * 1000), "playing".equals(n.state) ? 1f : 0f)
                    .build());
            session.setActive(true);
        } else {
            session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_NONE, 0, 0).build());
            session.setActive(false);
        }
        getSystemService(NotificationManager.class).notify(NID, buildNotification(n));
    }

    static String subtitle(NowPlaying n) {
        StringBuilder sb = new StringBuilder(n.artist);
        if (!n.groupName.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append("in ").append(n.groupName).append(" (").append(n.groupSize).append(")");
        return sb.toString();
    }

    private Notification buildNotification(NowPlaying n) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(open).setOngoing(true).setShowWhen(false);
        if (n != null && n.active()) {
            b.setContentTitle(n.title).setContentText(subtitle(n) + (n.user.isEmpty() ? "" : " · " + n.user));
            if (art != null) b.setLargeIcon(art);
            b.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()));
        } else {
            b.setContentTitle("AJ Panel").setContentText(n == null ? "starting…" : statusLine(n));
        }
        return b.build();
    }

    String statusLine(NowPlaying n) {
        if (n.error.equals("login needed")) return "Login needed (open AJ Panel → Settings, or deploy script)";
        if (!n.serverOk) return "Server " + cfg.host() + " not reachable" + (n.error.isEmpty() ? "" : " (" + n.error + ")");
        if (!n.registered) return "Waiting for Music Assistant to see this panel (" + cfg.maPlayerId() + ")";
        return "Ready · " + n.playerName;
    }

    @Override public void onSnapState(boolean running, String line) { }

    // ---------------- actions (from overlay / screen / media keys) ----------------
    private void async(String what, ThrowingRunnable r) {
        actions.execute(() -> { try { r.run(); Log.i(what); } catch (Exception e) { Log.e(what, e); } lastKey = ""; });
    }
    interface ThrowingRunnable { void run() throws Exception; }

    /** mute only this panel; the rest of the group keeps playing */
    public void pauseHere() {
        NowPlaying n = now; if (n == null) return;
        boolean m = !n.muted;
        async(m ? "pause here (mute)" : "resume here (unmute)", () -> ma.mute(cfg.maPlayerId(), m));
    }
    /** take this panel out of its group */
    public void leaveGroup() {
        NowPlaying n = now; if (n == null) return;
        if (n.staticMember) {   // MA would dissolve the whole saved group → only silence this panel
            async("leave saved group → mute + stop here", () -> ma.mute(cfg.maPlayerId(), true));
            return;
        }
        async("leave group", () -> ma.ungroup(cfg.maPlayerId()));
    }
    public void playPause() { groupCmd("play_pause"); }
    public void groupCmd(String c) {
        NowPlaying n = now; if (n == null) return;
        String target = n.groupId.isEmpty() ? cfg.maPlayerId() : n.groupId;
        async(c + " " + target, () -> ma.simple(c, target));
    }
    public void setVolume(int v) { async("volume " + v, () -> ma.volume(cfg.maPlayerId(), v)); }

    /** login with MA user/password, then swap for a long-lived token stored on the panel */
    public void login(String user, String pass, Runnable done) {
        io.execute(() -> {
            try {
                Ma m = new Ma(cfg.maBase(), null);
                m.login(user, pass, "AJ Panel " + cfg.name());
                String t;
                try { t = m.longToken("AJ Panel " + cfg.name() + " " + cfg.hostId()); }
                catch (Exception e) { Log.e("long-lived token (using session token)", e); t = m.token; }
                cfg.edit().putString("token", t).apply();
                Log.i("logged in as " + user);
            } catch (Exception e) { Log.e("login", e); }
            main.post(() -> { reload(); if (done != null) done.run(); });
        });
    }

    @Override public void onDestroy() {
        running = false;
        if (poller != null) poller.interrupt();
        snap.stop();
        overlay.remove();
        bridge.stop();
        session.release();
        if (wifiLock.isHeld()) wifiLock.release();
        if (cpuLock.isHeld()) cpuLock.release();
        instance = null;
        super.onDestroy();
    }
}
