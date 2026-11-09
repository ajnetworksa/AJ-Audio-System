package com.ajnetwork.ajaudio;

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
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Captures the audio other apps are playing on this phone (Android 10+ playback capture),
 * encodes it to AAC (ADTS) and serves it as a live stream at http://<phone-ip>:8765/live.aac.
 * Then it tells Music Assistant to play that URL on the chosen room or group.
 */
public class CaptureService extends Service {
    static final String TAG = "AJAudio";
    public static final int PORT = 8765;
    public static volatile boolean running;
    public static volatile String status = "";

    private MediaProjection projection;
    private AudioRecord rec;
    private MediaCodec enc;
    private ServerSocket server;
    private final List<BlockingQueue<byte[]>> clients = new CopyOnWriteArrayList<>();
    private Ma ma;
    private String target, targetName;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && "STOP".equals(i.getAction())) { stopAll(true); stopSelf(); return START_NOT_STICKY; }
        startFg();
        if (running || i == null) return START_NOT_STICKY;
        target = i.getStringExtra("target"); targetName = i.getStringExtra("targetName");
        ma = new Ma(i.getStringExtra("base"), i.getStringExtra("token"));
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(i.getIntExtra("code", 0), (Intent) i.getParcelableExtra("data"));
        if (projection == null) { status = "capture permission was not granted"; stopSelf(); return START_NOT_STICKY; }
        running = true;
        new Thread(this::run, "capture").start();
        return START_NOT_STICKY;
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("aj", "AJ Audio", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, CaptureService.class).setAction("STOP");
        PendingIntent ps = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "aj")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("Playing phone audio on " + (targetName == null ? "speakers" : targetName))
                .setContentText("Tap to open AJ Audio")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", ps).build())
                .setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private void run() {
        try {
            server = new ServerSocket(PORT);
            server.setReuseAddress(true);
            new Thread(this::acceptLoop, "http").start();

            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
            int min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(Math.max(min * 4, 48000))
                    .setAudioPlaybackCaptureConfig(cfg).build();

            MediaFormat mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2);
            mf.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            mf.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
            mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            rec.startRecording();

            String url = "http://" + myIp() + ":" + PORT + "/live.aac";
            status = "streaming " + url;
            Log.i(TAG, status);
            // tell Music Assistant to play our live stream on the target room/group
            new Thread(() -> {
                try { Thread.sleep(800); ma.playUrl(target, url); status = "playing on " + targetName; }
                catch (Exception e) { status = "Music Assistant refused: " + e.getMessage(); Log.e(TAG, "play_media", e); }
            }).start();

            byte[] pcm = new byte[4096];
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long frames = 0;
            while (running) {
                int n = rec.read(pcm, 0, pcm.length);
                if (n <= 0) continue;
                int ii = enc.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer b = enc.getInputBuffer(ii); b.clear(); b.put(pcm, 0, n);
                    enc.queueInputBuffer(ii, 0, n, frames * 1_000_000L / 48000, 0);
                    frames += n / 4;
                }
                int oi;
                while ((oi = enc.dequeueOutputBuffer(bi, 0)) >= 0) {
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) {
                        ByteBuffer ob = enc.getOutputBuffer(oi);
                        byte[] f = new byte[bi.size + 7];
                        adts(f, f.length);
                        ob.position(bi.offset); ob.get(f, 7, bi.size);
                        for (BlockingQueue<byte[]> q : clients) if (!q.offer(f)) { q.clear(); q.offer(f); }
                    }
                    enc.releaseOutputBuffer(oi, false);
                }
            }
        } catch (Exception e) {
            status = "capture error: " + e.getMessage();
            Log.e(TAG, "capture", e);
        }
        stopAll(false);
    }

    /** ADTS header for AAC-LC, 48 kHz, stereo */
    private static void adts(byte[] p, int len) {
        int profile = 2, freqIdx = 3, chan = 2;
        p[0] = (byte) 0xFF; p[1] = (byte) 0xF1;
        p[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chan >> 2));
        p[3] = (byte) (((chan & 3) << 6) + (len >> 11));
        p[4] = (byte) ((len & 0x7FF) >> 3);
        p[5] = (byte) (((len & 7) << 5) + 0x1F);
        p[6] = (byte) 0xFC;
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                new Thread(() -> serve(s), "client").start();
            } catch (Exception e) { if (running) Log.e(TAG, "accept", e); }
        }
    }

    private void serve(Socket s) {
        BlockingQueue<byte[]> q = new ArrayBlockingQueue<>(200);
        try {
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            byte[] req = new byte[2048]; int got = 0;
            while (got < req.length) { int n = in.read(req, got, req.length - got); if (n <= 0) break; got += n; if (new String(req, 0, got).contains("\r\n\r\n")) break; }
            String head = new String(req, 0, got);
            OutputStream os = s.getOutputStream();
            if (head.startsWith("HEAD")) {
                os.write("HTTP/1.0 200 OK\r\nContent-Type: audio/aac\r\nicy-name: AJ Audio phone\r\n\r\n".getBytes()); os.flush(); s.close(); return;
            }
            os.write("HTTP/1.0 200 OK\r\nContent-Type: audio/aac\r\nCache-Control: no-cache\r\nicy-name: AJ Audio phone\r\nConnection: close\r\n\r\n".getBytes());
            os.flush();
            clients.add(q);
            Log.i(TAG, "listener connected: " + s.getInetAddress());
            while (running) {
                byte[] f = q.poll(500, TimeUnit.MILLISECONDS);
                if (f != null) os.write(f);
            }
        } catch (Exception e) { Log.i(TAG, "listener left: " + e.getMessage()); }
        clients.remove(q);
        try { s.close(); } catch (Exception ignored) {}
    }

    private void stopAll(boolean tellMa) {
        boolean was = running;
        running = false;
        if (tellMa && was && ma != null && target != null) new Thread(() -> { try { ma.stop(target); } catch (Exception ignored) {} }).start();
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) {}
        try { if (enc != null) { enc.stop(); enc.release(); } } catch (Exception ignored) {}
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        rec = null; enc = null; server = null; projection = null;
        if (!status.startsWith("capture error") && !status.startsWith("Music Assistant refused")) status = "stopped";
        stopForeground(true);
    }

    @Override public void onDestroy() { stopAll(false); super.onDestroy(); }

    static String myIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }
}
