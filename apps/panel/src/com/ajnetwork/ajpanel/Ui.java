package com.ajnetwork.ajpanel;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** Tiny helpers so the UI can be built in code (no resources needed). */
public final class Ui {
    public static final int BG = 0xF00A0F1A, CARD = 0xFF141B29, ACCENT = 0xFF6BBC46, TEXT = Color.WHITE, SUB = 0xFFAAB4C3;
    private Ui() { }
    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    public static GradientDrawable round(Context c, int color, float r) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c, r)); return g;
    }
    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }
    public static Button button(Context c, String s) {
        Button b = new Button(c); b.setText(s); b.setAllCaps(false); b.setTextSize(18); b.setTextColor(TEXT);
        b.setBackground(round(c, 0xFF2C3545, 14)); b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 56)); b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        return b;
    }
    public static String fmt(double sec) {
        if (sec <= 0) return "0:00";
        int s = (int) sec; return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }
}
