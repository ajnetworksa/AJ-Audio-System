package com.ajnetwork.ajpanel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ajnetwork.ajpanel.Kit.*;

/**
 * The one navigation rail of AJ Panel, used by the dashboard (HomeActivity) and the music screen (MainActivity),
 * so both look and behave like one system: logo · Home · Rooms · Scenes · Climate · Music · Security · Apps · Settings.
 */
public final class Nav {
    private Nav() { }
    public static final int HOME = 0, ROOMS = 1, SCENES = 2, CLIMATE = 3, MUSIC = 4, SECURITY = 5, APPS = 6;
    public interface Go { void page(int p); }

    /** sizes for this screen (1280×800 design units); call before building any dashboard-style view */
    public static void setup(Activity a) {
        android.util.DisplayMetrics m = a.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, 1), h = Math.max(m.heightPixels, 1);
        Kit.k = Math.min(h / 800f, w / (w >= h * 1.4f ? 1280f : 900f));
    }
    /** look + language for the whole app (dashboard, music, settings) */
    public static Skin skin(Cfg cfg, Ha ha) {
        String sun = ha == null || !ha.connected ? null : ha.stateOf("sun.sun");
        if ("unavailable".equals(sun)) sun = null;
        return Skin.pick(cfg.look(), sun);
    }
    public static void applyLook(Cfg cfg) {
        Lang.ar = "ar".equals(cfg.lang());
        PanelService s = PanelService.instance;
        Kit.skin = skin(cfg, s == null || s.hub == null ? null : s.hub.ha);
        Kit.rtl = Lang.ar;
        Theme.apply(Kit.skin);
        Ui.apply(Kit.skin);
    }

    public static LinearLayout build(Activity a, int selected, Dash d, Go go, Runnable settings) {
        LinearLayout n = new LinearLayout(a); n.setOrientation(LinearLayout.VERTICAL); n.setGravity(Gravity.CENTER_HORIZONTAL);
        n.setPadding(0, u(26), 0, u(20));
        View logo = new LogoMark(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u(52), u(52)); lp.bottomMargin = u(16);
        n.addView(logo, lp);
        logo.setOnClickListener(v -> go.page(HOME));
        float hU = a.getResources().getDisplayMetrics().heightPixels / Kit.k;
        boolean small = hU < 760;
        int[][] items = {{HOME, Glyph.HOME}, {ROOMS, Glyph.ROOMS}, {SCENES, Glyph.SCENES}, {CLIMATE, Glyph.CLIMATE}, {MUSIC, Glyph.MUSIC}, {SECURITY, Glyph.SECURITY}, {APPS, Glyph.APPS}};
        String[] keys = {"home", "rooms", "scenes", "climate", "music", "security", "apps"};
        for (int i = 0; i < items.length; i++) {
            int pg = items[i][0];
            if (pg == CLIMATE && d.climate.isEmpty() && !hasTemps(d)) continue;
            if (pg == SECURITY && d.security.isEmpty() && d.openings.isEmpty() && d.safety.isEmpty()) continue;
            if (pg == APPS && launchable(a).isEmpty()) continue;
            Item it = new Item(a, items[i][1], Lang.s(keys[i]));
            it.sel(pg == selected);
            it.setOnClickListener(v -> go.page(pg));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(u(88), u(small ? 70 : 76)); p.bottomMargin = u(6);
            n.addView(it, p);
            n.setTag(n.getTag() == null ? new HashMap<Integer, Item>() : n.getTag());
            @SuppressWarnings("unchecked") Map<Integer, Item> map = (Map<Integer, Item>) n.getTag();
            map.put(pg, it);
        }
        n.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1));
        Item set = new Item(a, Glyph.GEAR, Lang.s("settings"));
        set.setOnClickListener(v -> settings.run());
        n.addView(set, new LinearLayout.LayoutParams(u(88), u(70)));
        return n;
    }
    /** highlight one page in a rail made by {@link #build} */
    @SuppressWarnings("unchecked")
    public static void select(LinearLayout nav, int page) {
        if (nav == null || !(nav.getTag() instanceof Map)) return;
        for (Map.Entry<Integer, Item> e : ((Map<Integer, Item>) nav.getTag()).entrySet()) e.getValue().sel(e.getKey() == page);
    }

    static boolean hasTemps(Dash d) { for (Dash.Room r : d.rooms) if (!r.temps.isEmpty()) return true; return false; }

    /** apps for the Apps page: everything with a launcher icon except Android's own tools (YouTube first) */
    public static List<ResolveInfo> launchable(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> l = new ArrayList<>();
        try {
            for (ResolveInfo r : c.getPackageManager().queryIntentActivities(i, 0)) {
                String p = r.activityInfo.packageName;
                if (p.equals(c.getPackageName())) continue;
                if (p.equals("com.android.settings") || p.startsWith("com.android.") && !p.contains("browser") && !p.contains("gallery")) continue;
                l.add(r);
            }
        } catch (Exception ignored) { }
        java.util.Collections.sort(l, (x, y) -> {
            boolean ax = x.activityInfo.packageName.contains("youtube"), ay = y.activityInfo.packageName.contains("youtube");
            return ax != ay ? (ax ? -1 : 1) : 0;
        });
        return l;
    }

    static class Item extends View {
        private final int glyph; private final String label; private boolean sel;
        private final TextPaint t = paint(13, 0, MED); private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Item(Context c, int g, String l) { super(c); glyph = g; label = l; setContentDescription(l); setClickable(true); Kit.pressable(this); }
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

    /** the green AJ mark */
    static class LogoMark extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); private final TextPaint t = paint(17, 0xFF0F1A0B, BOLD);
        LogoMark(Context c) { super(c); setClickable(true); setContentDescription("AJ"); }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            p.setShader(new RadialGradient(W / 2, H * 0.6f, W * 0.75f, 0x556EC850, 0x006EC850, Shader.TileMode.CLAMP)); c.drawCircle(W / 2, H * 0.6f, W * 0.75f, p);
            p.setShader(new LinearGradient(0, 0, W, H, skin.logo0, skin.logo1, Shader.TileMode.CLAMP));
            c.drawRoundRect(0, 0, W, H, uf(16), uf(16), p); p.setShader(null);
            c.drawText("AJ", (W - t.measureText("AJ")) / 2, H / 2 + t.getTextSize() * 0.36f, t);
        }
    }

    /** dashboard background: radial graphite / soft pearl + faint sheen */
    public static class Backdrop extends View {
        private final Paint p = new Paint(), s = new Paint();
        public Backdrop(Context c) { super(c); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            Skin k = skin;
            if (k.dark) p.setShader(new RadialGradient(w * (rtl ? 0.88f : 0.12f), 0, Math.max(w, h) * 0.9f, new int[]{k.bg0, k.bg1, k.bg2}, new float[]{0, .42f, 1}, Shader.TileMode.CLAMP));
            else p.setShader(new LinearGradient(0, 0, w, h, new int[]{k.bg0, k.bg1, k.bg2}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
            s.setShader(new LinearGradient(w * 0.45f, 0, w * 0.75f, h * 0.6f, new int[]{0, k.sheen, 0}, new float[]{0, .5f, 1}, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas c) { c.drawRect(0, 0, getWidth(), getHeight(), p); c.drawRect(0, 0, getWidth(), getHeight(), s); }
    }

    /** open a dashboard page from anywhere (music screen, settings) */
    public static void open(Activity a, int page) {
        a.startActivity(new Intent(a, HomeActivity.class).putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
}
