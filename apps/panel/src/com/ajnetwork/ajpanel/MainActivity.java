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
    private static final int NOW = 0, ROOMS = 1;
    private int page = NOW;
    private Cfg cfg;
    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout content, sheetLayer;
    private Backdrop backdrop;
    private NowPlaying n;
    private Bitmap art;
    private float wDp, hDp;

    private TextView railClock;
    private final View[] navItems = new View[2];

    private final Runnable tick = new Runnable() { public void run() { updateClock(); h.postDelayed(this, 1000); } };

    private int dp(float v) { return Theme.dp(this, v); }
    private PanelService svc() { return PanelService.instance; }
    private Site site() { PanelService s = svc(); return s == null ? null : s.site; }
    private String me() { return cfg.maPlayerId(); }
    private String myName() { return n != null && !n.playerName.isEmpty() ? n.playerName : cfg.name(); }
    private boolean grouped() { return n != null && !n.groupName.isEmpty(); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Cfg(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN);
        PanelService.start(this);
        DisplayMetrics m = getResources().getDisplayMetrics();
        wDp = m.widthPixels / m.density; hDp = m.heightPixels / m.density;

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

    @Override protected void onResume() { super.onResume(); PanelService.addListener(this); h.post(tick); }
    @Override protected void onPause() { PanelService.removeListener(this); h.removeCallbacks(tick); super.onPause(); }
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
        railClock = Theme.text(this, "", 21, TEXT, MEDIUM); railClock.setGravity(Gravity.CENTER);
        r.addView(railClock, new LinearLayout.LayoutParams(-1, -2));
        View gap = new View(this); r.addView(gap, new LinearLayout.LayoutParams(1, dp(30)));
        navItems[NOW] = navItem(NOTE, "Playing", () -> show(NOW));
        navItems[ROOMS] = navItem(Theme.ROOMS, "Rooms", () -> show(ROOMS));
        r.addView(navItems[NOW]);
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(dp(76), dp(76)); g2.topMargin = dp(10);
        r.addView(navItems[ROOMS], g2);
        r.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        r.addView(navItem(GEAR, "Settings", () -> startActivity(new Intent(this, SettingsActivity.class))));
        return r;
    }

    private View navItem(int icon, String label, Runnable go) {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setGravity(Gravity.CENTER);
        v.addView(new Icon(this, icon, MUTED), new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = Theme.text(this, label, 12, MUTED, MEDIUM); t.setPadding(0, dp(6), 0, 0);
        v.addView(t);
        clickable(v, box(this, 0x00000000, 18), 18);
        v.setContentDescription(label);
        v.setOnClickListener(x -> go.run());
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(76), dp(76)));
        return v;
    }

    private void markNav() {
        for (int i = 0; i < navItems.length; i++) {
            LinearLayout v = (LinearLayout) navItems[i];
            boolean sel = i == page;
            ((Icon) v.getChildAt(0)).color(sel ? TEXT : MUTED);
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
        page = p; roomsSig = "";
        content.removeAllViews();
        idleClock = null; idleDate = null;
        if (p == NOW) buildNow(); else buildRooms();
        markNav();
        updateClock();
        if (n != null) render();
    }

    // =================================== PLAYING ===================================
    private Cover npCover; private LinearLayout npIdleLeft; private TextView idleClock, idleDate;
    private LinearLayout npBanner, npPlayingBox, npElsewhere, npElsewhereList;
    private TextView npZone, npTitle, npArtist, npMeta, npElapsed, npDuration, npVolLabel, npVolValue, npBreak;
    private Slider npProgress, npVol;
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
        left.addView(npIdleLeft, new FrameLayout.LayoutParams(-1, -1));
        row.addView(left, new LinearLayout.LayoutParams(dp(artDp), dp(artDp)));

        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1); rp.leftMargin = dp(52);
        row.addView(r, rp);

        npBanner = new LinearLayout(this); npBanner.setOrientation(LinearLayout.VERTICAL);
        r.addView(npBanner);

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
        npVol = new Slider(this).listener((v, done) -> { if (done) act(s -> { if (grouped()) s.setGroupVolume(v); else s.setVolume(v); }); });
        vol.addView(npVol, side(0, 40, 6, 1));
        npVolValue = Theme.text(this, "", 15, SUB, MEDIUM); npVolValue.setGravity(Gravity.END);
        vol.addView(npVolValue, side(40, -2, 8));
        r.addView(vol, top(-1, -2, 4));

        LinearLayout acts = new LinearLayout(this);
        TextView grp = pill(this, "Group rooms", 0); grp.setOnClickListener(v -> openGroupSheet());
        npBreak = pill(this, "Take a break", 0); npBreak.setOnClickListener(v -> openBreak());
        acts.addView(grp, new LinearLayout.LayoutParams(-2, dp(48)));
        acts.addView(npBreak, side(-2, 48, 12));
        r.addView(acts, top(-2, -2, 22));

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
            npMeta.setText("Start music from the AJ Audio app, AirPlay or Bluetooth.");
            npMeta.setVisibility(n.registered && n.serverOk ? View.VISIBLE : View.GONE);
            renderElsewhere();
        }
        // volume: whole group when grouped
        Site st = site();
        int v = n.volume;
        if (grouped() && st != null) v = zoneVolume(st);
        npVolLabel.setText(grouped() ? "Group volume" : "Volume · " + myName());
        if (!npVol.dragging() && v >= 0) { npVol.setValue(v); npVolValue.setText(String.valueOf(v)); }
        iconOf(npMute).set(n.muted ? VOLUME_OFF : VOLUME);
        npMute.setContentDescription(n.muted ? "Unmute this room" : "Mute this room");
        npBreak.setVisibility(grouped() ? View.VISIBLE : View.GONE);
        renderBanner();
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
        TextView c = pill(this, s, 0); c.setTextSize(14); c.setMinHeight(dp(38)); c.setPadding(dp(16), 0, dp(16), 0);
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
            if (member && sel && r.available && sheetStart.size() > 1) {
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

    private TextView choice(String s, Runnable r) { TextView b = pill(this, s, 0); b.setTextSize(15); b.setOnClickListener(v -> r.run()); return b; }
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
        if (page == NOW) renderNow(); else renderRooms();
        if (sheetList != null) renderSheetVolumes();
    }
}
