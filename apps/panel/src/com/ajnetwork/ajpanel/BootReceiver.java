package com.ajnetwork.ajpanel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Starts AJ Panel at boot, and lets the deploy script configure it over adb:
 *   am broadcast -n com.ajnetwork.ajpanel/.BootReceiver -a com.ajnetwork.ajpanel.CONFIG
 *      [--es host 192.168.8.5] [--es name "Living room"] [--es token <MA long-lived token>]
 *      [--es engine oboe|opensl] [--ei latency 0] [--ez overlay true] [--ez player true] [--es ma_player_id <id>]
 *      [--es host_id_default ajpanel_<mac>]   (player id, only if the panel has none yet)
 *      [--ez zigbee_bridge true|false] [--ei zigbee_port 6638] [--es zigbee_allow <HA ip>|any|ha]   (TEST, see zigbee.md)
 *   ... -a com.ajnetwork.ajpanel.LOGIN --es user panels --es pass <password>
 *       (logs in once, keeps only a long-lived token; the password is not stored)
 *   ... -a com.ajnetwork.ajpanel.STOP   (stop service + player)
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction() == null ? "" : i.getAction();
        if (a.endsWith(".RELEASE_OWNER")) { Log.i("device owner released: " + AdminReceiver.release(c)); return; }
        if (a.endsWith(".STOP")) { c.stopService(new Intent(c, PanelService.class)); Log.i("stopped by broadcast"); return; }
        if (a.endsWith(".LOGIN")) {
            String u = i.getStringExtra("user"), p = i.getStringExtra("pass");
            if (u == null || p == null) return;
            PanelService.start(c);
            final PendingResult pr = goAsync();
            new Thread(() -> {
                for (int k = 0; k < 50 && PanelService.instance == null; k++) try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                PanelService s = PanelService.instance;
                if (s != null) s.login(u, p, null); else Log.i("login: service not running");
                pr.finish();
            }).start();
            return;
        }
        if (a.endsWith(".CONFIG")) {
            SharedPreferences.Editor e = new Cfg(c).edit();
            for (String k : new String[]{"host", "name", "token", "engine", "ma_player_id", "zigbee_tty", "zigbee_allow", "repair_token", "update_window", "update_days"}) if (i.hasExtra(k)) e.putString(k, i.getStringExtra(k));
            // stable player id (deploy passes the Wi-Fi MAC): only used when the panel has no id yet, so a reinstall
            // (e.g. after a signing-key change) keeps the same Music Assistant player instead of creating a new one
            String hid = i.getStringExtra("host_id_default");
            if (hid != null && hid.matches("ajpanel_[0-9a-f]{12}") && !c.getSharedPreferences("ajpanel", Context.MODE_PRIVATE).contains("host_id"))
                e.putString("host_id", hid);
            for (String k : new String[]{"latency", "ma_port", "snap_port", "zigbee_port", "update_delay_h"}) if (i.hasExtra(k)) e.putInt(k, i.getIntExtra(k, 0));
            for (String k : new String[]{"overlay", "player", "resample", "vendor_bridge", "system_volume", "zigbee_bridge", "share_bubble", "auto_update"}) if (i.hasExtra(k)) e.putBoolean(k, i.getBooleanExtra(k, true));
            e.commit();
            Log.i("config updated by broadcast");
            PanelService s = PanelService.instance;
            if (s != null) { s.reload(); return; }
        }
        PanelService.start(c);
    }
}
