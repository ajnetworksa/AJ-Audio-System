package com.ajnetwork.ajpanel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AJ Panel v1 screen (ui.md §C, mockups "AJ Audio UI Revamp"):
 *  HOME        clock · group tiles ("play here or join") · break banner · now-playing bar
 *  NOW PLAYING big art · group/source chips · transport · THIS ROOM (volume, mute here, take a break, add rooms)
 *  PLAY TO     "Play this on…" groups + rooms
 *  BREAK       dialog: 15 min / 1 h / 2 h / until 07:00 / until my music stops / until the group ends
 */
public class MainActivity extends Activity implements PanelService.Listener {
    private static final int HOME = 0, NOW = 1, PLAYTO = 2;
    private int page = HOME;
    private Cfg cfg;
    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout content, dialogLayer;
    private NowPlaying n;
    private Bitmap art;
    private boolean touchingVol;
    private final Set<String> picked = new LinkedHashSet<>();
    private String lastSig = "";

    // home widgets
    private TextView clock, date;
    private LinearLayout tiles, bannerBox;
    private TextView barTitle, barSub; private ImageView barArt; private View barProgress; private Button barPlay; private SeekBar barVol;

    private final Runnable tick = new Runnable() { public void run() { updateClock(); h.postDelayed(this, 1000); } };

    private int dp(float v) { return Theme.dp(this, v); }
    private PanelService svc() { return PanelService.instance; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Cfg(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        PanelService.start(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(Theme.page());
        root.addView(new Theme.Mountains(this), new FrameLayout.LayoutParams(-1, -1));
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        dialogLayer = new FrameLayout(this);
        dialogLayer.setVisibility(View.GONE);
        dialogLayer.setBackgroundColor(0xCC070B12);
        dialogLayer.setClickable(true);
        root.addView(dialogLayer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        show(HOME);
    }

    @Override protected void onResume() { super.onResume(); PanelService.addListener(this); h.post(tick); }
    @Override protected void onPause() { PanelService.removeListener(this); h.removeCallbacks(tick); super.onPause(); }
    @Override public void onBackPressed() {
        if (dialogLayer.getVisibility() == View.VISIBLE) { dialogLayer.setVisibility(View.GONE); return; }
        if (page != HOME) { show(HOME); return; }
        super.onBackPressed();
    }

    private void show(int p) {
        page = p; lastSig = "";
        content.removeAllViews();
        if (p == HOME) buildHome(); else if (p == NOW) buildNow(); else buildPlayTo();
        if (n != null) render();
    }

    // =================================== HOME ===================================
    private void buildHome() {
        FrameLayout f = new FrameLayout(this);
        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.VERTICAL);
        clock = Theme.text(this, "", 64, Theme.TEXT, true);
        date = Theme.text(this, "", 17, Theme.SUB, false);
        top.addView(clock); top.addView(date);
        f.addView(top, lp(-2, -2, Gravity.TOP | Gravity.START, 40, 24, 0, 0));

        LinearLayout rail = new LinearLayout(this); rail.setOrientation(LinearLayout.VERTICAL);
        Button rooms = roundIcon("▦", "Play this on other rooms"); rooms.setOnClickListener(v -> openPlayTo());
        Button set = roundIcon("⚙", "Settings"); set.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        rail.addView(rooms); LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(56), dp(56)); gap.topMargin = dp(14);
        rail.addView(set, gap);
        f.addView(rail, lp(-2, -2, Gravity.TOP | Gravity.END, 0, 24, 28, 0));

        LinearLayout mid = new LinearLayout(this); mid.setOrientation(LinearLayout.VERTICAL);
        bannerBox = new LinearLayout(this); bannerBox.setOrientation(LinearLayout.VERTICAL);
        mid.addView(bannerBox);
        TextView l = Theme.label(this, "Play here or join"); l.setPadding(0, dp(10), 0, dp(10));
        mid.addView(l);
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        tiles = new LinearLayout(this); tiles.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(tiles);
        mid.addView(hs);
        f.addView(mid, lp(-1, -2, Gravity.TOP, 40, 150, 40, 0));

        f.addView(buildBar(), lp(-1, dp(108), Gravity.BOTTOM, 0, 0, 0, 0));
        content.addView(f, new FrameLayout.LayoutParams(-1, -1));
        updateClock();
    }

    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Theme.BAR); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(24), 0, dp(24), 0);
        barArt = new ImageView(this); barArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        barArt.setBackground(Theme.art(this, 12)); barArt.setClipToOutline(true);
        bar.addView(barArt, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(16), 0, dp(12), 0);
        barTitle = Theme.text(this, "Nothing playing", 19, Theme.TEXT, true);
        barSub = Theme.text(this, "", 14, Theme.SUB, false);
        FrameLayout track = new FrameLayout(this); track.setBackground(Theme.box(this, 0xFF2A3550, 0, 2));
        barProgress = new View(this); barProgress.setBackground(Theme.accent(this, 2));
        track.addView(barProgress, new FrameLayout.LayoutParams(0, -1));
        t.addView(barTitle); t.addView(barSub);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(340), dp(4)); tp.topMargin = dp(8);
        t.addView(track, tp);
        t.setOnClickListener(v -> show(NOW)); barArt.setOnClickListener(v -> show(NOW));
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        Button prev = flat("⏮", "Previous"); prev.setOnClickListener(v -> act(s -> s.groupCmd("previous")));
        barPlay = Theme.button(this, "▶", 2); barPlay.setContentDescription("Play or pause");
        barPlay.setOnClickListener(v -> act(PanelService::playPause));
        Button next = flat("⏭", "Next"); next.setOnClickListener(v -> act(s -> s.groupCmd("next")));
        bar.addView(prev, new LinearLayout.LayoutParams(dp(56), dp(56)));
        bar.addView(barPlay, new LinearLayout.LayoutParams(dp(64), dp(64)));
        bar.addView(next, new LinearLayout.LayoutParams(dp(56), dp(56)));
        barVol = volumeBar();
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(dp(200), dp(48)); vp.leftMargin = dp(12);
        bar.addView(Theme.text(this, "🔈", 18, Theme.SUB, false));
        bar.addView(barVol, vp);
        return bar;
    }

    private void updateClock() {
        if (clock == null) return;
        Date d = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(d));
        String room = n != null && !n.playerName.isEmpty() ? n.playerName : cfg.name();
        date.setText(new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(d) + "  ·  " + room);
    }

    private void renderHome() {
        PanelService s = svc();
        Site site = s == null ? null : s.site;
        // break banner
        bannerBox.removeAllViews();
        if (s != null && s.breaks.active()) {
            LinearLayout b = new LinearLayout(this); b.setGravity(Gravity.CENTER_VERTICAL);
            b.setBackground(Theme.box(this, Theme.WARN_BG, 0xFF7A5A1E, 18)); b.setPadding(dp(20), dp(12), dp(14), dp(12));
            LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL);
            String gName = site == null ? "the group" : site.nameOf(s.breaks.groupId());
            tx.addView(Theme.text(this, "On a break from " + gName, 19, Theme.TEXT, true));
            tx.addView(Theme.text(this, s.breaks.describe() + " · the other rooms keep playing", 14, 0xFFE7D3AE, false));
            b.addView(tx, new LinearLayout.LayoutParams(0, -2, 1));
            Button more = Theme.button(this, "+30 min", 0); more.setOnClickListener(v -> act(x -> x.extendBreak(30 * 60_000L)));
            Button back = Theme.button(this, "Rejoin now", 1); back.setBackground(Theme.box(this, Theme.WARN, 0, 14));
            back.setOnClickListener(v -> act(PanelService::endBreak));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, dp(52)); bl.leftMargin = dp(10);
            b.addView(more, bl); b.addView(back, bl);
            bannerBox.addView(b);
        }
        // tiles
        String sig = siteSig(site) + (s != null && s.breaks.active());
        if (!sig.equals(lastSig)) {
            lastSig = sig;
            tiles.removeAllViews();
            if (site != null) {
                for (Site.Group g : site.groups) {
                    boolean mine = site.inGroup(cfg.maPlayerId(), g);
                    String state = g.playing() ? (g.title.isEmpty() ? g.state : "♪ " + g.title) : "idle";
                    String hint = mine ? "You are in this group" : (s != null && s.breaks.active() && s.breaks.groupId().equals(g.id)) ? "On a break" : "Tap to join";
                    View t = tile(g.name, g.members.size() + " rooms · " + state, hint, mine, false);
                    t.setOnClickListener(v -> { if (mine) show(NOW); else act(x -> x.joinGroup(g.id)); });
                    tiles.addView(t);
                }
            }
            String inGrp = n == null ? "" : n.groupName;
            View own = tile("This room only", inGrp.isEmpty() ? "Bluetooth · AirPlay" : "Take a break from " + inGrp,
                    "\"" + (n == null || n.playerName.isEmpty() ? cfg.name() : n.playerName) + " | Music\"", false, true);
            own.setOnClickListener(v -> { if (n != null && !n.groupName.isEmpty()) openBreak(); });
            tiles.addView(own);
        }
        renderBar();
    }

    private String siteSig(Site site) {
        if (site == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (Site.Group g : site.groups) sb.append(g.id).append(g.state).append(g.title).append(g.members).append(site.inGroup(cfg.maPlayerId(), g));
        return sb.toString() + (n == null ? "" : n.groupName);
    }

    private View tile(String title, String sub, String hint, boolean selected, boolean dashed) {
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setBackground(selected ? Theme.selected(this, 18) : Theme.box(this, dashed ? 0xFF141C2A : Theme.CARD, Theme.LINE, 18));
        t.addView(Theme.text(this, title, 19, Theme.TEXT, true));
        t.addView(Theme.text(this, sub, 14, Theme.SUB, false));
        TextView hv = Theme.text(this, hint, 14, selected ? 0xFF9CCBFF : Theme.SUB, false); hv.setPadding(0, dp(8), 0, 0);
        t.addView(hv);
        t.setClickable(true); t.setFocusable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(220), dp(116)); p.rightMargin = dp(14);
        t.setLayoutParams(p);
        return t;
    }

    private void renderBar() {
        if (barTitle == null || n == null) return;
        boolean act = n.active();
        barTitle.setText(act ? n.title : statusText());
        StringBuilder sub = new StringBuilder(act ? n.artist : "");
        if (!n.groupName.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(n.groupName).append(" (").append(n.groupSize).append(")");
        if (!n.user.isEmpty()) sub.append(" · played by ").append(n.user);
        else if (!n.source.isEmpty() && act) sub.append(" · via ").append(n.source);
        barSub.setText(sub.toString());
        barArt.setImageBitmap(act ? art : null);
        barPlay.setText("playing".equals(n.state) ? "❚❚" : "▶");
        if (!touchingVol && n.volume >= 0) barVol.setProgress(n.volume);
        View track = (View) barProgress.getParent();
        int w = track.getWidth();
        ViewGroup.LayoutParams lp = barProgress.getLayoutParams();
        lp.width = act && n.duration > 0 ? (int) (w * Math.min(1, n.elapsed / n.duration)) : 0;
        barProgress.setLayoutParams(lp);
    }

    private String statusText() {
        PanelService s = svc();
        return s == null ? "Starting…" : n.registered && n.serverOk ? "Nothing playing" : s.statusLine(n);
    }

    // =================================== NOW PLAYING ===================================
    private ImageView npArt; private TextView npChips, npTitle, npArtist, npUser, npTime; private View npProg; private Button npPlay, npMute; private SeekBar npVol;

    private void buildNow() {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(40), dp(28), dp(40), dp(24));
        LinearLayout left = new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL);
        Button back = Theme.button(this, "‹ Home", 0); back.setOnClickListener(v -> show(HOME));
        left.addView(back, new LinearLayout.LayoutParams(-2, dp(48)));
        npArt = new ImageView(this); npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setBackground(Theme.art(this, 24)); npArt.setClipToOutline(true);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(330), dp(330)); ap.topMargin = dp(16);
        left.addView(npArt, ap);
        row.addView(left);

        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(dp(36), dp(56), 0, 0);
        npChips = Theme.text(this, "", 14, 0xFF9CCBFF, true);
        npTitle = Theme.text(this, "", 36, Theme.TEXT, true);
        npArtist = Theme.text(this, "", 20, Theme.SUB, false);
        npUser = Theme.text(this, "", 17, 0xFF7DBEFF, false);
        r.addView(npChips); r.addView(npTitle); r.addView(npArtist); r.addView(npUser);
        FrameLayout track = new FrameLayout(this); track.setBackground(Theme.box(this, 0xFF2A3550, 0, 3));
        npProg = new View(this); npProg.setBackground(Theme.accent(this, 3));
        track.addView(npProg, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(6)); tp.topMargin = dp(14);
        r.addView(track, tp);
        npTime = Theme.text(this, "", 13, Theme.SUB, false); r.addView(npTime);
        LinearLayout tr = new LinearLayout(this); tr.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = Theme.button(this, "⏮", 3); prev.setOnClickListener(v -> act(s -> s.groupCmd("previous")));
        npPlay = Theme.button(this, "▶", 2); npPlay.setOnClickListener(v -> act(PanelService::playPause));
        Button next = Theme.button(this, "⏭", 3); next.setOnClickListener(v -> act(s -> s.groupCmd("next")));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(60), dp(60)); bp.rightMargin = dp(18);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(76), dp(76)); pp.rightMargin = dp(18);
        tr.addView(prev, bp); tr.addView(npPlay, pp); tr.addView(next, bp);
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(-2, -2); trp.topMargin = dp(8);
        r.addView(tr, trp);

        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.box(this, Theme.CARD, 0, 18)); box.setPadding(dp(16), dp(12), dp(16), dp(14));
        box.addView(Theme.label(this, "This room (" + cfg.name() + ")"));
        npVol = volumeBar();
        box.addView(npVol, new LinearLayout.LayoutParams(-1, dp(44)));
        LinearLayout btns = new LinearLayout(this);
        npMute = Theme.button(this, "Mute here", 0); npMute.setOnClickListener(v -> act(PanelService::pauseHere));
        Button brk = Theme.button(this, "Take a break…", 1); brk.setOnClickListener(v -> openBreak());
        Button add = Theme.button(this, "Add rooms…", 0); add.setOnClickListener(v -> openPlayTo());
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(52), 1); b1.rightMargin = dp(10);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(52), 1.4f); b2.rightMargin = dp(10);
        btns.addView(npMute, b1); btns.addView(brk, b2); btns.addView(add, new LinearLayout.LayoutParams(0, dp(52), 1));
        box.addView(btns);
        LinearLayout.LayoutParams bxp = new LinearLayout.LayoutParams(-1, -2); bxp.topMargin = dp(10);
        r.addView(box, bxp);
        row.addView(r, new LinearLayout.LayoutParams(0, -1, 1));
        content.addView(row, new FrameLayout.LayoutParams(-1, -1));
    }

    private void renderNow() {
        boolean act = n.active();
        npArt.setImageBitmap(act ? art : null);
        String chips = n.groupName.isEmpty() ? "This room" : n.groupName + " · " + n.groupSize + " rooms";
        if (!n.source.isEmpty()) chips += "   ·   via " + n.source;
        npChips.setText(chips);
        npTitle.setText(act ? n.title : "Nothing playing");
        npArtist.setText(act ? (n.artist + (n.album.isEmpty() ? "" : " · " + n.album)) : "");
        npUser.setText(n.user.isEmpty() ? "" : "Played by " + n.user);
        npTime.setText(act && n.duration > 0 ? Ui.fmt(n.elapsed) + " / " + Ui.fmt(n.duration) : "");
        View track = (View) npProg.getParent();
        ViewGroup.LayoutParams lp = npProg.getLayoutParams();
        lp.width = act && n.duration > 0 ? (int) (track.getWidth() * Math.min(1, n.elapsed / n.duration)) : 0;
        npProg.setLayoutParams(lp);
        npPlay.setText("playing".equals(n.state) ? "❚❚" : "▶");
        npMute.setText(n.muted ? "Unmute here" : "Mute here");
        if (!touchingVol && n.volume >= 0) npVol.setProgress(n.volume);
    }

    // =================================== PLAY THIS ON… ===================================
    private LinearLayout ptGroups, ptRooms; private Button ptGo;

    private void openPlayTo() {
        picked.clear();
        picked.add(cfg.maPlayerId());
        PanelService s = svc();
        if (s != null && s.site != null) {
            Site.Room me = s.site.room(cfg.maPlayerId());
            if (me != null) picked.addAll(me.members);
        }
        show(PLAYTO);
    }

    private void buildPlayTo() {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(40), dp(28), dp(40), dp(24));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ht = new LinearLayout(this); ht.setOrientation(LinearLayout.VERTICAL);
        ht.addView(Theme.text(this, "Play this on…", 26, Theme.TEXT, true));
        String what = n != null && n.active() ? "\"" + n.title + "\"" + (n.source.isEmpty() ? "" : " (" + n.source + ")") : "this room";
        ht.addView(Theme.text(this, cfg.name() + " is playing " + what + " – pick more rooms", 15, Theme.SUB, false));
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = Theme.button(this, "Close", 0); close.setOnClickListener(v -> show(HOME));
        head.addView(close, new LinearLayout.LayoutParams(-2, dp(52)));
        col.addView(head);
        TextView g = Theme.label(this, "Groups"); g.setPadding(0, dp(18), 0, dp(8)); col.addView(g);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        ptGroups = new LinearLayout(this); hs.addView(ptGroups); col.addView(hs);
        TextView rl = Theme.label(this, "Or pick rooms"); rl.setPadding(0, dp(18), 0, dp(8)); col.addView(rl);
        ScrollView sv = new ScrollView(this);
        ptRooms = new LinearLayout(this); ptRooms.setOrientation(LinearLayout.VERTICAL);
        sv.addView(ptRooms);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout foot = new LinearLayout(this); foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(Theme.text(this, "The music keeps playing – the other rooms join this room.", 14, Theme.SUB, false), new LinearLayout.LayoutParams(0, -2, 1));
        ptGo = Theme.button(this, "Play", 1);
        ptGo.setOnClickListener(v -> { act(s -> s.playOn(new ArrayList<>(picked))); show(HOME); });
        foot.addView(ptGo, new LinearLayout.LayoutParams(-2, dp(58)));
        col.addView(foot);
        content.addView(col, new FrameLayout.LayoutParams(-1, -1));
        renderPlayTo();
    }

    private void renderPlayTo() {
        PanelService s = svc();
        Site site = s == null ? null : s.site;
        String sig = siteSig(site) + picked;
        if (sig.equals(lastSig)) return;
        lastSig = sig;
        ptGroups.removeAllViews(); ptRooms.removeAllViews();
        if (site == null) return;
        for (Site.Group g : site.groups) {
            boolean sel = picked.containsAll(g.members) && !g.members.isEmpty();
            String busy = g.playing() && !site.inGroup(cfg.maPlayerId(), g) ? " · busy" : "";
            View t = tile(g.name, joinNames(site, g.members), sel ? "Selected" : busy.isEmpty() ? "Tap to pick these rooms" : "Playing something else", sel, false);
            t.setOnClickListener(v -> { if (sel) picked.removeAll(g.members); else picked.addAll(g.members); picked.add(cfg.maPlayerId()); lastSig = ""; renderPlayTo(); });
            ptGroups.addView(t);
        }
        LinearLayout line = null; int i = 0;
        for (Site.Room r : site.rooms) {
            if (i++ % 4 == 0) { line = new LinearLayout(this); ptRooms.addView(line); }
            boolean me = r.id.equals(cfg.maPlayerId());
            boolean sel = picked.contains(r.id);
            String label = (sel ? "✓ " : "") + r.name + (me ? " (this room)" : !r.available ? " · offline" : "");
            Button c = Theme.button(this, label, 0);
            c.setTextSize(16);
            c.setBackground(sel ? Theme.selected(this, 24) : Theme.box(this, Theme.CARD, Theme.LINE, 24));
            c.setEnabled(r.available && !me);
            c.setOnClickListener(v -> { if (sel) picked.remove(r.id); else picked.add(r.id); lastSig = ""; renderPlayTo(); });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(50)); cp.rightMargin = dp(10); cp.bottomMargin = dp(10);
            line.addView(c, cp);
        }
        ptGo.setText("Play on " + picked.size() + (picked.size() == 1 ? " room" : " rooms"));
    }

    private String joinNames(Site site, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) sb.append(sb.length() > 0 ? ", " : "").append(site.nameOf(id));
        return sb.toString();
    }

    // =================================== TAKE A BREAK ===================================
    private void openBreak() {
        if (n == null || n.groupId.isEmpty() || n.groupName.isEmpty()) return;
        String groupId = n.groupId, groupName = n.groupName.replaceAll(" \\+\\d+$", "");
        dialogLayer.removeAllViews();
        LinearLayout d = new LinearLayout(this); d.setOrientation(LinearLayout.VERTICAL);
        d.setBackground(Theme.box(this, Theme.CARD, 0xFF2E3A52, 26)); d.setPadding(dp(28), dp(24), dp(28), dp(24));
        d.addView(Theme.text(this, "Take a break from " + groupName, 24, Theme.TEXT, true));
        TextView ex = Theme.text(this, "Only this room goes quiet or plays its own music. The other rooms keep playing. The room joins the group again by itself.", 15, Theme.SUB, false);
        ex.setSingleLine(false); ex.setMaxLines(3); ex.setPadding(0, dp(6), 0, dp(14));
        d.addView(ex);
        d.addView(Theme.label(this, "For how long?"));
        LinearLayout r1 = new LinearLayout(this);
        long now = System.currentTimeMillis();
        r1.addView(choice("15 min", () -> brk(groupId, Breaks.TIME, now + 15 * 60_000L)), weight());
        r1.addView(choice("1 hour", () -> brk(groupId, Breaks.TIME, now + 3600_000L)), weight());
        r1.addView(choice("2 hours", () -> brk(groupId, Breaks.TIME, now + 2 * 3600_000L)), weight());
        r1.addView(choice("Until 07:00", () -> brk(groupId, Breaks.TIME, next7())), weight());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.topMargin = dp(10);
        d.addView(r1, rp);
        LinearLayout r2 = new LinearLayout(this);
        r2.addView(choice("Until I stop my own music", () -> brk(groupId, Breaks.MINE, 0)), weight());
        r2.addView(choice("Until the group's music ends", () -> brk(groupId, Breaks.GROUP_END, 0)), weight());
        LinearLayout.LayoutParams rp2 = new LinearLayout.LayoutParams(-1, -2); rp2.topMargin = dp(10);
        d.addView(r2, rp2);
        Button cancel = Theme.button(this, "Cancel", 0); cancel.setOnClickListener(v -> dialogLayer.setVisibility(View.GONE));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(54)); cp.gravity = Gravity.END; cp.topMargin = dp(16);
        d.addView(cancel, cp);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(dp(760), -2, Gravity.CENTER);
        dialogLayer.addView(d, dl);
        dialogLayer.setVisibility(View.VISIBLE);
    }

    private Button choice(String s, Runnable r) {
        Button b = Theme.button(this, s, 0); b.setMinHeight(dp(62)); b.setOnClickListener(v -> r.run()); return b;
    }
    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(62), 1); p.rightMargin = dp(10); return p;
    }
    private static long next7() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 7); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }
    private void brk(String groupId, String mode, long until) {
        act(s -> s.startBreak(groupId, mode, until, true));
        dialogLayer.setVisibility(View.GONE);
        show(HOME);
    }

    // =================================== shared ===================================
    interface SvcAction { void run(PanelService s); }
    private void act(SvcAction a) { PanelService s = svc(); if (s != null) a.run(s); }

    private SeekBar volumeBar() {
        SeekBar sb = new SeekBar(this); sb.setMax(100);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { }
            public void onStartTrackingTouch(SeekBar s) { touchingVol = true; }
            public void onStopTrackingTouch(SeekBar s) { touchingVol = false; int v = s.getProgress(); act(x -> x.setVolume(v)); }
        });
        return sb;
    }
    private Button roundIcon(String s, String desc) {
        Button b = Theme.button(this, s, 0); b.setTextSize(22); b.setPadding(0, 0, 0, 0);
        b.setBackground(Theme.box(this, Theme.CARD, Theme.LINE, 28)); b.setContentDescription(desc);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        return b;
    }
    private Button flat(String s, String desc) {
        Button b = Theme.button(this, s, 0); b.setBackground(null); b.setTextSize(22); b.setPadding(0, 0, 0, 0);
        b.setContentDescription(desc); return b;
    }
    private FrameLayout.LayoutParams lp(int w, int hh, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, hh, gravity);
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }

    @Override public void onUpdate(NowPlaying np, Bitmap a) {
        runOnUiThread(() -> { n = np; art = a; render(); });
    }
    private void render() {
        if (n == null) return;
        if (page == HOME) renderHome(); else if (page == NOW) renderNow(); else renderPlayTo();
    }
}
