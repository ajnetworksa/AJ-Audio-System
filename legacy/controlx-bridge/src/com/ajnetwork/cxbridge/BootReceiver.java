package com.ajnetwork.cxbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Starts the bridge at boot. Can also be driven from adb:
 *   am broadcast -n com.ajnetwork.cxbridge/.BootReceiver -a com.ajnetwork.cxbridge.START
 *       [--ez server true|false] [--es join 192.168.8.46|""] [--ei zones 4]
 *       [--es play http://stream-url] [--ez stop true]
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        SharedPreferences.Editor e = c.getSharedPreferences("cx", Context.MODE_PRIVATE).edit();
        boolean restart = false;
        if (i.hasExtra("server")) { e.putBoolean("server", i.getBooleanExtra("server", false)); restart = true; }
        if (i.hasExtra("join")) { e.putString("join", i.getStringExtra("join")); restart = true; }
        if (i.hasExtra("zones")) { e.putInt("zones", i.getIntExtra("zones", 4)); restart = true; }
        e.apply();
        Intent s = new Intent(c, BridgeService.class);
        if (restart && BridgeService.running) c.stopService(s);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);

        final String play = i.getStringExtra("play");
        final boolean stop = i.getBooleanExtra("stop", false);
        if (play != null || stop) {
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    for (int n = 0; n < 50 && BridgeService.group == null; n++) Thread.sleep(200);
                    GroupPlayer g = BridgeService.group;
                    if (g != null) {
                        if (stop) g.stop();
                        else { g.setUri(play, "Test stream"); g.play(); }
                    } else Log.i("group server is not running - send --ez server true first");
                } catch (Exception ex) { Log.e("receiver", ex); }
                pr.finish();
            }).start();
        }
    }
}
