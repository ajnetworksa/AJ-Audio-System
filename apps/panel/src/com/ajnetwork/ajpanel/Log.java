package com.ajnetwork.ajpanel;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** logcat tag "AJPanel" + in-memory ring + last crash on disk (all served to the Installer app by PanelHttp /api/log). */
public final class Log {
    public static final String TAG = "AJPanel";
    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static final SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static File crashFile;
    private Log() { }
    public static synchronized void i(String m) {
        android.util.Log.i(TAG, m);
        ring.addLast(f.format(new Date()) + " " + m);
        while (ring.size() > 600) ring.removeFirst();
    }
    public static void e(String m, Throwable t) { i(m + ": " + t); android.util.Log.w(TAG, m, t); }
    /** newest first */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : ring) sb.insert(0, s + "\n");
        return sb.toString();
    }

    /** keep the stack trace of a crash, so the installer can read it after the app restarted */
    public static void installCrashHandler(Context c) {
        if (crashFile != null) return;
        crashFile = new File(c.getFilesDir(), "last_crash.txt");
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            try (FileWriter w = new FileWriter(crashFile)) {
                StringWriter sw = new StringWriter(); ex.printStackTrace(new PrintWriter(sw));
                w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " thread " + t.getName() + "\n" + sw + "\n--- log before the crash ---\n" + dump());
            } catch (Throwable ignored) { }
            if (old != null) old.uncaughtException(t, ex);
        });
    }
    public static String lastCrash() {
        if (crashFile == null || !crashFile.exists()) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(crashFile))) {
            String l; int n = 0;
            while ((l = r.readLine()) != null && n++ < 400) sb.append(l).append('\n');
        } catch (Exception ignored) { }
        return sb.toString();
    }
    /** this app's own Android log (errors of the sync player, system messages about us) */
    public static String logcat(int lines) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", String.valueOf(lines), "*:W", TAG + ":I", "libEGL:S", "mali_winsys:S").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
        } catch (Exception e) { sb.append("logcat: ").append(e); }
        return sb.toString();
    }
}
