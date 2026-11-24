package com.ajnetwork.ajpanel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.ajnetwork.ajpanel.Theme.*;

/**
 * AJ Panel v2 screen (ui.md §F, D-019) – a calm, Sonos-like music player for the wall:
 *  rail         clock · Playing · Rooms · Settings
 *  PLAYING      big cover · song · who/what · transport · room or group volume · Group / Take a break
 *               idle: big clock + "playing elsewhere" with one-tap Join
 *  ROOMS        Scenes (saved groups + Everywhere) · every room/zone as a card with Join / Add / Group
 *  GROUP sheet  tick rooms to play together, per-room volume, scene shortcuts, Done
 *  BREAK        15 min / 1 h / 2 h / until 07:00 / until my music stops / until the group ends
 */
public class MainActivity extends Activity implements PanelService.Listener {
    private static final int NOW = 0, MUSIC = 1, ROOMS = 2;
    private int page = NOW;
    private Cfg cfg;
    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout content, sheetLayer;
    private Backdrop backdrop;
    private NowPlaying n;
    private Bitmap art;
    private float wDp, hDp;

    private TextView railClock;
    private final View[] navItems = new View[3];
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();

    private final Runnable tick = new Runnable() { public void run() { updateClock(); h.postDelayed(this, 1000); } };

    private int dp(float v) { return Theme.dp(this, v); }
    private PanelService svc() { return PanelService.instance; }
    private Site site() { PanelService s = svc(); return s == null ? null : s.site; }
    private String me() { return cfg.maPlayerId(); }
    private String myName() { return n != null && !n.playerName.isEmpty() ? n.playerName : cfg.name(); }
    private boolean grouped() { return n != null && !n.groupName.isEmpty(); }
    /** this panel started the music that plays now → it may set the whole group's volume */
    private boolean origin() { PanelService s = svc(); return s != null && s.isOrigin(); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Cfg(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
        PanelService.start(this);
        DisplayMetrics m = getResources().getDisplayMetrics();
        wDp = m.widthPixels / m.density; hDp = m.heightPixels / m.density;
        // dynamic size: designed for 1024×600 dp; larger screens (in dp) scale everything up so it fills the same share
        Theme.scale = Math.max(1f, Math.min(1.6f, Math.min(Math.max(wDp, hDp) / 1024f, Math.min(wDp, hDp) / 600f)));
        wDp /= Theme.scale; hDp /= Theme.scale;
        Log.i("screen " + m.widthPixels + "x" + m.heightPixels + " density " + m.density + " -> ui scale " + Theme.scale);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        backdrop = new Backdrop(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout row = new LinearLayout(this);
        row.addView(buildRail(), new LinearLayout.LayoutParams(dp(96), -1));
        content = new FrameLayout(this);
        row.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));
        sheetLayer = new FrameLayout(this);
        sheetLayer.setBackgroundColor(0x99000000);
        sheetLayer.setVisibility(View.GONE);
        sheetLayer.setOnClickListener(v -> closeSheet());
        root.addView(sheetLayer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        show(NOW);
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); shareSig = ""; }

    /** AJ Panel's own screen is in front (the floating Share button hides then) */
    static volatile boolean visible;
    @Override protected void onResume() { super.onResume(); visible = true; PanelService.addListener(this); h.post(tick); PanelService s = svc(); if (s != null && s.shareBubble != null) s.shareBubble.remove(); }
    @Override protected void onPause() { visible = false; PanelService.removeListener(this); h.removeCallbacks(tick); super.onPause(); }
    @Override public void onBackPressed() {
        if (sheetLayer.getVisibility() == View.VISIBLE) { closeSheet(); return; }
        if (page != NOW) { show(NOW); return; }
        super.onBackPressed();
    }

    // =================================== rail ===================================
    private View buildRail() {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_HORIZONTAL); r.setPadding(0, dp(26), 0, dp(22));
        r.setBackgroundColor(0x66000000);
        View logo = logoView(this);
        // back to the smart-home dashboard (HomeActivity) when it is on
        logo.setOnClickListener(v -> { if (cfg.dashboard()) startActivity(new Intent(this, HomeActivity.class)); });
        logo.setContentDescription("Home");
        r.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(42)));
        railClock = Theme.text(this, "", 21, TEXT, MEDIUM); railClock.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = dp(12);
        r.addView(railClock, cl);
        View gap = new View(this); r.addView(gap, new LinearLayout.LayoutParams(1, dp(22)));
        navItems[NOW] = navItem(NOTE, "Playing", () -> show(NOW));
        navItems[MUSIC] = navItem(LIBRARY, "Music", () -> show(MUSIC));
        navItems[ROOMS] = navItem(Theme.ROOMS, "Rooms", () -> show(ROOMS));
        // same size + same gap for every item, all centred in the rail
        for (View v : navItems) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(76), dp(76)); p.topMargin = dp(10); r.addView(v, p); }
        r.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        r.addView(navItem(GEAR, "Settings", () -> startActivity(new Intent(this, SettingsActivity.class))), new LinearLayout.LayoutParams(dp(76), dp(76)));
        return r;
    }

    private View navItem(int icon, String label, Runnable go) {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(28), dp(28)); ip.gravity = Gravity.CENTER_HORIZONTAL;
        v.addView(new Icon(this, icon, MUTED), ip);
        TextView t = Theme.text(this, label, 12, MUTED, MEDIUM); t.setPadding(0, dp(6), 0, 0); t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2); tp.gravity = Gravity.CENTER_HORIZONTAL;
        v.addView(t, tp);
        clickable(v, box(this, 0x00000000, 18), 18);
        v.setContentDescription(label);
        v.setOnClickListener(x -> go.run());
        return v;
    }

    private void markNav() {
        for (int i = 0; i < navItems.length; i++) {
            LinearLayout v = (LinearLayout) navItems[i];
            boolean sel = i == page;
            ((Icon) v.getChildAt(0)).color(sel ? BRAND : MUTED);
            ((TextView) v.getChildAt(1)).setTextColor(sel ? TEXT : MUTED);
            v.setBackground(press(this, box(this, sel ? 0x1AFFFFFF : 0x00000000, 18), 18, false));
        }
    }

    private void updateClock() {
        Date d = new Date();
        String t = new SimpleDateFormat("HH:mm", Locale.US).format(d);
        if (railClock != null) railClock.setText(t);
        if (idleClock != null) {
            idleClock.setText(t);
            idleDate.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(d));
        }
    }

    private void show(int p) {
        page = p; roomsSig = ""; shareSig = ""; updateSig = "";
        content.removeAllViews();
        idleClock = null; idleDate = null;
        if (p == NOW) buildNow(); else if (p == MUSIC) buildMusic(); else buildRooms();
        markNav();
        updateClock();
        if (n != null) render();
    }

    // =================================== PLAYING ===================================
    private Cover npCover; private LinearLayout npIdleLeft; private TextView idleClock, idleDate;
    private LinearLayout npUpdate, npShare, npBanner, npPlayingBox, npElsewhere, npElsewhereList;
    private TextView npBrowse, npZone, npTitle, npArtist, npMeta, npElapsed, npDuration, npVolLabel, npVolValue, npBreak;
    private Slider npProgress, npVol, npAllVol;
    private LinearLayout npAllRow; private TextView npAllValue;
    private FrameLayout npPlay, npMute;
    private String elsewhereSig = "";

    private void buildNow() {
        float artDp = Math.min(hDp - 84, (wDp - 96) * 0.42f);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(44), dp(36), dp(52), dp(32));

        FrameLayout left = new FrameLayout(this);
        npCover = new Cover(this, 14, 72);
        npCover.setElevation(dp(14));
        left.addView(npCover, new FrameLayout.LayoutParams(-1, -1));
        npIdleLeft = new LinearLayout(this); npIdleLeft.setOrientation(LinearLayout.VERTICAL); npIdleLeft.setGravity(Gravity.CENTER_VERTICAL);
        idleClock = Theme.text(this, "", artDp > 380 ? 112 : 92, TEXT, LIGHT);
        idleDate = Theme.text(this, "", 20, SUB, REGULAR); idleDate.setPadding(dp(6), dp(10), 0, 0);
        npIdleLeft.addView(idleClock); npIdleLeft.addView(idleDate);
        LinearLayout brand = brandLine(this, 26, 12, SUB);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2); bl.topMargin = dp(40); bl.leftMargin = dp(6);
        npIdleLeft.addView(brand, bl);
        left.addView(npIdleLeft, new FrameLayout.LayoutParams(-1, -1));
        row.addView(left, new LinearLayout.LayoutParams(dp(artDp), dp(artDp)));

        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.leftMargin = dp(52);
        row.addView(r, rp);

        npBanner = new LinearLayout(this); npBanner.setOrientation(LinearLayout.VERTICAL);
        r.addView(npBanner);
        npShare = new LinearLayout(this); npShare.setOrientation(LinearLayout.VERTICAL);
        r.addView(npShare);
        npUpdate = new LinearLayout(this); npUpdate.setOrientation(LinearLayout.VERTICAL);
        r.addView(npUpdate);

        // where this music plays: tap to group
        LinearLayout zone = new LinearLayout(this); zone.setGravity(Gravity.CENTER_VERTICAL);
        zone.setPadding(dp(14), 0, dp(10), 0);
        clickable(zone, box(this, 0x24FFFFFF, 20), 20);
        zone.addView(new Icon(this, SPEAKER, TEXT), new LinearLayout.LayoutParams(dp(20), dp(20)));
        npZone = Theme.text(this, "", 15, TEXT, MEDIUM); npZone.setPadding(dp(8), 0, dp(4), 0);
        zone.addView(npZone);
        zone.addView(new Icon(this, CHEVRON, SUB), new LinearLayout.LayoutParams(dp(18), dp(18)));
        zone.setOnClickListener(v -> openGroupSheet());
        zone.setContentDescription("Group rooms");
        r.addView(zone, new LinearLayout.LayoutParams(-2, dp(40)));

        npTitle = Theme.text(this, "", 34, TEXT, MEDIUM);
        npTitle.setSingleLine(false); npTitle.setMaxLines(2); npTitle.setLineSpacing(0, 1.08f);
        r.addView(npTitle, top(-1, -2, 22));
        npArtist = Theme.text(this, "", 20, SUB, REGULAR);
        r.addView(npArtist, top(-1, -2, 10));
        npMeta = Theme.text(this, "", 15, MUTED, REGULAR);
        r.addView(npMeta, top(-1, -2, 10));

        // playing-only part: progress + transport
        npPlayingBox = new LinearLayout(this); npPlayingBox.setOrientation(LinearLayout.VERTICAL);
        npProgress = new Slider(this).thumb(false);
        npPlayingBox.addView(npProgress, top(-1, dp(14), 22));
        LinearLayout times = new LinearLayout(this);
        npElapsed = Theme.text(this, "", 13, MUTED, REGULAR); npDuration = Theme.text(this, "", 13, MUTED, REGULAR);
        times.addView(npElapsed, new LinearLayout.LayoutParams(0, -2, 1)); times.addView(npDuration);
        npPlayingBox.addView(times, top(-1, -2, 4));
        LinearLayout tr = new LinearLayout(this); tr.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout prev = iconButton(this, PREV, 60, 30, 0, "Previous"); prev.setOnClickListener(v -> act(s -> s.groupCmd("previous")));
        npPlay = iconButton(this, PLAY, 76, 34, 1, "Play or pause"); npPlay.setOnClickListener(v -> act(PanelService::playPause));
        FrameLayout next = iconButton(this, NEXT, 60, 30, 0, "Next"); next.setOnClickListener(v -> act(s -> s.groupCmd("next")));
        tr.addView(prev); tr.addView(npPlay, side(76, 76, 22)); tr.addView(next, side(60, 60, 22));
        npPlayingBox.addView(tr, top(-2, -2, 14));
        r.addView(npPlayingBox, new LinearLayout.LayoutParams(-1, -2));

        // idle-only part: music elsewhere
        npElsewhere = new LinearLayout(this); npElsewhere.setOrientation(LinearLayout.VERTICAL);
        npElsewhere.addView(Theme.text(this, "Playing in other rooms", 15, SUB, MEDIUM), top(-2, -2, 26));
        npElsewhereList = new LinearLayout(this); npElsewhereList.setOrientation(LinearLayout.VERTICAL);
        npElsewhere.addView(npElsewhereList, top(-1, -2, 10));
        r.addView(npElsewhere, new LinearLayout.LayoutParams(-1, -2));

        // volume (room or whole group)
        npVolLabel = Theme.text(this, "", 13, MUTED, MEDIUM);
        r.addView(npVolLabel, top(-2, -2, 22));
        LinearLayout vol = new LinearLayout(this); vol.setGravity(Gravity.CENTER_VERTICAL);
        npMute = iconButton(this, VOLUME, 44, 24, 0, "Mute this room"); npMute.setOnClickListener(v -> act(PanelService::pauseHere));
        vol.addView(npMute);
        npVol = new Slider(this).listener((v, done) -> { if (done) act(s -> s.setVolume(v)); });
        vol.addView(npVol, side(0, 40, 6, 1));
        npVolValue = Theme.text(this, "", 15, SUB, MEDIUM); npVolValue.setGravity(Gravity.END);
        vol.addView(npVolValue, side(40, -2, 8));
        r.addView(vol, top(-1, -2, 4));
        // whole-group volume: only on the panel that started this music (D-020)
        npAllRow = new LinearLayout(this); npAllRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout gi = new FrameLayout(this);
        gi.addView(new Icon(this, GROUP, SUB), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        npAllRow.addView(gi, new LinearLayout.LayoutParams(dp(44), dp(40)));
        npAllVol = new Slider(this).listener((v, done) -> { if (done) act(s -> s.setGroupVolume(v)); });
        npAllRow.addView(npAllVol, side(0, 40, 6, 1));
        npAllValue = Theme.text(this, "", 15, SUB, MEDIUM); npAllValue.setGravity(Gravity.END);
        npAllRow.addView(npAllValue, side(40, -2, 8));
        npAllRow.setVisibility(View.GONE);
        r.addView(npAllRow, top(-1, -2, 0));

        LinearLayout acts = new LinearLayout(this);
        TextView grp = pill(this, "Group", 0); grp.setOnClickListener(v -> openGroupSheet());
        npBreak = pill(this, "Break", 0); npBreak.setOnClickListener(v -> openBreak());
        npBrowse = pill(this, "Browse", 1); npBrowse.setOnClickListener(v -> show(MUSIC));
        acts.addView(npBrowse, new LinearLayout.LayoutParams(-2, dp(48)));
        acts.addView(grp, side(-2, 48, 12));
        acts.addView(npBreak, side(-2, 48, 12));
        HorizontalScrollView ahs = new HorizontalScrollView(this); ahs.setHorizontalScrollBarEnabled(false);
        ahs.addView(acts);
        r.addView(ahs, top(-1, -2, 22));

        content.addView(row, new FrameLayout.LayoutParams(-1, -1));
        elsewhereSig = "";
    }

    private void renderNow() {
        boolean act = n.active();
        backdrop.set(act ? art : null);
        npCover.setVisibility(act ? View.VISIBLE : View.GONE);
        npIdleLeft.setVisibility(act ? View.GONE : View.VISIBLE);
        npCover.set(act ? art : null);
        npPlayingBox.setVisibility(act ? View.VISIBLE : View.GONE);
        npElsewhere.setVisibility(View.GONE);

        npZone.setText(grouped() ? zoneLabel() : myName());
        if (act) {
            npTitle.setText(n.title);
            npArtist.setText(n.artist + (n.album.isEmpty() ? "" : "  ·  " + n.album));
            StringBuilder meta = new StringBuilder();
            if (!n.user.isEmpty()) meta.append("Played by ").append(n.user);
            if (!n.source.isEmpty()) meta.append(meta.length() > 0 ? "  ·  " : "").append(n.source);
            if (!"playing".equals(n.state)) meta.append(meta.length() > 0 ? "  ·  " : "").append("Paused");
            npMeta.setText(meta);
            npMeta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);
            npProgress.setValue(n.duration > 0 ? (int) (100 * Math.min(1, n.elapsed / n.duration)) : 0);
            npElapsed.setText(n.duration > 0 ? Ui.fmt(n.elapsed) : "");
            npDuration.setText(n.duration > 0 ? Ui.fmt(n.duration) : "Live");
            iconOf(npPlay).set("playing".equals(n.state) ? PAUSE : PLAY);
        } else {
            npTitle.setText(n.registered && n.serverOk ? "Nothing playing" : statusText());
            npArtist.setText(n.registered && n.serverOk ? "in " + myName() : "");
            npMeta.setText("Pick something in Music, or play from the AJ Audio app, AirPlay or Bluetooth.");
            npMeta.setVisibility(n.registered && n.serverOk ? View.VISIBLE : View.GONE);
            renderElsewhere();
        }
        // volume: whole group when grouped
        Site st = site();
        int v = n.volume;
        npVolLabel.setText("Volume · " + myName());
        if (!npVol.dragging() && v >= 0) { npVol.setValue(v); npVolValue.setText(String.valueOf(v)); }
        boolean all = grouped() && st != null && origin();
        npAllRow.setVisibility(all ? View.VISIBLE : View.GONE);
        if (all && !npAllVol.dragging()) { int gv = zoneVolume(st); if (gv >= 0) { npAllVol.setValue(gv); npAllValue.setText("All " + gv); } }
        iconOf(npMute).set(n.muted ? VOLUME_OFF : VOLUME);
        npMute.setContentDescription(n.muted ? "Unmute this room" : "Mute this room");
        npBreak.setVisibility(grouped() ? View.VISIBLE : View.GONE);
        npBrowse.setVisibility(act ? View.GONE : View.VISIBLE);
        renderBanner();
        renderShare();
        renderUpdate();
    }

    private String zoneLabel() {
        Site st = site();
        String name = n.groupName.replaceAll(" \\+\\d+$", "");
        int count = n.groupSize;
        if (st != null) count = st.zoneMembers(n.groupId).size();
        return count > 1 ? name + "  ·  " + count + " rooms" : name;
    }

    private int zoneVolume(Site st) {
        List<String> ids = st.zoneMembers(n.groupId);
        int sum = 0, k = 0;
        for (String id : ids) { Site.Room r = st.room(id); if (r != null && r.volume >= 0) { sum += r.volume; k++; } }
        return k == 0 ? n.volume : Math.round(sum / (float) k);
    }

    /** idle: show what plays in other rooms, one tap to join */
    private void renderElsewhere() {
        Site st = site();
        if (st == null) return;
        StringBuilder sig = new StringBuilder();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, List<Site.Room>> z : st.zones().entrySet()) {
            Site.Room lead = z.getValue().get(0);
            String title = zoneTitle(st, z.getKey(), lead);
            if (z.getValue().contains(st.room(me())) || title.isEmpty() || !zonePlaying(st, z.getKey(), lead)) continue;
            keys.add(z.getKey()); sig.append(z.getKey()).append(title).append(z.getValue().size());
            if (keys.size() == 3) break;
        }
        npElsewhere.setVisibility(keys.isEmpty() ? View.GONE : View.VISIBLE);
        if (sig.toString().equals(elsewhereSig)) return;
        elsewhereSig = sig.toString();
        npElsewhereList.removeAllViews();
        for (String k : keys) {
            List<Site.Room> rooms = st.zones().get(k);
            if (rooms == null) continue;
            Site.Room lead = rooms.get(0);
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(8), dp(8));
            row.setBackground(box(this, 0x14FFFFFF, 14));
            Cover c = new Cover(this, 8, 22);
            row.addView(c, new LinearLayout.LayoutParams(dp(48), dp(48)));
            Art.thumb(cfg, zoneImage(st, k, lead), c::set);
            LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(dp(14), 0, dp(10), 0);
            tx.addView(Theme.text(this, zoneName(st, k, rooms), 16, TEXT, MEDIUM));
            TextView sub = Theme.text(this, zoneTitle(st, k, lead), 14, SUB, REGULAR); sub.setPadding(0, dp(4), 0, 0);
            tx.addView(sub);
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            TextView join = pill(this, "Join", 1); join.setMinHeight(dp(40));
            join.setOnClickListener(v -> act(s -> s.joinZone(k)));
            row.addView(join, new LinearLayout.LayoutParams(-2, dp(40)));
            npElsewhereList.addView(row, top(-1, -2, npElsewhereList.getChildCount() == 0 ? 0 : 8));
        }
    }

    private String updateSig = "";
    /** "AJ Panel vX is available" – one tap downloads it, Android asks "Install?" */
    private void renderUpdate() {
        String sig = Updater.availableVersion + "|" + Updater.state;
        if (sig.equals(updateSig)) return;
        updateSig = sig;
        npUpdate.removeAllViews();
        if (Updater.availableVersion.isEmpty() && Updater.state.isEmpty()) return;
        LinearLayout b = new LinearLayout(this); b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(box(this, 0x2A6BBC46, 16)); b.setPadding(dp(16), dp(10), dp(10), dp(10));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(0, 0, dp(8), 0);
        boolean busy = Updater.state.equals("downloading") || Updater.state.equals("waiting for Install");
        tx.addView(Theme.text(this, Updater.availableVersion.isEmpty() ? "App update" : "AJ Panel " + Updater.availableVersion + " is available", 16, TEXT, MEDIUM));
        String sub = Updater.state.isEmpty() ? "You have " + Updater.currentVersion(this) + ". Music keeps playing; it restarts for a moment."
                : Updater.state.equals("downloading") ? "Downloading…" : Updater.state.equals("waiting for Install") ? "Tap Install in the Android window" : Updater.state;
        TextView d = Theme.text(this, sub, 13, SUB, REGULAR); d.setSingleLine(false); d.setMaxLines(2); d.setPadding(0, dp(4), 0, 0);
        tx.addView(d);
        b.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        if (!busy && !Updater.availableVersion.isEmpty()) {
            TextView go = pill(this, "Update", 1); go.setMinHeight(dp(40));
            go.setOnClickListener(v -> { Updater.state = "downloading"; updateSig = ""; renderUpdate(); act(PanelService::installUpdate); });
            b.addView(go, new LinearLayout.LayoutParams(-2, dp(40)));
        }
        npUpdate.addView(b, new LinearLayout.LayoutParams(-1, -2));
        npUpdate.addView(new View(this), new LinearLayout.LayoutParams(1, dp(18)));
    }

    private void renderBanner() {
        PanelService s = svc();
        npBanner.removeAllViews();
        if (s == null || !s.breaks.active()) return;
        Site st = site();
        LinearLayout b = new LinearLayout(this); b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(box(this, 0x26F2B54A, 16)); b.setPadding(dp(16), dp(10), dp(10), dp(10));
        b.addView(new Icon(this, MOON, AMBER), new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(dp(12), 0, dp(8), 0);
        tx.addView(Theme.text(this, "On a break from " + (st == null ? "the group" : st.nameOf(s.breaks.groupId())), 16, TEXT, MEDIUM));
        TextView d = Theme.text(this, s.breaks.describe(), 13, 0xFFE8D2A6, REGULAR); d.setPadding(0, dp(4), 0, 0);
        tx.addView(d);
        b.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = pill(this, "+30 min", 0); more.setMinHeight(dp(40)); more.setOnClickListener(v -> act(x -> x.extendBreak(30 * 60_000L)));
        TextView back = pill(this, "Rejoin", 1); back.setMinHeight(dp(40)); back.setOnClickListener(v -> act(PanelService::endBreak));
        b.addView(more, new LinearLayout.LayoutParams(-2, dp(40)));
        b.addView(back, side(-2, 40, 8));
        npBanner.addView(b, new LinearLayout.LayoutParams(-1, -2));
        View gap = new View(this); npBanner.addView(gap, new LinearLayout.LayoutParams(1, dp(18)));
    }

    private String statusText() {
        PanelService s = svc();
        return s == null ? "Starting…" : s.statusLine(n);
    }

    // =================================== ROOMS ===================================
    private LinearLayout scenesRow, grid; private TextView roomsSub;
    private String roomsSig = "";

    private void buildRooms() {
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(40), dp(34), dp(44), dp(32));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ht = new LinearLayout(this); ht.setOrientation(LinearLayout.VERTICAL);
        ht.addView(Theme.text(this, "Rooms", 30, TEXT, MEDIUM));
        roomsSub = Theme.text(this, "", 15, SUB, REGULAR); roomsSub.setPadding(0, dp(8), 0, 0);
        ht.addView(roomsSub);
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        TextView g = pill(this, "Group this room", 1); g.setOnClickListener(v -> openGroupSheet());
        head.addView(g, new LinearLayout.LayoutParams(-2, dp(48)));
        col.addView(head);

        col.addView(Theme.text(this, "Scenes", 17, TEXT, MEDIUM), top(-2, -2, 30));
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        scenesRow = new LinearLayout(this); hs.addView(scenesRow);
        col.addView(hs, top(-1, -2, 12));

        col.addView(Theme.text(this, "All rooms", 17, TEXT, MEDIUM), top(-2, -2, 30));
        grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        col.addView(grid, top(-1, -2, 12));
        sv.addView(col);
        content.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    }

    private void renderRooms() {
        backdrop.set(n != null && n.active() ? art : null);
        Site st = site();
        if (st == null) { roomsSub.setText("Loading rooms…"); return; }
        Map<String, List<Site.Room>> zones = st.zones();
        StringBuilder sig = new StringBuilder();
        for (Site.Group gr : st.groups) sig.append(gr.id).append(gr.state).append(gr.members);
        for (Map.Entry<String, List<Site.Room>> z : zones.entrySet()) {
            Site.Room l = z.getValue().get(0);
            sig.append(z.getKey()).append(z.getValue().size()).append(zoneTitle(st, z.getKey(), l)).append(zonePlaying(st, z.getKey(), l));
            for (Site.Room r : z.getValue()) sig.append(r.available);
        }
        sig.append(n == null ? "" : n.active());
        if (sig.toString().equals(roomsSig)) return;
        roomsSig = sig.toString();

        int playing = 0, total = 0;
        for (Site.Room r : st.rooms) { total++; if (zonePlaying(st, st.zoneOf(r), r)) playing++; }
        roomsSub.setText(playing == 0 ? total + " rooms · all quiet" : playing + " of " + total + " rooms playing");

        // scenes
        scenesRow.removeAllViews();
        String myZone = myZone(st);
        List<String> mine = st.zoneMembers(myZone);
        List<String> everyone = new ArrayList<>();
        for (Site.Room r : st.rooms) if (r.available) everyone.add(r.id);
        boolean all = mine.containsAll(everyone) && everyone.size() > 1;
        scenesRow.addView(scene("Everywhere", everyone.size() + " rooms", all, () -> {
            List<String> add = new ArrayList<>(); for (String id : everyone) if (!mine.contains(id)) add.add(id);
            act(s -> s.applyGroup(add, new ArrayList<>()));
        }));
        for (Site.Group gr : st.groups) {
            boolean in = gr.id.equals(myZone);
            String sub = gr.members.size() + " rooms" + (gr.playing() && !gr.title.isEmpty() ? " · " + gr.title : "");
            scenesRow.addView(scene(gr.name, sub, in, () -> {
                if (in) { openGroupSheet(); return; }
                if (!gr.playing() && n != null && n.active()) {
                    // my music → play it in this scene's rooms too
                    List<String> add = new ArrayList<>(); for (String id : gr.members) if (!mine.contains(id)) add.add(id);
                    act(s -> s.applyGroup(add, new ArrayList<>()));
                } else act(s -> s.joinGroup(gr.id));
            }));
        }

        // zone cards, grid
        grid.removeAllViews();
        int cols = (wDp - 96) >= 1050 ? 3 : 2;
        LinearLayout line = null; int i = 0;
        List<Map.Entry<String, List<Site.Room>>> ordered = new ArrayList<>(zones.entrySet());
        // this room's zone first
        for (int k = 0; k < ordered.size(); k++) if (ordered.get(k).getKey().equals(myZone)) { ordered.add(0, ordered.remove(k)); break; }
        for (Map.Entry<String, List<Site.Room>> z : ordered) {
            if (i % cols == 0) { line = new LinearLayout(this); grid.addView(line, top(-1, -2, i == 0 ? 0 : 12)); }
            line.addView(zoneCard(st, z.getKey(), z.getValue(), z.getKey().equals(myZone)), side(0, 108, i % cols == 0 ? 0 : 12, 1));
            i++;
        }
        while (line != null && i % cols != 0) { line.addView(new View(this), side(0, 108, 12, 1)); i++; }
    }

    private String myZone(Site st) {
        Site.Room r = st.room(me());
        return r == null ? me() : st.zoneOf(r);
    }

    private View scene(String name, String sub, boolean selected, Runnable tap) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(18), 0, dp(18), 0);
        clickable(c, selected ? outline(this, SURFACE, TEXT, 16) : box(this, SURFACE, 16), 16);
        LinearLayout tl = new LinearLayout(this); tl.setGravity(Gravity.CENTER_VERTICAL);
        tl.addView(new Icon(this, GROUP, selected ? TEXT : SUB), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = Theme.text(this, name, 17, TEXT, MEDIUM); t.setPadding(dp(10), 0, 0, 0);
        tl.addView(t);
        c.addView(tl);
        TextView s = Theme.text(this, selected ? "This room is in it" : sub, 13, selected ? TEXT : SUB, REGULAR); s.setPadding(0, dp(8), 0, 0);
        c.addView(s);
        c.setOnClickListener(v -> tap.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(230), dp(80)); p.rightMargin = dp(12);
        c.setLayoutParams(p);
        return c;
    }

    private View zoneCard(Site st, String key, List<Site.Room> rooms, boolean mine) {
        Site.Room lead = rooms.get(0);
        boolean online = false; for (Site.Room r : rooms) online |= r.available;
        boolean playing = zonePlaying(st, key, lead);
        LinearLayout c = new LinearLayout(this); c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        clickable(c, mine ? outline(this, SURFACE, 0x66FFFFFF, 18) : box(this, SURFACE, 18), 18);
        c.setAlpha(online ? 1f : 0.45f);

        Cover cover = new Cover(this, 10, 30);
        cover.placeholder(SPEAKER);
        c.addView(cover, new LinearLayout.LayoutParams(dp(80), dp(80)));
        if (playing) Art.thumb(cfg, zoneImage(st, key, lead), cover::set);

        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(dp(14), 0, dp(8), 0);
        tx.addView(Theme.text(this, zoneName(st, key, rooms), 17, TEXT, MEDIUM));
        String title = zoneTitle(st, key, lead);
        TextView sub = Theme.text(this, !online ? "Offline" : playing && !title.isEmpty() ? title : "Nothing playing", 14, playing ? SUB : MUTED, REGULAR);
        sub.setPadding(0, dp(6), 0, 0);
        tx.addView(sub);
        LinearLayout badge = new LinearLayout(this); badge.setGravity(Gravity.CENTER_VERTICAL); badge.setPadding(0, dp(8), 0, 0);
        Bars bars = new Bars(this); bars.playing(playing && "playing".equals(zoneState(st, key, lead)));
        badge.addView(bars, new LinearLayout.LayoutParams(dp(14), dp(12)));
        if (mine) {
            TextView t = Theme.text(this, "This room", 12, LIVE, MEDIUM); t.setPadding(playing ? dp(8) : 0, 0, 0, 0);
            badge.addView(t);
        } else if (rooms.size() > 1) {
            TextView t = Theme.text(this, rooms.size() + " rooms together", 12, MUTED, MEDIUM); t.setPadding(playing ? dp(8) : 0, 0, 0, 0);
            badge.addView(t);
        }
        tx.addView(badge);
        c.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));

        String label; Runnable tap;
        if (mine) { label = "Group"; tap = this::openGroupSheet; }
        else if (!online) { label = null; tap = null; }
        else if (playing) { label = "Join"; tap = () -> act(s -> s.joinZone(key)); }
        else {
            label = "Add";
            List<String> add = new ArrayList<>(); for (Site.Room r : rooms) if (r.available) add.add(r.id);
            tap = () -> act(s -> s.applyGroup(add, new ArrayList<>()));
        }
        if (label != null) {
            TextView b = pill(this, label, mine ? 0 : playing ? 1 : 2); b.setMinHeight(dp(40)); b.setPadding(dp(18), 0, dp(18), 0);
            b.setOnClickListener(v -> tap.run());
            c.addView(b, new LinearLayout.LayoutParams(-2, dp(40)));
        }
        if (mine) c.setOnClickListener(v -> show(NOW));
        else if (tap != null) c.setOnClickListener(v -> tap.run());
        return c;
    }

    // zone helpers: a zone is a saved group, a leader with followers, or a single room
    private String zoneName(Site st, String key, List<Site.Room> rooms) {
        Site.Group g = st.group(key);
        if (g != null) return g.name;
        if (rooms.size() == 1) return rooms.get(0).name;
        Site.Room l = st.room(key);
        return (l == null ? rooms.get(0).name : l.name) + " + " + (rooms.size() - 1);
    }
    private String zoneTitle(Site st, String key, Site.Room lead) {
        Site.Group g = st.group(key);
        String t = g != null ? g.title : (st.room(key) != null ? st.room(key).title : lead.title);
        String a = g != null ? g.artist : (st.room(key) != null ? st.room(key).artist : lead.artist);
        if (t == null || t.isEmpty()) return "";
        return a == null || a.isEmpty() ? t : t + " · " + a;
    }
    private String zoneImage(Site st, String key, Site.Room lead) {
        Site.Group g = st.group(key);
        return g != null ? g.image : (st.room(key) != null ? st.room(key).image : lead.image);
    }
    private String zoneState(Site st, String key, Site.Room lead) {
        Site.Group g = st.group(key);
        return g != null ? g.state : (st.room(key) != null ? st.room(key).state : lead.state);
    }
    private boolean zonePlaying(Site st, String key, Site.Room lead) {
        String s = zoneState(st, key, lead);
        return "playing".equals(s) || "paused".equals(s);
    }

    // =================================== MUSIC (library) ===================================
    private static final String[][] TABS = {{Library.RECENT, "Recent"}, {Library.PLAYLISTS, "Playlists"}, {Library.ALBUMS, "Albums"},
            {Library.ARTISTS, "Artists"}, {Library.RADIO, "Radio"}, {Library.SONGS, "Songs"}};
    private String tab = Library.PLAYLISTS, query = "";
    private String playTarget = "";            // "" = where this room plays now
    private LinearLayout tabsRow, musicBody; private TextView musicTarget, musicStatus;
    private android.widget.EditText searchBox;
    private int musicReq = 0;

    private void buildMusic() {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(40), dp(30), dp(40), 0);
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Theme.text(this, "Music", 30, TEXT, MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));

        searchBox = new android.widget.EditText(this);
        searchBox.setSingleLine(true); searchBox.setTextSize(Theme.scale * 16); searchBox.setTextColor(TEXT); searchBox.setHintTextColor(MUTED);
        searchBox.setHint("Search songs, artists, radio");
        searchBox.setBackground(box(this, SURFACE2, 22)); searchBox.setPadding(dp(18), 0, dp(18), 0);
        searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.setText(query);
        searchBox.setOnEditorActionListener((v, id, ev) -> {
            query = v.getText().toString().trim();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
            loadMusic();
            return true;
        });
        head.addView(searchBox, side((int) Math.min(340, wDp * 0.3f), 44, 0));

        LinearLayout tgt = new LinearLayout(this); tgt.setGravity(Gravity.CENTER_VERTICAL); tgt.setPadding(dp(14), 0, dp(10), 0);
        clickable(tgt, box(this, SURFACE2, 22), 22);
        tgt.addView(new Icon(this, SPEAKER, TEXT), new LinearLayout.LayoutParams(dp(20), dp(20)));
        musicTarget = Theme.text(this, "", 15, TEXT, MEDIUM); musicTarget.setPadding(dp(8), 0, dp(4), 0);
        musicTarget.setMaxWidth(dp(220));
        tgt.addView(musicTarget);
        tgt.addView(new Icon(this, CHEVRON, SUB), new LinearLayout.LayoutParams(dp(18), dp(18)));
        tgt.setOnClickListener(v -> openTargetPicker());
        tgt.setContentDescription("Where to play");
        head.addView(tgt, side(-2, 44, 12));
        col.addView(head);

        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        tabsRow = new LinearLayout(this); hs.addView(tabsRow);
        col.addView(hs, top(-1, -2, 20));

        musicStatus = Theme.text(this, "", 15, SUB, REGULAR);
        col.addView(musicStatus, top(-2, -2, 16));
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false);
        musicBody = new LinearLayout(this); musicBody.setOrientation(LinearLayout.VERTICAL); musicBody.setPadding(0, 0, 0, dp(30));
        sv.addView(musicBody);
        col.addView(sv, top(-1, 0, 10, 1));
        content.addView(col, new FrameLayout.LayoutParams(-1, -1));
        renderTabs();
        renderMusic();
        loadMusic();
    }

    private void renderTabs() {
        tabsRow.removeAllViews();
        for (String[] t : TABS) {
            boolean sel = query.isEmpty() && t[0].equals(tab);
            TextView c = pill(this, t[1], sel ? 1 : 0); c.setTextSize(Theme.scale * 15); c.setMinHeight(dp(40)); c.setPadding(dp(18), 0, dp(18), 0);
            c.setOnClickListener(v -> { tab = t[0]; query = ""; searchBox.setText(""); renderTabs(); loadMusic(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(40)); p.rightMargin = dp(8);
            tabsRow.addView(c, p);
        }
    }

    /** the player the Music page plays on */
    private String target() {
        if (!playTarget.isEmpty()) return playTarget;
        Site st = site();
        return st == null ? me() : myZone(st);
    }
    private String targetName() {
        Site st = site();
        String t = target();
        if (st == null) return myName();
        if (t.equals(me())) return myName();
        Site.Group g = st.group(t);
        if (g != null) return g.name;
        List<String> m = st.zoneMembers(t);
        return st.nameOf(t) + (m.size() > 1 ? " + " + (m.size() - 1) : "");
    }

    private void renderMusic() {
        if (musicTarget != null) musicTarget.setText("Play on " + targetName());
    }

    private void loadMusic() {
        final int req = ++musicReq;
        final String q = query, t = tab;
        musicBody.removeAllViews();
        musicStatus.setText(q.isEmpty() ? "Loading…" : "Searching for “" + q + "”…");
        PanelService s = svc();
        if (s == null) return;
        worker.execute(() -> {
            List<List<Library.Item>> sections = new ArrayList<>();
            String err = null;
            try {
                if (q.isEmpty()) sections.add(Library.load(s.api(), t));
                else sections = Library.search(s.api(), q);
            } catch (Exception e) { err = e.getMessage(); Log.e("library " + t + " " + q, e); }
            final List<List<Library.Item>> res = sections; final String er = err;
            runOnUiThread(() -> { if (req == musicReq && page == MUSIC) showMusic(res, er, q, t); });
        });
    }

    private void showMusic(List<List<Library.Item>> sections, String err, String q, String t) {
        musicBody.removeAllViews();
        int total = 0; for (List<Library.Item> l : sections) total += l.size();
        if (err != null) { musicStatus.setText("Couldn't load the library (" + err + ")"); return; }
        if (total == 0) {
            musicStatus.setText(!q.isEmpty() ? "Nothing found for “" + q + "”"
                    : Library.RECENT.equals(t) ? "Nothing played yet" : "Nothing here yet – add music in Music Assistant");
            return;
        }
        musicStatus.setText(q.isEmpty() ? "Tap to play on " + targetName() : "Results for “" + q + "”");
        if (!q.isEmpty()) {
            String[] names = {"Songs", "Artists", "Albums", "Playlists", "Radio"};
            for (int i = 0; i < sections.size() && i < names.length; i++) {
                List<Library.Item> l = sections.get(i);
                if (l.isEmpty()) continue;
                musicBody.addView(Theme.text(this, names[i], 17, TEXT, MEDIUM), top(-2, -2, musicBody.getChildCount() == 0 ? 6 : 26));
                if (i == 0) addTrackRows(l); else addTiles(l);
            }
        } else if (Library.SONGS.equals(t)) addTrackRows(sections.get(0));
        else addTiles(sections.get(0));
    }

    private void addTiles(List<Library.Item> items) {
        float avail = wDp - 96 - 80;
        int cols = Math.max(3, (int) ((avail + 16) / (168 + 16)));
        int tile = (int) ((avail - 16 * (cols - 1)) / cols);
        LinearLayout line = null; int i = 0;
        for (Library.Item it : items) {
            if (i % cols == 0) { line = new LinearLayout(this); musicBody.addView(line, top(-1, -2, 14)); }
            LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
            clickable(c, box(this, 0x00000000, 12), 12);
            boolean round = "artist".equals(it.type);
            Cover cv = new Cover(this, round ? tile / 2f : 10, 40);
            cv.placeholder("radio".equals(it.type) ? SPEAKER : NOTE);
            c.addView(cv, new LinearLayout.LayoutParams(dp(tile), dp(tile)));
            Art.thumb(cfg, it.image, cv::set);
            TextView nm = Theme.text(this, it.name, 15, TEXT, MEDIUM); nm.setPadding(dp(2), dp(10), dp(2), 0);
            if (round) nm.setGravity(Gravity.CENTER_HORIZONTAL);
            c.addView(nm, new LinearLayout.LayoutParams(-1, -2));
            if (!it.sub.isEmpty()) {
                TextView sb = Theme.text(this, it.sub, 13, SUB, REGULAR); sb.setPadding(dp(2), dp(4), dp(2), dp(4));
                if (round) sb.setGravity(Gravity.CENTER_HORIZONTAL);
                c.addView(sb, new LinearLayout.LayoutParams(-1, -2));
            }
            c.setOnClickListener(v -> play(it));
            line.addView(c, side(tile, -2, i % cols == 0 ? 0 : 16));
            i++;
        }
    }

    private void addTrackRows(List<Library.Item> items) {
        LinearLayout line = null; int i = 0;
        for (Library.Item it : items) {
            if (i % 2 == 0) { line = new LinearLayout(this); musicBody.addView(line, top(-1, -2, i == 0 ? 8 : 4)); }
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(8), dp(8), dp(8), dp(8));
            clickable(r, box(this, 0x00000000, 12), 12);
            Cover cv = new Cover(this, 6, 22);
            r.addView(cv, new LinearLayout.LayoutParams(dp(52), dp(52)));
            Art.thumb(cfg, it.image, cv::set);
            LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(dp(14), 0, dp(8), 0);
            tx.addView(Theme.text(this, it.name, 16, TEXT, MEDIUM));
            TextView sb = Theme.text(this, it.sub, 14, SUB, REGULAR); sb.setPadding(0, dp(4), 0, 0); tx.addView(sb);
            r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(new Icon(this, PLAY, SUB), new LinearLayout.LayoutParams(dp(22), dp(22)));
            r.setOnClickListener(v -> play(it));
            line.addView(r, side(0, -2, i % 2 == 0 ? 0 : 16, 1));
            i++;
        }
        if (line != null && i % 2 == 1) line.addView(new View(this), side(0, 1, 16, 1));
    }

    private void play(Library.Item it) {
        String t = target();
        Site st = site();
        boolean here = t.equals(me()) || (st != null && st.zoneMembers(t).contains(me()))
                || (st != null && st.group(t) != null && st.group(t).members.contains(me()));
        act(s -> s.playMedia(t, it.uri));
        android.widget.Toast.makeText(this, "Playing “" + it.name + "” on " + targetName(), android.widget.Toast.LENGTH_SHORT).show();
        if (here) show(NOW);
    }

    /** choose where the Music page plays: where this room plays now, this room only, a saved group */
    private void openTargetPicker() {
        Site st = site();
        sheetLayer.removeAllViews();
        LinearLayout d = new LinearLayout(this); d.setOrientation(LinearLayout.VERTICAL); d.setClickable(true);
        d.setBackground(box(this, SURFACE, 22)); d.setPadding(dp(24), dp(22), dp(24), dp(18));
        d.addView(Theme.text(this, "Play on", 22, TEXT, MEDIUM));
        TextView ex = Theme.text(this, "Pick a group to play in all its rooms. To make a new group, use Group rooms.", 14, SUB, REGULAR);
        ex.setSingleLine(false); ex.setMaxLines(2);
        d.addView(ex, top(-1, -2, 8));
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        String zone = st == null ? me() : myZone(st);
        if (!zone.equals(me()) && st != null && st.group(zone) == null)
            list.addView(targetRow(GROUP, st.nameOf(zone) + " + " + (st.zoneMembers(zone).size() - 1), "Where this room plays now", ""));
        list.addView(targetRow(SPEAKER, myName(), "Only this room", me()));
        if (st != null) for (Site.Group g : st.groups)
            list.addView(targetRow(GROUP, g.name, g.members.size() + " rooms" + (g.members.contains(me()) ? " · includes this room" : "")
                    + (g.playing() ? " · playing now" : ""), g.id));
        ScrollView sv = new ScrollView(this); sv.addView(list);
        d.addView(sv, top(-1, -2, 12, 1));
        TextView grp = pill(this, "Group rooms…", 2); grp.setOnClickListener(v -> { closeSheet(); openGroupSheet(); });
        d.addView(grp, top(-2, dp(46), 14));
        sheetLayer.addView(d, new FrameLayout.LayoutParams(dp(Math.min(560, wDp - 120)), dp(Math.min(520, hDp - 60)), Gravity.CENTER));
        sheetLayer.setVisibility(View.VISIBLE);
    }

    private View targetRow(int icon, String name, String sub, String id) {
        boolean sel = id.equals(playTarget) || (playTarget.isEmpty() && id.isEmpty());
        if (playTarget.isEmpty() && id.equals(me()) && site() != null && myZone(site()).equals(me())) sel = true;
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(12), dp(12), dp(12), dp(12));
        clickable(r, sel ? box(this, 0x1AFFFFFF, 14) : box(this, 0x00000000, 14), 14);
        r.addView(new Icon(this, icon, sel ? TEXT : SUB), new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(dp(14), 0, dp(8), 0);
        tx.addView(Theme.text(this, name, 17, TEXT, MEDIUM));
        TextView s = Theme.text(this, sub, 13, SUB, REGULAR); s.setPadding(0, dp(4), 0, 0); tx.addView(s);
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        if (sel) r.addView(new Icon(this, CHECK, TEXT), new LinearLayout.LayoutParams(dp(22), dp(22)));
        r.setOnClickListener(v -> {
            playTarget = id.equals(me()) && site() != null && myZone(site()).equals(me()) ? "" : id;
            closeSheet(); renderMusic();
            if (musicStatus != null && musicStatus.getText().toString().startsWith("Tap to play")) musicStatus.setText("Tap to play on " + targetName());
        });
        return r;
    }

    // =================================== SHARE this panel's sound (YouTube …) ===================================
    private String shareSig = "", dismissedError = "";
    private final Set<String> sharePick = new LinkedHashSet<>();
    private String shareGroup = "";

    /** banner on the Playing screen: another app plays here → offer to play it in other rooms; or sharing status */
    private void renderShare() {
        MediaListener.Local l = ShareService.running ? null : MediaListener.playing(this);
        boolean err = !ShareService.running && !ShareService.lastError.isEmpty() && !ShareService.lastError.equals(dismissedError);
        String sig = ShareService.running + ShareService.sharingTo + ShareService.sharingWhat + ShareService.step + ShareService.direct
                + (l == null ? "" : l.app + l.title) + err + ShareService.lastError;
        if (sig.equals(shareSig)) return;
        shareSig = sig;
        npShare.removeAllViews();
        if (err) {
            LinearLayout b = new LinearLayout(this); b.setGravity(Gravity.CENTER_VERTICAL);
            b.setBackground(box(this, 0x33FF6B6B, 16)); b.setPadding(dp(16), dp(10), dp(10), dp(10));
            LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(0, 0, dp(8), 0);
            tx.addView(Theme.text(this, "Sharing stopped", 16, TEXT, MEDIUM));
            TextView d = Theme.text(this, ShareService.lastError, 13, 0xFFFFC9C9, REGULAR);
            d.setSingleLine(false); d.setMaxLines(3); d.setPadding(0, dp(4), 0, 0); tx.addView(d);
            b.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            TextView ok = pill(this, "OK", 0); ok.setMinHeight(dp(40)); ok.setOnClickListener(v -> { dismissedError = ShareService.lastError; shareSig = ""; renderShare(); });
            TextView again = pill(this, "Try again", 1); again.setMinHeight(dp(40)); again.setOnClickListener(v -> { dismissedError = ShareService.lastError; openShareSheet(); });
            b.addView(ok, new LinearLayout.LayoutParams(-2, dp(40)));
            b.addView(again, side(-2, 40, 8));
            npShare.addView(b, new LinearLayout.LayoutParams(-1, -2));
            npShare.addView(new View(this), new LinearLayout.LayoutParams(1, dp(18)));
            return;
        }
        if (!ShareService.running && l == null) return;
        LinearLayout b = new LinearLayout(this); b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(box(this, 0x1F6FDC8C, 16)); b.setPadding(dp(16), dp(10), dp(10), dp(10));
        Bars bars = new Bars(this); bars.playing(true);
        b.addView(bars, new LinearLayout.LayoutParams(dp(16), dp(14)));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(dp(12), 0, dp(8), 0);
        if (ShareService.running) {
            tx.addView(Theme.text(this, (ShareService.step.isEmpty() ? "Playing in: " : "Starting: ") + ShareService.sharingTo, 16, TEXT, MEDIUM));
            String info = !ShareService.step.isEmpty() ? ShareService.step + "…"
                    : (ShareService.sharingWhat.isEmpty() ? "Anything you play here is heard there too" : ShareService.sharingWhat)
                    + (ShareService.direct ? " · this panel a little ahead" : "");
            TextView d = Theme.text(this, info, 13, SUB, REGULAR);
            d.setPadding(0, dp(4), 0, 0); tx.addView(d);
            b.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            TextView ch = pill(this, "Change", 0); ch.setMinHeight(dp(40)); ch.setOnClickListener(v -> openShareSheet());
            TextView st = pill(this, "Stop", 1); st.setMinHeight(dp(40)); st.setOnClickListener(v -> { ShareService.stop(this); shareSig = ""; });
            b.addView(ch, new LinearLayout.LayoutParams(-2, dp(40)));
            b.addView(st, side(-2, 40, 8));
        } else {
            tx.addView(Theme.text(this, l.app + " is playing on this panel", 16, TEXT, MEDIUM));
            if (!l.title.isEmpty()) { TextView d = Theme.text(this, l.title + (l.artist.isEmpty() ? "" : " · " + l.artist), 13, SUB, REGULAR); d.setPadding(0, dp(4), 0, 0); tx.addView(d); }
            b.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            TextView go = pill(this, "Share", 1); go.setMinHeight(dp(40)); go.setOnClickListener(v -> openShareSheet());
            b.addView(go, new LinearLayout.LayoutParams(-2, dp(40)));
        }
        npShare.addView(b, new LinearLayout.LayoutParams(-1, -2));
        npShare.addView(new View(this), new LinearLayout.LayoutParams(1, dp(18)));
    }

    /** pick where this panel's sound should also play: a saved group, or rooms */
    private void openShareSheet() {
        if (android.os.Build.VERSION.SDK_INT < 29) { openShareUnsupported(); return; }
        Site st = site();
        if (st == null) return;
        sharePick.clear(); shareGroup = "";
        sheetLayer.removeAllViews();
        LinearLayout sh = new LinearLayout(this); sh.setOrientation(LinearLayout.VERTICAL);
        sh.setBackground(box(this, SURFACE, 0)); sh.setClickable(true);
        sh.setPadding(dp(28), dp(26), dp(28), dp(22));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Theme.text(this, "Play this panel in…", 24, TEXT, MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout x = iconButton(this, CLOSE, 44, 22, 2, "Close"); x.setOnClickListener(v -> closeSheet());
        head.addView(x);
        sh.addView(head);
        TextView expl = Theme.text(this, "Whatever plays on " + myName() + " (YouTube, Spotify, a browser …) plays here and in the rooms you pick, all in sync. The sound comes about a second after the picture.", 14, SUB, REGULAR);
        expl.setSingleLine(false); expl.setMaxLines(3); expl.setLineSpacing(0, 1.15f);
        sh.addView(expl, top(-1, -2, 8));
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        sh.addView(sv, top(-1, 0, 14, 1));
        LinearLayout foot = new LinearLayout(this); foot.setGravity(Gravity.CENTER_VERTICAL);
        if (ShareService.running) {
            TextView stop = pill(this, "Stop sharing", 2); stop.setOnClickListener(v -> { ShareService.stop(this); closeSheet(); shareSig = ""; });
            foot.addView(stop, new LinearLayout.LayoutParams(-2, dp(52)));
        }
        foot.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView go = pill(this, "Play", 1);
        foot.addView(go, new LinearLayout.LayoutParams(-2, dp(52)));
        sh.addView(foot, top(-1, -2, 14));
        go.setOnClickListener(v -> {
            if (shareGroup.isEmpty() && sharePick.isEmpty()) return;
            String label;
            if (!shareGroup.isEmpty()) label = st.nameOf(shareGroup);
            else { List<String> names = new ArrayList<>(); names.add(myName()); for (String id : sharePick) names.add(st.nameOf(id)); label = android.text.TextUtils.join(", ", names); }
            ShareService.request(this, shareGroup, new ArrayList<>(sharePick), label);
            closeSheet(); shareSig = "";
        });
        int w = (int) Math.min(520, wDp * 0.48f);
        sheetLayer.addView(sh, new FrameLayout.LayoutParams(dp(w), -1, Gravity.END));
        sheetLayer.setVisibility(View.VISIBLE);
        renderShareList(list, go);
    }

    /** Android 8/9 panels (DSPPA): Android only lets apps pick up other apps' sound from Android 10 on */
    private void openShareUnsupported() {
        sheetLayer.removeAllViews();
        LinearLayout d = new LinearLayout(this); d.setOrientation(LinearLayout.VERTICAL); d.setClickable(true);
        d.setBackground(box(this, SURFACE, 22)); d.setPadding(dp(30), dp(26), dp(30), dp(24));
        d.addView(Theme.text(this, "Play music in other rooms", 22, TEXT, MEDIUM));
        TextView ex = Theme.text(this, "This panel runs Android " + android.os.Build.VERSION.RELEASE + ". Android only lets one app pick up the sound of another app "
                + "(YouTube, Spotify …) from Android 10 on, so this panel can't send an app's sound to other rooms.\n\n"
                + "What works on this panel, in sync in every room:\n"
                + "•  Music – search songs, artists, radio and your streaming services (YouTube Music, Spotify, Tidal, TuneIn … once added in Music Assistant)\n"
                + "•  Spotify app → Connect to a device → pick this room or a group (needs Spotify Connect in Music Assistant)\n"
                + "•  Phones: AirPlay (iPhone) or \"Play my phone\" (Android) to any room or group", 15, SUB, REGULAR);
        ex.setSingleLine(false); ex.setMaxLines(14); ex.setLineSpacing(0, 1.2f);
        d.addView(ex, top(-1, -2, 12));
        LinearLayout foot = new LinearLayout(this); foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView close = pill(this, "Close", 2); close.setOnClickListener(v -> closeSheet());
        TextView music = pill(this, "Open Music", 1); music.setOnClickListener(v -> { closeSheet(); show(MUSIC); });
        foot.addView(close, new LinearLayout.LayoutParams(-2, dp(52)));
        foot.addView(music, side(-2, 52, 10));
        d.addView(foot, top(-1, -2, 20));
        sheetLayer.addView(d, new FrameLayout.LayoutParams(dp(Math.min(720, wDp - 80)), -2, Gravity.CENTER));
        sheetLayer.setVisibility(View.VISIBLE);
    }

    private void renderShareList(LinearLayout list, TextView go) {
        Site st = site();
        list.removeAllViews();
        if (st == null) return;
        if (!st.groups.isEmpty()) list.addView(Theme.text(this, "Groups", 15, SUB, MEDIUM), top(-2, -2, 4));
        for (Site.Group g : st.groups) {
            boolean sel = g.id.equals(shareGroup);
            String sub = g.members.size() + " rooms" + (g.members.contains(me()) ? " · with this room" : " · not this room");
            list.addView(shareRow(GROUP, g.name, sub, sel, true, () -> { shareGroup = sel ? "" : g.id; sharePick.clear(); renderShareList(list, go); }));
        }
        list.addView(Theme.text(this, "Or rooms (" + myName() + " plays too)", 15, SUB, MEDIUM), top(-2, -2, 18));
        for (Site.Room r : st.rooms) {
            if (r.id.equals(me())) continue;
            boolean sel = sharePick.contains(r.id);
            View row = shareRow(SPEAKER, r.name, !r.available ? "Offline" : r.playing() && !r.title.isEmpty() ? "Playing " + r.title : "", sel, false,
                    () -> { if (sel) sharePick.remove(r.id); else sharePick.add(r.id); shareGroup = ""; renderShareList(list, go); });
            if (!r.available) { row.setAlpha(0.4f); row.setOnClickListener(null); }
            list.addView(row);
        }
        go.setText(!shareGroup.isEmpty() ? "Play in " + st.nameOf(shareGroup) : sharePick.isEmpty() ? "Pick rooms" : "Play in " + (sharePick.size() + 1) + " rooms");
        go.setAlpha(shareGroup.isEmpty() && sharePick.isEmpty() ? 0.5f : 1f);
    }

    private View shareRow(int icon, String name, String sub, boolean sel, boolean radio, Runnable tap) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(12), dp(6), dp(12));
        clickable(row, box(this, 0x00000000, 12), 12);
        FrameLayout tick = new FrameLayout(this);
        tick.setBackground(sel ? box(this, TEXT, 14) : outline(this, 0x00000000, 0x66FFFFFF, 14));
        if (sel) tick.addView(new Icon(this, CHECK, INK), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        row.addView(tick, new LinearLayout.LayoutParams(dp(28), dp(28)));
        row.addView(new Icon(this, icon, SUB), side(22, 22, 14));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(dp(12), 0, 0, 0);
        tx.addView(Theme.text(this, name, 17, TEXT, MEDIUM));
        if (!sub.isEmpty()) { TextView s = Theme.text(this, sub, 13, SUB, REGULAR); s.setPadding(0, dp(4), 0, 0); tx.addView(s); }
        row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> tap.run());
        return row;
    }

    // =================================== GROUP sheet ===================================
    private final Set<String> picked = new LinkedHashSet<>();
    private final Map<String, Slider> sheetVols = new HashMap<>();
    private LinearLayout sheetList; private TextView sheetDone;
    private List<String> sheetStart = new ArrayList<>();

    private void openGroupSheet() {
        Site st = site();
        if (st == null) return;
        sheetStart = st.zoneMembers(myZone(st));
        picked.clear(); picked.add(me()); picked.addAll(sheetStart);

        sheetLayer.removeAllViews();
        LinearLayout sh = new LinearLayout(this); sh.setOrientation(LinearLayout.VERTICAL);
        sh.setBackground(box(this, SURFACE, 0)); sh.setClickable(true);
        sh.setPadding(dp(28), dp(26), dp(28), dp(22));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Theme.text(this, "Group rooms", 24, TEXT, MEDIUM), new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout x = iconButton(this, CLOSE, 44, 22, 2, "Close"); x.setOnClickListener(v -> closeSheet());
        head.addView(x);
        sh.addView(head);
        TextView expl = Theme.text(this, "Tick the rooms that should play together with " + myName() + ".", 14, SUB, REGULAR);
        expl.setSingleLine(false); expl.setMaxLines(2);
        sh.addView(expl, top(-1, -2, 8));

        // scene shortcuts
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.addView(chip("Only this room", () -> { picked.clear(); picked.add(me()); renderSheet(); }));
        chips.addView(chip("Everywhere", () -> { for (Site.Room r : st.rooms) if (r.available) picked.add(r.id); renderSheet(); }));
        for (Site.Group g : st.groups) chips.addView(chip(g.name, () -> { picked.clear(); picked.add(me()); picked.addAll(g.members); renderSheet(); }));
        hs.addView(chips);
        sh.addView(hs, top(-1, -2, 16));

        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false);
        sheetList = new LinearLayout(this); sheetList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(sheetList);
        sh.addView(sv, top(-1, 0, 14, 1));

        LinearLayout foot = new LinearLayout(this); foot.setGravity(Gravity.CENTER_VERTICAL);
        if (grouped()) {
            TextView brk = pill(this, "Take a break", 2);
            brk.setOnClickListener(v -> { closeSheet(); openBreak(); });
            foot.addView(brk, new LinearLayout.LayoutParams(-2, dp(52)));
        }
        foot.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        sheetDone = pill(this, "Done", 1);
        sheetDone.setOnClickListener(v -> applySheet());
        foot.addView(sheetDone, new LinearLayout.LayoutParams(-2, dp(52)));
        sh.addView(foot, top(-1, -2, 14));

        int w = (int) Math.min(520, wDp * 0.48f);
        sheetLayer.addView(sh, new FrameLayout.LayoutParams(dp(w), -1, Gravity.END));
        sheetLayer.setVisibility(View.VISIBLE);
        renderSheet();
    }

    private TextView chip(String s, Runnable r) {
        TextView c = pill(this, s, 0); c.setTextSize(Theme.scale * 14); c.setMinHeight(dp(38)); c.setPadding(dp(16), 0, dp(16), 0);
        c.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(38)); p.rightMargin = dp(8);
        c.setLayoutParams(p);
        return c;
    }

    private void renderSheet() {
        Site st = site();
        if (st == null || sheetList == null) return;
        sheetList.removeAllViews(); sheetVols.clear();
        List<Site.Room> order = new ArrayList<>(st.rooms);
        for (int k = 0; k < order.size(); k++) if (order.get(k).id.equals(me())) { order.add(0, order.remove(k)); break; }
        for (Site.Room r : order) {
            boolean isMe = r.id.equals(me());
            boolean sel = picked.contains(r.id);
            boolean member = sheetStart.contains(r.id);
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(4), dp(12), dp(4), dp(12));
            LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
            FrameLayout tick = new FrameLayout(this);
            tick.setBackground(sel ? box(this, TEXT, 14) : outline(this, 0x00000000, 0x66FFFFFF, 14));
            if (sel) tick.addView(new Icon(this, CHECK, INK), new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            top.addView(tick, new LinearLayout.LayoutParams(dp(28), dp(28)));
            LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(dp(16), 0, 0, 0);
            tx.addView(Theme.text(this, r.name + (isMe ? "  (this room)" : ""), 17, TEXT, MEDIUM));
            String status = !r.available ? "Offline"
                    : member && !isMe ? "Playing with this room"
                    : r.playing() && !r.title.isEmpty() ? "Playing " + r.title : "";
            if (!status.isEmpty()) { TextView s = Theme.text(this, status, 13, SUB, REGULAR); s.setPadding(0, dp(4), 0, 0); tx.addView(s); }
            top.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(top);
            if (member && sel && r.available && sheetStart.size() > 1 && (isMe || origin())) {
                LinearLayout vr = new LinearLayout(this); vr.setGravity(Gravity.CENTER_VERTICAL); vr.setPadding(dp(44), dp(6), 0, 0);
                vr.addView(new Icon(this, VOLUME, MUTED), new LinearLayout.LayoutParams(dp(20), dp(20)));
                String id = r.id;
                Slider s = new Slider(this).listener((v, done) -> { if (done) act(x -> x.setRoomVolume(id, v)); });
                s.setValue(Math.max(0, r.volume));
                vr.addView(s, side(0, 36, 10, 1));
                row.addView(vr);
                sheetVols.put(r.id, s);
            }
            row.setAlpha(r.available ? 1f : 0.4f);
            if (!isMe && r.available) {
                clickable(row, box(this, 0x00000000, 12), 12);
                row.setOnClickListener(v -> { if (sel) picked.remove(r.id); else picked.add(r.id); renderSheet(); });
            }
            sheetList.addView(row);
            View div = new View(this); div.setBackgroundColor(LINE);
            sheetList.addView(div, new LinearLayout.LayoutParams(-1, 1));
        }
        int k = picked.size();
        sheetDone.setText(k == 1 ? "Done" : "Play in " + k + " rooms");
    }

    private void applySheet() {
        List<String> add = new ArrayList<>(), remove = new ArrayList<>();
        for (String id : picked) if (!sheetStart.contains(id) && !id.equals(me())) add.add(id);
        for (String id : sheetStart) if (!picked.contains(id) && !id.equals(me())) remove.add(id);
        if (!add.isEmpty() || !remove.isEmpty()) act(s -> s.applyGroup(add, remove));
        closeSheet();
    }

    private void closeSheet() {
        sheetLayer.setVisibility(View.GONE); sheetLayer.removeAllViews();
        sheetList = null; sheetVols.clear(); roomsSig = "";
    }

    /** keep the per-room sliders current while the sheet is open */
    private void renderSheetVolumes() {
        Site st = site();
        if (st == null) return;
        for (Map.Entry<String, Slider> e : sheetVols.entrySet()) {
            Site.Room r = st.room(e.getKey());
            if (r != null && r.volume >= 0) e.getValue().setValue(r.volume);
        }
    }

    // =================================== TAKE A BREAK ===================================
    private void openBreak() {
        if (n == null || n.groupId.isEmpty() || n.groupName.isEmpty()) return;
        String groupId = n.groupId, groupName = n.groupName.replaceAll(" \\+\\d+$", "");
        sheetLayer.removeAllViews();
        LinearLayout d = new LinearLayout(this); d.setOrientation(LinearLayout.VERTICAL); d.setClickable(true);
        d.setBackground(box(this, SURFACE, 22)); d.setPadding(dp(30), dp(26), dp(30), dp(24));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new Icon(this, MOON, AMBER), new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = Theme.text(this, "Take a break from " + groupName, 22, TEXT, MEDIUM); t.setPadding(dp(12), 0, 0, 0);
        head.addView(t);
        d.addView(head);
        TextView ex = Theme.text(this, "Only " + myName() + " goes quiet or plays its own music. The other rooms keep playing, and this room joins again by itself.", 15, SUB, REGULAR);
        ex.setSingleLine(false); ex.setMaxLines(3); ex.setLineSpacing(0, 1.15f);
        d.addView(ex, top(-1, -2, 10));
        long now = System.currentTimeMillis();
        LinearLayout r1 = new LinearLayout(this);
        r1.addView(choice("15 minutes", () -> brk(groupId, Breaks.TIME, now + 15 * 60_000L)), weight(0));
        r1.addView(choice("1 hour", () -> brk(groupId, Breaks.TIME, now + 3600_000L)), weight(10));
        r1.addView(choice("2 hours", () -> brk(groupId, Breaks.TIME, now + 2 * 3600_000L)), weight(10));
        r1.addView(choice("Until 07:00", () -> brk(groupId, Breaks.TIME, next7())), weight(10));
        d.addView(r1, top(-1, -2, 22));
        LinearLayout r2 = new LinearLayout(this);
        r2.addView(choice("Until I stop my own music", () -> brk(groupId, Breaks.MINE, 0)), weight(0));
        r2.addView(choice("Until the group's music ends", () -> brk(groupId, Breaks.GROUP_END, 0)), weight(10));
        d.addView(r2, top(-1, -2, 10));
        TextView cancel = pill(this, "Cancel", 2); cancel.setOnClickListener(v -> closeSheet());
        LinearLayout.LayoutParams cp = top(-2, dp(48), 20); cp.gravity = Gravity.END;
        d.addView(cancel, cp);
        sheetLayer.addView(d, new FrameLayout.LayoutParams(dp(Math.min(760, wDp - 80)), -2, Gravity.CENTER));
        sheetLayer.setVisibility(View.VISIBLE);
    }

    private TextView choice(String s, Runnable r) { TextView b = pill(this, s, 0); b.setTextSize(Theme.scale * 15); b.setOnClickListener(v -> r.run()); return b; }
    private LinearLayout.LayoutParams weight(int left) { return side(0, 56, left, 1); }
    private static long next7() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 7); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }
    private void brk(String groupId, String mode, long until) {
        act(s -> s.startBreak(groupId, mode, until, true));
        closeSheet();
        show(NOW);
    }

    // =================================== shared ===================================
    interface SvcAction { void run(PanelService s); }
    private void act(SvcAction a) { PanelService s = svc(); if (s != null) a.run(s); }

    private LinearLayout.LayoutParams top(int w, int hh, int topDp) { return top(w, hh, topDp, 0); }
    private LinearLayout.LayoutParams top(int w, int hh, int topDp, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, hh, weight); p.topMargin = dp(topDp); return p;
    }
    /** horizontal params: width/height in dp (negative = MATCH/WRAP), left margin dp */
    private LinearLayout.LayoutParams side(int wDpx, int hDpx, int leftDp) { return side(wDpx, hDpx, leftDp, 0); }
    private LinearLayout.LayoutParams side(int wDpx, int hDpx, int leftDp, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(wDpx < 0 ? wDpx : dp(wDpx), hDpx < 0 ? hDpx : dp(hDpx), weight);
        p.leftMargin = dp(leftDp); return p;
    }

    @Override public void onUpdate(NowPlaying np, Bitmap a) {
        runOnUiThread(() -> { n = np; art = a; render(); });
    }
    private void render() {
        if (n == null) return;
        if (page == NOW) renderNow(); else if (page == ROOMS) renderRooms(); else renderMusic();
        if (sheetList != null) renderSheetVolumes();
    }
}
