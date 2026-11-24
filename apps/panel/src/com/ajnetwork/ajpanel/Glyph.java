package com.ajnetwork.ajpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Line icons for the dashboard, drawn on a 24×24 grid straight onto the canvas (no bitmaps, any size, any colour). */
public final class Glyph {
    private Glyph() { }
    public static final int HOME = 1, ROOMS = 2, SCENES = 3, CLIMATE = 4, MUSIC = 5, SECURITY = 6, APPS = 7, GEAR = 8, BULB = 9, POWER = 10,
            FAN = 11, CURTAIN = 12, SNOW = 13, FLAME = 14, LOCK = 15, UNLOCK = 16, DOOR = 17, MOTION = 18, ALERT = 19, SIGNAL = 20, MENU = 21,
            PLUS = 22, MINUS = 23, PLAY = 24, PAUSE = 25, NEXT = 26, PREV = 27, BACK = 28, SPARK = 29, DROP = 30, SUN = 31, MOON = 32, CLOUD = 33,
            RAIN = 34, VACUUM = 35, VALVE = 36, TAP = 37, CHECK = 38, CLOSE = 39, SHIELD = 40, THERMO = 41, CHEVRON = 42;

    private static final Paint s = new Paint(Paint.ANTI_ALIAS_FLAG), f = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path p = new Path();
    private static final RectF r = new RectF();
    static {
        s.setStyle(Paint.Style.STROKE); s.setStrokeCap(Paint.Cap.ROUND); s.setStrokeJoin(Paint.Join.ROUND);
        f.setStyle(Paint.Style.FILL);
    }

    public static int forKind(String kind, boolean on) {
        switch (kind) {
            case Dash.LIGHT: case Dash.DIMMER: return BULB;
            case Dash.SWITCH: return POWER;
            case Dash.FAN: return FAN;
            case Dash.COVER: return CURTAIN;
            case Dash.CLIMATE: return SNOW;
            case Dash.HEATER: return FLAME;
            case Dash.LOCK: return on ? LOCK : UNLOCK;
            case Dash.ALARM: return SHIELD;
            case Dash.SCENE: case Dash.SCRIPT: return SPARK;
            case Dash.VACUUM: return VACUUM;
            case Dash.VALVE: return VALVE;
            case Dash.BUTTON: return TAP;
            case Dash.GATE: return DOOR;
            default: return POWER;
        }
    }

    /** HA weather condition → icon */
    public static int weather(String cond) {
        if (cond == null) return CLOUD;
        if (cond.startsWith("sunny")) return SUN;
        if (cond.startsWith("clear")) return MOON;
        if (cond.contains("rain") || cond.contains("pour") || cond.contains("lightning") || cond.contains("hail") || cond.contains("snow")) return RAIN;
        return CLOUD;
    }

    /** draws icon {@code type} with its top-left at (x, y), {@code size} px square */
    public static synchronized void draw(Canvas c, int type, float x, float y, float size, int color) {
        float k = size / 24f;
        c.save(); c.translate(x, y); c.scale(k, k);
        s.setColor(color); f.setColor(color); s.setStrokeWidth(1.8f);
        p.reset();
        switch (type) {
            case HOME: p.moveTo(4, 10.5f); p.lineTo(12, 4); p.lineTo(20, 10.5f); p.lineTo(20, 20); p.lineTo(4, 20); p.close(); c.drawPath(p, s); c.drawLine(9, 14, 15, 14, s); break;
            case ROOMS: rr(c, 3, 3, 11, 11, 2); rr(c, 13, 3, 21, 11, 2); rr(c, 3, 13, 11, 21, 2); rr(c, 13, 13, 21, 21, 2); break;
            case SCENES: p.moveTo(12, 3); p.lineTo(20, 7.5f); p.lineTo(20, 16.5f); p.lineTo(12, 21); p.lineTo(4, 16.5f); p.lineTo(4, 7.5f); p.close(); c.drawPath(p, s);
                c.drawLine(12, 12, 20, 7.5f, s); c.drawLine(12, 12, 12, 21, s); c.drawLine(12, 12, 4, 7.5f, s); break;
            case CLIMATE: case THERMO: p.addRoundRect(new RectF(9.5f, 2, 14.5f, 15), 2.5f, 2.5f, Path.Direction.CW); c.drawPath(p, s); c.drawCircle(12, 17.5f, 3.6f, s);
                c.drawLine(12, 8, 12, 16, s); break;
            case MUSIC: c.drawLine(9, 18, 9, 5, s); c.drawLine(9, 5, 20, 3, s); c.drawLine(20, 3, 20, 16, s); c.drawCircle(6, 18, 3, s); c.drawCircle(17, 16, 3, s); break;
            case SECURITY: case SHIELD: p.moveTo(12, 3); p.lineTo(20, 6); p.lineTo(20, 12); p.cubicTo(20, 17, 16.5f, 20, 12, 21); p.cubicTo(7.5f, 20, 4, 17, 4, 12); p.lineTo(4, 6); p.close(); c.drawPath(p, s);
                if (type == SECURITY) { p.reset(); p.moveTo(9, 12); p.lineTo(11, 14); p.lineTo(15, 10); c.drawPath(p, s); } break;
            case APPS: for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) c.drawCircle(6 + i * 6, 6 + j * 6, 1.9f, f); break;
            case GEAR: for (int i = 0; i < 8; i++) { double a = Math.PI * 2 * i / 8; c.drawLine(12 + (float) Math.cos(a) * 7.2f, 12 + (float) Math.sin(a) * 7.2f, 12 + (float) Math.cos(a) * 9.6f, 12 + (float) Math.sin(a) * 9.6f, s); }
                c.drawCircle(12, 12, 6.2f, s); c.drawCircle(12, 12, 2.4f, s); break;
            case BULB: p.moveTo(9, 17); p.cubicTo(9, 14, 5.5f, 12.5f, 5.5f, 9); p.cubicTo(5.5f, 5.4f, 8.4f, 2.5f, 12, 2.5f); p.cubicTo(15.6f, 2.5f, 18.5f, 5.4f, 18.5f, 9);
                p.cubicTo(18.5f, 12.5f, 15, 14, 15, 17); p.close(); c.drawPath(p, s); c.drawLine(9.5f, 20, 14.5f, 20, s); c.drawLine(10.5f, 22.2f, 13.5f, 22.2f, s); break;
            case POWER: c.drawArc(new RectF(4.5f, 5, 19.5f, 20), -50, 280, false, s); c.drawLine(12, 3, 12, 11, s); break;
            case FAN: c.drawCircle(12, 12, 1.8f, f);
                for (int i = 0; i < 3; i++) { c.save(); c.rotate(i * 120, 12, 12); p.reset(); p.moveTo(12, 10); p.cubicTo(9, 8, 9.5f, 3, 12.5f, 3); p.cubicTo(15.5f, 3, 15.5f, 8, 13.6f, 10.4f); c.drawPath(p, s); c.restore(); }
                break;
            case CURTAIN: c.drawLine(3, 3.5f, 21, 3.5f, s); p.moveTo(5, 4); p.cubicTo(5, 10, 6, 15, 9.5f, 20.5f); p.lineTo(5, 20.5f); p.close(); c.drawPath(p, s);
                p.reset(); p.moveTo(19, 4); p.cubicTo(19, 10, 18, 15, 14.5f, 20.5f); p.lineTo(19, 20.5f); p.close(); c.drawPath(p, s); break;
            case SNOW: for (int i = 0; i < 3; i++) { c.save(); c.rotate(i * 60, 12, 12); c.drawLine(12, 3, 12, 21, s); c.drawLine(9.5f, 4.5f, 12, 7, s); c.drawLine(14.5f, 4.5f, 12, 7, s);
                c.drawLine(9.5f, 19.5f, 12, 17, s); c.drawLine(14.5f, 19.5f, 12, 17, s); c.restore(); } break;
            case FLAME: p.moveTo(12, 21); p.cubicTo(7.5f, 21, 5.5f, 17.5f, 6.5f, 14); p.cubicTo(7.5f, 10.5f, 11, 9, 11, 3); p.cubicTo(15, 6, 18.5f, 10, 18, 15); p.cubicTo(17.6f, 18.8f, 15, 21, 12, 21); p.close(); c.drawPath(p, s); break;
            case LOCK: case UNLOCK: rr(c, 5, 11, 19, 21, 2.5f);
                if (type == LOCK) c.drawArc(new RectF(8, 4, 16, 14), 180, 180, false, s); else c.drawArc(new RectF(8, 4, 16, 14), 180, 140, false, s);
                if (type == LOCK) { c.drawLine(8, 9, 8, 11, s); c.drawLine(16, 9, 16, 11, s); } else c.drawLine(8, 9, 8, 11, s);
                c.drawCircle(12, 16, 1.4f, f); break;
            case DOOR: rr(c, 6, 3, 18, 21, 1.5f); c.drawCircle(15, 12.5f, 1.1f, f); break;
            case MOTION: c.drawCircle(13, 4.5f, 1.8f, f); p.moveTo(8, 21); p.lineTo(11, 15); p.lineTo(13.5f, 16.5f); p.lineTo(15, 21); c.drawPath(p, s);
                p.reset(); p.moveTo(11, 15); p.lineTo(12, 9); p.lineTo(16, 12); p.lineTo(18.5f, 12); c.drawPath(p, s); c.drawLine(12, 9, 8, 11, s); break;
            case ALERT: p.moveTo(12, 3); p.lineTo(22, 20.5f); p.lineTo(2, 20.5f); p.close(); c.drawPath(p, f);
                f.setColor(0xFF000000); f.setAlpha(150); c.drawRect(11, 9, 13, 15, f); c.drawRect(11, 16.5f, 13, 18.5f, f); f.setColor(color); break;
            case SIGNAL: c.drawArc(new RectF(2, 6, 22, 26), -135, 90, false, s); c.drawArc(new RectF(5.5f, 10, 18.5f, 23), -135, 90, false, s); c.drawCircle(12, 19.5f, 1.6f, f); break;
            case MENU: c.drawLine(4, 7, 20, 7, s); c.drawLine(4, 12, 20, 12, s); c.drawLine(4, 17, 20, 17, s); break;
            case PLUS: s.setStrokeWidth(2.2f); c.drawLine(12, 5, 12, 19, s); c.drawLine(5, 12, 19, 12, s); break;
            case MINUS: s.setStrokeWidth(2.2f); c.drawLine(5, 12, 19, 12, s); break;
            case PLAY: p.moveTo(7, 4.5f); p.lineTo(19.5f, 12); p.lineTo(7, 19.5f); p.close(); c.drawPath(p, f); break;
            case PAUSE: c.drawRoundRect(new RectF(6, 5, 10, 19), 1, 1, f); c.drawRoundRect(new RectF(14, 5, 18, 19), 1, 1, f); break;
            case NEXT: p.moveTo(4, 5); p.lineTo(15, 12); p.lineTo(4, 19); p.close(); c.drawPath(p, f); c.drawRect(16, 5, 18, 19, f); break;
            case PREV: p.moveTo(20, 5); p.lineTo(9, 12); p.lineTo(20, 19); p.close(); c.drawPath(p, f); c.drawRect(6, 5, 8, 19, f); break;
            case BACK: s.setStrokeWidth(2.2f); p.moveTo(15, 5); p.lineTo(8, 12); p.lineTo(15, 19); c.drawPath(p, s); break;
            case CHEVRON: s.setStrokeWidth(2.2f); p.moveTo(9, 5); p.lineTo(16, 12); p.lineTo(9, 19); c.drawPath(p, s); break;
            case SPARK: p.moveTo(12, 2.5f); p.quadTo(13, 11, 21.5f, 12); p.quadTo(13, 13, 12, 21.5f); p.quadTo(11, 13, 2.5f, 12); p.quadTo(11, 11, 12, 2.5f); p.close(); c.drawPath(p, s);
                c.drawCircle(19, 5, 1.3f, f); break;
            case DROP: p.moveTo(12, 3); p.cubicTo(16, 8, 18.5f, 11.5f, 18.5f, 14.5f); p.cubicTo(18.5f, 18.2f, 15.6f, 21, 12, 21); p.cubicTo(8.4f, 21, 5.5f, 18.2f, 5.5f, 14.5f); p.cubicTo(5.5f, 11.5f, 8, 8, 12, 3); p.close(); c.drawPath(p, s); break;
            case SUN: c.drawCircle(12, 12, 4.5f, s); for (int i = 0; i < 8; i++) { double a = Math.PI * 2 * i / 8; c.drawLine(12 + (float) Math.cos(a) * 7.5f, 12 + (float) Math.sin(a) * 7.5f, 12 + (float) Math.cos(a) * 9.8f, 12 + (float) Math.sin(a) * 9.8f, s); } break;
            case MOON: p.addCircle(11.5f, 12.5f, 8, Path.Direction.CW); Path cut = new Path(); cut.addCircle(16, 8.5f, 7, Path.Direction.CW); p.op(cut, Path.Op.DIFFERENCE); c.drawPath(p, f); break;
            case CLOUD: case RAIN: p.moveTo(7, 17); p.cubicTo(3.5f, 17, 3, 12, 6.5f, 11.3f); p.cubicTo(7, 7, 13, 5.5f, 15, 9.5f); p.cubicTo(19, 8.5f, 21.5f, 12, 20, 15); p.cubicTo(19.4f, 16.3f, 18.3f, 17, 17, 17); p.close(); c.drawPath(p, s);
                if (type == RAIN) { c.drawLine(9, 19.5f, 8, 22, s); c.drawLine(13, 19.5f, 12, 22, s); c.drawLine(17, 19.5f, 16, 22, s); } break;
            case VACUUM: c.drawCircle(12, 12, 9, s); c.drawCircle(12, 12, 3.5f, s); c.drawLine(7, 6.5f, 17, 6.5f, s); break;
            case VALVE: c.drawLine(3, 15, 21, 15, s); c.drawLine(3, 19, 21, 19, s); c.drawLine(12, 15, 12, 8, s); c.drawLine(8, 6, 16, 6, s); break;
            case TAP: c.drawCircle(12, 12, 8.5f, s); c.drawCircle(12, 12, 3.5f, f); break;
            case CHECK: s.setStrokeWidth(2.4f); p.moveTo(5.5f, 12.5f); p.lineTo(10, 17); p.lineTo(18.5f, 7.5f); c.drawPath(p, s); break;
            case CLOSE: s.setStrokeWidth(2.2f); c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, s); c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, s); break;
            default: break;
        }
        c.restore();
    }
    private static void rr(Canvas c, float l, float t, float rt, float b, float rad) { r.set(l, t, rt, b); c.drawRoundRect(r, rad, rad, s); }

    /** a View showing one glyph (navigation, buttons) */
    public static class V extends View {
        int type, color;
        public V(Context c, int type, int color) { super(c); this.type = type; this.color = color; }
        public void set(int t, int c) { if (t != type || c != color) { type = t; color = c; invalidate(); } }
        @Override protected void onDraw(Canvas c) {
            float sz = Math.min(getWidth(), getHeight());
            boolean flip = getLayoutDirection() == LAYOUT_DIRECTION_RTL && (type == BACK || type == CHEVRON);
            if (flip) { c.save(); c.scale(-1, 1, getWidth() / 2f, 0); }
            Glyph.draw(c, type, (getWidth() - sz) / 2, (getHeight() - sz) / 2, sz, color);
            if (flip) c.restore();
        }
    }
}
