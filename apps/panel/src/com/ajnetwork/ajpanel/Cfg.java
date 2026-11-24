package com.ajnetwork.ajpanel;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import java.net.NetworkInterface;
import java.util.Collections;

/** All panel settings in one place (SharedPreferences "ajpanel"). */
public class Cfg {
    private final SharedPreferences p;
    private final Context c;
    public Cfg(Context c) { this.c = c.getApplicationContext(); p = this.c.getSharedPreferences("ajpanel", Context.MODE_PRIVATE); }

    public String host()       { return p.getString("host", "192.168.8.5"); }
    public int maPort()        { return p.getInt("ma_port", 8095); }
    public int snapPort()      { return p.getInt("snap_port", 1704); }
    public String token()      { return p.getString("token", ""); }
    public String name()       { return p.getString("name", Build.MODEL); }
    public String engine()     { return p.getString("engine", "oboe"); }      // oboe | opensl
    public boolean resample()  { return p.getBoolean("resample", true); }
    public int latencyMs()     { return p.getInt("latency", 0); }            // per-panel trim (snapclient --latency)
    public boolean player()    { return p.getBoolean("player", true); }      // run embedded sync player
    /** volume = the panel's real media volume (synced with MA both ways); false = old software volume in snapclient */
    public boolean systemVolume() { return p.getBoolean("system_volume", true); }
    /** Judian panels keep their own smart-home UI: no floating card by default, song goes to their player instead */
    public boolean overlay()   { return p.getBoolean("overlay", !DlnaMetaBridge.available(c)); }
    /** push now-playing into the panel's own music player (Judian) */
    public boolean vendorBridge() { return p.getBoolean("vendor_bridge", DlnaMetaBridge.available(c)); }
    /** GitHub repo with the releases (app updates) */
    public String repo()       { return p.getString("repo", "ajnetworksa/AJ-Audio-System"); }
    /** TEST: pass the built-in Zigbee radio to Home Assistant ZHA over TCP (see zigbee.md) */
    public boolean zigbeeBridge() { return p.getBoolean("zigbee_bridge", false); }
    public int zigbeePort()     { return p.getInt("zigbee_port", 6638); }
    public String zigbeeTty()   { return p.getString("zigbee_tty", "/dev/ttyS3"); }
    /** radio serial speed / flow control (radio firmware change only; normal = 115200 + rtscts) */
    public int zigbeeBaud()     { int b = p.getInt("zigbee_baud", 115200); return b > 0 ? b : 115200; }
    public boolean zigbeeRtsCts() { return !"none".equals(p.getString("zigbee_flow", "rtscts")); }
    /** only this address may connect (default: the music server = Home Assistant); "any" = everyone on the network, "ha"/empty = default */
    public String zigbeeAllow() { String a = p.getString("zigbee_allow", ""); return a.equals("any") ? "" : a.isEmpty() || a.equals("ha") ? host() : a; }
    /** floating Share button over other apps (YouTube …) */
    public boolean shareBubble() { return p.getBoolean("share_bubble", true); }
    public int shareBubbleY()    { return p.getInt("share_bubble_y", -1); }
    /** admin token (from the Installer) used ONLY to reload a blocked AriaCast Receiver – see AriaHeal */
    public String repairToken() { return p.getString("repair_token", ""); }
    /** automatic updates from GitHub: on/off, time window "HH-HH" (panel's local time), days "1234567" (1 = Monday) */
    public boolean autoUpdate()  { return p.getBoolean("auto_update", true); }
    public String updateWindow() { return p.getString("update_window", "03-05"); }
    public String updateDays()   { return p.getString("update_days", "1234567"); }
    /** wait this many hours after a release is published before installing it automatically (time to test it first) */
    public int updateDelayH()    { return p.getInt("update_delay_h", 0); }
    public String playerIdOverride() { return p.getString("ma_player_id", ""); }

    public String maBase() { return "http://" + host() + ":" + maPort(); }

    // ------------------------------------------------------------------ smart-home dashboard (HomeActivity)
    /** Home Assistant address; default = the music server (same Pi) on 8123 */
    public String haUrl()   { String u = p.getString("ha_url", ""); return u.isEmpty() ? "http://" + host() + ":8123" : u; }
    /** HA long-lived access token (typed/pasted on the panel or sent by the Installer) – never logged */
    /** Home Assistant user this panel signed in as (labels "AJ <user>" decide what it shows) */
    public String haUser() { return p.getString("ha_user", ""); }
    public String haUrlRaw() { return p.getString("ha_url", ""); }
    public String haToken() { return p.getString("ha_token", ""); }
    private JSONObjectSafe json(String k) { return new JSONObjectSafe(p.getString(k, "")); }
    /** this panel's dashboard settings (see Dash) – a fresh copy every call */
    public org.json.JSONObject panelData() { return json("dash_panel").o; }
    /** site-wide settings: owner name, defaults */
    public org.json.JSONObject siteData()  { return json("dash_site").o; }
    private String pick(String key, String def) {
        String v = panelData().optString(key, ""); if (!v.isEmpty()) return v;
        v = siteData().optString(key, ""); return v.isEmpty() ? def : v;
    }
    /** en | ar */
    public String lang()  { return pick("lang", "en"); }
    /** auto | graphite | pearl */
    public String look()  { return pick("look", "auto"); }
    public String ownerEn() { return siteData().optString("owner_en", ""); }
    public String ownerAr() { return siteData().optString("owner_ar", ""); }
    /** show the owner's name on this panel (header, splash, screensaver) */
    public boolean ownerShow() { return panelData().optBoolean("owner_show", false); }
    /** owner name in the panel's language (falls back to the other one) */
    public String owner() { String a = ownerAr(), e = ownerEn(); return "ar".equals(lang()) ? (a.isEmpty() ? e : a) : (e.isEmpty() ? a : e); }
    /** minutes without touch before the screensaver; 0 = never */
    public int saverMin() { return panelData().optInt("saver_min", 3); }
    /** AJ Panel is the home screen with the dashboard (false = old music screen only) */
    public boolean dashboard() { return p.getBoolean("dashboard", true); }

    // ------------------------------------------------------------------ PINs (stored as salted SHA-256, never in clear)
    public static final String OWNER = "pin_owner", INSTALLER = "pin_installer";
    public boolean hasPin(String which) { return !p.getString(which, "").isEmpty(); }
    public void setPin(String which, String pin) { p.edit().putString(which, pin == null || pin.isEmpty() ? "" : hash(pin)).apply(); }
    /** installer PIN opens everything; owner PIN opens owner things */
    public boolean checkPin(String which, String pin) {
        String h = hash(pin);
        if (h.equals(p.getString(INSTALLER, ""))) return true;
        return which.equals(OWNER) && h.equals(p.getString(OWNER, ""));
    }
    private String hash(String pin) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest((hostId() + ":" + pin).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(); for (byte x : d) b.append(String.format("%02x", x)); return b.toString();
        } catch (Exception e) { return ""; }
    }

    static final class JSONObjectSafe {
        final org.json.JSONObject o;
        JSONObjectSafe(String s) { org.json.JSONObject x; try { x = s == null || s.isEmpty() ? new org.json.JSONObject() : new org.json.JSONObject(s); } catch (Exception e) { x = new org.json.JSONObject(); } o = x; }
    }

    /** stable id sent to the server as snapclient --hostID; MA turns it into player id "ma_" + id */
    public String hostId() {
        String id = p.getString("host_id", null);
        if (id == null) {
            String mac = mac();
            if (mac == null) {
                String aid = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
                mac = aid == null ? Long.toHexString(System.nanoTime()) : aid;
            }
            id = "ajpanel_" + mac.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
            p.edit().putString("host_id", id).apply();
        }
        return id;
    }
    public String maPlayerId() {
        String o = playerIdOverride();
        return o.isEmpty() ? "ma_" + hostId().replaceAll("\\W+", "") : o;
    }

    private static String mac() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.getName().startsWith("wlan") && !ni.getName().startsWith("eth")) continue;
                byte[] a = ni.getHardwareAddress();
                if (a == null || a.length < 6) continue;
                StringBuilder sb = new StringBuilder();
                for (byte b : a) sb.append(String.format("%02x", b));
                if (!sb.toString().equals("020000000000")) return sb.toString();
            }
        } catch (Exception ignored) { }
        // Android 8+: getHardwareAddress() is often empty for apps – the kernel file still has it on most panels
        for (String ifc : new String[]{"wlan0", "eth0"}) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/sys/class/net/" + ifc + "/address"))) {
                String m = r.readLine();
                if (m != null && m.matches("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}") && !m.equals("02:00:00:00:00:00") && !m.equals("00:00:00:00:00:00"))
                    return m.replace(":", "").toLowerCase();
            } catch (Exception ignored) { }
        }
        return null;
    }

    public SharedPreferences.Editor edit() { return p.edit(); }
}
