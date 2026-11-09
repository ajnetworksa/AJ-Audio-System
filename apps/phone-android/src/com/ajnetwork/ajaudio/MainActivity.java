package com.ajnetwork.ajaudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AJ Audio: rooms + grouping for Music Assistant, and "play my phone audio" to any room or group.
 */
public class MainActivity extends Activity {
    static final int REQ_CAPTURE = 7, REQ_MIC = 8;
    private SharedPreferences sp;
    private Ma ma;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout roomsBox;
    private TextView status, serverLine;
    private EditText serverIn, userIn, passIn;
    private LinearLayout loginBox;
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, JSONObject> players = new HashMap<>();
    private final List<String> order = new ArrayList<>();
    private volatile boolean paused;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener disc;
    private boolean userDragging;

    // ------------------------------------------------------------------ UI building
    private static final int BG = Color.rgb(18, 20, 24), CARD = Color.rgb(32, 35, 42), ACCENT = Color.rgb(64, 160, 255);

    private TextView text(String s, float size, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }
    private Button button(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setOnClickListener(l); return b; }
    private EditText input(String hint, String val, boolean pw) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(val); e.setTextColor(Color.WHITE); e.setHintTextColor(Color.GRAY);
        e.setSingleLine(true);
        if (pw) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("aj", MODE_PRIVATE);
        ma = new Ma(sp.getString("base", "http://192.168.8.5:8095"), sp.getString("token", ""));

        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(32));
        sv.addView(root);

        root.addView(text("AJ Audio", 26, Color.WHITE));
        serverLine = text("", 13, Color.GRAY); root.addView(serverLine);

        // server + login
        loginBox = new LinearLayout(this); loginBox.setOrientation(LinearLayout.VERTICAL);
        serverIn = input("Music Assistant address", ma.base, false);
        userIn = input("Music Assistant username", sp.getString("user", ""), false);
        passIn = input("password", "", true);
        loginBox.addView(serverIn); loginBox.addView(userIn); loginBox.addView(passIn);
        loginBox.addView(button("Log in", v -> login()));
        root.addView(loginBox);

        status = text("", 14, Color.rgb(140, 220, 140)); status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        // actions
        TextView h = text("Rooms  (tick rooms, the first ticked one is the group leader)", 15, Color.LTGRAY);
        h.setPadding(0, dp(12), 0, dp(4)); root.addView(h);
        roomsBox = new LinearLayout(this); roomsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(roomsBox);

        LinearLayout r1 = new LinearLayout(this);
        r1.addView(button("Group ticked", v -> groupSelected()), new LinearLayout.LayoutParams(0, -2, 1));
        r1.addView(button("Ungroup ticked", v -> ungroupSelected()), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(r1);
        LinearLayout r2 = new LinearLayout(this);
        r2.addView(button("⏮", v -> leaderCmd("previous")), new LinearLayout.LayoutParams(0, -2, 1));
        r2.addView(button("⏯", v -> leaderCmd("play_pause")), new LinearLayout.LayoutParams(0, -2, 1));
        r2.addView(button("⏭", v -> leaderCmd("next")), new LinearLayout.LayoutParams(0, -2, 1));
        r2.addView(button("⏹", v -> leaderCmd("stop")), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(r2);

        Button phone = button("▶  Play my phone's audio on the leader / group", v -> startPhoneAudio());
        phone.setBackgroundColor(ACCENT); phone.setTextColor(Color.WHITE); phone.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(64)); lp.topMargin = dp(16);
        root.addView(phone, lp);
        root.addView(button("■  Stop phone audio", v -> startService(new Intent(this, CaptureService.class).setAction("STOP"))));
        TextView note = text("Plays whatever is playing on this phone (YouTube, browser, music apps that allow it). " +
                "Sound arrives 2–4 s later on the speakers. Turn this phone's own volume down if you hear it twice.", 12, Color.GRAY);
        note.setPadding(0, dp(8), 0, 0); root.addView(note);

        setContentView(sv);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        discover();
        updateLoginVisibility();
    }

    private void updateLoginVisibility() {
        boolean have = ma.token != null && !ma.token.isEmpty();
        loginBox.setVisibility(have ? View.GONE : View.VISIBLE);
        serverLine.setText("Server: " + ma.base + (have ? "   (logged in — long-press here to log out)" : ""));
        serverLine.setOnLongClickListener(v -> { sp.edit().remove("token").apply(); ma.token = ""; updateLoginVisibility(); return true; });
    }

    // ------------------------------------------------------------------ discovery (mDNS)
    private void discover() {
        try {
            nsd = (NsdManager) getSystemService(NSD_SERVICE);
            disc = new NsdManager.DiscoveryListener() {
                public void onStartDiscoveryFailed(String t, int e) {}
                public void onStopDiscoveryFailed(String t, int e) {}
                public void onDiscoveryStarted(String t) {}
                public void onDiscoveryStopped(String t) {}
                public void onServiceLost(NsdServiceInfo s) {}
                public void onServiceFound(NsdServiceInfo s) {
                    nsd.resolveService(s, new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo s, int e) {}
                        public void onServiceResolved(NsdServiceInfo r) {
                            String host = r.getHost().getHostAddress();
                            if (host.contains(":")) return;           // skip IPv6
                            String found = "http://" + host + ":" + r.getPort();
                            if (!sp.contains("base")) ui.post(() -> { ma.base = found; serverIn.setText(found); updateLoginVisibility(); toast("Found Music Assistant at " + found); });
                        }
                    });
                }
            };
            nsd.discoverServices("_mass._tcp", NsdManager.PROTOCOL_DNS_SD, disc);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ login
    private void login() {
        final String base = serverIn.getText().toString().trim().replaceAll("/+$", "");
        final String u = userIn.getText().toString().trim(), p = passIn.getText().toString();
        status.setText("Logging in…");
        new Thread(() -> {
            try {
                ma.base = base.startsWith("http") ? base : "http://" + base;
                String t = ma.login(u, p, "AJ Audio " + Build.MODEL);
                sp.edit().putString("base", ma.base).putString("token", t).putString("user", u).apply();
                ui.post(() -> { passIn.setText(""); updateLoginVisibility(); status.setText("Logged in"); });
                refresh();
            } catch (Exception e) { ui.post(() -> status.setText("Login failed: " + e.getMessage())); }
        }).start();
    }

    // ------------------------------------------------------------------ rooms
    @Override protected void onResume() {
        super.onResume(); paused = false;
        new Thread(() -> { while (!paused) { refresh(); try { Thread.sleep(3000); } catch (InterruptedException e) { break; } } }).start();
    }
    @Override protected void onPause() { super.onPause(); paused = true; }
    @Override protected void onDestroy() { try { nsd.stopServiceDiscovery(disc); } catch (Exception ignored) {} super.onDestroy(); }

    private static String name(JSONObject p) {
        String n = p.optString("display_name", ""); if (n.isEmpty()) n = p.optString("name", p.optString("player_id"));
        return n;
    }
    private static JSONArray members(JSONObject p) {
        JSONArray a = p.optJSONArray("group_members"); if (a == null) a = p.optJSONArray("group_childs");
        return a == null ? new JSONArray() : a;
    }
    private static String state(JSONObject p) {
        String s = p.optString("playback_state", p.optString("state", ""));
        JSONObject cm = p.optJSONObject("current_media");
        String t = cm == null ? "" : cm.optString("title", "");
        return s + (t.isEmpty() ? "" : " · " + t);
    }

    private void refresh() {
        if (ma.token == null || ma.token.isEmpty()) return;
        try {
            JSONArray a = ma.players();
            final List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i);
                if (!p.optBoolean("enabled", true) || p.optBoolean("hidden", false)) continue;
                list.add(p);
            }
            ui.post(() -> render(list));
        } catch (Ma.ApiException e) {
            if (e.code == 401) ui.post(() -> { sp.edit().remove("token").apply(); ma.token = ""; updateLoginVisibility(); status.setText("Please log in again"); });
            else ui.post(() -> status.setText("Music Assistant: " + e.getMessage()));
        } catch (Exception e) { ui.post(() -> status.setText("Cannot reach Music Assistant (" + e.getMessage() + ")")); }
    }

    private void render(List<JSONObject> list) {
        if (userDragging) return;
        players.clear(); order.clear();
        roomsBox.removeAllViews();
        // who is grouped under whom
        Map<String, String> leaderOf = new HashMap<>();
        for (JSONObject p : list) { JSONArray m = members(p); for (int i = 0; i < m.length(); i++) if (!m.optString(i).equals(p.optString("player_id"))) leaderOf.put(m.optString(i), name(p)); }
        for (final JSONObject p : list) {
            final String id = p.optString("player_id");
            players.put(id, p); order.add(id);
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(CARD); card.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.bottomMargin = dp(8);

            LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox cb = new CheckBox(this); cb.setChecked(selected.contains(id));
            cb.setOnCheckedChangeListener((v, on) -> { if (on) selected.add(id); else selected.remove(id); });
            top.addView(cb);
            LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL);
            int n = members(p).length();
            String grp = n > 1 ? "  ·  group of " + n : (leaderOf.containsKey(id) ? "  ·  in " + leaderOf.get(id) + "'s group" : "");
            names.addView(text(name(p) + grp, 17, Color.WHITE));
            names.addView(text(state(p) + (p.optBoolean("available", true) ? "" : "  (offline)"), 12, Color.GRAY));
            top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            card.addView(top);

            SeekBar vol = new SeekBar(this); vol.setMax(100);
            vol.setProgress(p.optInt("group_volume", p.optInt("volume_level", 0)) > 0 && n > 1 ? p.optInt("group_volume", p.optInt("volume_level", 0)) : p.optInt("volume_level", 0));
            vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int v, boolean user) {}
                public void onStartTrackingTouch(SeekBar s) { userDragging = true; }
                public void onStopTrackingTouch(SeekBar s) {
                    userDragging = false;
                    final int v = s.getProgress(); final boolean isGroup = members(p).length() > 1;
                    bg(() -> { if (isGroup) ma.groupVolume(id, v); else ma.volume(id, v); });
                }
            });
            card.addView(vol);
            roomsBox.addView(card, cl);
        }
        if (list.isEmpty()) roomsBox.addView(text("No rooms found yet.", 14, Color.GRAY));
    }

    // ------------------------------------------------------------------ actions
    private interface Job { void run() throws Exception; }
    private void bg(Job j) {
        new Thread(() -> {
            try { j.run(); refresh(); }
            catch (Exception e) { ui.post(() -> status.setText("Error: " + e.getMessage())); }
        }).start();
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private String leader() { return selected.isEmpty() ? null : selected.iterator().next(); }

    private void groupSelected() {
        if (selected.size() < 2) { toast("Tick at least two rooms"); return; }
        final String lead = leader();
        final JSONArray add = new JSONArray();
        for (String id : selected) if (!id.equals(lead)) add.put(id);
        status.setText("Grouping…");
        bg(() -> { ma.setMembers(lead, add, null); ui.post(() -> status.setText("Grouped with " + name(players.get(lead)) + " as leader")); });
    }

    private void ungroupSelected() {
        if (selected.isEmpty()) { toast("Tick the rooms to ungroup"); return; }
        final List<String> ids = new ArrayList<>(selected);
        bg(() -> { for (String id : ids) ma.ungroup(id); ui.post(() -> status.setText("Ungrouped")); });
    }

    private void leaderCmd(String c) {
        final String l = leader();
        if (l == null) { toast("Tick a room first"); return; }
        bg(() -> ma.simple(c, l));
    }

    private void startPhoneAudio() {
        if (leader() == null) { toast("Tick the room or the group leader to play on"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC); return;
        }
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        if (res != RESULT_OK || data == null) { status.setText("Audio capture was not allowed"); return; }
        String l = leader();
        Intent s = new Intent(this, CaptureService.class)
                .putExtra("code", res).putExtra("data", data)
                .putExtra("base", ma.base).putExtra("token", ma.token)
                .putExtra("target", l).putExtra("targetName", players.containsKey(l) ? name(players.get(l)) : l);
        startForegroundService(s);
        status.setText("Starting phone audio…");
        ui.postDelayed(statusTick, 1500);
    }

    private final Runnable statusTick = new Runnable() { public void run() {
        if (!CaptureService.status.isEmpty()) status.setText("Phone audio: " + CaptureService.status);
        if (CaptureService.running) ui.postDelayed(this, 1500);
    }};
}
