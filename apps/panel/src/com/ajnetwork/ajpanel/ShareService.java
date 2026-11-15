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
 * This panel keeps playing the app itself (no delay here); if it is part of the chosen saved group it takes
 * a break from that group while sharing, so nothing plays twice in this room.
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
        running = true; lastError = ""; sharingTo = label;
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

            audio = new AriaWs(cfg.host(), PORT, "/audio", s -> Log.i("share audio: " + s));
            meta = new AriaWs(cfg.host(), PORT, "/metadata", null);
            String target = route(ma, cfg);
            selectSource(ma, target);
            sendMeta(cfg, true);

            rec.startRecording();
            Log.i("share: streaming this panel's sound to " + sharingTo + " (" + target + ")");
            byte[] frame = new byte[FRAME];
            long lastMeta = System.currentTimeMillis();
            while (running) {
                int off = 0;
                while (off < FRAME && running) {
                    int n = rec.read(frame, off, FRAME - off);
                    if (n < 0) throw new IllegalStateException("capture error " + n);
                    off += n;
                }
                if (audio.isClosed()) throw new IllegalStateException("Music server closed the connection");
                audio.sendBinary(frame);
                if (System.currentTimeMillis() - lastMeta > 5000) { lastMeta = System.currentTimeMillis(); sendMeta(cfg, true); }
            }
        } catch (Exception e) {
            Log.e("share", e);
            if (running) fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** pick the player that plays the shared sound: the saved group, or the first chosen room leading the others */
    private String route(Ma ma, Cfg cfg) throws Exception {
        String group = prefs(this).getString("group", "");
        if (!group.isEmpty()) {
            Site st = Site.fetch(ma);
            Site.Group g = st.group(group);
            PanelService ps = PanelService.instance;
            if (g != null && g.members.contains(cfg.maPlayerId()) && ps != null) {
                // this room plays the app itself – step out of the group while sharing
                ps.breaks.start(group, Breaks.TIME, System.currentTimeMillis() + 12 * 3600_000L, true);
                prefs(this).edit().putBoolean("on_break", true).apply();
            }
            return group;
        }
        List<String> rooms = new ArrayList<>(Arrays.asList(prefs(this).getString("rooms", "").split(",")));
        rooms.remove(cfg.maPlayerId()); rooms.remove("");
        if (rooms.isEmpty()) throw new IllegalStateException("Pick at least one other room");
        String lead = rooms.get(0);
        if (rooms.size() > 1) {
            JSONArray add = new JSONArray();
            for (String r : rooms.subList(1, rooms.size())) add.put(r);
            ma.setMembers(lead, add, null);
        }
        return lead;
    }

    private void selectSource(Ma ma, String target) throws Exception {
        Object provs = ma.cmd("providers", new JSONObject());
        String inst = null;
        if (provs instanceof JSONArray) {
            JSONArray a = (JSONArray) provs;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && "ariacast_receiver".equals(p.optString("domain"))) inst = p.optString("instance_id");
            }
        }
        if (inst == null) throw new IllegalStateException("Add the \"AriaCast Receiver\" plugin in Music Assistant first");
        ma.cmd("players/cmd/select_source", new JSONObject().put("player_id", target).put("source", inst));
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
        running = false; sharingTo = ""; sharingWhat = "";
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
        if (prefs(this).getBoolean("on_break", false)) {
            prefs(this).edit().putBoolean("on_break", false).apply();
            PanelService ps = PanelService.instance;
            if (ps != null) ps.endBreak();
        }
        stopForeground(true);
    }

    @Override public void onDestroy() { stopAll(); super.onDestroy(); }
}
