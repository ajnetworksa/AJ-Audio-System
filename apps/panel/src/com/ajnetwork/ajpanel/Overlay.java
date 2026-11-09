package com.ajnetwork.ajpanel;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Floating now-playing card drawn over whatever app the panel shows (vendor home screen included).
 * Card → collapses to a chip after 10 s; tap chip = expand; long-press chip = open AJ Panel.
 * Needs "display over other apps": deploy script runs  appops set com.ajnetwork.ajpanel SYSTEM_ALERT_WINDOW allow
 */
public class Overlay {
    private final PanelService svc;
    private final Cfg cfg;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private View card, chip;
    private ImageView art;
    private TextView title, sub, user, chipText;
    private Button pauseHere, leave, playPause;
    private SeekBar vol;
    private boolean shown, expanded, userTouchingVol;
    private String lastPop = "";
    private final Runnable collapse = () -> setExpanded(false);

    Overlay(PanelService s, Cfg cfg) {
        this.svc = s; this.cfg = cfg;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
    }

    private WindowManager.LayoutParams lp(int w, int gravity) {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.y = Ui.dp(svc, 16);
        return p;
    }

    private void build() {
        Context c = svc;
        int pad = Ui.dp(c, 14);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.round(c, Ui.BG, 22));
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.round(c, Ui.CARD, 12));
        art.setClipToOutline(true);
        top.addView(art, new LinearLayout.LayoutParams(Ui.dp(c, 96), Ui.dp(c, 96)));
        LinearLayout txt = new LinearLayout(c);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(pad, 0, 0, 0);
        title = Ui.text(c, "", 24, Ui.TEXT, true);
        sub = Ui.text(c, "", 17, Ui.SUB, false);
        user = Ui.text(c, "", 17, Ui.ACCENT, false);
        txt.addView(title); txt.addView(sub); txt.addView(user);
        top.addView(txt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button close = Ui.button(c, "▾");
        close.setOnClickListener(v -> setExpanded(false));
        top.addView(close, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));
        root.addView(top);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, pad, 0, 0);
        playPause = Ui.button(c, "⏯");
        playPause.setOnClickListener(v -> { svc.playPause(); poke(); });
        pauseHere = Ui.button(c, "⏸ Pause here");
        pauseHere.setOnClickListener(v -> { svc.pauseHere(); poke(); });
        leave = Ui.button(c, "⏏ Leave group");
        leave.setOnClickListener(v -> { svc.leaveGroup(); poke(); });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(c, 56));
        bl.setMarginEnd(Ui.dp(c, 10));
        row.addView(playPause, bl); row.addView(pauseHere, bl); row.addView(leave, bl);
        vol = new SeekBar(c);
        vol.setMax(100);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser) poke(); }
            public void onStartTrackingTouch(SeekBar s) { userTouchingVol = true; }
            public void onStopTrackingTouch(SeekBar s) { userTouchingVol = false; svc.setVolume(s.getProgress()); }
        });
        row.addView(Ui.text(c, "🔊", 20, Ui.TEXT, false));
        row.addView(vol, new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1));
        root.addView(row);
        card = root;

        LinearLayout ch = new LinearLayout(c);
        ch.setBackground(Ui.round(c, Ui.BG, 28));
        ch.setPadding(Ui.dp(c, 20), Ui.dp(c, 12), Ui.dp(c, 20), Ui.dp(c, 12));
        chipText = Ui.text(c, "", 18, Ui.TEXT, false);
        chipText.setMaxWidth(Ui.dp(c, 420));
        ch.addView(chipText);
        ch.setOnClickListener(v -> setExpanded(true));
        ch.setOnLongClickListener(v -> {
            svc.startActivity(new Intent(svc, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        });
        chip = ch;
    }

    private void poke() { h.removeCallbacks(collapse); h.postDelayed(collapse, 10000); }

    private void setExpanded(boolean e) {
        if (!shown) return;
        if (e == expanded) { if (e) poke(); return; }
        expanded = e;
        try {
            if (e) { wm.removeView(chip); wm.addView(card, lp(Math.min(Ui.dp(svc, 760), svc.getResources().getDisplayMetrics().widthPixels - Ui.dp(svc, 32)), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL)); poke(); }
            else { wm.removeView(card); wm.addView(chip, lp(WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END)); }
        } catch (Exception ex) { Log.e("overlay", ex); }
    }

    /** called every poll; changed = something visible changed (new song, group, user…) */
    void update(NowPlaying n, Bitmap a, boolean changed) {
        if (!n.active()) { remove(); return; }
        if (card == null) build();
        if (!shown) {
            try { wm.addView(chip, lp(WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END)); shown = true; expanded = false; }
            catch (Exception e) { Log.e("overlay add (overlay permission?)", e); return; }
        }
        title.setText(n.title);
        sub.setText(PanelService.subtitle(n));
        String who = n.user.isEmpty() ? (n.source.isEmpty() ? "" : "▶ via " + n.source)
                : "▶ played by " + n.user + (n.source.isEmpty() ? "" : " (" + n.source + ")");
        user.setText(who);
        user.setVisibility(who.isEmpty() ? View.GONE : View.VISIBLE);
        art.setImageBitmap(a);
        pauseHere.setText(n.muted ? "▶ Resume here" : "⏸ Pause here");
        leave.setVisibility(n.groupName.isEmpty() ? View.GONE : View.VISIBLE);
        playPause.setText("playing".equals(n.state) ? "⏸" : "▶");
        if (!userTouchingVol && n.volume >= 0) vol.setProgress(n.volume);
        chipText.setText(("playing".equals(n.state) ? (n.muted ? "🔇 " : "♪ ") : "⏸ ") + n.title + (n.artist.isEmpty() ? "" : " – " + n.artist));
        String pop = n.state + "|" + n.title + "|" + n.groupName + "|" + n.user;
        if (!pop.equals(lastPop) && !n.muted) setExpanded(true);
        lastPop = pop;   // new song / new group → show card for 10 s
    }

    void remove() {
        if (!shown) return;
        try { wm.removeView(expanded ? card : chip); } catch (Exception ignored) { }
        shown = false; expanded = false; lastPop = "";
        h.removeCallbacks(collapse);
    }
}
