package com.ajnetwork.ajzigbee;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** AJ Network Solutions look (D-021): navy, logo green, built in code (no layout resources). */
public final class Ui {
    private Ui() { }
    public static final int BG = 0xFF0A0F1A, CARD = 0xFF141B29, CARD2 = 0xFF1F2738, LINE = 0x22FFFFFF;
    public static final int GREEN = 0xFF6BBC46, GREEN_DARK = 0xFF418331, NAVY = 0xFF0B1220;
    public static final int TEXT = 0xFFFFFFFF, SUB = 0xFFA1A9B8, MUTED = 0xFF6B7280, AMBER = 0xFFF2B54A, RED = 0xFFE5533D;

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static GradientDrawable box(Context c, int color, float r) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c, r)); return g;
    }

    public static Drawable press(Context c, int color, float r) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), box(c, color, r), box(c, 0xFFFFFFFF, r));
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    public static TextView button(Context c, String s, boolean primary) {
        TextView b = text(c, s, 15, primary ? NAVY : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(press(c, primary ? GREEN : CARD2, 12));
        b.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        b.setClickable(true); b.setFocusable(true);
        return b;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(box(c, CARD, 16));
        int p = dp(c, 14); l.setPadding(p, p, p, p);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }

    public static LinearLayout.LayoutParams margins(Context c, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b)); return p;
    }

    public static EditText field(Context c, String hint, String value, boolean secret) {
        EditText e = new EditText(c);
        e.setHint(hint); e.setText(value); e.setTextColor(TEXT); e.setHintTextColor(MUTED); e.setTextSize(15);
        e.setSingleLine(true);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setBackground(box(c, CARD2, 10));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        return e;
    }

    public static View gap(Context c, int h) { View v = new View(c); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h))); return v; }

    /** label · value line */
    public static LinearLayout kv(Context c, String k, String v) {
        LinearLayout r = row(c);
        TextView a = text(c, k, 13, SUB, false); a.setLayoutParams(lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f));
        TextView b = text(c, v, 13, TEXT, false); b.setLayoutParams(lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f));
        b.setTextIsSelectable(true);
        r.addView(a); r.addView(b); r.setPadding(0, dp(c, 3), 0, dp(c, 3));
        return r;
    }
}
