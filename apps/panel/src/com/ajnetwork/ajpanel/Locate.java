package com.ajnetwork.ajpanel;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "Locate" from the Installer app: which panel on the wall is this one? The screen flashes green with the room
 * name and address (over any app, screen switched on) and the panel beeps a few times. Tap the screen or wait
 * 30 s to end it. Also clears itself when called with on=false.
 */
final class Locate {
    private static View view;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static volatile boolean beeping;

    static void start(Context c, String name, String ip, boolean sound) {
        h.post(() -> show(c.getApplicationContext(), name, ip));
        if (sound) beep();
        h.removeCallbacks(stopper);
        h.postDelayed(stopper, 30_000);
        Log.i("locate: showing " + name + " / " + ip);
    }

    static void stop() { h.post(Locate::hide); beeping = false; }

    private static final Runnable stopper = Locate::stop;

    private static void show(Context c, String name, String ip) {
        hide();
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) { Log.i("locate: no overlay permission – sound only"); return; }
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        TextView t1 = new TextView(c); t1.setText("Here!"); t1.setTextSize(64); t1.setTypeface(Typeface.DEFAULT_BOLD); t1.setTextColor(0xFF0B1220); t1.setGravity(Gravity.CENTER);
        TextView t2 = new TextView(c); t2.setText(name); t2.setTextSize(40); t2.setTypeface(Typeface.DEFAULT_BOLD); t2.setTextColor(0xFF0B1220); t2.setGravity(Gravity.CENTER);
        TextView t3 = new TextView(c); t3.setText(ip + "\ntap to close"); t3.setTextSize(22); t3.setTextColor(0xFF0B1220); t3.setGravity(Gravity.CENTER);
        v.addView(t1); v.addView(t2); v.addView(t3);
        v.setOnClickListener(x -> stop());
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        @SuppressWarnings("deprecation")
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED, PixelFormat.OPAQUE);
        try { wm.addView(v, p); view = v; } catch (Exception e) { Log.i("locate: " + e.getMessage()); return; }
        // flash: brand green <-> white, every 0.5 s
        final int[] k = {0};
        Runnable flash = new Runnable() {
            @Override public void run() {
                if (view != v) return;
                v.setBackgroundColor(k[0]++ % 2 == 0 ? 0xFF6BBC46 : 0xFFFFFFFF);
                h.postDelayed(this, 500);
            }
        };
        h.post(flash);
    }

    private static void hide() {
        View v = view; view = null;
        if (v == null) return;
        try { ((WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(v); } catch (Exception ignored) { }
    }

    /** 5 short double-beeps on the alarm channel (heard even when the music volume is down) */
    private static void beep() {
        if (beeping) return;
        beeping = true;
        new Thread(() -> {
            int rate = 44100;
            short[] tone = new short[(int) (rate * 0.6)];
            for (int i = 0; i < tone.length; i++) {
                double f = i < rate * 0.25 ? 1046.5 : i > rate * 0.32 ? 1568 : 0;
                double env = Math.min(1, (i % (rate / 4)) / (rate * 0.01));
                tone[i] = (short) (Math.sin(2 * Math.PI * f * i / rate) * env * 0.7 * 32767);
            }
            AudioTrack t = null;
            try {
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(tone.length * 2).build();
                t.play();
                short[] gap = new short[rate / 2];
                for (int n = 0; n < 5 && beeping; n++) { t.write(tone, 0, tone.length); t.write(gap, 0, gap.length); }
                Thread.sleep(300);
            } catch (Exception e) { Log.i("locate beep: " + e.getMessage()); }
            finally { if (t != null) { try { t.stop(); } catch (Exception ignored) { } t.release(); } beeping = false; }
        }, "aj-locate").start();
    }
}
