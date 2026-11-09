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
 *   ... -a com.ajnetwork.ajpanel.STOP   (stop service + player)
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction() == null ? "" : i.getAction();
        if (a.endsWith(".STOP")) { c.stopService(new Intent(c, PanelService.class)); Log.i("stopped by broadcast"); return; }
        if (a.endsWith(".CONFIG")) {
            SharedPreferences.Editor e = new Cfg(c).edit();
            for (String k : new String[]{"host", "name", "token", "engine", "ma_player_id"}) if (i.hasExtra(k)) e.putString(k, i.getStringExtra(k));
            for (String k : new String[]{"latency", "ma_port", "snap_port"}) if (i.hasExtra(k)) e.putInt(k, i.getIntExtra(k, 0));
            for (String k : new String[]{"overlay", "player", "resample"}) if (i.hasExtra(k)) e.putBoolean(k, i.getBooleanExtra(k, true));
            e.commit();
            Log.i("config updated by broadcast");
            PanelService s = PanelService.instance;
            if (s != null) { s.reload(); return; }
        }
        PanelService.start(c);
    }
}
