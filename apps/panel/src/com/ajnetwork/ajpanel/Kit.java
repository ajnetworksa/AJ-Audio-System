package com.ajnetwork.ajpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Drawing + layout helpers for the dashboard. Everything is sized in design units of the 1280×800 mockup:
 * {@link #u(float)} turns them into pixels for this screen (DSPPA 1024×600 → 0.75), so all panels look the same.
 * RTL (Arabic): layouts mirror; canvas views use {@link #x} to mirror positions while text stays readable.
 */
public final class Kit {
    private Kit() { }
    /** px per design unit */
    public static float k = 1f;
    public static boolean rtl;
    public static Skin skin = Skin.GRAPHITE;
    public static int u(float v) { return Math.round(v * k); }
    public static float uf(float v) { return v * k; }

    public static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL),
            REG = Typeface.create("sans-serif", Typeface.NORMAL),
            MED = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    public static TextPaint paint(float sizeU, int color, Typeface tf) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTextSize(uf(sizeU)); p.setColor(color); p.setTypeface(tf);
        return p;
    }

    /** mirror an element at x (width w) inside a view of width W when the UI is right-to-left */
    public static float x(float W, float x, float w) { return rtl ? W - x - w : x; }

    /** draws text with its start at x (start = left in LTR, right in RTL), baseline y, cut to maxW with "…" */
    public static float text(Canvas c, CharSequence s, float W, float x, float y, TextPaint p, float maxW) {
        if (s == null) return 0;
        CharSequence t = maxW > 0 ? TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END) : s;
        float tw = p.measureText(t, 0, t.length());
        c.drawText(t, 0, t.length(), x(W, x, tw), y, p);
        return tw;
    }
    /** text whose END sits at {@code endX} measured from the end edge (right in LTR) */
    public static float textEnd(Canvas c, CharSequence s, float W, float endInset, float y, TextPaint p) {
        float tw = p.measureText(s, 0, s.length());
        c.drawText(s, 0, s.length(), x(W, W - endInset - tw, tw), y, p);
        return tw;
    }

    // ------------------------------------------------------------------ cards
    private static final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    static { edge.setStyle(Paint.Style.STROKE); }
    private static final RectF rf = new RectF();

    /** rounded gradient card: top→bottom (165°-ish), 1 px light top edge, pearl gets a hairline border + flat drop shade */
    public static void card(Canvas c, float l, float t, float r, float b, float rad, int top, int bottom, LinearGradient cache[]) {
        if (!skin.dark) { card.setShader(null); card.setColor(0x0F1B2A44); rf.set(l, t + uf(4), r, b + uf(4)); c.drawRoundRect(rf, rad, rad, card); }
        LinearGradient g = cache == null ? null : cache[0];
        if (g == null) {
            g = new LinearGradient(l, t, l + (r - l) * 0.35f, b, top, bottom, Shader.TileMode.CLAMP);
            if (cache != null) cache[0] = g;
        }
        card.setShader(g);
        rf.set(l, t, r, b);
        c.drawRoundRect(rf, rad, rad, card);
        card.setShader(null);
        edge.setStrokeWidth(Math.max(1, uf(1)));
        edge.setColor(skin.dark ? 0x17FFFFFF : 0x12000000);
        rf.inset(edge.getStrokeWidth() / 2, edge.getStrokeWidth() / 2);
        c.drawRoundRect(rf, rad, rad, edge);
    }

    // ------------------------------------------------------------------ simple views
    public static TextView tv(Context c, CharSequence s, float sizeU, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(sizeU)); t.setTextColor(color); t.setTypeface(tf);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); t.setIncludeFontPadding(false);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    /** rounded pill: icon + text */
    public static LinearLayout chip(Context c, int glyph, String text, int bg, int fg, float hU) {
        LinearLayout l = new LinearLayout(c); l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(u(14), 0, u(14), 0);
        l.setBackground(Theme.box(c, bg, 0)); ((android.graphics.drawable.GradientDrawable) l.getBackground()).setCornerRadius(uf(hU / 2));
        if (glyph != 0) {
            Glyph.V g = new Glyph.V(c, glyph, fg);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(u(17), u(17)); p.setMarginEnd(u(8));
            l.addView(g, p);
        }
        TextView t = tv(c, text, 14, fg, MED);
        l.addView(t);
        return l;
    }

    /** press feedback: slight shrink while touched */
    public static void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).start();
            else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) view.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
            return false;
        });
    }

    // ------------------------------------------------------------------ grids
    /** fixed grid: columns (first may be fixed width), equal rows; children say where they go (Cell) */
    public static class Grid extends ViewGroup {
        public static class Cell extends ViewGroup.LayoutParams {
            public final int col, row, cs, rs;
            public Cell(int col, int row, int cs, int rs) { super(-1, -1); this.col = col; this.row = row; this.cs = cs; this.rs = rs; }
        }
        final int cols, rows; final float firstU, gapU;
        public Grid(Context c, int cols, int rows, float firstColU, float gapU) { super(c); this.cols = cols; this.rows = rows; firstU = firstColU; this.gapU = gapU; }
        private float[] colX(int W) {
            float gap = uf(gapU), first = firstU > 0 ? uf(firstU) : -1;
            int rest = firstU > 0 ? cols - 1 : cols;
            float cw = ((firstU > 0 ? W - first - gap : W) - gap * (rest - 1)) / rest;
            float[] xs = new float[cols + 1];
            float x = 0;
            for (int i = 0; i < cols; i++) { xs[i] = x; x += (i == 0 && firstU > 0 ? first : cw) + gap; }
            xs[cols] = W + gap;
            return xs;
        }
        @Override protected void onMeasure(int ws, int hs) {
            int W = MeasureSpec.getSize(ws), H = MeasureSpec.getSize(hs);
            float[] xs = colX(W); float gap = uf(gapU), rh = (H - gap * (rows - 1)) / rows;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i); Cell p = (Cell) v.getLayoutParams();
                int w = Math.round(xs[p.col + p.cs] - gap - xs[p.col]), h = Math.round(rh * p.rs + gap * (p.rs - 1));
                v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(W, H);
        }
        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
            int W = r - l; float[] xs = colX(W); float gap = uf(gapU), rh = (b - t - gap * (rows - 1)) / rows;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i); Cell p = (Cell) v.getLayoutParams();
                int x = Math.round(xs[p.col]), y = Math.round(p.row * (rh + gap));
                int w = v.getMeasuredWidth();
                if (rtl) x = W - x - w;
                v.layout(x, y, x + w, y + v.getMeasuredHeight());
            }
        }
    }

    /** flowing grid for scroll pages: n columns, fixed row height; a child with Span(2) takes two columns */
    public static class Flow extends ViewGroup {
        public static class Span extends ViewGroup.LayoutParams { public final int n; public Span(int n) { super(-1, -1); this.n = n; } }
        final int cols; final float rowU, gapU;
        public Flow(Context c, int cols, float rowU, float gapU) { super(c); this.cols = cols; this.rowU = rowU; this.gapU = gapU; }
        @Override protected void onMeasure(int ws, int hs) {
            int W = MeasureSpec.getSize(ws); float gap = uf(gapU), cw = (W - gap * (cols - 1)) / cols, rh = uf(rowU);
            int col = 0, row = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                int n = v.getLayoutParams() instanceof Span ? Math.min(cols, ((Span) v.getLayoutParams()).n) : 1;
                if (col + n > cols) { col = 0; row++; }
                v.measure(MeasureSpec.makeMeasureSpec(Math.round(cw * n + gap * (n - 1)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.round(rh), MeasureSpec.EXACTLY));
                col += n;
            }
            int rowsN = getChildCount() == 0 ? 0 : row + 1;
            setMeasuredDimension(W, Math.round(rowsN * rh + Math.max(0, rowsN - 1) * gap + uf(8)));
        }
        @Override protected void onLayout(boolean ch, int l, int t, int r, int b) {
            int W = r - l; float gap = uf(gapU), cw = (W - gap * (cols - 1)) / cols, rh = uf(rowU);
            int col = 0, row = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                int n = v.getLayoutParams() instanceof Span ? Math.min(cols, ((Span) v.getLayoutParams()).n) : 1;
                if (col + n > cols) { col = 0; row++; }
                int x = Math.round(col * (cw + gap)), y = Math.round(row * (rh + gap)), w = v.getMeasuredWidth();
                if (rtl) x = W - x - w;
                v.layout(x, y, x + w, y + v.getMeasuredHeight());
                col += n;
            }
        }
    }

    // ------------------------------------------------------------------ fat slider (sheets)
    public interface OnValue { void value(int v, boolean done); }
    /** big rounded slider 0..100 with the value inside; grows from the start edge (right in Arabic) */
    public static class Fader extends View {
        private int value, shown; private boolean drag; private OnValue l; private int c0, c1;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint tp;
        private String suffix = "%";
        public Fader(Context c, int from, int to) { super(c); c0 = from; c1 = to; tp = paint(22, 0xFFFFFFFF, MED); }
        public Fader on(OnValue x) { l = x; return this; }
        public Fader suffix(String s) { suffix = s; return this; }
        public void set(int v) { if (drag) return; value = shown = Math.max(0, Math.min(100, v)); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), r = H / 2;
            bg.setColor(skin.track); rf.set(0, 0, W, H); c.drawRoundRect(rf, r, r, bg);
            float fw = Math.max(H, W * shown / 100f);
            float fx = x(W, 0, fw);
            fill.setShader(new LinearGradient(fx, 0, fx + fw, 0, rtl ? c1 : c0, rtl ? c0 : c1, Shader.TileMode.CLAMP));
            rf.set(fx, 0, fx + fw, H); c.drawRoundRect(rf, r, r, fill);
            tp.setColor(shown > 18 ? 0xFFFFFFFF : skin.text);
            String s = shown + suffix;
            float tw = tp.measureText(s);
            c.drawText(s, x(W, uf(22), tw), H / 2 + tp.getTextSize() * 0.35f, tp);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float px = rtl ? getWidth() - e.getX() : e.getX();
            int v = Math.round(Math.max(0, Math.min(1, px / Math.max(1, getWidth()))) * 100);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: drag = true; getParent().requestDisallowInterceptTouchEvent(true);
                case MotionEvent.ACTION_MOVE: shown = v; invalidate(); if (l != null) l.value(v, false); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: drag = false; value = shown = v; invalidate(); if (l != null) l.value(v, true); return true;
                default: return true;
            }
        }
    }
}
