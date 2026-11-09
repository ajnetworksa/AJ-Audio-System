package com.ajnetwork.cxbridge;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** logcat tag "CXBridge" + last 200 lines kept in memory for the status screen */
public final class Log {
    static final String TAG = "CXBridge";
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static synchronized void add(String s) {
        lines.addFirst(fmt.format(new Date()) + "  " + s);
        while (lines.size() > 200) lines.removeLast();
    }
    public static void i(String s) { android.util.Log.i(TAG, s); add(s); }
    public static void e(String s, Throwable t) { android.util.Log.e(TAG, s, t); add("ERR " + s + ": " + t); }
    public static synchronized String dump() {
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        return b.toString();
    }
}
