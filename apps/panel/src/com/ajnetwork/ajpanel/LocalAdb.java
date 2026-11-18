package com.ajnetwork.ajpanel;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Silent self-updates on panels where Android refuses a "device owner" (Judian X8/P8: "there are already some
 * accounts on the device"): AJ Panel talks to the panel's own debug bridge (adbd, 127.0.0.1:5555 – already on for
 * deploying) and installs its update with "cmd package install" – no "Install?" dialog.
 * Needs ONE tap on the panel the first time: "Allow debugging?" → tick "Always allow" → Allow
 * (started by deploy_ajpanel / Installer Panel tools via pair()).
 */
final class LocalAdb {
    private static SharedPreferences p(Context c) { return c.getSharedPreferences("ajpanel_adb", Context.MODE_PRIVATE); }
    static boolean ok(Context c) { return p(c).getBoolean("ok", false); }
    static volatile String state = "";

    /** connect once so the panel shows "Allow debugging?" (waits up to 90 s for the tap) */
    static void pair(Context c) {
        new Thread(() -> {
            state = "waiting for Allow on the panel";
            Log.i("local adb: asking for permission – tap Allow (Always allow) on the panel");
            try (Adb a = new Adb("127.0.0.1", 5555, new File(c.getFilesDir(), "adb"), 90_000)) {
                String r = a.shell("echo ok").trim();
                boolean good = r.endsWith("ok");
                p(c).edit().putBoolean("ok", good).apply();
                state = good ? "allowed" : "unexpected answer";
                Log.i("local adb: " + state + " – updates install without a tap");
            } catch (Exception e) {
                state = "not allowed: " + e.getMessage();
                Log.i("local adb: " + state);
            }
        }, "aj-adb-pair").start();
    }

    /** install an APK over the panel's own adb; null = sent ok (the app restarts by itself), else the error */
    static String install(Context c, byte[] apk) {
        try (Adb a = new Adb("127.0.0.1", 5555, new File(c.getFilesDir(), "adb"), 5_000)) {
            Log.i("update: installing " + apk.length + " bytes over local adb (no tap needed)");
            String r = a.install(apk, "-r");
            if (r.contains("Success")) return null;
            return "adb install: " + r.trim();
        } catch (java.io.EOFException | java.net.SocketException e) {
            return null;   // our own process was replaced/killed by the install – that is success
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("allow")) p(c).edit().putBoolean("ok", false).apply();
            return "adb: " + e.getMessage();
        }
    }
}
