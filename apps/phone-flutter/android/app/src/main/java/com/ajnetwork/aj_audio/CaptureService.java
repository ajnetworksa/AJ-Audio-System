package com.ajnetwork.aj_audio;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * "Play my phone": captures what other apps play on this phone (Android 10+ playback capture)
 * and streams it live to Music Assistant's AriaCast Receiver (ws://server:12889, 48 kHz stereo PCM,
 * 20 ms frames), then tells Music Assistant to play that source on the chosen room/group.
 * Apps that forbid capture (some video/DRM apps) stay silent – Android rule.
 */
@TargetApi(29)
public class CaptureService extends Service {
    static final String ACTION_START = "start", ACTION_STOP = "stop", ACTION_SWITCH = "switch";
    static final String TAG = "AJCapture";
    static volatile boolean running;
    static volatile String lastError = "";
    private static final int FRAME = 3840, PORT = 12889, NID = 7;

    private MediaProjection projection;
    private AudioRecord rec;
    private AriaWs audio, meta;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private volatile boolean mutedByUs, muteCheck;
    private long mutedAt;

    private static boolean isSilent(byte[] f) {
        for (int i = 0; i < f.length; i += 2) { int v = (short) ((f[i] & 0xFF) | (f[i + 1] << 8)); if (v > 30 || v < -30) return false; }
        return true;
    }

    /** mute / restore this phone's media volume (what was playing on the phone's own speaker) */
    private void mutePhone(boolean mute) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    mute ? android.media.AudioManager.ADJUST_MUTE : android.media.AudioManager.ADJUST_UNMUTE, 0);
            mutedByUs = mute;
            Capture.prefs(this).edit().putBoolean("muted_by_us", mute).apply();
            if (mute) { mutedAt = System.currentTimeMillis(); muteCheck = true; }
            Log.i(TAG, mute ? "phone muted" : "phone volume restored");
        } catch (Exception e) { Log.w(TAG, "mute", e); }
    }

    /** if the app was killed while the phone was muted, restore the volume next time */
    static void restoreIfNeeded(android.content.Context c) {
        if (!Capture.prefs(c).getBoolean("muted_by_us", false) || running) return;
        android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(AUDIO_SERVICE);
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0);
        Capture.prefs(c).edit().putBoolean("muted_by_us", false).apply();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i == null || ACTION_STOP.equals(i.getAction())) { stopAll(); stopSelf(); return START_NOT_STICKY; }
        if (ACTION_SWITCH.equals(i.getAction())) {
            // move the running stream to another room/group (picker) – no new consent needed
            new Thread(() -> {
                try {
                    selectSource(Capture.prefs(this).getString("server", ""), Capture.prefs(this).getString("token", ""), Capture.prefs(this).getString("target", ""));
                    getSystemService(NotificationManager.class).notify(NID, notification("Playing phone audio on " + Capture.prefs(this).getString("targetName", ""), "Tap Stop to end"));
                } catch (Exception e) { lastError = String.valueOf(e.getMessage()); }
            }, "aj-switch").start();
            return START_NOT_STICKY;
        }
        String name = Capture.prefs(this).getString("targetName", "music system");
        Notification n = notification("Playing phone audio on " + name, "Tap Stop to end");
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NID, n);
        if (running) stopAll();
        int code = i.getIntExtra("code", 0);
        Intent data = i.getParcelableExtra("data");
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = m.getMediaProjection(code, data);
        if (projection == null) { fail("Android did not allow capturing"); return START_NOT_STICKY; }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopAll(); stopSelf(); }
        }, null);
        running = true;
        lastError = "";
        wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aj:capture");
        wake.acquire(6 * 3600_000L);
        worker = new Thread(this::stream, "aj-capture");
        worker.start();
        return START_NOT_STICKY;
    }

    private void stream() {
        String server = Capture.prefs(this).getString("server", "");
        String token = Capture.prefs(this).getString("token", "");
        String target = Capture.prefs(this).getString("target", "");
        String host = Uri.parse(server).getHost();
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
            int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(Math.max(min, FRAME * 8))
                    .setAudioPlaybackCaptureConfig(cfg).build();

            // the music server must run the AriaCast Receiver plugin (port 12889)
            String inst = ariaInstance(server, token);
            try {
                audio = new AriaWs(host, PORT, "/audio", s -> Log.i(TAG, "audio: " + s));
                meta = new AriaWs(host, PORT, "/metadata", null);
                openStats(host);
            } catch (java.io.IOException e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("ECONNREFUSED") || m.contains("refused") || m.contains("timed out") || m.contains("failed to connect"))
                    throw new IllegalStateException("The music server's AriaCast Receiver isn't running (port 12889). In Music Assistant: Settings → Plugins → AriaCast Receiver – add it or check its error.");
                throw e;
            }
            // send sound right away (MA drops a source that sends nothing for 1 s), then point the room/group at it
            rec.startRecording();
            Thread pump = new Thread(this::pumpSafe, "aj-capture-pump");
            pump.start();
            boolean early = false;
            try { api(server, token, "players/cmd/select_source", new JSONObject().put("player_id", target).put("source", inst)); early = true; }
            catch (Exception e) { Log.i(TAG, "select before play not possible yet: " + e.getMessage()); }
            meta.sendText(new JSONObject().put("type", "update").put("data", new JSONObject()
                    .put("title", Build.MODEL + " audio").put("artist", "AJ Audio Client").put("is_playing", true)).toString());
            if (!early) {
                // first stream since the server started: the plugin only offers its source once it plays
                Exception last = null;
                for (int t = 0; t < 4 && running; t++) {
                    Thread.sleep(700);
                    try { api(server, token, "players/cmd/select_source", new JSONObject().put("player_id", target).put("source", inst)); last = null; break; }
                    catch (Exception e) { last = e; }
                }
                if (last != null) throw new IllegalStateException("The music server didn't start the sound: " + last.getMessage());
            }
            pump.join();
            if (pumpError != null) throw pumpError;
        } catch (Exception e) {
            Log.w(TAG, "stream", e);
            if (running) fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private volatile Exception pumpError;
    private void pumpSafe() {
        try { pump(); } catch (Exception e) { if (running) { pumpError = e; fail(e.getMessage() == null ? e.toString() : e.getMessage()); } }
    }

    private void pump() throws Exception {
        {
            byte[] frame = new byte[FRAME];
            long soundSince = 0, silentSince = 0;
            boolean muteOk = !Capture.prefs(this).getBoolean("mute_breaks_capture", false) && Capture.prefs(this).getBoolean("mute_phone", true);
            while (running) {
                int off = 0;
                while (off < FRAME && running) {
                    int n = rec.read(frame, off, FRAME - off);
                    if (n < 0) throw new IllegalStateException("capture error " + n);
                    off += n;
                }
                if (audio.isClosed()) throw new IllegalStateException("Music server closed the connection");
                if (dropBudget > 0) { dropBudget--; continue; }   // catch up: server queue too long
                audio.sendBinary(frame);
                // mute the phone's own speaker once real sound flows; if this phone then only captures
                // silence (its capture follows the volume), unmute again and remember that for next time
                boolean silent = isSilent(frame);
                long now = System.currentTimeMillis();
                if (muteOk && !mutedByUs && !silent) {
                    if (soundSince == 0) soundSince = now;
                    if (now - soundSince > 800) { mutePhone(true); silentSince = 0; }
                } else if (mutedByUs && muteCheck) {
                    if (silent) {
                        if (silentSince == 0) silentSince = now;
                        if (now - silentSince > 3000) {
                            mutePhone(false); muteOk = false; muteCheck = false;
                            Capture.prefs(this).edit().putBoolean("mute_breaks_capture", true).apply();
                            Log.w(TAG, "capture is silent while muted on this phone - leaving volume on");
                        }
                    } else if (silentSince == 0 && now - mutedAt > 3000) {
                        muteCheck = false;   // sound still flows while muted: muting is fine on this phone
                    } else { silentSince = 0; }
                }
            }
        }
    }

    private String ariaInstance(String server, String token) throws Exception {
        Object provs = api(server, token, "providers", new JSONObject());
        if (provs instanceof JSONArray) {
            JSONArray a = (JSONArray) provs;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && "ariacast_receiver".equals(p.optString("domain"))) return p.optString("instance_id");
            }
        }
        throw new IllegalStateException("The music server needs the \"AriaCast Receiver\" plugin: Music Assistant → Settings → Plugins → Add a plugin → AriaCast Receiver");
    }

    /** Music Assistant API: find the AriaCast plugin instance and select it as source on the target */
    private void selectSource(String server, String token, String target) throws Exception {
        Object provs = api(server, token, "providers", new JSONObject());
        String inst = null;
        if (provs instanceof JSONArray) {
            JSONArray a = (JSONArray) provs;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && "ariacast_receiver".equals(p.optString("domain"))) inst = p.optString("instance_id");
            }
        }
        if (inst == null) throw new IllegalStateException("Ask your installer to add the \"AriaCast Receiver\" plugin in Music Assistant");
        api(server, token, "players/cmd/select_source", new JSONObject().put("player_id", target).put("source", inst));
    }

    static Object api(String server, String token, String cmd, JSONObject args) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + "/api").openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(15000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        String body = new JSONObject().put("message_id", String.valueOf(System.nanoTime())).put("command", cmd).put("args", args).toString();
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close(); }
        String s = b.toString("UTF-8").trim();
        if (code >= 400) throw new IllegalStateException("Music server: " + code + " " + s);
        if (s.startsWith("[")) return new JSONArray(s);
        if (s.startsWith("{")) return new JSONObject(s);
        return s;
    }

    private void fail(String msg) {
        lastError = msg;
        Log.w(TAG, "failed: " + msg);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NID + 1, notification("Play my phone stopped", msg));
        stopAll();
        stopSelf();
    }

    private Notification notification(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("capture", "Play my phone", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "capture")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(running)
                .build();
    }

    private synchronized void stopAll() {
        boolean was = running;
        running = false;
        if (was && meta != null) {
            try {
                meta.sendText(new JSONObject().put("type", "update").put("data", new JSONObject().put("is_playing", false)).toString());
            } catch (Exception ignored) { }
        }
        if (mutedByUs) mutePhone(false);
        if (audio != null) audio.close();
        if (meta != null) meta.close();
        if (stats != null) { stats.close(); stats = null; }
        audio = null; meta = null;
        if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); rec = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) { } projection = null; }
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(true);
    }

    @Override public void onDestroy() { stopAll(); super.onDestroy(); }
    // ---- low latency: the AriaCast receiver keeps a queue of up to 100 frames (2 s). Any hiccup (Wi-Fi, CPU)
    // fills it and that delay would stay for the whole session. Its /stats socket reports the queue once a
    // second; when more than 3 frames (60 ms) wait, we skip that many frames so the delay shrinks back.
    private AriaWs stats;
    private volatile int dropBudget;
    static int queuedFrames(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"queuedFrames\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
    private void openStats(String host) {
        try { stats = new AriaWs(host, PORT, "/stats", s -> { int q = queuedFrames(s); if (q > 3) dropBudget = q - 2; }); }
        catch (Exception e) { stats = null; }   // older plugin without /stats: just no trimming
    }

}
