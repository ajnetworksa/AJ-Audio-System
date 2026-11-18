package com.ajnetwork.ajpanel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Full-screen now playing (left) + status/settings (right). */
public class SettingsActivity extends Activity implements PanelService.Listener {
    private Cfg cfg;
    private ImageView art;
    private TextView title, sub, user, status, log, time;
    private Button pauseHere, leave, playPause;
    private SeekBar vol;
    private boolean touchingVol;
    private View settings;
    private ScrollView settingsScroll, logScroll;
    private void showSettings(boolean on) {
        settingsScroll.setVisibility(on ? View.VISIBLE : View.GONE);
        logScroll.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Cfg(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PanelService.start(this);
        int pad = Ui.dp(this, 20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF0E1117);
        root.setPadding(pad, pad, pad, pad);

        // ---- left: now playing ----
        LinearLayout np = new LinearLayout(this);
        np.setOrientation(LinearLayout.VERTICAL);
        np.setGravity(Gravity.CENTER_HORIZONTAL);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.round(this, Ui.CARD, 18));
        art.setClipToOutline(true);
        np.addView(art, new LinearLayout.LayoutParams(Ui.dp(this, 260), Ui.dp(this, 260)));
        title = Ui.text(this, "Nothing playing", 30, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER); title.setPadding(0, pad, 0, 0);
        sub = Ui.text(this, "", 20, Ui.SUB, false); sub.setGravity(Gravity.CENTER);
        user = Ui.text(this, "", 20, Ui.ACCENT, false); user.setGravity(Gravity.CENTER);
        time = Ui.text(this, "", 16, Ui.SUB, false); time.setGravity(Gravity.CENTER);
        np.addView(title, fill()); np.addView(sub, fill()); np.addView(user, fill()); np.addView(time, fill());
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, pad, 0, 0);
        Button prev = Ui.button(this, "⏮"); prev.setOnClickListener(v -> svc(s -> s.groupCmd("previous")));
        playPause = Ui.button(this, "⏯"); playPause.setOnClickListener(v -> svc(PanelService::playPause));
        Button next = Ui.button(this, "⏭"); next.setOnClickListener(v -> svc(s -> s.groupCmd("next")));
        pauseHere = Ui.button(this, "⏸ Pause here"); pauseHere.setOnClickListener(v -> svc(PanelService::pauseHere));
        leave = Ui.button(this, "⏏ Leave group"); leave.setOnClickListener(v -> svc(PanelService::leaveGroup));
        for (Button x : new Button[]{prev, playPause, next, pauseHere, leave}) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 60));
            l.setMarginEnd(Ui.dp(this, 10)); row.addView(x, l);
        }
        np.addView(row);
        vol = new SeekBar(this); vol.setMax(100);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { }
            public void onStartTrackingTouch(SeekBar s) { touchingVol = true; }
            public void onStopTrackingTouch(SeekBar s) { touchingVol = false; int v = s.getProgress(); svc(x -> x.setVolume(v)); }
        });
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(Ui.dp(this, 420), Ui.dp(this, 56));
        vl.topMargin = Ui.dp(this, 10);
        np.addView(vol, vl);
        root.addView(np, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3));

        // ---- right: status + settings ----
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackground(Ui.round(this, Ui.CARD, 18));
        side.setPadding(pad, pad, pad, pad);
        side.addView(Ui.text(this, "AJ Panel " + version(), 22, Ui.TEXT, true));
        status = Ui.text(this, "", 15, Ui.SUB, false); status.setSingleLine(false); status.setMaxLines(6);
        side.addView(status);
        Button set = Ui.button(this, "⚙ Settings");
        // settings and log share the space below: one or the other, each scrollable (small screens like the DSPPA)
        set.setOnClickListener(v -> showSettings(settingsScroll.getVisibility() != View.VISIBLE));
        side.addView(set, topGap());
        Button upd = Ui.button(this, "Check for app update");
        upd.setOnClickListener(v -> {
            PanelService sv = PanelService.instance;
            if (sv == null) return;
            upd.setText("Checking…");
            sv.checkUpdate(() -> {
                if (Updater.availableVersion.isEmpty()) { upd.setText("Up to date (" + version() + ")"); return; }
                upd.setText("Install AJ Panel " + Updater.availableVersion);
                upd.setOnClickListener(x -> { upd.setText("Downloading… then tap Install"); sv.installUpdate(); });
            });
        });
        side.addView(upd, topGap());
        settings = buildSettings();
        settingsScroll = new ScrollView(this); settingsScroll.addView(settings);
        side.addView(settingsScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        log = Ui.text(this, "", 11, 0xFF7F8A99, false); log.setSingleLine(false); log.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll = new ScrollView(this); logScroll.addView(log);
        side.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        showSettings(cfg.token().isEmpty());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2);
        sl.setMarginStart(pad);
        root.addView(side, sl);
        setContentView(root);
    }

    private View buildSettings() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        EditText host = field("Server (Music Assistant) IP", cfg.host());
        EditText name = field("Panel name", cfg.name());
        EditText u = field("MA username", "");
        EditText p = field("MA password", ""); p.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText lat = field("Latency trim ms (+ = later)", String.valueOf(cfg.latencyMs())); lat.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        CheckBox opensl = check("Use OpenSL output (try if sound stutters)", "opensl".equals(cfg.engine()));
        CheckBox ov = check("Show floating now-playing card", cfg.overlay());
        CheckBox pl = check("Run built-in sync player", cfg.player());
        CheckBox vb = check("Show song in the panel's own music player (Judian)", cfg.vendorBridge());
        CheckBox sysVol = check("Volume = panel volume (buttons and app stay in sync)", cfg.systemVolume());
        for (View v : new View[]{host, name, u, p, lat, opensl, ov, pl, vb, sysVol}) s.addView(v);
        Button save = Ui.button(this, "Save & connect");
        save.setOnClickListener(v -> {
            int l = 0; try { l = Integer.parseInt(lat.getText().toString().trim()); } catch (Exception ignored) { }
            cfg.edit().putString("host", host.getText().toString().trim()).putString("name", name.getText().toString().trim())
                    .putInt("latency", l).putString("engine", opensl.isChecked() ? "opensl" : "oboe")
                    .putBoolean("overlay", ov.isChecked()).putBoolean("player", pl.isChecked()).putBoolean("vendor_bridge", vb.isChecked()).putBoolean("system_volume", sysVol.isChecked()).apply();
            String user = u.getText().toString().trim();
            PanelService sv = PanelService.instance;
            if (sv == null) { PanelService.start(this); return; }
            if (!user.isEmpty()) sv.login(user, p.getText().toString(), () -> Toast.makeText(this, cfg.token().isEmpty() ? "Login failed – see log" : "Logged in", Toast.LENGTH_LONG).show());
            else sv.reload();
            p.setText("");
        });
        s.addView(save, topGap());
        Button perm = Ui.button(this, "Allow floating card");
        perm.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this))
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { Toast.makeText(this, "Use the deploy script (appops)", Toast.LENGTH_LONG).show(); }
            else Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show();
        });
        s.addView(perm, topGap());
        return s;
    }

    private EditText field(String hint, String v) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(v); e.setTextColor(Ui.TEXT); e.setHintTextColor(Ui.SUB);
        e.setSingleLine(true); e.setTextSize(17); return e;
    }
    private CheckBox check(String t, boolean v) { CheckBox c = new CheckBox(this); c.setText(t); c.setTextColor(Ui.TEXT); c.setChecked(v); return c; }
    private LinearLayout.LayoutParams fill() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams topGap() { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)); l.topMargin = Ui.dp(this, 10); return l; }
    private String version() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; } }

    interface SvcAction { void run(PanelService s); }
    private void svc(SvcAction a) { PanelService s = PanelService.instance; if (s != null) a.run(s); }

    @Override protected void onResume() { super.onResume(); PanelService.addListener(this); }
    @Override protected void onPause() { PanelService.removeListener(this); super.onPause(); }

    @Override public void onUpdate(NowPlaying n, Bitmap a) {
        runOnUiThread(() -> {
            PanelService s = PanelService.instance;
            boolean act = n.active();
            title.setText(act ? n.title : "Nothing playing");
            sub.setText(act ? PanelService.subtitle(n) : (n.groupName.isEmpty() ? "" : "in " + n.groupName + " (" + n.groupSize + ")"));
            String who = n.user.isEmpty() ? (n.source.isEmpty() ? "" : "▶ via " + n.source) : "▶ played by " + n.user + (n.source.isEmpty() ? "" : " (" + n.source + ")");
            user.setText(act ? who : "");
            time.setText(act && n.duration > 0 ? Ui.fmt(n.elapsed) + " / " + Ui.fmt(n.duration) : "");
            art.setImageBitmap(act ? a : null);
            pauseHere.setText(n.muted ? "▶ Resume here" : "⏸ Pause here");
            leave.setVisibility(n.groupName.isEmpty() ? View.GONE : View.VISIBLE);
            playPause.setText("playing".equals(n.state) ? "⏸" : "▶");
            if (!touchingVol && n.volume >= 0) vol.setProgress(n.volume);
            StringBuilder st = new StringBuilder();
            st.append(s != null ? s.statusLine(n) : "service not running").append('\n');
            st.append("Player id: ").append(cfg.maPlayerId()).append('\n');
            if (s != null && s.snap != null)
                st.append("Sync player: ").append(cfg.player() ? (s.snap.connected ? "connected" : "connecting…") : "off")
                  .append(s.snap.restarts > 0 ? " · restarts " + s.snap.restarts : "").append('\n');
            st.append("Server: ").append(cfg.host());
            status.setText(st.toString());
            log.setText(Log.dump());
        });
    }
}
