package com.ajnetwork.ajpanel;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** logcat tag "AJPanel" + small in-memory ring shown on the status screen. */
public final class Log {
    public static final String TAG = "AJPanel";
    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static final SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private Log() { }
    public static synchronized void i(String m) {
        android.util.Log.i(TAG, m);
        ring.addLast(f.format(new Date()) + " " + m);
        while (ring.size() > 200) ring.removeFirst();
    }
    public static void e(String m, Throwable t) { i(m + ": " + t); android.util.Log.w(TAG, m, t); }
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : ring) sb.insert(0, s + "\n");
        return sb.toString();
    }
}
