package com.ajnetwork.cxbridge;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

/**
 * Decodes one audio URL (http/https/file) with Android's MediaCodec, converts it to
 * 16-bit stereo at the server rate, and feeds the SnapServer in real time (20 ms chunks).
 * When nothing is playing, silence keeps flowing so clients stay locked to the clock.
 */
public class GroupPlayer {
    public enum State { STOPPED, PLAYING, PAUSED, TRANSITIONING }

    private final SnapServer server;
    private final int rate, chunkFrames;
    private volatile State state = State.STOPPED;
    private volatile String url, title = "";
    private volatile int volume = 100;            // 0..100 software gain
    private volatile boolean muted;
    private volatile long durationUs, positionUs, seekToUs = -1;
    private volatile boolean running;
    private Thread decodeThread, pacerThread;
    private int generation;

    // ring buffer of interleaved stereo shorts, ~8 s
    private final short[] ring;
    private int rHead, rCount;
    private final Object lock = new Object();

    public GroupPlayer(SnapServer s) {
        server = s; rate = s.rate; chunkFrames = rate / 50;
        ring = new short[rate * 2 * 8];
    }

    public State state() { return state; }
    public String url() { return url; }
    public String title() { return title; }
    public long durationMs() { return durationUs / 1000; }
    public long positionMs() { return positionUs / 1000; }
    public int volume() { return volume; }
    public void setVolume(int v) { volume = Math.max(0, Math.min(100, v)); }
    public boolean muted() { return muted; }
    public void setMuted(boolean m) { muted = m; }

    public void startPacer() {
        running = true;
        pacerThread = new Thread(this::pacerLoop, "group-pacer");
        pacerThread.setPriority(Thread.MAX_PRIORITY);
        pacerThread.start();
    }

    public void shutdown() { running = false; stop(); }

    public synchronized void setUri(String u, String t) {
        stop();
        url = u; title = t == null ? "" : t; durationUs = 0; positionUs = 0;
        Log.i("group: new source " + title + "  " + u);
    }

    public synchronized void play() {
        if (url == null) return;
        if (state == State.PAUSED) { state = State.PLAYING; return; }
        if (state == State.PLAYING) return;
        state = State.TRANSITIONING;
        final int gen = ++generation;
        final String u = url;
        decodeThread = new Thread(() -> decode(u, gen), "group-decode");
        decodeThread.start();
    }

    public synchronized void pause() { if (state == State.PLAYING || state == State.TRANSITIONING) state = State.PAUSED; }

    public synchronized void stop() {
        generation++;
        state = State.STOPPED;
        synchronized (lock) { rHead = 0; rCount = 0; lock.notifyAll(); }
        positionUs = 0;
    }

    public void seekMs(long ms) { seekToUs = ms * 1000; synchronized (lock) { rHead = 0; rCount = 0; lock.notifyAll(); } }

    // ------------------------------------------------------------------ decoder
    private void decode(String u, int gen) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            HashMap<String, String> hdr = new HashMap<>();
            hdr.put("User-Agent", "ControlXBridge/2.0");
            if (u.startsWith("http")) ex.setDataSource(u, hdr); else ex.setDataSource(u);
            int track = -1; MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IllegalStateException("no audio track");
            ex.selectTrack(track);
            durationUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            int inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), inCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            Log.i("group: decoding " + fmt.getString(MediaFormat.KEY_MIME) + " " + inRate + " Hz " + inCh + " ch, " + (durationUs / 1000000) + " s");
            if (gen == generation && state == State.TRANSITIONING) state = State.PLAYING;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false;
            double srcPos = 0;                    // fractional read position for the resampler
            while (gen == generation) {
                if (seekToUs >= 0) { ex.seekTo(seekToUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC); codec.flush(); inEos = false; positionUs = seekToUs; seekToUs = -1; }
                if (!inEos) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int n = ex.readSampleData(ib, 0);
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                        else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); inCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        short[] in = new short[info.size / 2];
                        ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(in);
                        int frames = in.length / inCh;
                        // linear resample to server rate, mono -> stereo, multichannel -> first two
                        double step = (double) inRate / rate;
                        short[] out = new short[((int) (frames / step) + 4) * 2];
                        int o = 0, rc = inCh > 1 ? 1 : 0;
                        while (srcPos < frames && o + 1 < out.length) {
                            int i0 = (int) srcPos; double fr = srcPos - i0;
                            short l0 = in[i0 * inCh], r0 = in[i0 * inCh + rc];
                            short l1 = i0 + 1 < frames ? in[(i0 + 1) * inCh] : l0;
                            short r1 = i0 + 1 < frames ? in[(i0 + 1) * inCh + rc] : r0;
                            out[o++] = (short) (l0 + (l1 - l0) * fr);
                            out[o++] = (short) (r0 + (r1 - r0) * fr);
                            srcPos += step;
                        }
                        srcPos = Math.max(0, srcPos - frames);
                        write(out, o, gen);
                        positionUs = info.presentationTimeUs;
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            // let the buffer drain, then report stopped
            while (gen == generation) { synchronized (lock) { if (rCount == 0) break; } Thread.sleep(50); }
            if (gen == generation) { state = State.STOPPED; positionUs = 0; Log.i("group: end of track"); }
        } catch (Exception e) {
            Log.e("group decode " + u, e);
            if (gen == generation) state = State.STOPPED;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    /** blocking write into the ring (waits while full = natural back-pressure) */
    private void write(short[] s, int n, int gen) throws InterruptedException {
        int i = 0;
        synchronized (lock) {
            while (i < n && gen == generation) {
                while (rCount >= ring.length && gen == generation) lock.wait(100);
                if (gen != generation) return;
                int tail = (rHead + rCount) % ring.length;
                ring[tail] = s[i++]; rCount++;
            }
        }
    }

    // ------------------------------------------------------------------ real-time pacer
    private void pacerLoop() {
        final int samples = chunkFrames * 2;
        final long chunkUs = 20_000;
        short[] buf = new short[samples];
        byte[] pcm = new byte[samples * 2];
        long nextUs = server.nowUs() + 200_000;   // first chunk timestamp
        while (running) {
            try {
                long now = server.nowUs();
                long ahead = nextUs - now;
                // keep about 100 ms ahead of real time
                if (ahead > 100_000) { Thread.sleep((ahead - 100_000) / 1000 + 1); continue; }
                if (ahead < -500_000) nextUs = now;            // we fell far behind: resync
                int got = 0;
                if (state == State.PLAYING) {
                    synchronized (lock) {
                        got = Math.min(samples, rCount);
                        for (int i = 0; i < got; i++) { buf[i] = ring[rHead]; rHead = (rHead + 1) % ring.length; }
                        rCount -= got;
                        lock.notifyAll();
                    }
                }
                float gain = muted ? 0f : (volume / 100f) * (volume / 100f);
                for (int i = 0; i < samples; i++) {
                    int v = i < got ? (int) (buf[i] * gain) : 0;
                    pcm[2 * i] = (byte) v; pcm[2 * i + 1] = (byte) (v >> 8);
                }
                server.broadcast(nextUs, pcm, pcm.length);
                nextUs += chunkUs;
            } catch (InterruptedException e) { break; }
            catch (Exception e) { Log.e("pacer", e); }
        }
    }
}
