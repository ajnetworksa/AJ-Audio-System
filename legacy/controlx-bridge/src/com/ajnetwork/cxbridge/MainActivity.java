package com.ajnetwork.cxbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small status screen: start/stop the bridge, choose zone count, see live log. */
public class MainActivity extends Activity {
    private TextView status, log;
    private final Handler h = new Handler();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(Color.rgb(20, 22, 26));

        TextView t = new TextView(this);
        t.setText("ControlX Bridge  -  Control X <-> YJY player");
        t.setTextColor(Color.WHITE); t.setTextSize(20);
        root.addView(t);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(btn("Start", v -> startBridge()));
        row.addView(btn("Stop", v -> stopService(new Intent(this, BridgeService.class))));
        for (int n = 1; n <= 4; n++) { final int k = n; row.addView(btn(n + " zone" + (n > 1 ? "s" : ""), v -> setZones(k))); }
        root.addView(row);

        // ---- group (multi-room) controls ----
        final android.content.SharedPreferences sp = getSharedPreferences("cx", MODE_PRIVATE);
        LinearLayout g = new LinearLayout(this);
        g.setGravity(Gravity.CENTER_VERTICAL);
        g.addView(btn("Group server ON", v -> { sp.edit().putBoolean("server", true).putString("join", "").apply(); restart(); }));
        g.addView(btn("Group server OFF", v -> { sp.edit().putBoolean("server", false).apply(); BridgeService.leaveGroup(this); restart(); }));
        final android.widget.EditText host = new android.widget.EditText(this);
        host.setHint("server IP e.g. 192.168.8.46"); host.setText(sp.getString("join", "")); host.setTextColor(Color.WHITE); host.setHintTextColor(Color.GRAY);
        host.setMinWidth(320);
        g.addView(host);
        g.addView(btn("Join group", v -> { String h = host.getText().toString().trim(); sp.edit().putString("join", h).apply(); BridgeService.joinGroup(this, h); }));
        g.addView(btn("Leave group", v -> { sp.edit().putString("join", "").apply(); BridgeService.leaveGroup(this); }));
        root.addView(g);

        LinearLayout t2 = new LinearLayout(this);
        t2.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.EditText url = new android.widget.EditText(this);
        url.setHint("test: http://... mp3/aac/flac stream or file URL"); url.setTextColor(Color.WHITE); url.setHintTextColor(Color.GRAY);
        url.setText(sp.getString("testurl", "http://ice1.somafm.com/groovesalad-128-mp3"));
        t2.addView(url, new LinearLayout.LayoutParams(0, -2, 1f));
        t2.addView(btn("Play to group", v -> {
            GroupPlayer gp = BridgeService.group;
            if (gp == null) { Log.i("turn Group server ON first"); return; }
            String u = url.getText().toString().trim(); sp.edit().putString("testurl", u).apply();
            gp.setUri(u, "Test stream"); gp.play();
        }));
        t2.addView(btn("Pause", v -> { if (BridgeService.group != null) BridgeService.group.pause(); }));
        t2.addView(btn("Stop", v -> { if (BridgeService.group != null) BridgeService.group.stop(); }));
        root.addView(t2);

        status = new TextView(this); status.setTextColor(Color.rgb(140, 220, 140)); status.setTextSize(15);
        root.addView(status);

        ScrollView sv = new ScrollView(this);
        log = new TextView(this); log.setTextColor(Color.LTGRAY); log.setTextSize(12); log.setTypeface(android.graphics.Typeface.MONOSPACE);
        sv.addView(log);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        startBridge();
    }

    private Button btn(String s, android.view.View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setOnClickListener(l); return b; }

    private void startBridge() {
        Intent s = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
    }

    private void restart() {
        stopService(new Intent(this, BridgeService.class));
        h.postDelayed(this::startBridge, 1200);
    }

    private void setZones(int n) {
        getSharedPreferences("cx", MODE_PRIVATE).edit().putInt("zones", n).apply();
        stopService(new Intent(this, BridgeService.class));
        h.postDelayed(this::startBridge, 800);
    }

    private final Runnable tick = new Runnable() { public void run() {
        status.setText(BridgeService.running ? BridgeService.statusLine : "stopped");
        log.setText(Log.dump());
        h.postDelayed(this, 1000);
    }};
    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }
}
