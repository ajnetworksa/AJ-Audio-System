package com.ajnetwork.ajzigbee;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;

/** Floor plan with heat map, measured points and router markers. Tap = measure here / open a point, long-press = place a marker. */
public final class PlanView extends View {
    public interface Listener {
        void tapEmpty(float x, float y);
        void tapPoint(Site.Point p);
        void tapMarker(Site.Marker m);
        void longPress(float x, float y);
    }

    private Site site;
    private Site.Floor floor;
    private Bitmap plan, heat;
    private int heatKey = -1;
    private Listener listener;
    private float zoom = 1f, panX, panY;
    private final GestureDetector gd;
    private final ScaleGestureDetector sgd;
    public boolean showHeat = true, showSuggested = true;

    public PlanView(Context c) {
        super(c);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { tap(e.getX(), e.getY(), false); return true; }
            @Override public void onLongPress(MotionEvent e) { tap(e.getX(), e.getY(), true); }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) { if (zoom > 1.01f) { panX -= dx; panY -= dy; clampPan(); invalidate(); } return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { zoom = zoom > 1.5f ? 1f : 2.5f; if (zoom == 1f) { panX = panY = 0; } invalidate(); return true; }
        });
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { zoom = Math.max(1f, Math.min(6f, zoom * d.getScaleFactor())); clampPan(); invalidate(); return true; }
        });
    }

    public void setListener(Listener l) { listener = l; }

    public void show(Site s, Site.Floor f, Bitmap planBitmap) {
        site = s; floor = f; plan = planBitmap; heatKey = -1; zoom = 1f; panX = panY = 0; invalidate();
    }

    public void changed() { heatKey = -1; invalidate(); }

    private void clampPan() {
        float maxX = getWidth() * (zoom - 1) / 2f, maxY = getHeight() * (zoom - 1) / 2f;
        panX = Math.max(-maxX, Math.min(maxX, panX)); panY = Math.max(-maxY, Math.min(maxY, panY));
    }

    /** plan rectangle inside the view at zoom 1 */
    static RectF fit(int w, int h, Bitmap plan) {
        float pw = plan != null ? plan.getWidth() : 4, ph = plan != null ? plan.getHeight() : 3;
        float s = Math.min(w / pw, h / ph);
        float dw = pw * s, dh = ph * s;
        return new RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
    }

    private RectF planRect() {
        RectF r = fit(getWidth(), getHeight(), plan);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        return new RectF(cx + (r.left - cx) * zoom + panX, cy + (r.top - cy) * zoom + panY, cx + (r.right - cx) * zoom + panX, cy + (r.bottom - cy) * zoom + panY);
    }

    private void tap(float vx, float vy, boolean longPress) {
        if (floor == null || listener == null) return;
        RectF r = planRect();
        float x = (vx - r.left) / r.width(), y = (vy - r.top) / r.height();
        float hit = Ui.dp(getContext(), 22) / r.width();
        if (!longPress) {
            for (Site.Point p : floor.points) if (Math.hypot(p.x - x, (p.y - y) * r.height() / r.width()) < hit) { listener.tapPoint(p); return; }
            for (Site.Marker m : floor.markers) if (Math.hypot(m.x - x, (m.y - y) * r.height() / r.width()) < hit) { listener.tapMarker(m); return; }
        }
        if (x < 0 || x > 1 || y < 0 || y > 1) return;
        if (longPress) listener.longPress(x, y); else listener.tapEmpty(x, y);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        sgd.onTouchEvent(e);
        if (!sgd.isInProgress()) gd.onTouchEvent(e);
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(Ui.BG);
        if (floor == null) return;
        RectF r = planRect();
        int key = floor.points.size() * 31 + site.targets.size() * 7 + (int) (sumScore() * 1000);
        if (showHeat && key != heatKey) { heat = heatmap(site, floor, 72, plan); heatKey = key; }
        draw(c, r, site, floor, plan, showHeat ? heat : null, showSuggested ? Analyzer.suggestRouters(site, floor) : null, Ui.dp(getContext(), 1), true);
    }

    private double sumScore() { double s = 0; for (Site.Point p : floor.points) s += Analyzer.overall(site, p).score + p.x + p.y; return s; }

    /** shared with the PDF report. u = size unit in px */
    public static void draw(Canvas c, RectF r, Site site, Site.Floor floor, Bitmap plan, Bitmap heat, List<Site.Marker> suggested, float u, boolean screen) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (plan != null) c.drawBitmap(plan, new Rect(0, 0, plan.getWidth(), plan.getHeight()), r, p);
        else {
            p.setColor(screen ? 0xFF111827 : 0xFFF3F4F6); c.drawRect(r, p);
            p.setColor(screen ? 0xFF1F2937 : 0xFFD1D5DB); p.setStrokeWidth(u);
            for (int i = 1; i < 10; i++) { float x = r.left + r.width() * i / 10f; c.drawLine(x, r.top, x, r.bottom, p); }
            for (int i = 1; i < 8; i++) { float y = r.top + r.height() * i / 8f; c.drawLine(r.left, y, r.right, y, p); }
        }
        if (heat != null) { p.setAlpha(150); c.drawBitmap(heat, new Rect(0, 0, heat.getWidth(), heat.getHeight()), r, p); p.setAlpha(255); }
        p.setTextAlign(Paint.Align.CENTER);
        // markers: coordinator (square), router (triangle), suggested (star)
        for (Site.Marker m : floor.markers) marker(c, p, r.left + m.x * r.width(), r.top + m.y * r.height(), m.kind, m.label, u);
        if (suggested != null) for (Site.Marker m : suggested) marker(c, p, r.left + m.x * r.width(), r.top + m.y * r.height(), "suggested", m.label, u);
        int i = 1;
        for (Site.Point pt : floor.points) {
            float x = r.left + pt.x * r.width(), y = r.top + pt.y * r.height();
            Analyzer.Cover cv = Analyzer.overall(site, pt);
            p.setStyle(Paint.Style.FILL); p.setColor(Analyzer.color(cv.grade));
            c.drawCircle(x, y, 11 * u, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * u); p.setColor(Color.WHITE);
            c.drawCircle(x, y, 11 * u, p);
            p.setStyle(Paint.Style.FILL); p.setColor(cv.grade == Analyzer.Grade.FAIR ? 0xFF111111 : Color.WHITE);
            p.setTextSize(11 * u); p.setFakeBoldText(true);
            c.drawText(pt.label.isEmpty() ? String.valueOf(i) : pt.label, x, y + 4 * u, p);
            p.setFakeBoldText(false);
            i++;
        }
    }

    static void marker(Canvas c, Paint p, float x, float y, String kind, String label, float u) {
        p.setStyle(Paint.Style.FILL);
        Path path = new Path();
        float s = 10 * u;
        if (kind.equals("coordinator")) {
            p.setColor(0xFF2563EB); c.drawRect(x - s, y - s, x + s, y + s, p);
            p.setColor(Color.WHITE); p.setTextSize(11 * u); p.setFakeBoldText(true); c.drawText("C", x, y + 4 * u, p); p.setFakeBoldText(false);
        } else if (kind.equals("router")) {
            p.setColor(0xFF7C3AED);
            path.moveTo(x, y - s * 1.2f); path.lineTo(x + s * 1.1f, y + s * 0.8f); path.lineTo(x - s * 1.1f, y + s * 0.8f); path.close();
            c.drawPath(path, p);
            p.setColor(Color.WHITE); p.setTextSize(9 * u); c.drawText("R", x, y + 4 * u, p);
        } else {
            for (int k = 0; k < 10; k++) {
                double a = Math.PI / 2 + k * Math.PI / 5; float rr = k % 2 == 0 ? s * 1.4f : s * 0.6f;
                float px = x + (float) (Math.cos(a) * rr), py = y - (float) (Math.sin(a) * rr);
                if (k == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            path.close();
            p.setColor(0xFFFFD60A); c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * u); p.setColor(0xFF111111); c.drawPath(path, p); p.setStyle(Paint.Style.FILL);
        }
        if (label != null && !label.isEmpty()) {
            p.setTextSize(10 * u); p.setColor(0xFF111111);
            float w = p.measureText(label) + 6 * u;
            p.setColor(0xDDFFFFFF); c.drawRect(x - w / 2, y + s * 1.3f, x + w / 2, y + s * 1.3f + 13 * u, p);
            p.setColor(0xFF111111); c.drawText(label, x, y + s * 1.3f + 10 * u, p);
        }
    }

    /** inverse-distance heat map of point scores (red → amber → green); transparent far from any point */
    public static Bitmap heatmap(Site site, Site.Floor f, int cols, Bitmap plan) {
        if (f.points.isEmpty()) return null;
        float aspect = plan != null ? plan.getHeight() / (float) plan.getWidth() : 0.75f;
        int rows = Math.max(8, Math.round(cols * aspect));
        int n = f.points.size();
        double[] px = new double[n], py = new double[n], sc = new double[n];
        for (int i = 0; i < n; i++) {
            Site.Point p = f.points.get(i);
            px[i] = p.x; py[i] = p.y * aspect; sc[i] = Analyzer.overall(site, p).score;
        }
        double reach = f.metersAcross > 0 ? Math.min(0.5, 7.0 / f.metersAcross) : 0.22;
        int[] pix = new int[cols * rows];
        for (int yy = 0; yy < rows; yy++) for (int xx = 0; xx < cols; xx++) {
            double x = (xx + 0.5) / cols, y = (yy + 0.5) / rows * aspect;
            double wsum = 0, vsum = 0, dmin = 9;
            for (int i = 0; i < n; i++) {
                double d = Math.hypot(x - px[i], y - py[i]);
                dmin = Math.min(dmin, d);
                double w = 1 / Math.pow(d + 0.01, 2);
                wsum += w; vsum += w * sc[i];
            }
            if (dmin > reach) { pix[yy * cols + xx] = 0; continue; }
            double v = vsum / wsum;
            int alpha = (int) (200 * (1 - Math.pow(dmin / reach, 2)));
            pix[yy * cols + xx] = (alpha << 24) | ramp(v);
        }
        Bitmap b = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888);
        b.setPixels(pix, 0, cols, 0, 0, cols, rows);
        return b;
    }

    static int ramp(double v) {
        int r, g, bl;
        if (v < 0.5) { double t = v / 0.5; r = 229; g = (int) (83 + t * (181 - 83)); bl = (int) (61 - t * 61); }
        else { double t = (v - 0.5) / 0.5; r = (int) (242 - t * (242 - 76)); g = (int) (181 + t * (194 - 181)); bl = (int) (t * 90); }
        return (r << 16) | (g << 8) | Math.max(0, bl);
    }
}
