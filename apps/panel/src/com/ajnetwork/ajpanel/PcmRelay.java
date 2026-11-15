package com.ajnetwork.ajpanel;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.InputStream;

/**
 * Share mode (FIX-026): snapclient writes its synced PCM to stdout (`--player file`), this plays it with our
 * own AudioTrack on the ALARM stream. That way the panel's media volume can be turned down to silence the
 * app being shared (YouTube …) on the panel itself, while the synced music – the same as in every other
 * room – still plays here.
 *
 * snapclient paces the data with the system clock, the AudioTrack runs on the sound chip's clock; to keep the
 * delay constant we hold the AudioTrack queue at TARGET_MS by dropping / repeating single frames (0.2 %).
 */
public class PcmRelay {
    public static final int RATE = 48000;
    public static final int TARGET_MS = 80;
    private static final int CHUNK = 1920;                // 10 ms of 48 kHz stereo 16-bit
    private static final int FRAME = 4;

    private AudioTrack track;
    private long written;                                 // frames handed to the track
    private volatile boolean running;
    /** share "direct" fallback: this panel plays the app itself, our synced copy stays silent */
    public static volatile boolean muted;
    private boolean appliedMute;

    /** total output delay (queue + hardware) in ms, for snapclient --latency */
    public static int outputLatencyMs() {
        int hw = 40;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack t = build(min * 2);
            java.lang.reflect.Method m = AudioTrack.class.getMethod("getLatency");
            int lat = (Integer) m.invoke(t);
            int buf = (min * 2 / FRAME) * 1000 / RATE;
            hw = Math.max(10, Math.min(200, lat - buf));
            t.release();
        } catch (Throwable ignored) { }
        return TARGET_MS + hw;
    }

    private static AudioTrack build(int bytes) {
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    /** blocking: plays the stream until it ends or stop() */
    public void run(InputStream in) {
        running = true;
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(min * 2, RATE * FRAME * (TARGET_MS * 3) / 1000);
        track = build(bytes);
        written = 0;
        fill(TARGET_MS);                       // start with the target queue so the delay is right from the first second
        track.play();
        byte[] buf = new byte[CHUNK + FRAME];
        long corr = 0;
        try {
            while (running) {
                int off = 0;
                while (off < CHUNK) { int n = in.read(buf, off, CHUNK - off); if (n < 0) return; off += n; }
                long queuedMs = (written - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL)) * 1000 / RATE;
                int len = CHUNK;
                if (written > RATE && queuedMs > TARGET_MS + 15) { len -= FRAME; corr--; }          // a bit late: drop one frame
                else if (written > RATE && queuedMs < TARGET_MS - 15) {                            // a bit early: repeat one frame
                    System.arraycopy(buf, CHUNK - FRAME, buf, CHUNK, FRAME); len += FRAME; corr++;
                }
                if (queuedMs > TARGET_MS + 250) continue;                                           // way behind (stall): skip 10 ms
                if (written > RATE && queuedMs < TARGET_MS - 50) fill((int) (TARGET_MS - queuedMs));   // ran dry: refill with silence
                if (muted != appliedMute) { appliedMute = muted; track.setVolume(muted ? 0f : 1f); }
                int w = track.write(buf, 0, len, AudioTrack.WRITE_NON_BLOCKING);
                if (w > 0) written += w / FRAME;
            }
        } catch (Exception e) {
            Log.e("pcm relay", e);
        } finally {
            Log.i("pcm relay stopped (drift corrections " + corr + ")");
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
            running = false;
        }
    }

    private void fill(int ms) {
        byte[] z = new byte[RATE * FRAME * ms / 1000 / FRAME * FRAME];
        int w = track.write(z, 0, z.length, AudioTrack.WRITE_NON_BLOCKING);
        if (w > 0) written += w / FRAME;
    }

    public void stop() { running = false; }
}
