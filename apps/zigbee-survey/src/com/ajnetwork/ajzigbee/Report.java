package com.ajnetwork.ajzigbee;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import com.ajnetwork.ajzigbee.radio.Model;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A4 PDF survey report (android.graphics.pdf – no library). */
public final class Report {
    private static final int W = 595, H = 842, M = 40;
    private final Context ctx;
    private final PdfDocument doc = new PdfDocument();
    private PdfDocument.Page page;
    private Canvas c;
    private float y;
    private int pageNo;
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String footer;

    private Report(Context ctx, String footer) { this.ctx = ctx; this.footer = footer; }

    public static void write(Context ctx, Site s, List<Net.Network> nets, PlansLoader plans, OutputStream out) throws Exception {
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        Report r = new Report(ctx, s.name + " · Zigbee survey · " + date);
        r.build(s, nets, plans, date);
        r.doc.writeTo(out);
        r.doc.close();
    }

    public interface PlansLoader { Bitmap load(Site.Floor f); }

    // ---------------- page helpers ----------------
    private void newPage() {
        if (page != null) finishPage();
        pageNo++;
        page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
        c = page.getCanvas();
        y = M;
    }

    private void finishPage() {
        tp.setTypeface(Typeface.DEFAULT); tp.setTextSize(8); tp.setColor(0xFF6B7280); tp.setTextAlign(Paint.Align.LEFT);
        c.drawText(footer, M, H - 20, tp);
        tp.setTextAlign(Paint.Align.RIGHT); c.drawText("AJ Network Solutions · page " + pageNo, W - M, H - 20, tp);
        tp.setTextAlign(Paint.Align.LEFT);
        doc.finishPage(page);
    }

    private void need(float h) { if (y + h > H - 40) newPage(); }

    private void h1(String s) { need(40); font(20, true, 0xFF0B1220); c.drawText(s, M, y + 18, tp); y += 30; }

    private void h2(String s) {
        need(34); y += 6;
        tp.setColor(0xFF6BBC46); c.drawRect(M, y, M + 4, y + 16, tp);
        font(13, true, 0xFF0B1220); c.drawText(s, M + 10, y + 13, tp); y += 24;
    }

    private void font(float size, boolean bold, int color) {
        tp.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        tp.setTextSize(size); tp.setColor(color); tp.setTextAlign(Paint.Align.LEFT);
    }

    private void para(String s) { para(s, 10, 0xFF1F2937, M, W - 2 * M); }

    private void para(String s, float size, int color, float x, float width) {
        font(size, false, color);
        for (String line : wrap(s, width)) { need(size + 4); c.drawText(line, x, y + size, tp); y += size + 4; }
        y += 2;
    }

    private void bullet(String s) {
        font(10, false, 0xFF1F2937);
        List<String> lines = wrap(s, W - 2 * M - 14);
        for (int i = 0; i < lines.size(); i++) { need(14); if (i == 0) c.drawText("•", M + 2, y + 10, tp); c.drawText(lines.get(i), M + 14, y + 10, tp); y += 14; }
        y += 1;
    }

    private List<String> wrap(String s, float width) {
        List<String> out = new ArrayList<>();
        for (String para : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String w : para.split(" ")) {
                String t = line.length() == 0 ? w : line + " " + w;
                if (tp.measureText(t) > width && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(w); }
                else line = new StringBuilder(t);
            }
            out.add(line.toString());
        }
        return out;
    }

    /** simple table; widths are fractions of the text width */
    private void table(String[] head, float[] widths, List<String[]> rows, List<Integer> colors) {
        float tw = W - 2 * M;
        need(36);
        font(8.5f, true, 0xFF374151);
        tp.setColor(0xFFE5E7EB); c.drawRect(M, y, W - M, y + 15, tp); tp.setColor(0xFF111827);
        float x = M + 3; for (int i = 0; i < head.length; i++) { c.drawText(ellipsize(head[i], widths[i] * tw - 6), x, y + 11, tp); x += widths[i] * tw; }
        y += 17;
        font(8.5f, false, 0xFF1F2937);
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            // row height = tallest wrapped cell (max 3 lines)
            List<List<String>> cells = new ArrayList<>(); int lines = 1;
            for (int i = 0; i < row.length; i++) { List<String> w = wrap(row[i] == null ? "" : row[i], widths[i] * tw - 6); if (w.size() > 3) w = w.subList(0, 3); cells.add(w); lines = Math.max(lines, w.size()); }
            float rh = 11 * lines + 4;
            if (y + rh > H - 40) { newPage(); font(8.5f, false, 0xFF1F2937); }
            if (r % 2 == 1) { tp.setColor(0xFFF9FAFB); c.drawRect(M, y - 1, W - M, y + rh - 1, tp); }
            x = M + 3;
            for (int i = 0; i < row.length; i++) {
                if (i == 0 && colors != null && r < colors.size() && colors.get(r) != 0) { tp.setColor(colors.get(r)); c.drawCircle(x + 4, y + 6, 4, tp); }
                tp.setColor(0xFF1F2937);
                float yy = y + 9;
                for (String l : cells.get(i)) { c.drawText(l, x + (i == 0 && colors != null ? 11 : 0), yy, tp); yy += 11; }
                x += widths[i] * tw;
            }
            y += rh;
        }
        y += 6;
    }

    private String ellipsize(String s, float w) {
        if (tp.measureText(s) <= w) return s;
        while (s.length() > 1 && tp.measureText(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ---------------- content ----------------
    private void build(Site s, List<Net.Network> nets, PlansLoader plans, String date) {
        newPage();
        try {
            Bitmap logo = BitmapFactory.decodeStream(ctx.getAssets().open("logo.png"));
            if (logo != null) c.drawBitmap(logo, null, new RectF(W - M - 56, M - 6, W - M, M - 6 + 56f * logo.getHeight() / logo.getWidth()), tp);
        } catch (Exception ignored) { }
        font(9, true, 0xFF418331); c.drawText("AJ NETWORK SOLUTIONS", M, y + 8, tp); y += 18;
        h1("Zigbee site survey");
        para(s.name + (s.customer.isEmpty() ? "" : " · " + s.customer) + " · " + date, 11, 0xFF374151, M, W - 2 * M);
        if (!s.notes.isEmpty()) para(s.notes);

        // ---- summary ----
        int good = 0, fair = 0, poor = 0, total = 0;
        for (Site.Floor f : s.floors) for (Site.Point p : f.points) {
            Analyzer.Grade g = Analyzer.overall(s, p).grade; total++;
            if (g == Analyzer.Grade.GOOD) good++; else if (g == Analyzer.Grade.FAIR) fair++; else if (g != Analyzer.Grade.UNKNOWN) poor++;
        }
        h2("Summary");
        if (total > 0) {
            para(String.format(Locale.US, "%d spots measured on %d floor(s): %d good, %d fair, %d poor / no signal (%.0f%% covered well).",
                    total, s.floors.size(), good, fair, poor, 100.0 * good / total));
            // bar
            need(18); float bx = M, bw = W - 2 * M;
            tp.setColor(Analyzer.color(Analyzer.Grade.GOOD)); c.drawRect(bx, y, bx + bw * good / total, y + 10, tp); bx += bw * good / total;
            tp.setColor(Analyzer.color(Analyzer.Grade.FAIR)); c.drawRect(bx, y, bx + bw * fair / total, y + 10, tp); bx += bw * fair / total;
            tp.setColor(Analyzer.color(Analyzer.Grade.POOR)); c.drawRect(bx, y, M + bw, y + 10, tp);
            y += 18;
        } else para("No spots measured yet.");
        for (String l : Analyzer.coordinatorAdvice(s, nets)) bullet(l);
        Analyzer.ChannelAdvice ca = Analyzer.channels(s, nets);
        for (String l : ca.lines) bullet(l);

        // ---- coordinators ----
        h2("Coordinators");
        if (!s.coordinatorText.isEmpty()) {
            font(10, true, 0xFF111827); need(14); c.drawText("Survey stick (plugged into the phone)", M, y + 10, tp); y += 15;
            List<String[]> rows = new ArrayList<>();
            for (String line : s.coordinatorText.split("\n")) { int i = line.indexOf(": "); if (i > 0) rows.add(new String[]{line.substring(0, i), line.substring(i + 2)}); }
            table(new String[]{"Item", "Value"}, new float[]{0.32f, 0.68f}, rows, null);
        }
        for (Net.Network n : nets) {
            font(10, true, 0xFF111827); need(14); c.drawText(n.label(), M, y + 10, tp); y += 15;
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Channel", n.channel > 0 ? String.valueOf(n.channel) : "?"});
            rows.add(new String[]{"PAN / extended PAN", (n.pan >= 0 ? String.format(Locale.US, "0x%04X", n.pan) : "?") + " / " + Model.ieeeColons(n.extPan)});
            if (!n.coordIeee.isEmpty()) rows.add(new String[]{"Coordinator IEEE", n.coordIeee});
            if (!n.radio.isEmpty() || !n.coordInfo.isEmpty()) rows.add(new String[]{"Radio", (n.radio + " " + n.coordInfo).trim()});
            Analyzer.Health h = Analyzer.health(n);
            rows.add(new String[]{"Health", String.join(" ", h.lines)});
            if (!n.error.isEmpty()) rows.add(new String[]{"Note", n.error});
            table(new String[]{"Item", "Value"}, new float[]{0.32f, 0.68f}, rows, null);
        }
        if (s.coordinatorText.isEmpty() && nets.isEmpty()) para("No coordinator connected and no Home Assistant network loaded.");

        // ---- floors ----
        for (Site.Floor f : s.floors) {
            newPage();
            h1(f.name);
            Bitmap plan = plans.load(f);
            float maxH = 430, wAvail = W - 2 * M;
            RectF fr = PlanView.fit((int) wAvail, (int) maxH, plan);
            RectF r = new RectF(M + fr.left, y + fr.top, M + fr.right, y + fr.bottom);
            List<Site.Marker> sug = Analyzer.suggestRouters(s, f);
            PlanView.draw(c, r, s, f, plan, PlanView.heatmap(s, f, 90, plan), sug, 0.8f, false);
            y += fr.bottom + 8;
            legend();
            int planned = Analyzer.plannedRouters(f);
            if (planned > 0) para(String.format(Locale.US, "Planning estimate: %.0f m², %s walls → about %d router(s) for even coverage.", f.areaM2, f.walls, planned));
            if (!sug.isEmpty()) para("★ = suggested router spot (" + sug.size() + "): a mains-powered Zigbee router (smart plug, in-wall switch with neutral, repeater) between the weak area and good coverage.");
            List<String[]> rows = new ArrayList<>(); List<Integer> col = new ArrayList<>();
            int i = 1;
            for (Site.Point p : f.points) {
                Analyzer.Cover ov = Analyzer.overall(s, p);
                StringBuilder cov = new StringBuilder();
                if (s.targets.isEmpty()) cov.append(Analyzer.cover(p, null).text());
                else for (Site.Target t : s.targets) { if (cov.length() > 0) cov.append("\n"); cov.append(t.name.isEmpty() ? "net" : t.name).append(": ").append(Analyzer.cover(p, t).text()); }
                String test = p.test == null ? "" : p.test.name + ": LQI " + p.test.lqi + (p.test.rssi != Integer.MIN_VALUE ? ", " + p.test.rssi + " dBm" : "");
                String wifi = "";
                Site.Wifi best = null; for (Site.Wifi w : p.wifi) if (w.freq < 2500 && (best == null || w.level > best.level)) best = w;
                if (best != null) wifi = (best.ssid.isEmpty() ? "hidden" : best.ssid) + " ch" + Analyzer.wifiChannel(best.freq) + " " + best.level + " dBm (" + p.wifi.size() + " APs)";
                rows.add(new String[]{(p.label.isEmpty() ? String.valueOf(i) : p.label) + (p.note.isEmpty() ? "" : " – " + p.note), cov.toString(), test, wifi});
                col.add(Analyzer.color(ov.grade));
                i++;
            }
            if (!rows.isEmpty()) table(new String[]{"Spot", "Zigbee coverage", "Test device", "Strongest 2.4 GHz Wi-Fi"}, new float[]{0.16f, 0.42f, 0.18f, 0.24f}, rows, col);
        }

        // ---- channels ----
        newPage();
        h1("Radio channels");
        Map<Integer, Double> wifi = Analyzer.wifiLoad(s), noise = Analyzer.noise(s);
        float ch0 = M + 30, chartH = 150, base = y + chartH;
        float bw = (W - 2 * M - 40) / 16f;
        font(8, false, 0xFF6B7280);
        for (int db = -100; db <= -30; db += 10) { float yy = base - (db + 100) / 70f * chartH; tp.setColor(0xFFE5E7EB); c.drawLine(ch0, yy, W - M, yy, tp); tp.setColor(0xFF6B7280); c.drawText(db + "", M, yy + 3, tp); }
        for (int ch = 11; ch <= 26; ch++) {
            float x = ch0 + (ch - 11) * bw;
            double w = wifi.get(ch), n = noise.containsKey(ch) ? noise.get(ch) : -100;
            tp.setColor(0xFF60A5FA); c.drawRect(x + 2, base - (float) (Math.max(0, w + 100) / 70 * chartH), x + bw / 2, base, tp);
            tp.setColor(0xFFF97316); c.drawRect(x + bw / 2, base - (float) (Math.max(0, n + 100) / 70 * chartH), x + bw - 2, base, tp);
            tp.setColor(ch == ca.best ? 0xFF418331 : 0xFF374151); tp.setFakeBoldText(ch == ca.best);
            c.drawText(String.valueOf(ch), x + bw / 2 - 5, base + 11, tp); tp.setFakeBoldText(false);
        }
        y = base + 20;
        font(8.5f, false, 0xFF374151);
        tp.setColor(0xFF60A5FA); c.drawRect(M, y, M + 10, y + 8, tp); tp.setColor(0xFF374151); c.drawText("Wi-Fi power on the Zigbee channel (from the phone's Wi-Fi scan)", M + 14, y + 8, tp); y += 12;
        tp.setColor(0xFFF97316); c.drawRect(M, y, M + 10, y + 8, tp); tp.setColor(0xFF374151); c.drawText("Measured channel noise (EZSP energy scan, average)", M + 14, y + 8, tp); y += 18;
        for (String l : ca.lines) bullet(l);
        for (Net.Network n : nets) if (!n.busyPercent.isEmpty()) {
            StringBuilder b = new StringBuilder(n.label() + " – channel use seen by the coordinator (ZHA diagnostics, % busy): ");
            for (Map.Entry<Integer, Double> e : n.busyPercent.entrySet()) b.append(e.getKey()).append(": ").append(String.format(Locale.US, "%.0f", e.getValue())).append("  ");
            para(b.toString());
        }
        // Wi-Fi APs
        Map<String, Site.Wifi> aps = new java.util.LinkedHashMap<>();
        for (Site.Floor f : s.floors) for (Site.Point p : f.points) for (Site.Wifi w : p.wifi) { Site.Wifi o = aps.get(w.bssid); if (o == null || w.level > o.level) aps.put(w.bssid, w); }
        List<Site.Wifi> apl = new ArrayList<>(aps.values()); apl.sort((a, b) -> b.level - a.level);
        if (!apl.isEmpty()) {
            h2("Wi-Fi access points heard (strongest first)");
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < Math.min(30, apl.size()); i++) { Site.Wifi w = apl.get(i); rows.add(new String[]{w.ssid.isEmpty() ? "(hidden)" : w.ssid, w.bssid, (w.freq < 2500 ? "2.4 GHz ch " : "5 GHz ch ") + (w.freq < 2500 ? Analyzer.wifiChannel(w.freq) : (w.freq - 5000) / 5), w.level + " dBm"}); }
            table(new String[]{"SSID", "BSSID", "Band / channel", "Strongest"}, new float[]{0.34f, 0.28f, 0.22f, 0.16f}, rows, null);
        }

        // ---- devices ----
        for (Net.Network n : nets) {
            newPage();
            h1("Devices – " + n.label());
            Analyzer.Health h = Analyzer.health(n);
            for (String l : h.lines) bullet(l);
            List<Net.Device> ds = new ArrayList<>(n.devices);
            ds.sort((a, b) -> { if (a.available != b.available) return a.available ? 1 : -1; return Integer.compare(a.lqi < 0 ? 999 : a.lqi, b.lqi < 0 ? 999 : b.lqi); });
            List<String[]> rows = new ArrayList<>(); List<Integer> col = new ArrayList<>();
            for (Net.Device d : ds) {
                if (rows.size() >= 150) break;
                String sig = (d.lqi >= 0 ? "LQI " + d.lqi : "") + (d.hasRssi() ? (d.lqi >= 0 ? ", " : "") + d.rssi + " dBm" : "");
                String state = !d.available ? "offline" : h.singleList.contains(d) ? "1 good link" : "";
                rows.add(new String[]{d.name, d.type + (d.power.isEmpty() ? "" : " · " + d.power), (d.maker + " " + d.model).trim(), sig, state});
                col.add(!d.available ? Analyzer.color(Analyzer.Grade.NONE) : d.lqi < 0 ? 0xFF9CA3AF : Analyzer.color(d.hasRssi() ? Analyzer.gradeRssi(d.rssi) : Analyzer.gradeLqi(d.lqi)));
            }
            table(new String[]{"Device", "Type", "Model", "Signal", "Note"}, new float[]{0.30f, 0.18f, 0.24f, 0.16f, 0.12f}, rows, col);
        }

        // ---- method ----
        h2("How this was measured");
        para("At each spot the survey stick (a Zigbee coordinator plugged into the phone over USB-OTG) sent beacon requests on the network's channel; "
                + "the coordinator and every router in range answered, and the signal of each answer was recorded (Silicon Labs sticks: RSSI in dBm; TI sticks: LQI). "
                + "Silicon Labs sticks also measured the noise on all 16 channels. The phone's Wi-Fi scan recorded nearby 2.4 GHz access points. "
                + "When a test device was chosen, its signal as reported by Home Assistant was recorded too. The stick was only read – nothing on it was changed.");
        para("Grades: RSSI ≥ −70 dBm good, −70 to −85 fair, below −85 poor; LQI ≥ 150 good, 80–149 fair, below 80 poor. A real device on a wall or in a "
                + "metal box can be 5–10 dB worse than the phone held in the hand, so treat 'fair' spots as candidates for a router when battery sensors go there.");
        finishPage();
        page = null;
    }

    private void legend() {
        need(16);
        font(8.5f, false, 0xFF374151);
        float x = M;
        Analyzer.Grade[] gs = {Analyzer.Grade.GOOD, Analyzer.Grade.FAIR, Analyzer.Grade.POOR, Analyzer.Grade.NONE};
        for (Analyzer.Grade g : gs) { tp.setColor(Analyzer.color(g)); c.drawCircle(x + 4, y + 5, 4, tp); tp.setColor(0xFF374151); c.drawText(Analyzer.word(g), x + 11, y + 8, tp); x += tp.measureText(Analyzer.word(g)) + 26; }
        tp.setColor(0xFF2563EB); c.drawRect(x, y + 1, x + 8, y + 9, tp); tp.setColor(0xFF374151); c.drawText("coordinator", x + 11, y + 8, tp); x += 70;
        tp.setColor(0xFF7C3AED); c.drawCircle(x + 4, y + 5, 4, tp); tp.setColor(0xFF374151); c.drawText("router", x + 11, y + 8, tp); x += 46;
        tp.setColor(0xFFFFD60A); c.drawCircle(x + 4, y + 5, 4, tp); tp.setColor(0xFF374151); c.drawText("★ suggested router", x + 11, y + 8, tp);
        y += 16;
    }
}
