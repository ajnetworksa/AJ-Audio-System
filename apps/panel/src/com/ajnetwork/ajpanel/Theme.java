package com.ajnetwork.ajpanel;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * AJ Panel look (D-019, ui.md §F) in the AJ Network Solutions brand (D-021): calm navy-black, Sonos-like,
 * logo green for primary actions / live state, album art carries the rest of the colour. Icons are drawn vectors.
 */
public final class Theme {
    private Theme() { }

    // ---- colours: set from the panel look (graphite / pearl) by apply(), same palette as the dashboard ----
    public static int BG = 0xFF121316;          // page
    public static int SURFACE = 0xFF2F3238;     // cards, sheets
    public static int SURFACE2 = 0xFF33373F;    // buttons, chips
    public static int SURFACE3 = 0xFF3E434C;    // pressed / selected chip
    public static int LINE = 0x17FFFFFF;
    public static int TEXT = 0xFFF1F2F4;
    public static int BRAND = 0xFFFF9F43;       // primary action = the dashboard accent
    public static int BRAND_DARK = 0xFFC9762C;
    public static final int NAVY = 0xFF0B1220;
    public static int SUB = 0xFF9CA0A8;
    public static int MUTED = 0xFF6E727A;
    public static int LIVE = 0xFF6FDB9A;        // "playing" indicator
    public static final int AMBER = 0xFFF2B54A; // break
    public static int INK = 0xFF1A1208;         // text on the accent
    /** see-through layers over the page (chips, idle cards): white on graphite, black on pearl */
    public static int OVER = 0x24FFFFFF, OVER_SOFT = 0x1AFFFFFF, OVER_STRONG = 0x66FFFFFF, RIPPLE = 0x22FFFFFF, TRACK = 0x38FFFFFF;
    public static boolean dark = true;

    /** take the colours of the dashboard look (call before building a screen) */
    public static void apply(Skin k) {
        dark = k.dark;
        BG = k.bg2; SURFACE = k.card0; LINE = k.line; TEXT = k.text; SUB = k.sub; MUTED = k.muted; LIVE = k.ok;
        SURFACE2 = k.dark ? 0xFF33373F : 0xFFE9ECF0;
        SURFACE3 = k.dark ? 0xFF3E434C : 0xFFDDE2E8;
        BRAND = k.accent; BRAND_DARK = k.dark ? 0xFFC9762C : 0xFFB8601A;
        INK = k.dark ? 0xFF1A1208 : 0xFFFFFFFF;
        OVER = k.dark ? 0x24FFFFFF : 0x12000000; OVER_SOFT = k.dark ? 0x1AFFFFFF : 0x0C000000; OVER_STRONG = k.dark ? 0x66FFFFFF : 0x55000000;
        RIPPLE = k.dark ? 0x22FFFFFF : 0x1A000000; TRACK = k.dark ? 0x38FFFFFF : 0x22000000;
    }

    /** UI scale so every panel looks alike: 1.0 on a 1024×600 dp screen, bigger on screens with more dp (e.g. DSPPA). Set by MainActivity. */
    public static float scale = 1f;
    public static int dp(Context c, float v) { return Math.round(v * scale * c.getResources().getDisplayMetrics().density); }

    // ---- type ----
    public static final int LIGHT = 0, REGULAR = 1, MEDIUM = 2;
    public static TextView text(Context c, CharSequence s, float sp, int color, int weight) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp * scale); t.setTextColor(color);
        t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(weight == LIGHT ? "sans-serif-light" : weight == MEDIUM ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    // ---- shapes ----
    public static GradientDrawable box(Context c, int color, float radius) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c, radius)); return g;
    }
    public static GradientDrawable outline(Context c, int fill, int stroke, float radius) {
        GradientDrawable g = box(c, fill, radius); g.setStroke(dp(c, 1.5f), stroke); return g;
    }
    /** background with touch ripple */
    public static Drawable press(Context c, Drawable content, float radius, boolean darkRipple) {
        boolean dark = darkRipple;
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x22000000 : RIPPLE), content, box(c, 0xFFFFFFFF, radius));
    }
    public static void clickable(View v, Drawable bg, float radius) {
        v.setBackground(press(v.getContext(), bg, radius, false)); v.setClickable(true); v.setFocusable(true);
    }

    /** text pill button. kind: 0 = grey, 1 = white (primary), 2 = outline */
    public static TextView pill(Context c, String s, int kind) {
        TextView b = text(c, s, 16, kind == 1 ? INK : TEXT, MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 22), 0, dp(c, 22), 0);
        b.setMinHeight(dp(c, 48));
        Drawable bg = kind == 1 ? box(c, BRAND, 24) : kind == 2 ? outline(c, 0x00000000, OVER_STRONG, 24) : box(c, SURFACE2, 24);
        b.setBackground(press(c, bg, 24, kind == 1));
        b.setClickable(true); b.setFocusable(true);
        return b;
    }

    /** round icon button. kind: 0 = no background, 1 = white disc (play), 2 = grey disc */
    public static FrameLayout iconButton(Context c, int icon, int sizeDp, int iconDp, int kind, String desc) {
        FrameLayout f = new FrameLayout(c);
        Icon i = new Icon(c, icon, kind == 1 ? INK : TEXT);
        f.addView(i, new FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER));
        Drawable bg = kind == 1 ? box(c, BRAND, sizeDp) : kind == 2 ? box(c, SURFACE2, sizeDp) : box(c, 0x00000000, sizeDp);
        f.setBackground(press(c, bg, sizeDp, kind == 1));
        f.setClickable(true); f.setFocusable(true); f.setContentDescription(desc);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return f;
    }
    public static Icon iconOf(FrameLayout iconButton) { return (Icon) iconButton.getChildAt(0); }

    /** the company logo mark (assets/logo_mark.png), null if missing */
    private static android.graphics.Bitmap logo;
    public static android.graphics.Bitmap logo(Context c) {
        if (logo == null) {
            try (java.io.InputStream in = c.getAssets().open("logo_mark.png")) { logo = android.graphics.BitmapFactory.decodeStream(in); }
            catch (Exception ignored) { }
        }
        return logo;
    }
    public static android.widget.ImageView logoView(Context c) {
        android.widget.ImageView v = new android.widget.ImageView(c);
        v.setImageBitmap(logo(c)); v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        v.setContentDescription("AJ Network Solutions");
        return v;
    }
    /** logo + "AJ NETWORK SOLUTIONS" */
    public static LinearLayout brandLine(Context c, int markDp, float textSp, int color) {
        LinearLayout l = new LinearLayout(c); l.setGravity(Gravity.CENTER_VERTICAL);
        l.addView(logoView(c), new LinearLayout.LayoutParams(dp(c, markDp * 0.7f), dp(c, markDp)));
        TextView t = text(c, "AJ NETWORK SOLUTIONS", textSp, color, MEDIUM); t.setLetterSpacing(0.12f);
        t.setPadding(dp(c, 10), 0, 0, 0);
        l.addView(t);
        return l;
    }

    // =========================================================== icons
    public static final int PLAY = 1, PAUSE = 2, NEXT = 3, PREV = 4, VOLUME = 5, VOLUME_OFF = 6, ROOMS = 7, NOTE = 8,
            GEAR = 9, CHECK = 10, CLOSE = 11, PLUS = 12, GROUP = 13, MOON = 14, CHEVRON = 15, SPEAKER = 16, LIBRARY = 17;

    /** small vector icons drawn on a 24×24 grid */
    public static class Icon extends View {
        private int type, color;
        private final Paint f = new Paint(Paint.ANTI_ALIAS_FLAG), s = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path p = new Path();
        public Icon(Context c, int type, int color) {
            super(c); this.type = type; this.color = color;
            f.setStyle(Paint.Style.FILL);
            s.setStyle(Paint.Style.STROKE); s.setStrokeCap(Paint.Cap.ROUND); s.setStrokeJoin(Paint.Join.ROUND);
        }
        public void set(int t) { if (t != type) { type = t; invalidate(); } }
        public void color(int c) { if (c != color) { color = c; invalidate(); } }

        @Override protected void onDraw(Canvas cv) {
            float k = Math.min(getWidth(), getHeight()) / 24f;
            cv.save(); cv.translate((getWidth() - 24 * k) / 2, (getHeight() - 24 * k) / 2); cv.scale(k, k);
            f.setColor(color); s.setColor(color); s.setStrokeWidth(2f);
            p.reset();
            switch (type) {
                case PLAY: p.moveTo(7, 4.5f); p.lineTo(19.5f, 12); p.lineTo(7, 19.5f); p.close(); round(cv); break;
                case PAUSE: cv.drawRoundRect(new RectF(6, 4.5f, 10, 19.5f), 1.2f, 1.2f, f); cv.drawRoundRect(new RectF(14, 4.5f, 18, 19.5f), 1.2f, 1.2f, f); break;
                case NEXT: p.moveTo(4.5f, 5.5f); p.lineTo(14, 12); p.lineTo(4.5f, 18.5f); p.close(); round(cv);
                    cv.drawRoundRect(new RectF(16, 5.5f, 19, 18.5f), 1, 1, f); break;
                case PREV: p.moveTo(19.5f, 5.5f); p.lineTo(10, 12); p.lineTo(19.5f, 18.5f); p.close(); round(cv);
                    cv.drawRoundRect(new RectF(5, 5.5f, 8, 18.5f), 1, 1, f); break;
                case VOLUME: case VOLUME_OFF:
                    p.moveTo(3.5f, 9.5f); p.lineTo(7, 9.5f); p.lineTo(11.5f, 5.5f); p.lineTo(11.5f, 18.5f); p.lineTo(7, 14.5f); p.lineTo(3.5f, 14.5f); p.close();
                    s.setStyle(Paint.Style.FILL_AND_STROKE); s.setStrokeWidth(1.2f); cv.drawPath(p, s); s.setStyle(Paint.Style.STROKE); s.setStrokeWidth(2f);
                    if (type == VOLUME) {
                        cv.drawArc(new RectF(10.5f, 8.5f, 17.5f, 15.5f), -50, 100, false, s);
                        cv.drawArc(new RectF(10, 5, 21, 19), -50, 100, false, s);
                    } else { cv.drawLine(15, 9.5f, 20, 14.5f, s); cv.drawLine(20, 9.5f, 15, 14.5f, s); }
                    break;
                case ROOMS:
                    s.setStrokeWidth(1.8f);
                    cv.drawRoundRect(new RectF(4, 4, 11, 11), 2, 2, s); cv.drawRoundRect(new RectF(13, 4, 20, 11), 2, 2, s);
                    cv.drawRoundRect(new RectF(4, 13, 11, 20), 2, 2, s); cv.drawRoundRect(new RectF(13, 13, 20, 20), 2, 2, s); break;
                case NOTE:
                    s.setStrokeWidth(1.8f);
                    cv.drawLine(9, 17, 9, 5.5f, s); cv.drawLine(9, 5.5f, 19, 4, s); cv.drawLine(19, 4, 19, 15.5f, s);
                    cv.drawCircle(6.5f, 17, 2.6f, f); cv.drawCircle(16.5f, 15.5f, 2.6f, f); break;
                case GEAR: {
                    s.setStrokeWidth(1.8f);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * 2 * i / 8;
                        cv.drawLine(12 + (float) Math.cos(a) * 7.2f, 12 + (float) Math.sin(a) * 7.2f, 12 + (float) Math.cos(a) * 9.6f, 12 + (float) Math.sin(a) * 9.6f, s);
                    }
                    cv.drawCircle(12, 12, 6.2f, s); cv.drawCircle(12, 12, 2.4f, s); break;
                }
                case CHECK: s.setStrokeWidth(2.6f); p.moveTo(5.5f, 12.5f); p.lineTo(10, 17); p.lineTo(18.5f, 7.5f); cv.drawPath(p, s); break;
                case CLOSE: cv.drawLine(6.5f, 6.5f, 17.5f, 17.5f, s); cv.drawLine(17.5f, 6.5f, 6.5f, 17.5f, s); break;
                case PLUS: cv.drawLine(12, 5.5f, 12, 18.5f, s); cv.drawLine(5.5f, 12, 18.5f, 12, s); break;
                case GROUP:
                    s.setStrokeWidth(1.8f);
                    cv.drawRoundRect(new RectF(3.5f, 6, 11.5f, 18), 2, 2, s); cv.drawRoundRect(new RectF(12.5f, 6, 20.5f, 18), 2, 2, s);
                    cv.drawCircle(7.5f, 13.5f, 2, s); cv.drawCircle(16.5f, 13.5f, 2, s); break;
                case MOON:
                    p.addCircle(11.5f, 12.5f, 8, Path.Direction.CW);
                    Path cut = new Path(); cut.addCircle(16, 8.5f, 7, Path.Direction.CW);
                    p.op(cut, Path.Op.DIFFERENCE); cv.drawPath(p, f); break;
                case CHEVRON: s.setStrokeWidth(2.2f); p.moveTo(7, 9.5f); p.lineTo(12, 14.5f); p.lineTo(17, 9.5f); cv.drawPath(p, s); break;
                case SPEAKER:
                    s.setStrokeWidth(1.8f);
                    cv.drawRoundRect(new RectF(6, 3, 18, 21), 3, 3, s); cv.drawCircle(12, 14.5f, 3.4f, s); cv.drawCircle(12, 7.5f, 1.2f, f); break;
                case LIBRARY:
                    s.setStrokeWidth(2f);
                    cv.drawLine(5, 4.5f, 5, 19.5f, s); cv.drawLine(9.5f, 4.5f, 9.5f, 19.5f, s); cv.drawLine(14, 4.5f, 14, 19.5f, s);
                    cv.drawLine(17, 5.5f, 20.5f, 19, s); break;
                default: break;
            }
            cv.restore();
        }
        private void round(Canvas cv) {   // softly rounded filled triangle
            s.setStyle(Paint.Style.FILL_AND_STROKE); s.setStrokeWidth(2.2f); cv.drawPath(p, s);
            s.setStyle(Paint.Style.STROKE); s.setStrokeWidth(2f);
        }
    }

    // =========================================================== slider
    public interface SliderListener { void onChange(int value, boolean done); }

    /** thin track + round thumb, 0..100. Moves on drag, reports while dragging and on release. */
    public static class Slider extends View {
        private int value, shown;
        private boolean dragging, showThumb = true, enabled = true;
        private SliderListener l;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Slider(Context c) {
            super(c);
            track.setColor(TRACK); fill.setColor(BRAND); thumb.setColor(dark ? TEXT : 0xFFFFFFFF);
            thumb.setShadowLayer(dp(c, 3), 0, dp(c, 1), 0x66000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }
        public Slider thumb(boolean t) { showThumb = t; return this; }
        public Slider listener(SliderListener x) { l = x; return this; }
        public boolean dragging() { return dragging; }
        public void setValue(int v) { if (dragging) return; v = Math.max(0, Math.min(100, v)); if (v != shown) { value = shown = v; invalidate(); } }
        public void enable(boolean e) { enabled = e; setAlpha(e ? 1f : 0.35f); }

        @Override protected void onDraw(Canvas cv) {
            float h = getHeight(), r = dp(getContext(), 8), y = h / 2, th = dp(getContext(), 2);
            float x0 = showThumb ? r : 0, x1 = getWidth() - (showThumb ? r : 0);
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            float x = rtl ? x1 - (x1 - x0) * shown / 100f : x0 + (x1 - x0) * shown / 100f;
            cv.drawRoundRect(new RectF(x0, y - th, x1, y + th), th, th, track);
            if (rtl) cv.drawRoundRect(new RectF(Math.min(x1 - th * 2, x), y - th, x1, y + th), th, th, fill);
            else cv.drawRoundRect(new RectF(x0, y - th, Math.max(x0 + th * 2, x), y + th), th, th, fill);
            if (showThumb) cv.drawCircle(x, y, dragging ? r * 1.25f : r, thumb);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!enabled || l == null) return false;
            float r = showThumb ? dp(getContext(), 8) : 0;
            float ex = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() - e.getX() : e.getX();
            int v = Math.round(Math.max(0, Math.min(1, (ex - r) / Math.max(1, getWidth() - 2 * r))) * 100);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: dragging = true; getParent().requestDisallowInterceptTouchEvent(true);
                case MotionEvent.ACTION_MOVE: shown = v; invalidate(); l.onChange(v, false); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    dragging = false; shown = value = v; invalidate(); l.onChange(v, true); return true;
                default: return true;
            }
        }
    }

    // =========================================================== backdrop
    /** the page background: a heavily blurred, darkened copy of the album art (or plain near-black) */
    public static class Backdrop extends View {
        private Bitmap blur; private Bitmap src;
        private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint();
        public Backdrop(Context c) { super(c); }
        public void set(Bitmap b) {
            if (b == src) return;
            src = b;
            blur = b == null ? null : Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(b, 12, 12, true), 48, 48, true);
            invalidate();
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            int b = BG & 0x00FFFFFF;
            shade.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xB8000000 | b, 0xE0000000 | b, 0xF7000000 | b}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas cv) {
            if (blur == null) return;
            int w = getWidth(), h = getHeight(), side = Math.max(w, h);
            cv.drawBitmap(blur, null, new Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2), p);
            cv.drawRect(0, 0, w, h, shade);
        }
    }

    // =========================================================== playing indicator
    /** three little animated bars, shown next to rooms that are playing */
    public static class Bars extends View {
        private boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public Bars(Context c) { super(c); p.setColor(LIVE); }
        public void playing(boolean v) { if (v != on) { on = v; invalidate(); } setVisibility(v ? VISIBLE : GONE); }
        @Override protected void onDraw(Canvas cv) {
            if (!on) return;
            float w = getWidth() / 5f, h = getHeight();
            long t = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) {
                float k = 0.35f + 0.65f * (float) Math.abs(Math.sin(t / (260.0 + i * 70) + i * 1.7));
                cv.drawRoundRect(new RectF(i * 2 * w, h * (1 - k), i * 2 * w + w, h), w / 2, w / 2, p);
            }
            postInvalidateDelayed(90);
        }
    }

    /** square art with rounded corners; falls back to a calm placeholder with a note icon */
    public static class Cover extends FrameLayout {
        private final android.widget.ImageView img;
        private final Icon ph;
        public Cover(Context c, float radius, float iconDp) {
            super(c);
            setBackground(box(c, SURFACE2, radius)); setClipToOutline(true);
            ph = new Icon(c, NOTE, MUTED);
            addView(ph, new LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER));
            img = new android.widget.ImageView(c); img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            addView(img, new LayoutParams(-1, -1));
        }
        public void set(Bitmap b) { img.setImageBitmap(b); ph.setVisibility(b == null ? VISIBLE : GONE); }
        public void placeholder(int icon) { ph.set(icon); }
    }
}
