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
    static final String ACTION_START = "start", ACTION_STOP = "stop";
    static final String TAG = "AJCapture";
    static volatile boolean running;
    static volatile String lastError = "";
    private static final int FRAME = 3840, PORT = 12889, NID = 7;

    private MediaProjection projection;
    private AudioRecord rec;
    private AriaWs audio, meta;
    private Thread worker;
    private PowerManager.WakeLock wake;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i == null || ACTION_STOP.equals(i.getAction())) { stopAll(); stopSelf(); return START_NOT_STICKY; }
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

            audio = new AriaWs(host, PORT, "/audio", s -> Log.i(TAG, "audio: " + s));
            meta = new AriaWs(host, PORT, "/metadata", null);
            // route the AriaCast source to the chosen room/group BEFORE announcing playback,
            // so Music Assistant doesn't start it on its default player
            selectSource(server, token, target);
            meta.sendText(new JSONObject().put("type", "update").put("data", new JSONObject()
                    .put("title", Build.MODEL + " audio").put("artist", "AJ Audio Client").put("is_playing", true)).toString());

            rec.startRecording();
            byte[] frame = new byte[FRAME];
            while (running) {
                int off = 0;
                while (off < FRAME && running) {
                    int n = rec.read(frame, off, FRAME - off);
                    if (n < 0) throw new IllegalStateException("capture error " + n);
                    off += n;
                }
                if (audio.isClosed()) throw new IllegalStateException("Music server closed the connection");
                audio.sendBinary(frame);
            }
        } catch (Exception e) {
            Log.w(TAG, "stream", e);
            if (running) fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
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

    private static Object api(String server, String token, String cmd, JSONObject args) throws Exception {
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
        if (audio != null) audio.close();
        if (meta != null) meta.close();
        audio = null; meta = null;
        if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); rec = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) { } projection = null; }
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(true);
    }

    @Override public void onDestroy() { stopAll(); super.onDestroy(); }
}
