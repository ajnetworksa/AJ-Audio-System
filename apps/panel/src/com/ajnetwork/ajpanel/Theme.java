package com.ajnetwork.ajpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Colours and building blocks of the v1 panel look (mockups "AJ Audio UI Revamp", D-017). */
public final class Theme {
    private Theme() { }
    public static final int TEXT = 0xFFF2F4F8, SUB = 0xFFB9C2D0, ACCENT = 0xFF40A0FF, ACCENT2 = 0xFF9A86FF,
            CARD = 0xFF1A2333, CARD2 = 0xFF232D40, LINE = 0xFF3A4760, BAR = 0xEE111824, WARN = 0xFFF5B547, WARN_BG = 0xFF2A2215,
            SEL1 = 0xFF1E3A66, SEL2 = 0xFF2B2156, INK = 0xFF0B1220;

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static GradientDrawable page() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1C2C52, 0xFF0F1622, 0xFF2A1640});
        return g;
    }
    public static GradientDrawable box(Context c, int color, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c, radius));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }
    public static GradientDrawable selected(Context c, float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{SEL1, SEL2});
        g.setCornerRadius(dp(c, radius)); g.setStroke(dp(c, 2), ACCENT);
        return g;
    }
    public static GradientDrawable accent(Context c, float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{ACCENT, ACCENT2});
        g.setCornerRadius(dp(c, radius));
        return g;
    }
    public static GradientDrawable art(Context c, float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF3A6FD8, 0xFF8A4FD8, 0xFFE0609A});
        g.setCornerRadius(dp(c, radius));
        return g;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }
    public static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 13, SUB, true); t.setLetterSpacing(0.08f); return t;
    }
    /** kind: 0 = outline, 1 = accent (gradient), 2 = white round (play), 3 = flat card */
    public static Button button(Context c, String s, int kind) {
        Button b = new Button(c); b.setText(s); b.setAllCaps(false); b.setTextSize(17);
        b.setMinHeight(dp(c, 56)); b.setMinimumHeight(dp(c, 56)); b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        b.setGravity(Gravity.CENTER); b.setStateListAnimator(null);
        switch (kind) {
            case 1: b.setBackground(accent(c, 16)); b.setTextColor(INK); b.setTypeface(Typeface.DEFAULT_BOLD); break;
            case 2: b.setBackground(box(c, TEXT, 0, 40)); b.setTextColor(0xFF0F1622); b.setTextSize(26); break;
            case 3: b.setBackground(box(c, CARD, 0, 28)); b.setTextColor(TEXT); b.setTextSize(22); break;
            default: b.setBackground(box(c, CARD2, LINE, 14)); b.setTextColor(TEXT);
        }
        return b;
    }

    /** the calm mountain landscape of the DSPPA home look */
    public static class Mountains extends View {
        private final Paint a = new Paint(Paint.ANTI_ALIAS_FLAG), b = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Mountains(Context c) { super(c); a.setColor(0xCC1A2244); b.setColor(0xD9221B44); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            Path p1 = new Path();
            float[][] m1 = {{0, .70f}, {.18f, .55f}, {.29f, .63f}, {.46f, .42f}, {.55f, .50f}, {.68f, .38f}, {.84f, .57f}, {1, .50f}};
            p1.moveTo(0, h); for (float[] q : m1) p1.lineTo(q[0] * w, q[1] * h); p1.lineTo(w, h); p1.close();
            cv.drawPath(p1, a);
            Path p2 = new Path();
            float[][] m2 = {{0, .78f}, {.21f, .67f}, {.38f, .73f}, {.55f, .63f}, {.74f, .72f}, {1, .65f}};
            p2.moveTo(0, h); for (float[] q : m2) p2.lineTo(q[0] * w, q[1] * h); p2.lineTo(w, h); p2.close();
            cv.drawPath(p2, b);
        }
    }
}
