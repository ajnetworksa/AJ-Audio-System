package com.ajnetwork.ajpanel;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * "Play this panel's sound in other rooms" (FIX-025): captures what another app plays on this panel
 * (YouTube, Spotify, browser … – Android 10 playback capture; our own sync player is excluded) and streams
 * it live to Music Assistant's AriaCast Receiver, then plays that source on the chosen rooms / saved group.
 * FIX-026: this panel plays in sync with the others – it is part of the target, its own copy of the app is
 * turned down (media stream 0) and the synced music plays here via PcmRelay on the alarm stream.
 */
@TargetApi(29)
public class ShareService extends Service {
    static final String ACTION_STOP = "stop";
    private static final int FRAME = 3840, PORT = 12889, NID = 9;

    static volatile boolean running;
    static volatile String lastError = "", sharingTo = "", sharingWhat = "";

    private MediaProjection projection;
    private AudioRecord rec;
    private AriaWs audio, meta;
    private PowerManager.WakeLock wake;
    private volatile boolean here;          // this panel plays the shared sound too (share mode)

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("ajpanel_share", MODE_PRIVATE); }

    /** remember where to share; then ShareActivity gets Android's capture permission and starts this service */
    static void request(Context c, String groupId, List<String> rooms, String label) {
        prefs(c).edit().putString("group", groupId == null ? "" : groupId)
                .putString("rooms", android.text.TextUtils.join(",", rooms)).putString("label", label).apply();
        c.startActivity(new Intent(c, ShareActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    static void stop(Context c) { c.startService(new Intent(c, ShareService.class).setAction(ACTION_STOP)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i == null || ACTION_STOP.equals(i.getAction())) { stopAll(); stopSelf(); return START_NOT_STICKY; }
        String label = prefs(this).getString("label", "other rooms");
        startForeground(NID, notification("Playing this panel's sound in " + label), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if (running) stopAll();
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = m.getMediaProjection(i.getIntExtra("code", 0), i.getParcelableExtra("data"));
        if (projection == null) { fail("Android did not allow capturing the sound"); return START_NOT_STICKY; }
        projection.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { stopAll(); stopSelf(); } }, null);
        running = true; lastError = ""; sharingTo = label; pumpError = null; lastLoud = 0; direct = false;
        wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ajpanel:share");
        wake.acquire(12 * 3600_000L);
        new Thread(this::stream, "aj-share").start();
        return START_NOT_STICKY;
    }

    private void stream() {
        Cfg cfg = new Cfg(this);
        Ma ma = new Ma(cfg.maBase(), cfg.token());
        try {
            AudioPlaybackCaptureConfiguration cc = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(Process.myUid())          // never capture our own sync player (would loop)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
            int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(Math.max(min, FRAME * 8))
                    .setAudioPlaybackCaptureConfig(cc).build();

            // 1. the music server must have the AriaCast Receiver plugin – check BEFORE grouping anything
            String inst = ariaInstance(ma);
            step("connecting to the music server");
            audio = new AriaWs(cfg.host(), PORT, "/audio", s -> Log.i("share audio: " + s));
            meta = new AriaWs(cfg.host(), PORT, "/metadata", null);

            // 2. start sending sound right away (MA gives up on a source that sends nothing for 1 s)
            rec.startRecording();
            pump = new Thread(this::pumpLoop, "aj-share-pump");
            pump.start();

            // 3. group the rooms, then point them at this panel's sound
            step("grouping rooms");
            String target = route(ma, cfg);
            boolean early = false;
            try { ma.cmd("players/cmd/select_source", new JSONObject().put("player_id", target).put("source", inst)); early = true; }
            catch (Exception e) { Log.i("share: select before play not possible yet (" + e.getMessage() + ") – will select after start"); }
            sendMeta(cfg, true);
            if (!early) {
                // first share since the server started: the plugin only offers its source once it plays
                Exception last = null;
                for (int t = 0; t < 4 && running; t++) {
                    Thread.sleep(700);
                    try { ma.cmd("players/cmd/select_source", new JSONObject().put("player_id", target).put("source", inst)); last = null; break; }
                    catch (Exception e) { last = e; }
                }
                if (last != null) throw new IllegalStateException("the music server didn't start the shared sound: " + last.getMessage());
            }
            step("");
            Log.i("share: streaming this panel's sound to " + sharingTo + " (" + target + ")");

            // 4. watch: metadata every 5 s; does capture still work with this panel's own speaker turned down?
            long started = System.currentTimeMillis(), lastMeta = started;
            boolean checked = !here;
            while (running) {
                Thread.sleep(250);
                if (pumpError != null) throw pumpError;
                long now = System.currentTimeMillis();
                if (!checked) {
                    if (lastLoud > started) checked = true;
                    else if (now - started > 5000 && MediaListener.playing(this) != null) {
                        // this panel's Android only captures what it can hear: keep its speaker on, mute our synced copy instead
                        checked = true;
                        PanelService ps = PanelService.instance;
                        if (ps != null) ps.shareDirect();
                        direct = true;
                        Log.i("share: capture silent with media volume 0 – this panel plays the app directly");
                    } else if (now - started > 20000) checked = true;
                }
                if (now - lastMeta > 5000) { lastMeta = now; sendMeta(cfg, true); }
            }
        } catch (Exception e) {
            Log.e("share", e);
            if (running) fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private volatile Thread pump;
    private volatile List<String> addedRooms;
    private volatile Exception pumpError;
    private volatile long lastLoud;
    static volatile String step = "";
    static volatile boolean direct;
    private static void step(String s) { step = s; }

    /** reads the captured sound and sends it, 20 ms frames */
    private void pumpLoop() {
        byte[] frame = new byte[FRAME];
        try {
            while (running) {
                int off = 0;
                while (off < FRAME && running) {
                    int n = rec.read(frame, off, FRAME - off);
                    if (n < 0) throw new IllegalStateException("capture error " + n);
                    off += n;
                }
                if (!running) break;
                if (audio.isClosed()) throw new IllegalStateException("the music server closed the connection");
                audio.sendBinary(frame);
                if (!silent(frame)) lastLoud = System.currentTimeMillis();
            }
        } catch (Exception e) { if (running) pumpError = e; }
    }

    private String ariaInstance(Ma ma) throws Exception {
        Object provs = ma.cmd("providers", new JSONObject());
        if (provs instanceof JSONArray) {
            JSONArray a = (JSONArray) provs;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && "ariacast_receiver".equals(p.optString("domain"))) return p.optString("instance_id");
            }
        }
        throw new IllegalStateException("the music server needs the \"AriaCast Receiver\" plugin (Music Assistant → Settings → Providers → Add → AriaCast Receiver)");
    }

    private static boolean silent(byte[] f) {
        for (int i = 0; i < f.length; i += 2) { int v = (short) ((f[i] & 0xFF) | (f[i + 1] << 8)); if (v > 30 || v < -30) return false; }
        return true;
    }

    /** pick the player that plays the shared sound: the saved group, or the first chosen room leading the others */
    private String route(Ma ma, Cfg cfg) throws Exception {
        String group = prefs(this).getString("group", "");
        String me = cfg.maPlayerId();
        PanelService ps = PanelService.instance;
        if (!group.isEmpty()) {
            Site.Group g = Site.fetch(ma).group(group);
            boolean inIt = g != null && g.members.contains(me);
            if (inIt && ps != null) {
                if (ps.breaks.active() && group.equals(ps.breaks.groupId())) ps.breaks.end(null, "sharing to this group");
                ps.shareMode(true);
            }
            here = inIt;
            return group;
        }
        // this room leads, the picked rooms follow – all in sync
        List<String> rooms = new ArrayList<>(Arrays.asList(prefs(this).getString("rooms", "").split(",")));
        rooms.remove(me); rooms.remove("");
        if (rooms.isEmpty()) throw new IllegalStateException("Pick at least one other room");
        JSONArray add = new JSONArray();
        for (String r : rooms) add.put(r);
        ma.setMembers(me, add, null);
        addedRooms = rooms;
        if (ps != null) ps.shareMode(true);
        here = true;
        return me;
    }

    private String lastMetaSig = "";
    private void sendMeta(Cfg cfg, boolean playing) throws Exception {
        MediaListener.Local l = MediaListener.playing(this);
        String room = cfg.name();
        String title = l != null && !l.title.isEmpty() ? l.title : (l != null ? l.app : "Panel sound") + " · " + room;
        String artist = l != null && !l.title.isEmpty() ? (l.artist.isEmpty() ? l.app : l.artist) : "from " + room;
        sharingWhat = l == null ? "" : (l.title.isEmpty() ? l.app : l.title);
        String sig = title + artist + playing;
        if (sig.equals(lastMetaSig) && playing) return;
        lastMetaSig = sig;
        meta.sendText(new JSONObject().put("type", "update").put("data", new JSONObject()
                .put("title", title).put("artist", artist).put("is_playing", playing)).toString());
    }

    private void fail(String msg) {
        lastError = msg;
        // undo the grouping we did for this share
        List<String> added = addedRooms; addedRooms = null;
        if (added != null && !added.isEmpty()) new Thread(() -> {
            try {
                Cfg cfg = new Cfg(this);
                JSONArray r = new JSONArray(); for (String x : added) r.put(x);
                new Ma(cfg.maBase(), cfg.token()).setMembers(cfg.maPlayerId(), null, r);
            } catch (Exception e) { Log.e("share: ungroup after failure", e); }
        }, "share-undo").start();
        Log.i("share stopped: " + msg);
        getSystemService(NotificationManager.class).notify(NID + 1, notification("Sharing stopped: " + msg));
        stopAll();
        stopSelf();
    }

    private Notification notification(String title) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("share", "Share panel sound", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, ShareService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "share").setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title).addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }

    private synchronized void stopAll() {
        boolean was = running;
        running = false; sharingTo = ""; sharingWhat = ""; step = ""; direct = false;
        if (was && meta != null) {
            try { meta.sendText(new JSONObject().put("type", "update").put("data", new JSONObject().put("is_playing", false)).toString()); }
            catch (Exception ignored) { }
        }
        if (audio != null) audio.close();
        if (meta != null) meta.close();
        audio = null; meta = null;
        if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); rec = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) { } projection = null; }
        if (wake != null && wake.isHeld()) wake.release();
        PanelService ps = PanelService.instance;
        if (ps != null) ps.shareMode(false);
        here = false;
        stopForeground(true);
    }

    @Override public void onDestroy() { stopAll(); super.onDestroy(); }
}
