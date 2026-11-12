package com.ajnetwork.aj_audio;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/** Entry points for "Play my phone": settings, start (consent screen), stop, status. */
public final class Capture {
    private Capture() { }
    static final String PREFS = "aj_capture";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static void saveTarget(Context c, String server, String token, String target, String targetName) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (!server.isEmpty()) e.putString("server", server);
        if (!token.isEmpty()) e.putString("token", token);
        if (!target.isEmpty()) e.putString("target", target).putString("targetName", targetName);
        e.apply();
    }

    /** opens the (invisible) activity that asks Android for permission to capture audio */
    public static void start(Context c) {
        Intent i = new Intent(c, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, CaptureService.class).setAction(CaptureService.ACTION_STOP));
    }

    public static Map<String, Object> status(Context c) {
        Map<String, Object> m = new HashMap<>();
        m.put("running", CaptureService.running);
        m.put("target", prefs(c).getString("target", ""));
        m.put("targetName", prefs(c).getString("targetName", ""));
        m.put("error", CaptureService.lastError);
        return m;
    }
}
