package com.ajnetwork.ajpanel;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.ajnetwork.ajpanel.Kit.*;

/**
 * AJ Panel home screen (system plan, v4 look): the smart-home dashboard generated from Home Assistant.
 *  nav     Home · Rooms · Scenes · Climate · Music · Security · Apps · (Settings)
 *  HOME    clock + weather · now playing · this room's tiles / owner's favourites · energy today
 *  ROOMS   every HA area as a card → room page with all its devices
 *  …       Scenes, Climate, Security, Apps (YouTube etc.)
 * Graphite / pearl look (auto by sun), English / Arabic (RTL), owner name, PINs, screensaver.
 * Everything is drawn by a few custom Views; a state change redraws only the tiles of that entity.
 */
public class HomeActivity extends Activity implements DashHub.L, PanelService.Listener, Tiles.Host, Tiles.WeatherSource {
    private static final int HOME = 0, ROOMS = 1, SCENES = 2, CLIMATE = 3, MUSIC = 4, SECURITY = 5, APPS = 6, ROOM = 10;
    private int page = HOME; private String roomId = ""; private boolean scenesAll;
    private Cfg cfg;
    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout root, content, overlay;
    private LinearLayout nav;
    private TextView hTitle, hOwner, hStatus; private LinearLayout hChips;
    private final Map<String, List<View>> views = new HashMap<>();
    private Tiles.MusicCard music; private Tiles.ClockCard clock; private View energy;
    private final List<View> roomCards = new ArrayList<>();
    private NowPlaying now; private Bitmap art;
    private Skin builtSkin; private boolean builtAr; private float wU, hU;
    private long lastTouch = System.currentTimeMillis();
    private View saver; private boolean firstStart = true;
    private Runnable sheetRefresh;
    /** the dashboard is in front (floating card / share button stay hidden) */
    static volatile boolean visible;

    private DashHub hub() { PanelService s = PanelService.instance; return s == null ? null : s.hub; }
    @Override public Ha ha() { DashHub d = hub(); Ha a = d == null ? null : d.ha; return a == null ? EMPTY : a; }
    private Dash dash() { DashHub d = hub(); return d == null ? new Dash() : d.dash; }
    @Override public String weatherId() { return dash().weather; }
    /** stand-in while there is no HA connection (everything reads as unavailable) */
    private static final Ha EMPTY = new Ha("http://127.0.0.1:1", "", "none", Runnable::run);

    private final Runnable tick = new Runnable() { public void run() { onTick(); h.postDelayed(this, 1000); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Cfg(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        immersive();
        PanelService.start(this);
        if (!cfg.dashboard()) { startActivity(new Intent(this, MainActivity.class)); finish(); return; }
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, 1), hh = Math.max(m.heightPixels, 1);
        // design is 1280×800; wide screens scale by height, squarer/portrait screens get a compact layout
        Kit.k = Math.min(hh / 800f, w / (w >= hh * 1.4f ? 1280f : 900f));
        wU = w / Kit.k; hU = hh / Kit.k;
        Log.i("home: screen " + w + "x" + hh + " -> " + Math.round(wU) + "x" + Math.round(hU) + " units (k " + String.format(Locale.US, "%.2f", Kit.k) + ")");
        root = new FrameLayout(this);
        setContentView(root);
        build();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() {
        super.onResume(); immersive(); visible = true;
        PanelService.addListener(this);
        DashHub d = hub(); attached = d; if (d != null) d.addListener(this);
        h.removeCallbacks(tick); h.post(tick);
        lastTouch = System.currentTimeMillis();
        if (needsRebuild()) build(); else showPage(page);
    }
    @Override protected void onPause() {
        visible = false;
        PanelService.removeListener(this);
        if (attached != null) attached.removeListener(this); attached = null;
        h.removeCallbacks(tick);
        super.onPause();
    }
    @Override public void onBackPressed() {
        if (overlay.getChildCount() > 0) { closeSheet(); return; }
        if (page == ROOM) { showPage(ROOMS); return; }
        if (page != HOME) { showPage(HOME); return; }
        // the panel's home screen stays; as a normal app (vendor home still on) back leaves to the vendor screen
        if (!isDefaultHome()) moveTaskToBack(true);
    }
    private boolean isDefaultHome() {
        try {
            ResolveInfo r = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); closeSheet(); hideSaver(); if (page != HOME) showPage(HOME); }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        lastTouch = System.currentTimeMillis();
        if (saver != null) { if (e.getActionMasked() == MotionEvent.ACTION_UP) hideSaver(); return true; }
        return super.dispatchTouchEvent(e);
    }

    // =================================================================================== look + structure
    private Skin wantSkin() { Ha a = ha(); return Skin.pick(cfg.look(), a == EMPTY ? null : a.stateOf("sun.sun").equals("unavailable") ? null : a.stateOf("sun.sun")); }
    private boolean needsRebuild() { DashHub d = hub(); if (d != null) d.applyLang(); return builtSkin != wantSkin() || builtAr != Lang.ar; }

    private void build() {
        DashHub d = hub(); if (d != null) d.applyLang(); else Lang.ar = "ar".equals(cfg.lang());
        Kit.skin = builtSkin = wantSkin(); Kit.rtl = builtAr = Lang.ar;
        root.removeAllViews();
        root.setLayoutDirection(Lang.ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.addView(new Backdrop(this), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout row = new LinearLayout(this);
        nav = buildNav();
        row.addView(nav, new LinearLayout.LayoutParams(u(116), -1));
        LinearLayout main = new LinearLayout(this); main.setOrientation(LinearLayout.VERTICAL);
        main.setPaddingRelative(0, u(22), u(28), u(24));
        main.addView(buildHeader(), new LinearLayout.LayoutParams(-1, u(44)));
        content = new FrameLayout(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1); cp.topMargin = u(16);
        main.addView(content, cp);
        row.addView(main, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));
        overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        saver = null;
        showPage(page);
        if (firstStart) { firstStart = false; splash(); }
    }

    /** radial graphite / soft pearl background with a faint diagonal sheen (one bitmap-free draw) */
    private static class Backdrop extends View {
        private final Paint p = new Paint(), s = new Paint();
        Backdrop(android.content.Context c) { super(c); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            Skin k = skin;
            if (k.dark) p.setShader(new RadialGradient(w * (rtl ? 0.88f : 0.12f), 0, Math.max(w, h) * 0.9f, new int[]{k.bg0, k.bg1, k.bg2}, new float[]{0, .42f, 1}, Shader.TileMode.CLAMP));
            else p.setShader(new LinearGradient(0, 0, w, h, new int[]{k.bg0, k.bg1, k.bg2}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            s.setShader(new LinearGradient(w * 0.45f, 0, w * 0.75f, h * 0.6f, new int[]{0, k.sheen, 0}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas c) { c.drawRect(0, 0, getWidth(), getHeight(), p); c.drawRect(0, 0, getWidth(), getHeight(), s); }
    }

    // ------------------------------------------------------------------ navigation
    private final Map<Integer, NavItem> navItems = new HashMap<>();
    private LinearLayout buildNav() {
        LinearLayout n = new LinearLayout(this); n.setOrientation(LinearLayout.VERTICAL); n.setGravity(Gravity.CENTER_HORIZONTAL);
        n.setPadding(0, u(26), 0, u(20));
        View logo = new LogoMark(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(52), u(52)); lp.bottomMargin = u(16);
        n.addView(logo, lp);
        logo.setOnClickListener(v -> showPage(HOME));
        navItems.clear();
        Dash d = dash();
        boolean small = hU < 760;
        int[][] items = {{HOME, Glyph.HOME}, {ROOMS, Glyph.ROOMS}, {SCENES, Glyph.SCENES}, {CLIMATE, Glyph.CLIMATE}, {MUSIC, Glyph.MUSIC}, {SECURITY, Glyph.SECURITY}, {APPS, Glyph.APPS}};
        String[] keys = {"home", "rooms", "scenes", "climate", "music", "security", "apps"};
        for (int i = 0; i < items.length; i++) {
            int pg = items[i][0];
            if (pg == CLIMATE && d.climate.isEmpty() && !hasTemps(d)) continue;
            if (pg == SECURITY && d.security.isEmpty() && d.openings.isEmpty() && d.safety.isEmpty()) continue;
            if (pg == APPS && launchable().isEmpty()) continue;
            NavItem it = new NavItem(this, items[i][1], Lang.s(keys[i]));
            it.setOnClickListener(v -> { if (pg == MUSIC) openMusic(); else showPage(pg); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(u(88), u(small ? 70 : 76)); p.bottomMargin = u(6);
            n.addView(it, p);
            navItems.put(pg, it);
        }
        n.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        NavItem set = new NavItem(this, Glyph.GEAR, Lang.s("settings"));
        set.setOnClickListener(v -> withPin(Cfg.OWNER, () -> startActivity(new Intent(this, SettingsActivity.class))));
        n.addView(set, new LinearLayout.LayoutParams(u(88), u(70)));
        return n;
    }
    private static boolean hasTemps(Dash d) { for (Dash.Room r : d.rooms) if (!r.temps.isEmpty()) return true; return false; }

    private void markNav() {
        int sel = page == ROOM ? ROOMS : page;
        for (Map.Entry<Integer, NavItem> e : navItems.entrySet()) e.getValue().sel(e.getKey() == sel);
    }

    static class NavItem extends View {
        private final int glyph; private final String label; private boolean sel;
        private final TextPaint t = paint(13, 0, MED); private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        NavItem(android.content.Context c, int g, String l) { super(c); glyph = g; label = l; setContentDescription(l); setClickable(true); Kit.pressable(this); }
        void sel(boolean s) { if (s != sel) { sel = s; invalidate(); } }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            if (sel) {
                p.setShader(new LinearGradient(0, 0, W * 0.3f, H, skin.accentBg0, skin.accentBg1, Shader.TileMode.CLAMP));
                c.drawRoundRect(0, 0, W, H, uf(20), uf(20), p); p.setShader(null);
            }
            int col = sel ? skin.accent : skin.sub;
            float g = uf(28);
            Glyph.draw(c, glyph, (W - g) / 2, H * 0.5f - g + uf(2), g, col);
            t.setColor(col); t.setTypeface(sel ? BOLD : MED);
            CharSequence s = android.text.TextUtils.ellipsize(label, t, W - uf(6), android.text.TextUtils.TruncateAt.END);
            c.drawText(s, 0, s.length(), (W - t.measureText(s, 0, s.length())) / 2, H * 0.5f + uf(22), t);
        }
    }

    /** the green AJ mark from the mockups (logo bitmap when the app has it) */
    static class LogoMark extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); private final TextPaint t = paint(17, 0xFF0F1A0B, BOLD);
        LogoMark(android.content.Context c) { super(c); setClickable(true); setContentDescription("AJ"); }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            p.setShader(new RadialGradient(W / 2, H * 0.6f, W * 0.75f, 0x556EC850, 0x006EC850, Shader.TileMode.CLAMP)); c.drawCircle(W / 2, H * 0.6f, W * 0.75f, p);
            p.setShader(new LinearGradient(0, 0, W, H, skin.logo0, skin.logo1, Shader.TileMode.CLAMP));
            c.drawRoundRect(0, 0, W, H, uf(16), uf(16), p); p.setShader(null);
            c.drawText("AJ", (W - t.measureText("AJ")) / 2, H / 2 + t.getTextSize() * 0.36f, t);
        }
    }

    // ------------------------------------------------------------------ header
    private View buildHeader() {
        LinearLayout hd = new LinearLayout(this); hd.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL); titles.setGravity(Gravity.CENTER_VERTICAL);
        hOwner = tv(this, "", 12, skin.accent, BOLD); hOwner.setLetterSpacing(Lang.ar ? 0 : 0.14f);
        titles.addView(hOwner);
        LinearLayout tr = new LinearLayout(this); tr.setGravity(Gravity.BOTTOM);
        hTitle = tv(this, "", 26, skin.text, MED);
        hStatus = tv(this, "", 15, skin.sub, REG); hStatus.setPaddingRelative(u(14), 0, 0, u(3));
        tr.addView(hTitle); tr.addView(hStatus, new LinearLayout.LayoutParams(0, -2, 1));
        titles.addView(tr);
        hd.addView(titles, new LinearLayout.LayoutParams(0, -1, 1));
        hChips = new LinearLayout(this); hChips.setGravity(Gravity.CENTER_VERTICAL);
        hd.addView(hChips, new LinearLayout.LayoutParams(-2, -1));
        FrameLayout menu = new FrameLayout(this);
        GradientDrawable mb = new GradientDrawable(); mb.setColor(skin.chip); mb.setCornerRadius(uf(22)); menu.setBackground(mb);
        menu.addView(new Glyph.V(this, Glyph.MENU, skin.text), new FrameLayout.LayoutParams(u(22), u(22), Gravity.CENTER));
        menu.setContentDescription(Lang.s("settings"));
        menu.setOnClickListener(v -> withPin(Cfg.OWNER, () -> startActivity(new Intent(this, SettingsActivity.class))));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(u(44), u(44)); mp.setMarginStart(u(12));
        hd.addView(menu, mp);
        return hd;
    }

    private void updateHeader() {
        if (hTitle == null) return;
        Ha a = ha(); Dash d = dash();
        String owner = cfg.ownerShow() ? cfg.owner() : "";
        String title, status = "";
        switch (page) {
            case ROOM: { Dash.Room r = d.byId.get(roomId); title = r == null ? Lang.s("rooms") : r.name.isEmpty() ? Lang.s("other") : r.name; status = roomStatus(a, d, r); break; }
            case ROOMS: title = Lang.s("rooms"); break;
            case SCENES: title = Lang.s("scenes"); break;
            case CLIMATE: title = Lang.s("climate"); break;
            case SECURITY: title = Lang.s("security"); break;
            case APPS: title = Lang.s("apps"); break;
            default: {
                title = d.mine != null ? d.mine.name : !owner.isEmpty() ? owner : Lang.s("home");
                if (d.mine == null && !owner.isEmpty()) owner = "";
                status = roomStatus(a, d, d.mine);
                if (now != null && "playing".equals(now.state)) status += (status.isEmpty() ? "" : " · ") + Lang.s("music_playing");
            }
        }
        hTitle.setText(title);
        hOwner.setText(Lang.ar ? owner : owner.toUpperCase(Locale.ROOT));
        hOwner.setVisibility(owner.isEmpty() ? View.GONE : View.VISIBLE);
        hStatus.setText(status);
        // chips: alarms / open doors / HA connection / Zigbee
        hChips.removeAllViews();
        DashHub hub = hub();
        if (hub == null || hub.ha == null) addChip(Glyph.ALERT, Lang.s("connect_ha"), skin.dangerBg, skin.danger);
        else if (!hub.ha.connected) addChip(Glyph.ALERT, hub.ha.status.startsWith("token") ? Lang.s("ha_token_bad") : Lang.s("ha_offline"), skin.dangerBg, skin.danger);
        else {
            List<String> al = d.alarmsNow(a), op = d.openNow(a);
            if (!al.isEmpty()) addChip(Glyph.ALERT, Lang.s("alarm_chip") + " · " + nameOf(a, al.get(0)), skin.dangerBg, skin.danger);
            if (op.size() == 1) addChip(Glyph.ALERT, nameOf(a, op.get(0)) + " · " + Lang.s("open"), skin.dangerBg, skin.danger);
            else if (op.size() > 1) addChip(Glyph.ALERT, Lang.f("open_n", op.size()), skin.dangerBg, skin.danger);
        }
        if (cfg.zigbeeBridge()) {
            boolean ok = ZigbeeBridge.status.startsWith("connected");
            addChip(Glyph.SIGNAL, "ZIGBEE", ok ? skin.chip : skin.dangerBg, ok ? skin.chipText : skin.danger);
        }
    }
    private void addChip(int g, String t, int bg, int fg) {
        LinearLayout c = chip(this, g, t, bg, fg, 38);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, u(38)); p.setMarginStart(u(10));
        hChips.addView(c, p);
    }
    private static String nameOf(Ha a, String id) { String n = a.attrs(id).optString("friendly_name", id); return n.length() > 22 ? n.substring(0, 21) + "…" : n; }

    private String roomStatus(Ha a, Dash d, Dash.Room r) {
        if (r == null) { int n = d.lightsOn(a, null); return n == 0 ? "" : n == 1 ? Lang.s("light_on") : Lang.f("lights_on", n); }
        StringBuilder b = new StringBuilder();
        int n = d.lightsOn(a, r);
        b.append(n == 0 ? Lang.s("all_off") : n == 1 ? Lang.s("light_on") : Lang.f("lights_on", n));
        String t = Dash.firstValue(a, r.temps), hu = Dash.firstValue(a, r.hums);
        if (!t.isEmpty()) try { b.append(" · ").append(Tiles.num(Double.parseDouble(t))).append("°"); } catch (Exception ignored) { }
        if (!hu.isEmpty()) try { b.append(" · ").append(Tiles.num(Double.parseDouble(hu))).append("%"); } catch (Exception ignored) { }
        return b.toString();
    }

    // =================================================================================== pages
    private void showPage(int p) {
        if (content == null) return;
        if (p == MUSIC) { openMusic(); return; }
        page = p;
        content.removeAllViews(); views.clear(); roomCards.clear(); music = null; clock = null; energy = null;
        switch (p) {
            case ROOMS: buildRooms(); break;
            case ROOM: buildRoom(); break;
            case SCENES: buildScenes(); break;
            case CLIMATE: buildClimate(); break;
            case SECURITY: buildSecurity(); break;
            case APPS: buildApps(); break;
            default: page = HOME; buildHome();
        }
        markNav();
        updateHeader();
    }

    private void track(String id, View v) { List<View> l = views.get(id); if (l == null) views.put(id, l = new ArrayList<>()); l.add(v); }
    private Tiles.TileView tile(Dash.Tile t) { Tiles.TileView v = new Tiles.TileView(this, t, this); track(t.id, v); return v; }

    private boolean wide() { return wU - 116 - 28 >= 1000; }

    private void buildHome() {
        DashHub hub = hub();
        Dash d = dash();
        music = new Tiles.MusicCard(this, this::openMusic, this::musicCmd);
        if (now != null) music.set(now, art);
        clock = new Tiles.ClockCard(this, this).onWeather(() -> { if (d.weather != null) showMoreInfo(d.weather); });
        boolean noHa = hub == null || hub.ha == null;
        Ha a = ha();
        boolean hasEnergy = a.energyToday >= 0;
        if (wide()) {
            Grid g = new Grid(this, 4, 3, 360, 14);
            g.addView(clock, new Grid.Cell(0, 0, 1, 3));
            g.addView(music, new Grid.Cell(1, 0, 2, 1));
            if (noHa) { g.addView(setupCard(), new Grid.Cell(1, 1, 3, 2)); content.addView(g); return; }
            int[][] slots = hasEnergy ? new int[][]{{3, 0}, {1, 1}, {2, 1}, {3, 1}, {3, 2}} : new int[][]{{3, 0}, {1, 1}, {2, 1}, {3, 1}, {1, 2}, {2, 2}, {3, 2}};
            for (int i = 0; i < slots.length && i < d.favs.size(); i++) g.addView(tile(d.favs.get(i)), new Grid.Cell(slots[i][0], slots[i][1], 1, 1));
            if (hasEnergy) { energy = new Tiles.EnergyCard(this, a); g.addView(energy, new Grid.Cell(1, 2, 2, 1)); }
            content.addView(g);
        } else {
            int cols = wU - 116 - 28 >= 700 ? 3 : 2;
            Flow f = new Flow(this, cols, 220, 14);
            f.addView(clock, new Flow.Span(cols));
            f.addView(music, new Flow.Span(cols));
            if (noHa) f.addView(setupCard(), new Flow.Span(cols));
            else {
                for (Dash.Tile t : d.favs) f.addView(tile(t), new Flow.Span(1));
                if (hasEnergy) { energy = new Tiles.EnergyCard(this, a); f.addView(energy, new Flow.Span(cols)); }
            }
            content.addView(scroll(f));
        }
    }

    private View setupCard() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(u(34), u(26), u(34), u(26));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{skin.card0, skin.card1}); bg.setCornerRadius(uf(26)); c.setBackground(bg);
        c.addView(new Glyph.V(this, Glyph.HOME, skin.accent), new LinearLayout.LayoutParams(u(48), u(48)));
        TextView t = tv(this, Lang.s("connect_ha"), 26, skin.text, MED); t.setPadding(0, u(16), 0, 0); c.addView(t);
        TextView s = tv(this, Lang.s("connect_ha_sub"), 16, skin.sub, REG); s.setSingleLine(false); s.setMaxLines(3); s.setPadding(0, u(8), 0, 0); c.addView(s);
        c.setOnClickListener(v -> withPin(Cfg.OWNER, () -> startActivity(new Intent(this, SettingsActivity.class))));
        return c;
    }

    private ScrollView scroll(View v) {
        ScrollView s = new ScrollView(this); s.setVerticalScrollBarEnabled(false); s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setClipToPadding(false); s.addView(v); return s;
    }
    private TextView empty(String key) { TextView t = tv(this, Lang.s(key), 18, skin.sub, REG); t.setGravity(Gravity.CENTER); t.setSingleLine(false); return t; }

    private void buildRooms() {
        Dash d = dash();
        if (d.rooms.isEmpty()) { content.addView(empty(hub() == null || hub().ha == null ? "connect_ha" : "no_devices")); return; }
        int cols = wide() ? 3 : 2;
        Flow f = new Flow(this, cols, 180, 14);
        for (Dash.Room r : d.rooms) {
            Tiles.RoomCard c = new Tiles.RoomCard(this, d, r, this, () -> { roomId = r.id; showPage(ROOM); });
            roomCards.add(c);
            f.addView(c, new Flow.Span(1));
        }
        content.addView(scroll(f));
    }

    private void buildRoom() {
        Dash d = dash(); Dash.Room r = d.byId.get(roomId);
        if (r == null) { showPage(ROOMS); return; }
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout back = chip(this, Glyph.BACK, Lang.s("rooms"), skin.chip, skin.chipText, 44);
        back.setOnClickListener(v -> showPage(ROOMS));
        bar.addView(back, new LinearLayout.LayoutParams(-2, u(44)));
        col.addView(bar, new LinearLayout.LayoutParams(-1, u(44)));
        int cols = wide() ? 4 : 3;
        Flow f = new Flow(this, cols, 210, 14);
        for (Dash.Tile t : r.tiles) f.addView(tile(t), new Flow.Span(t.kind.equals(Dash.CLIMATE) && cols >= 4 ? 1 : 1));
        if (r.tiles.isEmpty()) { col.addView(empty("no_devices"), new LinearLayout.LayoutParams(-1, -1)); content.addView(col); return; }
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, 0, 1); fp.topMargin = u(14);
        col.addView(scroll(f), fp);
        content.addView(col);
    }

    private void buildScenes() {
        Dash d = dash();
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        boolean hasMine = false;
        if (d.mine != null) for (Dash.Tile t : d.scenes) if (d.mine.id.equals(t.area)) hasMine = true;
        boolean all = scenesAll || !hasMine;
        if (hasMine) {
            LinearLayout bar = new LinearLayout(this);
            LinearLayout a = chip(this, 0, Lang.s("this_room"), !all ? skin.text : skin.chip, !all ? (skin.dark ? 0xFF17191E : 0xFFFFFFFF) : skin.chipText, 44);
            LinearLayout b = chip(this, 0, Lang.s("whole_home"), all ? skin.text : skin.chip, all ? (skin.dark ? 0xFF17191E : 0xFFFFFFFF) : skin.chipText, 44);
            a.setOnClickListener(v -> { scenesAll = false; showPage(SCENES); }); b.setOnClickListener(v -> { scenesAll = true; showPage(SCENES); });
            bar.addView(a, new LinearLayout.LayoutParams(-2, u(44)));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, u(44)); bp.setMarginStart(u(10)); bar.addView(b, bp);
            col.addView(bar, new LinearLayout.LayoutParams(-1, u(44)));
        }
        Flow f = new Flow(this, wide() ? 4 : 3, 200, 14);
        int n = 0;
        for (Dash.Tile t : d.scenes) if (all || d.mine.id.equals(t.area)) { f.addView(tile(t), new Flow.Span(1)); n++; }
        if (n == 0) { content.addView(empty("no_scenes")); return; }
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, 0, 1); if (hasMine) fp.topMargin = u(14);
        col.addView(scroll(f), fp);
        content.addView(col);
    }

    private void buildClimate() {
        Dash d = dash(); Ha a = ha();
        int cols = wide() ? 4 : 3;
        Flow f = new Flow(this, cols, 210, 14);
        for (Dash.Tile t : d.climate) f.addView(tile(t), new Flow.Span(1));
        for (Dash.Room r : d.rooms) {
            if (r.temps.isEmpty() && r.hums.isEmpty()) continue;
            String title = r.name.isEmpty() ? Lang.s("other") : r.name;
            Tiles.InfoTile it = new Tiles.InfoTile(this, Glyph.THERMO, title, () -> {
                String t = Dash.firstValue(a, r.temps), hu = Dash.firstValue(a, r.hums);
                String s = "";
                try { if (!t.isEmpty()) s = Tiles.num(Double.parseDouble(t)) + "°"; } catch (Exception ignored) { }
                try { if (!hu.isEmpty()) s += (s.isEmpty() ? "" : "  ") + Tiles.num(Double.parseDouble(hu)) + "%"; } catch (Exception ignored) { }
                return s.isEmpty() ? "—" : s;
            }, null);
            for (String id : r.temps) track(id, it);
            for (String id : r.hums) track(id, it);
            f.addView(it, new Flow.Span(1));
        }
        content.addView(scroll(f));
    }

    private void buildSecurity() {
        Dash d = dash(); Ha a = ha();
        Flow f = new Flow(this, wide() ? 4 : 3, 200, 14);
        for (Dash.Tile t : d.security) f.addView(tile(t), new Flow.Span(1));
        List<String> sensors = new ArrayList<>(d.safety); sensors.addAll(d.openings);
        for (Dash.Room r : d.rooms) sensors.addAll(r.motions);
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>(sensors);
        for (String id : uniq) {
            String dc = a.attrs(id).optString("device_class");
            boolean motion = Dash.isMotion(dc), safety = Dash.isSafety(dc);
            Tiles.InfoTile it = new Tiles.InfoTile(this, motion ? Glyph.MOTION : safety ? Glyph.ALERT : Glyph.DOOR, nameOf(a, id), () -> {
                String st = a.stateOf(id);
                if (st.equals("unavailable") || st.equals("unknown")) return Lang.s("unavailable");
                boolean on = st.equals("on");
                return motion ? Lang.s(on ? "detected" : "clear") : safety ? Lang.s(on ? "alarm_chip" : "ok") : Lang.s(on ? "open" : "closed");
            }, () -> "on".equals(a.stateOf(id)) && !motion);
            track(id, it);
            f.addView(it, new Flow.Span(1));
        }
        content.addView(scroll(f));
    }

    private List<ResolveInfo> launchable() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> l = new ArrayList<>();
        try {
            for (ResolveInfo r : getPackageManager().queryIntentActivities(i, 0)) {
                String p = r.activityInfo.packageName;
                if (p.equals(getPackageName())) continue;
                if (p.equals("com.android.settings") || p.startsWith("com.android.") && !p.contains("browser") && !p.contains("gallery")) continue;
                l.add(r);
            }
        } catch (Exception ignored) { }
        // YouTube first
        java.util.Collections.sort(l, (x, y) -> {
            boolean ax = x.activityInfo.packageName.contains("youtube"), ay = y.activityInfo.packageName.contains("youtube");
            return ax != ay ? (ax ? -1 : 1) : 0;
        });
        return l;
    }

    private void buildApps() {
        List<ResolveInfo> l = launchable();
        if (l.isEmpty()) { content.addView(empty("no_apps")); return; }
        Flow f = new Flow(this, wide() ? 5 : 3, 190, 14);
        PackageManager pm = getPackageManager();
        for (ResolveInfo r : l) {
            String pkg = r.activityInfo.packageName, cls = r.activityInfo.name;
            f.addView(new Tiles.AppTile(this, r.loadIcon(pm), String.valueOf(r.loadLabel(pm)), () -> {
                try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { Log.i("apps: cannot open " + pkg + ": " + e.getMessage()); }
            }), new Flow.Span(1));
        }
        content.addView(scroll(f));
    }

    private void openMusic() { startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
    private void musicCmd(String c) {
        PanelService s = PanelService.instance; if (s == null) return;
        if (c.equals("play_pause")) s.playPause(); else s.groupCmd(c);
    }

    // =================================================================================== live updates
    @Override public void onDash(String what, String id) {
        if (content == null) return;
        switch (what) {
            case Ha.STATE: {
                List<View> l = views.get(id);
                if (l != null) for (View v : l) { if (v instanceof Tiles.TileView) ((Tiles.TileView) v).refresh(); else v.invalidate(); }
                if (page == ROOMS) for (View v : roomCards) v.invalidate();
                if (sheetRefresh != null && sheetFor != null && sheetFor.equals(id)) sheetRefresh.run();
                if ("sun.sun".equals(id) && needsRebuild()) { build(); return; }
                if (id != null && headerCares(id)) updateHeaderSoon();
                if (id != null && id.equals(dash().weather) && clock != null) clock.invalidate();
                return;
            }
            case DashHub.REBUILT: case Ha.DATA:
                if (needsRebuild()) build();
                else { rebuildNav(); showPage(page); }
                return;
            case Ha.EXTRA:
                if (clock != null) clock.invalidate();
                if (page == HOME && energy == null && ha().energyToday >= 0) showPage(HOME);
                else if (energy != null) energy.invalidate();
                return;
            default: updateHeader();
        }
    }
    private void rebuildNav() {
        if (nav == null) return;
        LinearLayout n = buildNav();
        ViewGroupHelper.replace(nav, n);
        nav = n;
    }
    /** only states the header shows (lights on, open doors, alarms, room temperature) – not every power sensor */
    private boolean headerCares(String id) {
        Dash d = dash();
        if (id.startsWith("light.")) return true;
        if (d.openings.contains(id) || d.safety.contains(id)) return true;
        Dash.Room r = page == ROOM ? d.byId.get(roomId) : d.mine;
        return r != null && (r.temps.contains(id) || r.hums.contains(id));
    }
    private final Runnable headerLater = this::updateHeader;
    private void updateHeaderSoon() { h.removeCallbacks(headerLater); h.postDelayed(headerLater, 300); }

    @Override public void onUpdate(NowPlaying n, Bitmap a) {
        runOnUiThread(() -> {
            boolean changed = now == null || !now.key().equals(n.key()) || a != art;
            now = n; art = a;
            if (music != null && changed) music.set(n, a);
            if (changed && page == HOME) updateHeader();
            if (saver instanceof Saver) saver.invalidate();
        });
    }

    private int lastMinute = -1;
    private DashHub attached;
    private void onTick() {
        // the service (and with it the HA connection) may start after this screen: attach as soon as it exists
        DashHub hb = hub();
        if (hb != attached) { if (attached != null) attached.removeListener(this); attached = hb; if (hb != null) { hb.addListener(this); if (needsRebuild()) build(); else { rebuildNav(); showPage(page); } } }
        Calendar c = Calendar.getInstance();
        int m = c.get(Calendar.MINUTE);
        if (clock != null && c.get(Calendar.SECOND) % 10 == 0) clock.invalidate();
        if (m != lastMinute) {
            lastMinute = m;
            if (saver != null) saver.invalidate();
            if (needsRebuild() && overlay.getChildCount() == 0 && saver == null) { build(); return; }
            Ha a = ha(); if (a != EMPTY) a.refreshExtras();
        }
        int idle = cfg.saverMin();
        if (idle > 0 && saver == null && overlay.getChildCount() == 0 && System.currentTimeMillis() - lastTouch > idle * 60_000L) showSaver();
    }

    // =================================================================================== actions + sheets
    @Override public void act(Dash.Tile t, String action, double value) {
        DashHub d = hub(); if (d == null) return;
        if (action.equals("unlock") && cfg.hasPin(Cfg.OWNER)) { withPin(Cfg.OWNER, () -> d.act(t, action, value)); return; }
        d.act(t, action, value);
    }

    private String sheetFor;
    @Override public void detail(Dash.Tile t) {
        Ha a = ha();
        LinearLayout card = sheetCard(t.name);
        sheetFor = t.id;
        JSONObject at = a.attrs(t.id);
        boolean un = Dash.unavailable(a, t);
        if (un) { card.addView(line(Lang.s("unavailable"))); openSheet(card, null); return; }
        Runnable refresh = null;
        switch (t.kind) {
            case Dash.DIMMER: case Dash.LIGHT: {
                if (t.kind.equals(Dash.DIMMER)) {
                    Kit.Fader f = new Kit.Fader(this, 0xFFFFB547, 0xFFFFE08A).on((v, done) -> { if (done) act(t, v == 0 ? "off" : "brightness", Math.max(1, v)); });
                    int b = "on".equals(a.stateOf(t.id)) ? Math.max(1, Tiles.pct(at, "brightness", 255)) : 0; f.set(b);
                    card.addView(label(Lang.s("brightness")));
                    card.addView(f, new LinearLayout.LayoutParams(-1, u(76)));
                    refresh = () -> f.set("on".equals(a.stateOf(t.id)) ? Math.max(1, Tiles.pct(a.attrs(t.id), "brightness", 255)) : 0);
                }
                card.addView(buttons(new String[]{Lang.s("on"), Lang.s("off")}, i -> act(t, i == 0 ? "on" : "off", 0)));
                break;
            }
            case Dash.COVER: {
                if ((at.optInt("supported_features") & 4) != 0) {
                    Kit.Fader f = new Kit.Fader(this, 0xFF2CB8C8, 0xFF7FE6D2).on((v, done) -> { if (done) act(t, "position", v); });
                    f.set(at.optInt("current_position", 0));
                    card.addView(label(Lang.s("position")));
                    card.addView(f, new LinearLayout.LayoutParams(-1, u(76)));
                    refresh = () -> f.set(a.attrs(t.id).optInt("current_position", 0));
                }
                card.addView(buttons(new String[]{Lang.s("open_cmd"), Lang.s("stop"), Lang.s("close_cmd")}, i -> act(t, i == 0 ? "open" : i == 1 ? "stop" : "close", 0)));
                break;
            }
            case Dash.CLIMATE: case Dash.HEATER: {
                TextView big = tv(this, "", 72, skin.text, LIGHT); big.setGravity(Gravity.CENTER);
                Runnable show = () -> { double tt = a.attrs(t.id).optDouble("temperature", Double.NaN); big.setText(Double.isNaN(tt) ? Lang.state(a.stateOf(t.id)) : Tiles.num(tt) + "°"); };
                show.run(); refresh = show;
                LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER);
                FrameLayout minus = roundBtn(Glyph.MINUS), plus = roundBtn(Glyph.PLUS);
                minus.setOnClickListener(v -> step(t, -1)); plus.setOnClickListener(v -> step(t, 1));
                row.addView(minus, new LinearLayout.LayoutParams(u(64), u(64)));
                row.addView(big, new LinearLayout.LayoutParams(u(220), -2));
                row.addView(plus, new LinearLayout.LayoutParams(u(64), u(64)));
                card.addView(row, new LinearLayout.LayoutParams(-1, u(110)));
                JSONArray modes = at.optJSONArray("hvac_modes");
                if (modes != null && modes.length() > 0) {
                    card.addView(label(Lang.s("mode")));
                    String[] ms = new String[modes.length()]; String[] lab = new String[ms.length];
                    for (int i = 0; i < ms.length; i++) { ms[i] = modes.optString(i); lab[i] = Lang.state(ms[i].equals("heat_cool") ? "auto" : ms[i]); }
                    card.addView(buttons(lab, i -> act(t, "hvac:" + ms[i], 0)));
                }
                break;
            }
            case Dash.LOCK:
                card.addView(line(Lang.state(a.stateOf(t.id))));
                card.addView(buttons(new String[]{Lang.s("lock_cmd"), Lang.s("unlock_cmd")}, i -> { closeSheet(); act(t, i == 0 ? "lock" : "unlock", 0); }));
                break;
            case Dash.FAN: {
                if (at.has("percentage")) {
                    Kit.Fader f = new Kit.Fader(this, 0xFF6C5CE7, 0xFFB9A8FF).on((v, done) -> { if (done) act(t, v == 0 ? "off" : "percentage", v); });
                    f.set(at.optInt("percentage", 0)); refresh = () -> f.set(a.attrs(t.id).optInt("percentage", 0));
                    card.addView(f, new LinearLayout.LayoutParams(-1, u(76)));
                }
                card.addView(buttons(new String[]{Lang.s("on"), Lang.s("off")}, i -> act(t, i == 0 ? "on" : "off", 0)));
                break;
            }
            case Dash.SCENE: case Dash.SCRIPT: case Dash.BUTTON:
                card.addView(buttons(new String[]{Lang.s("run")}, i -> { act(t, "run", 0); closeSheet(); }));
                break;
            case Dash.ALARM:
                card.addView(line(Lang.state(a.stateOf(t.id))));
                break;
            default:
                card.addView(line(Lang.state(a.stateOf(t.id))));
                card.addView(buttons(new String[]{Lang.s("on"), Lang.s("off")}, i -> act(t, i == 0 ? "on" : "off", 0)));
        }
        openSheet(card, refresh);
    }

    private void step(Dash.Tile t, int dir) {
        JSONObject at = ha().attrs(t.id);
        double cur = at.optDouble("temperature", Double.NaN);
        if (Double.isNaN(cur)) return;
        act(t, "temp", cur + dir * at.optDouble("target_temp_step", 0.5));
    }

    /** weather etc.: just a short readout */
    private void showMoreInfo(String id) {
        Ha a = ha(); JSONObject at = a.attrs(id);
        LinearLayout card = sheetCard(at.optString("friendly_name", id));
        card.addView(line(Lang.state(a.stateOf(id).replace("-", "_")) + (at.has("temperature") ? " · " + Tiles.num(at.optDouble("temperature")) + "°" : "")
                + (at.has("humidity") ? " · " + Lang.s("humidity") + " " + Tiles.num(at.optDouble("humidity")) + "%" : "")));
        sheetFor = id;
        openSheet(card, null);
    }

    private LinearLayout sheetCard(String title) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(u(30), u(24), u(30), u(28));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(skin.sheetBg); bg.setCornerRadius(uf(28)); card.setBackground(bg);
        card.setClickable(true);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tv(this, title, 24, skin.text, MED), new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout x = roundBtn(Glyph.CLOSE); x.setOnClickListener(v -> closeSheet());
        top.addView(x, new LinearLayout.LayoutParams(u(48), u(48)));
        card.addView(top, new LinearLayout.LayoutParams(-1, u(52)));
        return card;
    }
    private TextView label(String s) { TextView t = tv(this, s, 14, skin.sub, MED); t.setPadding(0, u(16), 0, u(10)); return t; }
    private TextView line(String s) { TextView t = tv(this, s, 20, skin.text, REG); t.setPadding(0, u(14), 0, u(6)); t.setSingleLine(false); return t; }
    private FrameLayout roundBtn(int glyph) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(); g.setColor(skin.chip == 0xFFFFFFFF ? 0xFFEEF1F5 : skin.chip); g.setCornerRadius(uf(32)); f.setBackground(g);
        f.addView(new Glyph.V(this, glyph, skin.text), new FrameLayout.LayoutParams(u(24), u(24), Gravity.CENTER));
        f.setClickable(true); Kit.pressable(f);
        return f;
    }
    interface OnIndex { void pick(int i); }
    private View buttons(String[] labels, OnIndex on) {
        LinearLayout row = new LinearLayout(this); row.setPadding(0, u(18), 0, 0);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView b = tv(this, labels[i], 17, skin.text, MED); b.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable(); g.setColor(skin.dark ? 0xFF33373F : 0xFFEEF1F5); g.setCornerRadius(uf(26)); b.setBackground(g);
            b.setClickable(true); Kit.pressable(b);
            b.setOnClickListener(v -> on.pick(idx));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, u(52), 1); if (i > 0) p.setMarginStart(u(10));
            row.addView(b, p);
        }
        return row;
    }

    private void openSheet(View card, Runnable refresh) {
        overlay.removeAllViews();
        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(skin.scrim);
        scrim.setOnClickListener(v -> closeSheet());
        scrim.addView(card, new FrameLayout.LayoutParams(Math.min(u(640), root.getWidth() - u(60)), -2, Gravity.CENTER));
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        card.setAlpha(0f); card.setTranslationY(uf(30)); card.animate().alpha(1f).translationY(0).setDuration(160).start();
        sheetRefresh = refresh;
    }
    private void closeSheet() { if (overlay != null) overlay.removeAllViews(); sheetRefresh = null; sheetFor = null; }

    // =================================================================================== PIN
    /** runs {@code ok} at once when no PIN is set, else after the owner (or installer) PIN */
    private void withPin(String which, Runnable ok) {
        if (!cfg.hasPin(which) && !cfg.hasPin(Cfg.INSTALLER)) { ok.run(); return; }
        LinearLayout card = sheetCard(Lang.s("enter_pin"));
        TextView dots = tv(this, "", 34, skin.text, MED); dots.setGravity(Gravity.CENTER); dots.setLetterSpacing(0.4f);
        card.addView(dots, new LinearLayout.LayoutParams(-1, u(64)));
        StringBuilder pin = new StringBuilder();
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 3; c++) {
                String k = keys[r * 3 + c];
                TextView b = tv(this, k, 26, skin.text, k.equals("OK") ? MED : LIGHT); b.setGravity(Gravity.CENTER);
                GradientDrawable g = new GradientDrawable(); g.setColor(skin.dark ? 0xFF33373F : 0xFFEEF1F5); g.setCornerRadius(uf(18)); b.setBackground(g);
                b.setClickable(true); Kit.pressable(b);
                b.setOnClickListener(v -> {
                    if (k.equals("⌫")) { if (pin.length() > 0) pin.setLength(pin.length() - 1); }
                    else if (k.equals("OK")) {
                        if (cfg.checkPin(which, pin.toString())) { closeSheet(); ok.run(); return; }
                        dots.setText(Lang.s("wrong_pin")); pin.setLength(0); return;
                    } else if (pin.length() < 8) pin.append(k);
                    StringBuilder d = new StringBuilder(); for (int i = 0; i < pin.length(); i++) d.append('•'); dots.setText(d);
                });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, u(64), 1); p.setMargins(u(5), u(5), u(5), u(5));
                row.addView(b, p);
            }
            grid.addView(row);
        }
        card.addView(grid);
        openSheet(card, null);
    }

    // =================================================================================== screensaver + splash
    private void showSaver() {
        closeSheet();
        Saver s = new Saver(this);
        saver = s;
        root.addView(s, new FrameLayout.LayoutParams(-1, -1));
        s.setAlpha(0f); s.animate().alpha(1f).setDuration(600).start();
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        brightness(hr >= 22 || hr < 6 ? 0.03f : 0.18f);
        if (page != HOME) showPage(HOME);
    }
    private void hideSaver() {
        if (saver == null) return;
        root.removeView(saver); saver = null; brightness(-1f);
        lastTouch = System.currentTimeMillis();
    }
    private void brightness(float b) { WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = b; getWindow().setAttributes(lp); }

    /** big calm clock on black, owner name, weather; moves a little every minute (no burn-in) */
    class Saver extends View {
        private final TextPaint time = paint(150, 0xFFE8EAED, LIGHT), date = paint(24, 0xFF9CA0A8, REG), own = paint(18, 0xFFE2C487, BOLD), song = paint(18, 0xFF9CA0A8, REG);
        Saver(android.content.Context c) { super(c); setBackgroundColor(0xFF050608); }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            Date d = new Date();
            int m = Calendar.getInstance().get(Calendar.MINUTE);
            float dx = (float) Math.sin(m * 0.7) * uf(40), dy = (float) Math.cos(m * 0.9) * uf(30);
            int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            boolean night = hr >= 22 || hr < 6;
            time.setColor(night ? 0xFF6E727A : 0xFFE8EAED);
            String t = new SimpleDateFormat("HH:mm", Locale.US).format(d);
            String ds = new SimpleDateFormat("EEEE, d MMMM", Lang.locale()).format(d);
            Ha a = ha(); String w = weatherId();
            if (w != null && a.state(w) != null && a.attrs(w).has("temperature")) ds += "  ·  " + Tiles.num(a.attrs(w).optDouble("temperature")) + "°";
            float cy = H / 2 + dy;
            c.drawText(t, (W - time.measureText(t)) / 2 + dx, cy + uf(40), time);
            c.drawText(ds, (W - date.measureText(ds)) / 2 + dx, cy + uf(96), date);
            String o = cfg.ownerShow() ? cfg.owner() : "";
            if (!o.isEmpty()) { own.setLetterSpacing(Lang.ar ? 0 : 0.2f); String os = Lang.ar ? o : o.toUpperCase(Locale.ROOT); c.drawText(os, (W - own.measureText(os)) / 2 + dx, cy - uf(110), own); }
            if (now != null && "playing".equals(now.state) && !now.title.isEmpty()) {
                String s = "♪  " + now.title + (now.artist.isEmpty() ? "" : " – " + now.artist);
                CharSequence e = android.text.TextUtils.ellipsize(s, song, W * 0.7f, android.text.TextUtils.TruncateAt.END);
                c.drawText(e, 0, e.length(), (W - song.measureText(e, 0, e.length())) / 2 + dx, cy + uf(140), song);
            }
        }
    }

    private void splash() {
        FrameLayout s = new FrameLayout(this);
        s.setBackgroundColor(skin.dark ? 0xFF121316 : 0xFFF4F6F9);
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER);
        Bitmap logo = Theme.logo(this);
        if (logo != null) { ImageView iv = new ImageView(this); iv.setImageBitmap(logo); iv.setScaleType(ImageView.ScaleType.FIT_CENTER); c.addView(iv, new LinearLayout.LayoutParams(u(90), u(120))); }
        else c.addView(new LogoMark(this), new LinearLayout.LayoutParams(u(96), u(96)));
        String o = cfg.ownerShow() ? cfg.owner() : "";
        if (!o.isEmpty()) {
            TextView w = tv(this, Lang.s("welcome"), 18, skin.sub, REG); w.setPadding(0, u(30), 0, 0); c.addView(w);
            TextView on = tv(this, o, 34, skin.text, MED); on.setPadding(0, u(6), 0, 0); c.addView(on);
        }
        TextView b = tv(this, "AJ NETWORK SOLUTIONS", 13, skin.sub, MED); b.setLetterSpacing(0.2f); b.setPadding(0, u(o.isEmpty() ? 26 : 34), 0, 0);
        c.addView(b);
        s.addView(c, new FrameLayout.LayoutParams(-1, -1));
        root.addView(s, new FrameLayout.LayoutParams(-1, -1));
        s.animate().alpha(0f).setStartDelay(1300).setDuration(450).withEndAction(() -> root.removeView(s)).start();
    }

    /** small helper: swap a child view in place */
    static final class ViewGroupHelper {
        static void replace(View old, View neu) {
            android.view.ViewGroup p = (android.view.ViewGroup) old.getParent();
            if (p == null) return;
            int i = p.indexOfChild(old);
            android.view.ViewGroup.LayoutParams lp = old.getLayoutParams();
            p.removeViewAt(i); p.addView(neu, i, lp);
        }
    }
}
