package com.ajnetwork.ajpanel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.ajnetwork.ajpanel.Kit.*;

/**
 * The dashboard cards, each ONE View that draws itself (no nested layouts = cheap to draw and to update).
 * A tile redraws only when its own entity changes ({@link TileView#refresh}).
 */
public final class Tiles {
    private Tiles() { }

    /** what a tile asks the screen to do */
    public interface Host {
        Ha ha();
        /** action: toggle | on | off | brightness | open | close | stop | temp | lock | unlock | run | position */
        void act(Dash.Tile t, String action, double value);
        void detail(Dash.Tile t);
    }

    static String num(double v) { return Math.abs(v - Math.round(v)) < 0.05 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v); }
    static int pct(JSONObject a, String k, int max) { return a.has(k) && !a.isNull(k) ? (int) Math.round(a.optDouble(k, 0) * 100 / max) : -1; }

    // =================================================================================== base with touch zones
    static abstract class Touch extends View {
        static final class Zone { final RectF r = new RectF(); String action; double v; }
        final List<Zone> zones = new ArrayList<>();
        private float dx, dy; private boolean longFired, moved;
        private final int slop;
        private final Runnable longPress = () -> { longFired = true; performHapticFeedback(0); onLong(); };
        Touch(Context c) { super(c); slop = ViewConfiguration.get(c).getScaledTouchSlop(); setClickable(true); }
        Zone zone(float l, float t, float r, float b, String a, double v) {
            Zone z = new Zone(); z.r.set(Kit.x(getWidth(), l, r - l), t, Kit.x(getWidth(), l, r - l) + (r - l), b); z.action = a; z.v = v; zones.add(z); return z;
        }
        abstract void onTap(String zoneAction, double v, float x, float y);
        void onLong() { }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dx = e.getX(); dy = e.getY(); longFired = false; moved = false;
                    animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).start();
                    postDelayed(longPress, 550); return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - dx) > slop || Math.abs(e.getY() - dy) > slop) { moved = true; removeCallbacks(longPress); }
                    return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress); animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                    if (!longFired && !moved) {
                        for (Zone z : zones) if (z.r.contains(dx, dy)) { onTap(z.action, z.v, dx, dy); return true; }
                        onTap(null, 0, dx, dy);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress); animate().scaleX(1f).scaleY(1f).setDuration(110).start(); return true;
                default: return true;
            }
        }
    }

    // =================================================================================== entity tile
    public static class TileView extends Touch {
        public final Dash.Tile t;
        private final Host host;
        private final LinearGradient[] g = new LinearGradient[1];
        private int gTop, gBottom;
        private long optUntil, ranAt; private boolean optOn;
        private final TextPaint name = paint(16, 0, MED), sub = paint(12, 0, REG), value = paint(20, 0, LIGHT), big = paint(52, 0, LIGHT), small = paint(12, 0, MED), state = paint(18, 0, MED);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public TileView(Context c, Dash.Tile t, Host host) { super(c); this.t = t; this.host = host; setContentDescription(t.name); }

        /** HA state of this tile changed */
        public void refresh() { optUntil = 0; invalidate(); }
        boolean on() { return System.currentTimeMillis() < optUntil ? optOn : Dash.isOn(host.ha(), t); }
        void optimistic(boolean v) { optOn = v; optUntil = System.currentTimeMillis() + 4000; invalidate(); postInvalidateDelayed(4100); }

        @Override void onTap(String a, double v, float x, float y) {
            Ha ha = host.ha();
            if (Dash.unavailable(ha, t)) { host.detail(t); return; }
            if (a != null) {
                if (a.equals("brightness")) { float W = getWidth(), pad = uf(18); double pc = Math.max(1, Math.min(100, ((rtl ? W - x : x) - pad) * 100 / (W - 2 * pad))); host.act(t, "brightness", pc); optimistic(true); return; }
                if (a.equals("toggle")) { optimistic(!on()); host.act(t, "toggle", 0); return; }
                host.act(t, a, v); invalidate(); return;
            }
            switch (t.kind) {
                case Dash.SCENE: case Dash.SCRIPT: case Dash.BUTTON: ranAt = System.currentTimeMillis(); invalidate(); postInvalidateDelayed(1600); host.act(t, "run", 0); return;
                case Dash.LOCK: if (on()) host.detail(t); else host.act(t, "lock", 0); return;   // never unlock with one tap
                case Dash.CLIMATE: case Dash.HEATER: case Dash.ALARM: host.detail(t); return;
                case Dash.COVER: host.detail(t); return;
                default: optimistic(!on()); host.act(t, "toggle", 0);
            }
        }
        @Override void onLong() { host.detail(t); }

        @Override protected void onDraw(Canvas c) {
            Ha ha = host.ha();
            float W = getWidth(), H = getHeight(), pad = uf(18), rad = uf(26);
            boolean un = Dash.unavailable(ha, t), on = on();
            JSONObject a = ha.attrs(t.id);
            String st = ha.stateOf(t.id);
            Skin.Tone tn = skin.tone(t.kind, on, un);
            if (g[0] == null || gTop != tn.top || gBottom != tn.bottom) { g[0] = null; gTop = tn.top; gBottom = tn.bottom; }
            card(c, 0, 0, W, H, rad, tn.top, tn.bottom, g);
            zones.clear();
            name.setColor(tn.text); sub.setColor(tn.sub); value.setColor(tn.value); big.setColor(tn.text); state.setColor(tn.value);
            float endW = uf(70);
            String subText = un ? Lang.s("unavailable") : subLine(a, st, on);
            // ----- end of the top row (value / switch / RUN)
            float topEnd = 0;
            if (!un) switch (t.kind) {
                case Dash.DIMMER: if (on) { int b = pct(a, "brightness", 255); topEnd = textEnd(c, (b < 0 ? 100 : Math.max(1, b)) + "%", W, pad, pad + uf(18), value); } break;
                case Dash.COVER: { int pos = a.optInt("current_position", -1); topEnd = textEnd(c, pos >= 0 ? pos + "%" : Lang.state(st), W, pad, pad + uf(18), value); break; }
                case Dash.SCENE: case Dash.SCRIPT: case Dash.BUTTON: {
                    boolean done = System.currentTimeMillis() - ranAt < 1500;
                    String s = done ? Lang.s("done") : Lang.s("run");
                    small.setColor(tn.dark()); float tw = small.measureText(s) + uf(20), h = uf(24);
                    p.setColor(done ? 0xFF3BB273 : tn.value); r.set(Kit.x(W, W - pad - tw, tw), pad, Kit.x(W, W - pad - tw, tw) + tw, pad + h);
                    c.drawRoundRect(r, h / 2, h / 2, p);
                    small.setColor(done ? 0xFFFFFFFF : tn.dark()); c.drawText(s, r.left + uf(10), r.top + h * 0.68f, small);
                    topEnd = tw; break;
                }
                case Dash.LOCK: case Dash.ALARM: case Dash.LIGHT: break;
                default: { // switch-like: little toggle
                    float sw = uf(34), sh = uf(20), x0 = Kit.x(W, W - pad - sw, sw);
                    p.setColor(on ? 0xFFFFFFFF : (skin.dark ? 0xFF4A4E56 : 0xFFD5DAE1)); r.set(x0, pad, x0 + sw, pad + sh); c.drawRoundRect(r, sh / 2, sh / 2, p);
                    boolean knobEnd = rtl != on;   // LTR: on = right
                    p.setColor(on ? tn.top : 0xFFFFFFFF);
                    c.drawCircle(knobEnd ? x0 + sw - sh / 2 : x0 + sh / 2, pad + sh / 2, sh / 2 - uf(2), p);
                    zone(W - pad - sw - uf(14), 0, W, pad + sh + uf(14), "toggle", 0);
                    topEnd = sw;
                }
            }
            float maxName = W - 2 * pad - (topEnd > 0 ? topEnd + uf(10) : 0);
            text(c, t.name, W, pad, pad + uf(15), name, maxName);
            text(c, subText, W, pad, pad + uf(34), sub, maxName);
            // ----- body
            float top = pad + uf(46), bottom = H - pad;
            if (un) { Glyph.draw(c, Glyph.forKind(t.kind, false), Kit.x(W, pad, uf(40)), bottom - uf(40), uf(40), tn.sub); return; }
            switch (t.kind) {
                case Dash.DIMMER: case Dash.LIGHT: drawLamp(c, W, top, bottom - (t.kind.equals(Dash.DIMMER) ? uf(20) : 0), on, tn);
                    if (t.kind.equals(Dash.DIMMER)) {
                        int b = on ? Math.max(1, pct(a, "brightness", 255)) : 0;
                        float y = bottom - uf(6); p.setShader(null); p.setColor(tn.btn); r.set(pad, y, W - pad, y + uf(6)); c.drawRoundRect(r, uf(3), uf(3), p);
                        float fw = (W - 2 * pad) * b / 100f;
                        if (fw > 0) { float fx = Kit.x(W, pad, fw); p.setShader(new LinearGradient(fx, 0, fx + fw, 0, 0xFFFFB547, 0xFFFFE08A, Shader.TileMode.CLAMP)); r.set(fx, y, fx + fw, y + uf(6)); c.drawRoundRect(r, uf(3), uf(3), p); p.setShader(null); }
                        zone(0, y - uf(26), W, H, "brightness", 0);
                    } else textEnd(c, Lang.s(on ? "on" : "off"), W, pad, bottom - uf(2), state);
                    break;
                case Dash.COVER: {
                    float bh = uf(28), by = bottom - bh;
                    drawCurtain(c, W, top, by - uf(8), a.optInt("current_position", "open".equals(st) ? 100 : 0), tn);
                    String[] lab = {Lang.s("open_cmd"), Lang.s("stop"), Lang.s("close_cmd")}, act = {"open", "stop", "close"};
                    float gap = uf(6), bw = (W - 2 * pad - 2 * gap) / 3;
                    small.setColor(tn.text);
                    for (int i = 0; i < 3; i++) {
                        float lx = pad + i * (bw + gap), x0 = Kit.x(W, lx, bw);
                        p.setColor(tn.btn); r.set(x0, by, x0 + bw, by + bh); c.drawRoundRect(r, bh / 2, bh / 2, p);
                        float tw = small.measureText(lab[i]); c.drawText(lab[i], x0 + (bw - tw) / 2, by + bh * 0.66f, small);
                        zone(lx, by - uf(8), lx + bw, H, act[i], 0);
                    }
                    break;
                }
                case Dash.CLIMATE: case Dash.HEATER: {
                    double target = a.optDouble("temperature", Double.NaN);
                    String tt = Double.isNaN(target) ? Lang.state(st) : num(target) + "°";
                    c.drawText(tt, Kit.x(W, pad, big.measureText(tt)), bottom - uf(4), big);
                    if (on && !Double.isNaN(target)) {
                        double step = a.optDouble("target_temp_step", 0.5);
                        float d = uf(36), x0 = W - pad - d;
                        String[] act = {"temp", "temp"}; double[] val = {target + step, target - step}; int[] gl = {Glyph.PLUS, Glyph.MINUS};
                        for (int i = 0; i < 2; i++) {
                            float y0 = bottom - d * (2 - i) - uf(6) * (1 - i);
                            float cx = Kit.x(W, x0, d);
                            p.setColor(tn.btn); c.drawCircle(cx + d / 2, y0 + d / 2, d / 2, p);
                            Glyph.draw(c, gl[i], cx + d * 0.25f, y0 + d * 0.25f, d * 0.5f, tn.text);
                            zone(x0 - uf(8), y0 - uf(3), W, y0 + d + uf(3), act[i], val[i]);
                        }
                    }
                    break;
                }
                case Dash.LOCK: case Dash.ALARM: {
                    float d = uf(46), y0 = bottom - d, cx = Kit.x(W, pad, d) + d / 2;
                    int ring = on ? 0xFF3BB273 : 0xFFD9564A;
                    if (st.equals("triggered") || st.equals("jammed")) ring = 0xFFFF3B30;
                    p.setShader(new RadialGradient(cx, y0 + d / 2, d, (ring & 0x00FFFFFF) | 0x66000000, ring & 0x00FFFFFF, Shader.TileMode.CLAMP));
                    c.drawCircle(cx, y0 + d / 2, d, p); p.setShader(null);
                    p.setColor(ring); c.drawCircle(cx, y0 + d / 2, d / 2, p);
                    Glyph.draw(c, t.kind.equals(Dash.ALARM) ? Glyph.SHIELD : on ? Glyph.LOCK : Glyph.UNLOCK, cx - d * 0.25f, y0 + d * 0.25f, d * 0.5f, 0xFF0F2418);
                    text(c, Lang.state(st), W, pad + d + uf(12), y0 + d / 2 + uf(6), state, W - 2 * pad - d - uf(12));
                    break;
                }
                case Dash.SCENE: case Dash.SCRIPT: case Dash.BUTTON: {
                    float d = Math.min(uf(64), bottom - top - uf(22));
                    float x0 = Kit.x(W, pad, d);
                    p.setColor(tn.btn); c.drawCircle(x0 + d / 2, top + d / 2 + uf(4), d / 2, p);
                    Glyph.draw(c, Glyph.SPARK, x0 + d * 0.22f, top + d * 0.22f + uf(4), d * 0.56f, tn.value);
                    JSONArray ents = a.optJSONArray("entity_id");
                    if (ents != null && ents.length() > 0) text(c, Lang.f("devices_n", ents.length()), W, pad, bottom - uf(2), sub, W - 2 * pad);
                    break;
                }
                default: {
                    float d = Math.min(uf(60), bottom - top - uf(26));
                    float x0 = Kit.x(W, pad, d);
                    if (on && tn.glow != 0) { p.setShader(new RadialGradient(x0 + d / 2, top + d / 2, d, (tn.glow & 0x00FFFFFF) | 0x55000000, tn.glow & 0x00FFFFFF, Shader.TileMode.CLAMP)); c.drawCircle(x0 + d / 2, top + d / 2, d, p); p.setShader(null); }
                    p.setColor(tn.btn); c.drawCircle(x0 + d / 2, top + d / 2, d / 2, p);
                    Glyph.draw(c, Glyph.forKind(t.kind, on), x0 + d * 0.22f, top + d * 0.22f, d * 0.56f, on ? tn.value : tn.sub);
                    textEnd(c, Lang.state(st), W, pad, bottom - uf(2), state);
                }
            }
        }

        private String subLine(JSONObject a, String st, boolean on) {
            switch (t.kind) {
                case Dash.CLIMATE: case Dash.HEATER: {
                    String act = a.optString("hvac_action", "");
                    double cur = a.optDouble("current_temperature", Double.NaN);
                    String m = !act.isEmpty() && !act.equals("null") ? Lang.state(act) : Lang.state(st);
                    return Double.isNaN(cur) ? m : m + " · " + num(cur) + "° " + Lang.s("now");
                }
                case Dash.COVER: return Lang.state(st);
                case Dash.LIGHT: case Dash.DIMMER: return Lang.s(t.kind.equals(Dash.DIMMER) ? "k_dimmer" : "k_light");
                default: return Lang.s("k_" + t.kind);
            }
        }

        private void drawLamp(Canvas c, float W, float top, float bottom, boolean on, Skin.Tone tn) {
            float h = bottom - top; if (h < uf(30)) return;
            float cx = W / 2, cy = top + h * 0.55f, rw = Math.min(W * 0.4f, h * 1.1f);
            if (on) {
                p.setShader(new RadialGradient(cx, cy + h * 0.1f, rw, (tn.glow & 0x00FFFFFF) | 0xCC000000, tn.glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
                r.set(cx - rw, cy - h * 0.45f, cx + rw, cy + h * 0.55f); c.drawOval(r, p); p.setShader(null);
            }
            float sw = rw * 0.5f, sh = h * 0.32f, sy = cy - sh * 0.4f;
            Path sp = new Path(); r.set(cx - sw, sy - sh, cx + sw, sy + sh); sp.arcTo(r, 180, 180); sp.close();
            p.setShader(new LinearGradient(0, sy - sh, 0, sy, skin.dark ? 0xFFFFFFFF : 0xFFFFFFFF, skin.dark ? 0xFFD9DCE2 : 0xFFC9CED6, Shader.TileMode.CLAMP));
            c.drawPath(sp, p); p.setShader(null);
            p.setColor(on ? 0xFFFFC53D : (skin.dark ? 0xFF5A5E66 : 0xFFB8BEC7));
            r.set(cx - sw * 0.93f, sy - sh * 0.3f, cx + sw * 0.93f, sy + sh * 0.3f); c.drawArc(r, 0, 180, true, p);
        }

        private void drawCurtain(Canvas c, float W, float top, float bottom, int pos, Skin.Tone tn) {
            float h = bottom - top; if (h < uf(26)) return;
            float w = Math.min(W * 0.62f, h * 1.9f), x0 = (W - w) / 2;
            p.setColor(tn.sub); r.set(x0 - w * 0.08f, top, x0 + w * 1.08f, top + uf(4)); c.drawRoundRect(r, uf(2), uf(2), p);
            p.setColor(skin.dark ? 0xFF0F2522 : 0xFFE8FAFB); r.set(x0, top + uf(4), x0 + w, bottom); c.drawRoundRect(r, uf(3), uf(3), p);
            float half = w / 2 * (1 - Math.max(0, Math.min(100, pos)) / 100f * 0.82f);
            p.setColor(tn.glow);
            Path a = new Path(); a.moveTo(x0, top + uf(4)); a.lineTo(x0 + half, top + uf(4)); a.cubicTo(x0 + half - half * 0.12f, top + h * 0.4f, x0 + half, top + h * 0.7f, x0 + half + half * 0.1f, bottom); a.lineTo(x0, bottom); a.close();
            c.drawPath(a, p);
            Path b = new Path(); float xr = x0 + w; b.moveTo(xr, top + uf(4)); b.lineTo(xr - half, top + uf(4)); b.cubicTo(xr - half + half * 0.12f, top + h * 0.4f, xr - half, top + h * 0.7f, xr - half - half * 0.1f, bottom); b.lineTo(xr, bottom); b.close();
            c.drawPath(b, p);
        }
    }

    // =================================================================================== clock + weather
    public static class ClockCard extends Touch {
        private final Host host;
        private final LinearGradient[] g = new LinearGradient[1], wg = new LinearGradient[1];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint day = paint(13, 0, BOLD), time = paint(64, 0, LIGHT), date = paint(15, 0, REG), wt = paint(30, 0, LIGHT),
                ws = paint(13, 0, REG), fc = paint(13, 0, REG);
        private final RectF r = new RectF();
        private Runnable onWeather;
        public ClockCard(Context c, Host h) { super(c); host = h; }
        public ClockCard onWeather(Runnable r) { onWeather = r; return this; }
        @Override void onTap(String a, double v, float x, float y) { if (onWeather != null && y > getHeight() * 0.6f) onWeather.run(); }

        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), pad = uf(20);
            card(c, 0, 0, W, H, uf(26), skin.dark ? 0xFF2F3238 : 0xFFFFFFFF, skin.dark ? 0xFF1B1D21 : 0xFFF3F5F8, g);
            Date now = new Date();
            // dial: ticks ring, glow + arc (minutes of the hour), face
            // tall card (Home column): dial on top, weather below; wide card (compact screens): dial at the start, weather beside it
            boolean horiz = W > H * 1.3f;
            float weatherH = horiz ? H - 2 * pad : uf(150);
            float D = horiz ? H - 2 * pad : Math.min(W - 2 * pad, H - weatherH - pad * 2 - uf(12));
            float cx = horiz ? Kit.x(W, pad, D) + D / 2 : W / 2, cy = horiz ? H / 2 : pad + uf(6) + D / 2, R = D / 2;
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(skin.dark ? 0xFF3A3F49 : 0xFFCDD3DC); p.setStrokeWidth(uf(2.2f));
            for (int i = 0; i < 60; i++) {
                double an = Math.PI * 2 * i / 60; float r1 = R * 0.9f, r2 = R * (i % 5 == 0 ? 0.97f : 0.94f);
                c.drawLine(cx + (float) Math.cos(an) * r1, cy + (float) Math.sin(an) * r1, cx + (float) Math.cos(an) * r2, cy + (float) Math.sin(an) * r2, p);
            }
            float ar = R * 0.772f;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            float sweep = 360f * (cal.get(java.util.Calendar.MINUTE) * 60 + cal.get(java.util.Calendar.SECOND)) / 3600f;
            r.set(cx - ar, cy - ar, cx + ar, cy + ar);
            p.setShader(null); p.setColor(skin.dark ? 0xFF232831 : 0xFFE6EAF0); p.setStrokeWidth(uf(10)); c.drawOval(r, p);
            SweepGradient sg = new SweepGradient(cx, cy, new int[]{0xFFA8F0FF, 0xFF4FA8FF, 0xFF7B5CFF, 0xFFA8F0FF}, new float[]{0, 0.45f, 0.9f, 1});
            Matrix m = new Matrix(); m.setRotate(-90, cx, cy); sg.setLocalMatrix(m);
            p.setShader(sg);
            if (sweep > 1) {
                p.setAlpha(skin.dark ? 60 : 40); p.setStrokeWidth(uf(22)); c.drawArc(r, -90, sweep, false, p);   // soft glow (no blur)
                p.setAlpha(255); p.setStrokeWidth(uf(10)); c.drawArc(r, -90, sweep, false, p);
            }
            p.setShader(null); p.setStyle(Paint.Style.FILL);
            float fr = R * 0.676f;
            p.setShader(new RadialGradient(cx, cy - fr * 0.2f, fr * 1.2f, skin.dark ? 0xFF2A3140 : 0xFFFFFFFF, skin.dark ? 0xFF14161A : 0xFFE9EDF3, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, fr, p); p.setShader(null);
            Locale loc = Lang.locale();
            day.setColor(skin.dark ? 0xFF8FC9FF : 0xFF3D7BD9); day.setLetterSpacing(Lang.ar ? 0 : 0.18f);
            time.setColor(skin.text); date.setColor(skin.sub);
            time.setTextSize(Math.min(uf(64), fr * 0.62f));
            String d = new SimpleDateFormat("EEEE", loc).format(now); if (!Lang.ar) d = d.toUpperCase(loc);
            String tm = new SimpleDateFormat("HH:mm", Locale.US).format(now);
            String dt = new SimpleDateFormat("d MMMM", loc).format(now);
            c.drawText(d, cx - day.measureText(d) / 2, cy - time.getTextSize() * 0.55f, day);
            c.drawText(tm, cx - time.measureText(tm) / 2, cy + time.getTextSize() * 0.33f, time);
            c.drawText(dt, cx - date.measureText(dt) / 2, cy + time.getTextSize() * 0.33f + uf(26), date);
            // weather
            Ha ha = host.ha();
            String w = host instanceof WeatherSource ? ((WeatherSource) host).weatherId() : null;
            JSONObject st = w == null || ha == null ? null : ha.state(w);
            float wy = horiz ? pad : H - pad - weatherH, wx = horiz ? pad + D + uf(20) : pad, ww = horiz ? W - wx - pad : W - 2 * pad;
            int wt0 = skin.dark ? 0xFF2C3B52 : 0xFFEAF2FF, wt1 = skin.dark ? 0xFF1D2636 : 0xFFD7E5FA;
            float bx = Kit.x(W, wx, ww);
            p.setShader(new LinearGradient(bx, wy, bx + ww * 0.3f, wy + weatherH, wt0, wt1, Shader.TileMode.CLAMP));
            r.set(bx, wy, bx + ww, wy + weatherH); c.drawRoundRect(r, uf(20), uf(20), p); p.setShader(null);
            wt.setColor(skin.text); ws.setColor(skin.dark ? 0xFFA9B8CC : 0xFF5A6B82); fc.setColor(skin.dark ? 0xFFC9D4E2 : 0xFF45505E);
            if (st == null) {
                Glyph.draw(c, Glyph.CLOUD, Kit.x(W, wx + uf(18), uf(40)), wy + uf(20), uf(40), ws.getColor());
                text(c, ha == null || !ha.connected ? Lang.s("connecting") : "—", W, wx + uf(72), wy + uf(48), ws, ww - uf(90));
                return;
            }
            JSONObject a = st.optJSONObject("attributes"); if (a == null) a = new JSONObject();
            String cond = st.optString("state");
            int gl = Glyph.weather(cond);
            float iz = uf(44), ix = Kit.x(W, wx + uf(18), iz);
            if (gl == Glyph.SUN) {
                p.setShader(new RadialGradient(ix + iz * 0.45f, wy + uf(18) + iz * 0.45f, iz * 0.5f, 0xFFFFE9A0, 0xFFFF9F1A, Shader.TileMode.CLAMP));
                c.drawCircle(ix + iz / 2, wy + uf(18) + iz / 2, iz * 0.42f, p); p.setShader(null);
            } else Glyph.draw(c, gl, ix, wy + uf(18), iz, skin.dark ? 0xFFF4F7FB : 0xFF4A6FA5);
            String temp = a.has("temperature") ? num(a.optDouble("temperature")) + "°" : "";
            text(c, temp, W, wx + uf(76), wy + uf(50), wt, uf(100));
            String place = ha.config.optString("location_name", "");
            String hum = a.has("humidity") ? " · " + num(a.optDouble("humidity")) + "%" : "";
            text(c, (place.isEmpty() ? "" : place + " · ") + Lang.state(cond.replace("-", "_")) + hum, W, wx + uf(76), wy + uf(70), ws, ww - uf(140));
            JSONArray f = ha.forecast;
            if (f != null && f.length() > 0) {
                double hi = -999, lo = 999;
                for (int i = 0; i < Math.min(24, f.length()); i++) { double tv = f.optJSONObject(i).optDouble("temperature", Double.NaN); if (!Double.isNaN(tv)) { hi = Math.max(hi, tv); lo = Math.min(lo, tv); } }
                if (hi > -999) { textEnd(c, "H " + num(hi) + "°", W, pad + uf(16), wy + uf(36), ws); textEnd(c, "L " + num(lo) + "°", W, pad + uf(16), wy + uf(54), ws); }
                int n = 5; float cw = (ww - uf(16)) / n, fy = wy + uf(96);
                int[] pick = {0, 1, 2, 4, 6};
                for (int i = 0; i < n; i++) {
                    JSONObject e = f.optJSONObject(Math.min(f.length() - 1, pick[i] * Math.max(1, f.length() / 12)));
                    if (e == null) continue;
                    String hh = e.optString("datetime", "");
                    String lab = hh.length() >= 13 ? hh.substring(11, 13) + "h" : "";
                    try { lab = new SimpleDateFormat("HH", Locale.US).format(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(hh)) + "h"; } catch (Exception ignored) { }
                    float colX = Kit.x(W, wx + uf(8) + i * cw, cw), mid = colX + cw / 2;
                    c.drawText(lab, mid - fc.measureText(lab) / 2, fy, fc);
                    String ec = e.optString("condition");
                    p.setColor(ec.startsWith("sunny") ? 0xFFFF9F1A : ec.startsWith("clear") ? 0xFF8FA4C4 : ec.contains("rain") ? 0xFF4FA8FF : 0xFFB8C4D6);
                    c.drawCircle(mid, fy + uf(13), uf(5), p);
                    String tv = num(e.optDouble("temperature", 0)) + "°";
                    c.drawText(tv, mid - fc.measureText(tv) / 2, fy + uf(38), fc);
                }
            }
        }
    }
    /** the screen tells the clock card which weather entity to show */
    public interface WeatherSource { String weatherId(); }

    // =================================================================================== now playing
    public static class MusicCard extends Touch {
        private final LinearGradient[] g = new LinearGradient[1];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint lab = paint(12, 0, BOLD), title = paint(24, 0, MED), artist = paint(15, 0, REG), small = paint(12, 0, REG), vol = paint(13, 0, REG);
        private final RectF r = new RectF();
        private NowPlaying n; private Bitmap art; private long at;
        private Bitmap shaderFor; private BitmapShader shader;
        private final Runnable open; private final java.util.function.Consumer<String> cmd;
        public MusicCard(Context c, Runnable open, java.util.function.Consumer<String> cmd) { super(c); this.open = open; this.cmd = cmd; }
        public void set(NowPlaying n, Bitmap art) { this.n = n; this.art = art; at = System.currentTimeMillis(); invalidate(); }
        public boolean playing() { return n != null && "playing".equals(n.state); }
        @Override void onTap(String a, double v, float x, float y) { if (a != null) { cmd.accept(a); return; } open.run(); }

        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), pad = uf(18);
            Skin.Tone tn = skin.music();
            card(c, 0, 0, W, H, uf(26), tn.top, tn.bottom, g);
            zones.clear();
            float A = Math.min(H - 2 * pad, uf(150));
            float ax = Kit.x(W, pad, A), ay = (H - A) / 2;
            r.set(ax, ay, ax + A, ay + A);
            if (art != null) {
                if (shaderFor != art) { shader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); shaderFor = art; }
                Matrix m = new Matrix(); float sc = Math.max(A / art.getWidth(), A / art.getHeight()); m.setScale(sc, sc); m.postTranslate(ax, ay); shader.setLocalMatrix(m);
                p.setShader(shader);
            } else p.setShader(new RadialGradient(ax + A * 0.3f, ay + A * 0.3f, A, new int[]{0xFFFFB36B, 0xFFE0567F, 0xFF5B3AA8}, new float[]{0, .45f, 1}, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, uf(18), uf(18), p); p.setShader(null);
            if (art == null) Glyph.draw(c, Glyph.MUSIC, ax + A * 0.3f, ay + A * 0.3f, A * 0.4f, 0xCCFFFFFF);
            float tx = pad + A + uf(18), tw = W - tx - pad;
            lab.setColor(tn.value); lab.setLetterSpacing(Lang.ar ? 0 : 0.14f); title.setColor(tn.text); artist.setColor(tn.sub); small.setColor(tn.sub); vol.setColor(tn.sub);
            boolean active = n != null && n.active();
            String zone = n == null ? "" : !n.groupName.isEmpty() ? n.groupName : n.playerName;
            String l = Lang.s("now_playing") + (zone.isEmpty() ? "" : " · " + (Lang.ar ? zone : zone.toUpperCase(Locale.ROOT)));
            float y = ay + uf(14);
            text(c, l, W, tx, y, lab, tw);
            text(c, active ? n.title : Lang.s("nothing_playing"), W, tx, y + uf(34), title, tw);
            text(c, active ? n.artist : Lang.s("tap_music"), W, tx, y + uf(58), artist, tw);
            if (!active) return;
            // progress
            double el = n.elapsed + ("playing".equals(n.state) ? (System.currentTimeMillis() - at) / 1000.0 : 0);
            if (n.duration > 0) el = Math.min(el, n.duration);
            float py = y + uf(80), bw = tw - uf(84);
            String e1 = Ui.fmt(el), e2 = n.duration > 0 ? Ui.fmt(n.duration) : "";
            text(c, e1, W, tx, py + uf(4), small, uf(40));
            textEnd(c, e2, W, pad, py + uf(4), small);
            float bx = tx + uf(42);
            p.setColor(tn.btn); r.set(Kit.x(W, bx, bw), py - uf(3), Kit.x(W, bx, bw) + bw, py + uf(2)); c.drawRoundRect(r, uf(3), uf(3), p);
            float fw = n.duration > 0 ? (float) (bw * el / n.duration) : 0;
            if (fw > 0) { float fx = Kit.x(W, bx, fw); p.setShader(new LinearGradient(fx, 0, fx + fw, 0, rtl ? 0xFFE0567F : 0xFFFFB36B, rtl ? 0xFFFFB36B : 0xFFE0567F, Shader.TileMode.CLAMP)); r.set(fx, py - uf(3), fx + fw, py + uf(2)); c.drawRoundRect(r, uf(3), uf(3), p); p.setShader(null); }
            // controls
            float cy = py + uf(36), b = uf(52), s = uf(40);
            float x0 = tx;
            drawBtn(c, W, x0, cy - s / 2, s, Glyph.PREV, false, tn); zone(x0 - uf(6), cy - b / 2, x0 + s + uf(6), cy + b / 2, "previous", 0);
            x0 += s + uf(12);
            drawBtn(c, W, x0, cy - b / 2, b, "playing".equals(n.state) ? Glyph.PAUSE : Glyph.PLAY, true, tn); zone(x0 - uf(6), cy - b / 2, x0 + b + uf(6), cy + b / 2, "play_pause", 0);
            x0 += b + uf(12);
            drawBtn(c, W, x0, cy - s / 2, s, Glyph.NEXT, false, tn); zone(x0 - uf(6), cy - b / 2, x0 + s + uf(6), cy + b / 2, "next", 0);
            if (n.volume >= 0) textEnd(c, Lang.s("vol") + " " + n.volume + "%", W, pad, cy + uf(5), vol);
            if ("playing".equals(n.state)) postInvalidateDelayed(1000);
        }
        private void drawBtn(Canvas c, float W, float x, float y, float d, int glyph, boolean disc, Skin.Tone tn) {
            float rx = Kit.x(W, x, d);
            if (disc) { p.setColor(skin.dark ? 0xFFF1F2F4 : 0xFFFFFFFF); c.drawCircle(rx + d / 2, y + d / 2, d / 2, p); }
            float g = d * (disc ? 0.38f : 0.55f);
            Glyph.draw(c, glyph, rx + (d - g) / 2, y + (d - g) / 2, g, disc ? (skin.dark ? 0xFF2A2036 : 0xFF4E4294) : tn.text);
        }
    }

    // =================================================================================== energy
    public static class EnergyCard extends View {
        private final Ha ha;
        private final LinearGradient[] g = new LinearGradient[1];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint lab = paint(12, 0, BOLD), val = paint(36, 0, LIGHT), unit = paint(15, 0, REG), delta = paint(13, 0, MED);
        private final RectF r = new RectF();
        public EnergyCard(Context c, Ha ha) { super(c); this.ha = ha; }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), pad = uf(20);
            card(c, 0, 0, W, H, uf(26), skin.card0, skin.card1, g);
            lab.setColor(skin.sub); lab.setLetterSpacing(Lang.ar ? 0 : 0.14f); val.setColor(skin.text); unit.setColor(skin.sub);
            text(c, Lang.s("energy_today"), W, pad, pad + uf(12), lab, uf(170));
            double today = ha.energyToday, yest = ha.energyYesterday;
            String v = today < 0 ? "—" : String.format(Locale.US, today >= 100 ? "%.0f" : "%.1f", today);
            float vw = text(c, v, W, pad, H / 2 + uf(14), val, 0);
            text(c, " kWh", W, pad + vw, H / 2 + uf(14), unit, 0);
            if (today >= 0 && yest > 0) {
                // compare with the same share of yesterday (hours so far)
                double share = yest * Math.min(1, ha.energyHours.length / 24.0);
                int dp = (int) Math.round((today - share) * 100 / Math.max(0.01, share));
                delta.setColor(dp <= 0 ? skin.ok : skin.danger);
                text(c, (dp <= 0 ? "▼ " : "▲ ") + Math.abs(dp) + "% " + Lang.s("vs_yesterday"), W, pad, H - pad, delta, uf(180));
            }
            double[] h = ha.energyHours;
            int n = 12; float bx = pad + uf(170), bw = W - bx - pad, gap = uf(7), cw = (bw - gap * (n - 1)) / n, top = pad + uf(6), bottom = H - pad;
            double max = 0.0001; int from = Math.max(0, h.length - n);
            for (int i = from; i < h.length; i++) max = Math.max(max, h[i]);
            for (int i = 0; i < n; i++) {
                int idx = from + i;
                double vv = idx < h.length ? h[idx] : 0;
                float bh = Math.max(uf(4), (float) ((bottom - top) * vv / max));
                float x0 = Kit.x(W, bx + i * (cw + gap), cw);
                boolean last = idx == h.length - 1;
                if (last) p.setShader(new LinearGradient(0, bottom - bh, 0, bottom, 0xFF8CF0B8, 0xFF2FAE6A, Shader.TileMode.CLAMP));
                else { p.setShader(null); p.setColor(skin.track); }
                r.set(x0, bottom - bh, x0 + cw, bottom); c.drawRoundRect(r, uf(4), uf(4), p);
            }
            p.setShader(null);
        }
    }

    // =================================================================================== room card (Rooms page)
    public static class RoomCard extends Touch {
        public final Dash.Room room;
        private final Dash dash; private final Host host; private final Runnable open;
        private final LinearGradient[] g = new LinearGradient[1]; private int gTop;
        private final TextPaint name = paint(20, 0, MED), sub = paint(13, 0, REG), temp = paint(30, 0, LIGHT);
        public RoomCard(Context c, Dash d, Dash.Room room, Host host, Runnable open) { super(c); this.dash = d; this.room = room; this.host = host; this.open = open; setContentDescription(room.name); }
        @Override void onTap(String a, double v, float x, float y) { open.run(); }
        @Override protected void onDraw(Canvas c) {
            Ha ha = host.ha();
            float W = getWidth(), H = getHeight(), pad = uf(20);
            int on = dash.lightsOn(ha, room);
            Skin.Tone tn = skin.tone(Dash.LIGHT, on > 0, false);
            if (gTop != tn.top) { g[0] = null; gTop = tn.top; }
            card(c, 0, 0, W, H, uf(26), tn.top, tn.bottom, g);
            name.setColor(tn.text); sub.setColor(tn.sub); temp.setColor(tn.text);
            String tv = Dash.firstValue(ha, room.temps);
            float tw = 0;
            if (!tv.isEmpty()) { try { tv = num(Double.parseDouble(tv)) + "°"; } catch (Exception ignored) { } tw = textEnd(c, tv, W, pad, pad + uf(26), temp); }
            text(c, room.name.isEmpty() ? Lang.s("other") : room.name, W, pad, pad + uf(20), name, W - 2 * pad - tw - uf(8));
            String s = on == 0 ? Lang.s("all_off") : on == 1 ? Lang.s("light_on") : Lang.f("lights_on", on);
            text(c, s, W, pad, pad + uf(44), sub, W - 2 * pad);
            // up to 6 device icons, lit when on
            float d = uf(30), y = H - pad - d; int i = 0;
            for (Dash.Tile t : room.tiles) {
                if (i >= 6 || t.kind.equals(Dash.SCENE) || t.kind.equals(Dash.SCRIPT)) continue;
                boolean lit = Dash.isOn(ha, t);
                Glyph.draw(c, Glyph.forKind(t.kind, lit), Kit.x(W, pad + i * (d + uf(10)), d), y, d, lit ? tn.value : (skin.dark ? 0x66FFFFFF : 0x66000000));
                i++;
            }
        }
    }

    // =================================================================================== app tile
    public static class AppTile extends Touch {
        private final android.graphics.drawable.Drawable icon; private final String label; private final Runnable open;
        private final LinearGradient[] g = new LinearGradient[1];
        private final TextPaint name = paint(15, 0, MED);
        public AppTile(Context c, android.graphics.drawable.Drawable icon, String label, Runnable open) { super(c); this.icon = icon; this.label = label; this.open = open; setContentDescription(label); }
        @Override void onTap(String a, double v, float x, float y) { open.run(); }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), d = Math.min(uf(76), H * 0.5f);
            card(c, 0, 0, W, H, uf(26), skin.card0, skin.card1, g);
            if (icon != null) { int x = Math.round((W - d) / 2), y = Math.round(H * 0.18f); icon.setBounds(x, y, x + Math.round(d), y + Math.round(d)); icon.draw(c); }
            name.setColor(skin.text);
            CharSequence t = android.text.TextUtils.ellipsize(label, name, W - uf(24), android.text.TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), (W - name.measureText(t, 0, t.length())) / 2, H - uf(22), name);
        }
    }

    // =================================================================================== sensor line (climate / security pages)
    public static class InfoTile extends View {
        private final int glyph; private final String title; private final java.util.function.Supplier<String> value; private final java.util.function.Supplier<Boolean> alert;
        private final LinearGradient[] g = new LinearGradient[1];
        private final TextPaint t1 = paint(14, 0, MED), t2 = paint(26, 0, LIGHT);
        public InfoTile(Context c, int glyph, String title, java.util.function.Supplier<String> value, java.util.function.Supplier<Boolean> alert) { super(c); this.glyph = glyph; this.title = title; this.value = value; this.alert = alert; }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), pad = uf(18);
            boolean al = alert != null && alert.get();
            card(c, 0, 0, W, H, uf(22), al ? skin.dangerBg : skin.card0, al ? skin.dangerBg : skin.card1, al ? null : g);
            t1.setColor(al ? skin.danger : skin.sub); t2.setColor(al ? skin.danger : skin.text);
            float d = uf(26);
            Glyph.draw(c, glyph, Kit.x(W, pad, d), pad, d, al ? skin.danger : skin.sub);
            text(c, title, W, pad + d + uf(10), pad + uf(18), t1, W - 2 * pad - d - uf(10));
            text(c, value.get(), W, pad, H - pad, t2, W - 2 * pad);
        }
    }
}
