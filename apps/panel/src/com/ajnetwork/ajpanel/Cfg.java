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
    /** only this address may connect (default: the music server = Home Assistant); "any" = everyone on the network, "ha"/empty = default */
    public String zigbeeAllow() { String a = p.getString("zigbee_allow", ""); return a.equals("any") ? "" : a.isEmpty() || a.equals("ha") ? host() : a; }
    /** floating Share button over other apps (YouTube …) */
    public boolean shareBubble() { return p.getBoolean("share_bubble", true); }
    public int shareBubbleY()    { return p.getInt("share_bubble_y", -1); }
    /** admin token (from the Installer) used ONLY to reload a blocked AriaCast Receiver – see AriaHeal */
    public String repairToken() { return p.getString("repair_token", ""); }
    public String playerIdOverride() { return p.getString("ma_player_id", ""); }

    public String maBase() { return "http://" + host() + ":" + maPort(); }

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
