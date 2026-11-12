package com.ajnetwork.aj_audio;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "Play my phone on…" sheet – opened from the Quick Settings tile, like the AirPlay / Media output picker:
 * groups first, then rooms; tap one to start (or move) the phone's sound there; Stop at the bottom.
 */
@TargetApi(29)
public class PickerActivity extends Activity {
    private static final int TEXT = 0xFFF2F4F8, SUB = 0xFFB9C2D0, ACCENT = 0xFF40A0FF;
    private LinearLayout list;
    private TextView status;
    private final Handler h = new Handler(Looper.getMainLooper());

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x99000000));
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> finish());
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1C2C52, 0xFF141C2A, 0xFF2A1640});
        float r = dp(26);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setPadding(dp(20), dp(14), dp(20), dp(20));
        sheet.setClickable(true);

        View grab = new View(this);
        GradientDrawable gd = new GradientDrawable(); gd.setColor(0x55FFFFFF); gd.setCornerRadius(dp(3)); grab.setBackground(gd);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(40), dp(5)); gp.gravity = Gravity.CENTER_HORIZONTAL; gp.bottomMargin = dp(12);
        sheet.addView(grab, gp);
        sheet.addView(text("Play my phone on…", 22, TEXT, true));
        status = text("", 14, SUB, false);
        status.setPadding(0, dp(4), 0, dp(10));
        sheet.addView(status);
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        ProgressBar pb = new ProgressBar(this); list.addView(pb);
        sv.addView(list);
        sheet.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        if (CaptureService.running) {
            Button stop = new Button(this);
            stop.setText("Stop playing on " + Capture.prefs(this).getString("targetName", ""));
            stop.setAllCaps(false); stop.setTextColor(0xFF1A0B0B); stop.setTextSize(16);
            GradientDrawable sb = new GradientDrawable(); sb.setColor(0xFFFF8A80); sb.setCornerRadius(dp(14)); stop.setBackground(sb);
            stop.setOnClickListener(v -> { Capture.stop(this); finish(); });
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(52)); sp.topMargin = dp(12);
            sheet.addView(stop, sp);
        }
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.72);
        root.addView(sheet, new FrameLayout.LayoutParams(-1, maxH, Gravity.BOTTOM));
        setContentView(root);
        status.setText(CaptureService.running ? "Now playing on " + Capture.prefs(this).getString("targetName", "") + " – tap another to move it"
                : "Everything this phone plays goes to the room or group you pick");
        new Thread(this::load, "picker-load").start();
    }

    private void load() {
        String server = Capture.prefs(this).getString("server", ""), token = Capture.prefs(this).getString("token", "");
        List<JSONObject> groups = new ArrayList<>(), rooms = new ArrayList<>();
        String err = null;
        try {
            if (server.isEmpty()) throw new IllegalStateException("Open AJ Audio Client once and sign in");
            Object r = CaptureService.api(server, token, "players/all", new JSONObject().put("return_unavailable", false));
            JSONArray a = r instanceof JSONArray ? (JSONArray) r : new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p == null || p.optBoolean("hide_in_ui") || !p.optBoolean("enabled", true)) continue;
                if (p.optString("player_id").contains("ariacast")) continue;
                ("group".equals(p.optString("type")) ? groups : rooms).add(p);
            }
        } catch (Exception e) { err = e.getMessage(); }
        final String e2 = err;
        h.post(() -> show(groups, rooms, e2));
    }

    private void show(List<JSONObject> groups, List<JSONObject> rooms, String err) {
        list.removeAllViews();
        if (err != null) { list.addView(text(err, 15, 0xFFFF8A80, false)); return; }
        String cur = CaptureService.running ? Capture.prefs(this).getString("target", "") : "";
        if (!groups.isEmpty()) list.addView(label("GROUPS"));
        for (JSONObject g : groups) list.addView(row(g, true, cur));
        if (!rooms.isEmpty()) list.addView(label("ROOMS"));
        for (JSONObject p : rooms) list.addView(row(p, false, cur));
    }

    private View row(JSONObject p, boolean group, String cur) {
        String id = p.optString("player_id"), name = p.optString("name");
        boolean on = id.equals(cur);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = on ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF1E3A66, 0xFF2B2156}) : new GradientDrawable();
        if (!on) bg.setColor(0xE61A2333);
        bg.setCornerRadius(dp(16));
        if (on) bg.setStroke(dp(2), ACCENT);
        r.setBackground(bg);
        TextView icon = text(group ? "▣" : "◉", 22, on ? ACCENT : SUB, false);
        icon.setPadding(0, 0, dp(14), 0);
        r.addView(icon);
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(name, 17, TEXT, true));
        JSONArray m = p.optJSONArray("group_members");
        JSONObject media = p.optJSONObject("current_media");
        String state = p.optString("playback_state", "idle");
        String sub = (group && m != null ? m.length() + " rooms · " : "") + ("playing".equals(state) && media != null ? "♪ " + media.optString("title") : state);
        if (on) sub = "Playing your phone";
        t.addView(text(sub, 13, on ? 0xFF9CCBFF : SUB, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (on) r.addView(text("✓", 20, ACCENT, true));
        r.setOnClickListener(v -> pick(id, name));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private void pick(String id, String name) {
        Capture.prefs(this).edit().putString("target", id).putString("targetName", name).apply();
        if (CaptureService.running) {
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_SWITCH));
        } else {
            Capture.start(this);
        }
        finish();
    }

    private TextView label(String s) {
        TextView t = text(s, 12, SUB, true); t.setLetterSpacing(0.1f); t.setPadding(dp(4), dp(10), 0, dp(8)); return t;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @Override public void finish() { super.finish(); overridePendingTransition(0, android.R.anim.fade_out); }
}
