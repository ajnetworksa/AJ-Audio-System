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
    private VolumeSync volume;
    Breaks breaks;
    private PanelHttp http;
    volatile Site site;
    private long lastSite;
    private final Handler main = new Handler(Looper.getMainLooper());
    Handler main() { return main; }
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService actions = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private final long created = System.currentTimeMillis();
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
        shareBubble = new ShareBubble(this, cfg);
        volume = new VolumeSync(this, ma, cfg);
        breaks = new Breaks(this, ma, cfg);
        hub = new DashHub(this, cfg);
        http = new PanelHttp(this, cfg);
        http.start();
        bridge = new DlnaMetaBridge(this, paused -> {
            NowPlaying n = now;
            if (n == null) return;
            if (paused) { if (!n.muted) pauseHere(); }          // pause on the panel's own screen = pause here
            else if (n.muted) pauseHere();                      // play on the panel's screen = resume here
            else if (!"playing".equals(n.state)) playPause();   // group was paused -> play the group
        });
        Log.installCrashHandler(this);
        Log.i("service created, hostID " + cfg.hostId() + " -> MA player " + cfg.maPlayerId());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        reload();
        return START_STICKY;
    }

    /** (re)apply settings: server, token, player on/off */
    public void reload() {
        ma.base = cfg.maBase();
        ma.token = cfg.token();
        String sig = cfg.player() + cfg.host() + cfg.snapPort() + cfg.hostId() + cfg.engine() + cfg.resample() + cfg.latencyMs() + cfg.systemVolume();
        if (volume != null) volume.reset();
        if (!sig.equals(snapSig)) {   // only restart audio when its settings changed
            snapSig = sig;
            snap.stop();
            if (cfg.player()) snap.start();
        }
        applyZigbee();
        if (hub != null) hub.sync();
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
            volume.update(n);
            checkOrigin(n);
            keepGroup(n);
            lightenIfStruggling();
            if (n.serverOk && System.currentTimeMillis() - created > 60_000) {
                Updater.maybeCheck(this, cfg, false);
                Updater.autoInstall(this, cfg, n);
            }
            if (n.serverOk && System.currentTimeMillis() - lastSite > 3000) {
                lastSite = System.currentTimeMillis();
                try { site = Site.fetch(ma); } catch (Exception e) { Log.e("players/all", e); }
                breaks.update(site, n);
            }
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
        shareBubble.update();
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
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, cfg.dashboard() ? HomeActivity.class : MainActivity.class),
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
    // ---- self-healing for weak panels (DSPPA): the sync player converts 48 kHz to the panel's 44.1 kHz itself, which is
    // heavy for its CPU; while music plays the player then misses its time-sync answers and drops the connection
    // ("Time sync request failed"). After 3 such drops in 5 minutes let Android convert instead (much cheaper).
    private void lightenIfStruggling() {
        if (snap == null || !cfg.resample()) return;
        int f = snap.syncFailures(5 * 60_000L);
        if (f < 3) return;
        String rate = ((android.media.AudioManager) getSystemService(AUDIO_SERVICE)).getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        if ("48000".equals(rate)) return;   // nothing to convert – resampling isn't the cause
        cfg.edit().putBoolean("resample", false).putBoolean("resample_auto_off", true).apply();
        Log.i("sync player: " + f + " connection drops in 5 min while converting 48000 -> " + rate + " Hz – switching to Android's converter (lighter)");
        main.post(this::reload);
    }

    // ---- self-healing group membership ----
    // Music Assistant drops a player from its group when its sync connection breaks for a moment (seen on the
    // DSPPA's Wi-Fi: time-sync timeouts while streaming). The player reconnects within a second, but stays out of
    // the group ("Join" shows again). If we were in a playing group, the connection broke shortly before we were
    // dropped, and nobody here asked to leave, join that group again.
    private String keptGroup = "";
    private long keptAt, userLeftAt;
    private int rejoins;
    private long lastRejoinAt;
    /*
     * 0.8.8: logs showed the DSPPA re-joining 5× in 17 s – every poll, while its sync connection was still down.
     * Each join made Music Assistant re-form the whole group ("Dissolving syncgroup … re-forming with a new leader",
     * "Player smart can not be grouped") – so one weak panel kept interrupting all the others.
     * Now: only after the sync connection has been up again for 8 s, at most once per 30 s, max 3 times.
     */
    private void keepGroup(NowPlaying n) {
        long now = System.currentTimeMillis();
        String g = currentGroupId();
        if (!g.isEmpty() && "playing".equals(n.state)) { keptGroup = g; keptAt = now; return; }
        if (keptGroup.isEmpty() || !g.isEmpty()) return;
        boolean recent = now - keptAt < 90_000;
        boolean dropped = snap != null && now - snap.lastDrop < 45_000;
        boolean byUser = now - userLeftAt < 120_000 || breaks.active();
        if (!recent || !dropped || byUser || rejoins >= 3 || !snap.connected) { if (!recent) { keptGroup = ""; rejoins = 0; } return; }
        if (now - snap.connectedAt < 8_000 || now - lastRejoinAt < 30_000) return;   // let the connection settle first
        Site st = site;
        boolean stillPlaying = false;
        if (st != null) {
            Site.Group sg = st.group(keptGroup);
            if (sg != null) stillPlaying = "playing".equals(sg.state);
            Site.Room lead = st.room(keptGroup);
            if (lead != null) stillPlaying = lead.playing();
        }
        if (!stillPlaying) return;
        String target = keptGroup;
        rejoins++;
        keptAt = now;   // keep the memory of the group alive while we wait
        lastRejoinAt = now;
        Log.i("group: dropped from " + target + " after a connection hiccup – joining again (" + rejoins + ")");
        joinZone(target);
    }

    /** take this panel out of its group */
    public void leaveGroup() {
        userLeftAt = System.currentTimeMillis(); keptGroup = "";
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

    // ---------------- v1: groups, breaks, play-to ----------------
    /** join a saved group (this room becomes a member of its session / preset) */
    public void joinGroup(String groupId) {
        async("join " + groupId, () -> {
            if (breaks.active() && breaks.groupId().equals(groupId)) breaks.end(site, "joined by hand");
            ma.setMembers(groupId, new org.json.JSONArray().put(cfg.maPlayerId()), null);
        });
        lastSite = 0;
    }
    /** take a break from the group: mode = Breaks.TIME/MINE/GROUP_END */
    public void startBreak(String groupId, String mode, long untilMs, boolean quiet) {
        userLeftAt = System.currentTimeMillis(); keptGroup = "";
        async("break " + mode, () -> breaks.start(groupId, mode, untilMs, quiet));
        lastSite = 0;
    }
    public void endBreak() { async("rejoin now", () -> breaks.end(site, "rejoin now")); lastSite = 0; }
    public void extendBreak(long ms) { breaks.extend(ms); lastKey = ""; }
    /** play what this room plays on more rooms: this room leads, the others join */
    public void playOn(java.util.List<String> roomIds) {
        async("play on " + roomIds, () -> {
            org.json.JSONArray add = new org.json.JSONArray();
            for (String id : roomIds) if (!id.equals(cfg.maPlayerId())) add.put(id);
            if (add.length() > 0) ma.setMembers(cfg.maPlayerId(), add, null);
        });
        lastSite = 0;
    }
    // ---- who started the music (D-020): that panel may change the whole group's volume, the others only their own ----
    private volatile String originZone = "";
    private volatile long originIdleSince;
    /** call when THIS panel starts music on a zone (Music page, Share) */
    public void markOrigin(String zone) { originZone = zone; originIdleSince = 0; Log.i("origin: this panel started the music on " + zone); }
    public boolean isOrigin() {
        NowPlaying n = now;
        return n != null && !originZone.isEmpty() && (originZone.equals(n.groupId) || originZone.equals(cfg.maPlayerId()) && n.isLeader);
    }
    private void checkOrigin(NowPlaying n) {
        if (originZone.isEmpty()) return;
        boolean mine = originZone.equals(n.groupId) || originZone.equals(cfg.maPlayerId());
        if (mine && n.active()) { originIdleSince = 0; return; }
        long t = System.currentTimeMillis();
        if (originIdleSince == 0) originIdleSince = t;
        if (t - originIdleSince > 20_000) { originZone = ""; Log.i("origin: cleared (music ended / room left the group)"); }
    }

    public void setRoomVolume(String id, int v) { async("volume " + id + " " + v, () -> ma.volume(id, v)); lastSite = 0; }
    /** volume of the whole zone this room plays in */
    public void setGroupVolume(int v) {
        NowPlaying n = now; if (n == null) return;
        String g = n.groupId.isEmpty() ? cfg.maPlayerId() : n.groupId;
        if (g.equals(cfg.maPlayerId()) && !n.isLeader) { setVolume(v); return; }
        async("group volume " + v, () -> ma.groupVolume(g, v)); lastSite = 0;
    }
    /** change who plays together with this room (from the Group sheet) */
    public void applyGroup(java.util.List<String> add, java.util.List<String> remove) {
        NowPlaying n = now; if (n == null) return;
        String target = n.groupId.isEmpty() ? cfg.maPlayerId() : n.groupId;
        async("group +" + add + " -" + remove + " on " + target, () -> {
            org.json.JSONArray a = new org.json.JSONArray(), r = new org.json.JSONArray();
            for (String id : add) a.put(id);
            for (String id : remove) if (!id.equals(cfg.maPlayerId())) r.put(id);
            if (a.length() > 0 || r.length() > 0) ma.setMembers(target, a.length() > 0 ? a : null, r.length() > 0 ? r : null);
        });
        lastSite = 0;
    }
    /**
     * Share mode on/off (FIX-026): our sync player switches to PcmRelay on the alarm stream and the panel's
     * media volume goes to 0, so the shared app (YouTube …) is only heard as the synced copy, like every other room.
     */
    public void shareMode(boolean on) {
        if (snap.pipe == on) return;
        snap.pipe = on;
        PcmRelay.muted = false;
        volume.shareMode(on);
        if (cfg.player()) new Thread(() -> snap.restart(), "share-mode").start();
        Log.i("share mode " + (on ? "on" : "off"));
    }
    public boolean inShareMode() { return snap.pipe; }
    /** share fallback (capture needs this panel's speaker on): play the app directly here, mute our synced copy */
    public void shareDirect() {
        PcmRelay.muted = true;
        volume.shareDirect();
    }

    /** "Update to vX" on the panel screen */
    public void installUpdate() {
        String url = Updater.availableUrl;
        if (url.isEmpty()) return;
        io.execute(() -> Updater.download(this, url));
    }
    /** "Check for updates" in settings */
    public void checkUpdate(Runnable done) { io.execute(() -> { Updater.maybeCheck(this, cfg, true); if (done != null) main.post(done); }); }

    /** Music page: blocking library calls from the activity's worker thread */
    Ma api() { return ma; }
    /** start a song / album / playlist / radio on a room, a room's zone or a saved group */
    public void playMedia(String target, String uri) {
        markOrigin(target);
        async("play " + uri + " on " + target, () -> {
            if (breaks.active() && target.equals(breaks.groupId())) breaks.end(site, "played from panel");
            ma.cmd("player_queues/play_media", new org.json.JSONObject().put("queue_id", target).put("media", uri).put("option", "replace"));
        });
        lastSite = 0; lastKey = "";
    }

    /** join the music of another room / zone (its leader) or a saved group */
    public void joinZone(String leaderId) {
        Site st = site;
        if (st != null && st.group(leaderId) != null) { joinGroup(leaderId); return; }
        async("join " + leaderId, () -> ma.setMembers(leaderId, new org.json.JSONArray().put(cfg.maPlayerId()), null));
        lastSite = 0;
    }

    /** the group this room belongs to right now (saved group or a room leading/followed), "" = none */
    public String currentGroupId() { NowPlaying n = now; return n == null ? "" : n.groupId.equals(cfg.maPlayerId()) ? "" : n.groupId; }
    public String myId() { return cfg.maPlayerId(); }
    /** settings changed from the Setup app (network thread) */
    void reloadFromAnyThread() { main.post(() -> { ma.base = cfg.maBase(); reload(); }); }

    /** login with MA user/password, then swap for a long-lived token stored on the panel */
    /** result of the last panel sign-in, for the Settings screen / API ("music: ok · home: wrong password") */
    public volatile String loginResult = "";

    /**
     * Panel sign-in with ONE username + password: Music Assistant (rooms this user may play) and Home Assistant
     * (devices this user's panels may show, see Dash labels). Each side keeps only its own long-lived token.
     * which: "both" | "music" | "home"
     */
    public void login(String user, String pass, Runnable done) { login(user, pass, "both", done); }
    public void login(String user, String pass, String which, Runnable done) {
        io.execute(() -> {
            StringBuilder res = new StringBuilder();
            if (!which.equals("home")) {
                try {
                    Ma m = new Ma(cfg.maBase(), null);
                    m.login(user, pass, "AJ Panel " + cfg.name());
                    String t;
                    try { t = m.longToken("AJ Panel " + cfg.name() + " " + cfg.hostId()); }
                    catch (Exception e) { Log.e("long-lived token (using session token)", e); t = m.token; }
                    cfg.edit().putString("token", t).apply();
                    Log.i("logged in as " + user + " (long-lived token saved)");
                    res.append("Music: signed in");
                } catch (Exception e) { Log.e("login", e); res.append("Music: ").append(e.getMessage() == null ? "failed" : e.getMessage()); }
            }
            if (!which.equals("music")) {
                HaLogin.Result r = HaLogin.login(cfg.haUrl(), user, pass, cfg.name(), cfg.hostId());
                if (r.token != null) {
                    cfg.edit().putString("ha_token", r.token).putString("ha_user", user).apply();
                    Log.i("home assistant: signed in as " + user + " (long-lived token saved)");
                    if (res.length() > 0) res.append(" · ");
                    res.append("Home: signed in as ").append(user);
                } else {
                    Log.i("home assistant sign-in: " + r.error);
                    if (res.length() > 0) res.append(" · ");
                    res.append("Home: ").append(r.error);
                }
            }
            loginResult = res.toString();
            main.post(() -> { reload(); if (done != null) done.run(); });
        });
    }

    ZigbeeBridge zigbee;
    /** Home Assistant connection + generated dashboard (HomeActivity, /api/dashboard) */
    public DashHub hub;
    ShareBubble shareBubble;

    /** TEST: Zigbee radio -> TCP for Home Assistant ZHA (off unless zigbee_bridge is set) */
    private void applyZigbee() {
        String want = cfg.zigbeeBridge() ? cfg.zigbeeTty() + cfg.zigbeePort() + cfg.zigbeeAllow() : "";
        String have = zigbee == null ? "" : zigbee.sig();
        if (want.equals(have)) return;
        if (zigbee != null) { zigbee.stop(); zigbee = null; Log.i("zigbee bridge stopped"); }
        if (!want.isEmpty()) { ZigbeeBridge.nativeDir = getApplicationInfo().nativeLibraryDir; zigbee = new ZigbeeBridge(cfg.zigbeeTty(), cfg.zigbeePort(), cfg.zigbeeAllow()); zigbee.start(); }
    }

    @Override public void onDestroy() {
        running = false;
        if (zigbee != null) zigbee.stop();
        if (hub != null) hub.stop();
        if (poller != null) poller.interrupt();
        snap.stop();
        overlay.remove();
        shareBubble.remove();
        bridge.stop();
        http.stop();
        session.release();
        if (wifiLock.isHeld()) wifiLock.release();
        if (cpuLock.isHeld()) cpuLock.release();
        instance = null;
        super.onDestroy();
    }
}
