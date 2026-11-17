package com.ajnetwork.ajzigbee;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ajnetwork.ajzigbee.radio.Model;
import com.ajnetwork.ajzigbee.radio.Radio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AJ Zigbee Survey: plug a Zigbee coordinator into the phone (USB-OTG), the app detects it (Silicon Labs EZSP or
 * TI Z-Stack), shows what it is, and measures coverage spot by spot on a floor plan. Home Assistant (ZHA and/or
 * Zigbee2MQTT) adds the live network's health and a "test device" reading. Ends in a PDF report.
 */
public class MainActivity extends Activity {
    static final String ACTION_USB = "com.ajnetwork.ajzigbee.USB_PERMISSION";
    static final int REQ_PLAN = 11, REQ_PDF = 12, REQ_JSON = 13, REQ_PERM = 14;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private FrameLayout content;
    private TextView status;
    private final TextView[] tabs = new TextView[4];
    private int tab;

    // coordinator
    private UsbSerial usb;
    private Radio radio;
    private volatile boolean busy;
    private Model.ScanResult lastQuick;
    private final List<String> log = new ArrayList<>();

    // survey
    private List<Site> sites = new ArrayList<>();
    private Site site;
    private int floorIdx;
    private PlanView plan;
    private final Map<String, Bitmap> planCache = new LinkedHashMap<>();
    private boolean optWifi = true, optNoise = true;
    private Uri lastReport;

    // home assistant
    private Net.Ha ha;
    private final List<Net.Z2m> z2ms = new ArrayList<>();
    private final List<Net.Network> nets = new ArrayList<>();
    private String testSource = "", testIeee = "", testName = "";
    private final Map<String, Boolean> openLists = new LinkedHashMap<>();

    // =========================================================================================== lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("ajzigbee", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.BG); getWindow().setNavigationBarColor(Ui.BG);
        sites = Site.loadAll(getFilesDir());
        String last = prefs.getString("site", "");
        for (Site s : sites) if (s.id.equals(last)) site = s;
        if (site == null && !sites.isEmpty()) site = sites.get(0);
        if (site == null) { site = newSite("Site 1"); }
        optWifi = prefs.getBoolean("wifi", true); optNoise = prefs.getBoolean("noise", true);
        testSource = prefs.getString("test_src", ""); testIeee = prefs.getString("test_ieee", ""); testName = prefs.getString("test_name", "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout head = Ui.row(this);
        head.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 6));
        TextView t = Ui.text(this, "AJ Zigbee Survey", 19, Ui.TEXT, true);
        head.addView(t, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        status = Ui.text(this, "", 12, Ui.SUB, false);
        status.setGravity(Gravity.END);
        head.addView(status, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        root.addView(head);
        content = new FrameLayout(this);
        root.addView(content, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.CARD);
        String[] names = {"Coordinator", "Survey", "Network", "Report"};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            TextView tv = Ui.text(this, names[i], 14, Ui.SUB, true);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
            tv.setOnClickListener(v -> showTab(k));
            tabs[i] = tv;
            bar.addView(tv, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(bar);
        setContentView(root);

        IntentFilter f = new IntentFilter(ACTION_USB);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbRx, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(usbRx, f);
        askPermissions();
        showTab(0);
        handleIntent(getIntent());
        updateStatus();
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIntent(i); }

    private void handleIntent(Intent i) {
        if (i != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d != null && radio == null) { showTab(0); connect(d); }
        }
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(usbRx); } catch (Exception ignored) { }
        closeUsb();
        if (ha != null) ha.close();
        for (Net.Z2m z : z2ms) z.close();
        super.onDestroy();
    }

    private void askPermissions() {
        List<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { p.add(Manifest.permission.ACCESS_FINE_LOCATION); p.add(Manifest.permission.ACCESS_COARSE_LOCATION); }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED) p.add("android.permission.NEARBY_WIFI_DEVICES");
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), REQ_PERM);
    }

    void log(String s) {
        synchronized (log) { log.add(s); while (log.size() > 200) log.remove(0); }
        android.util.Log.i("AJZigbee", s);
    }

    private void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

    private void updateStatus() {
        String r = radio != null ? (radio.isEzsp() ? "EZSP stick" : "TI stick") + " ✓" : "no stick";
        String n = nets.isEmpty() ? "" : " · " + nets.size() + " HA network" + (nets.size() > 1 ? "s" : "");
        status.setText(r + n + "\n" + site.name);
        status.setTextColor(radio != null ? Ui.GREEN : Ui.SUB);
    }

    private void showTab(int k) {
        tab = k;
        for (int i = 0; i < 4; i++) { tabs[i].setTextColor(i == k ? Ui.GREEN : Ui.SUB); }
        content.removeAllViews();
        View v;
        switch (k) {
            case 0: v = coordinatorTab(); break;
            case 1: v = surveyTab(); break;
            case 2: v = networkTab(); break;
            default: v = reportTab(); break;
        }
        content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateStatus();
    }

    private void refresh() { ui.post(() -> showTab(tab)); }

    private ScrollView scroll(LinearLayout inner) {
        ScrollView s = new ScrollView(this);
        inner.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 24));
        s.addView(inner);
        return s;
    }

    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private void add(LinearLayout parent, View v) { parent.addView(v, Ui.margins(this, 0, 6, 0, 6)); }

    // =========================================================================================== USB
    private final BroadcastReceiver usbRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_USB.equals(i.getAction())) {
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) connect(d);
                else toast("USB permission refused");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                if (usb != null) { log("stick unplugged"); closeUsb(); refresh(); }
            }
        }
    };

    private List<UsbDevice> serialDevices() {
        UsbManager m = (UsbManager) getSystemService(USB_SERVICE);
        List<UsbDevice> r = new ArrayList<>();
        if (m == null) return r;
        for (UsbDevice d : m.getDeviceList().values()) if (UsbSerial.looksLikeSerial(d)) r.add(d);
        return r;
    }

    private void pickAndConnect() {
        List<UsbDevice> ds = serialDevices();
        if (ds.isEmpty()) {
            dialog("No USB stick found", "Plug the Zigbee coordinator into the phone with a USB-OTG adapter (USB-C to USB-A), then tap Connect again.\n\n"
                    + "Tip: if Android asks which app to open for the device, pick AJ Zigbee Survey and tick 'always'.");
            return;
        }
        if (ds.size() == 1) { connect(ds.get(0)); return; }
        String[] names = new String[ds.size()];
        for (int i = 0; i < ds.size(); i++) names[i] = UsbSerial.label(ds.get(i)) + " (" + UsbSerial.ids(ds.get(i)) + ")";
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Which stick?").setItems(names, (d, w) -> connect(ds.get(w))).show();
    }

    private void connect(UsbDevice d) {
        UsbManager m = (UsbManager) getSystemService(USB_SERVICE);
        if (!m.hasPermission(d)) {
            Intent i = new Intent(ACTION_USB).setPackage(getPackageName());
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            m.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, fl));
            return;
        }
        if (busy) return;
        busy = true;
        closeUsb();
        progress("Detecting the stick…", "Trying Silicon Labs (EZSP) and TI (Z-Stack) at the usual speeds. This takes up to 15 s.");
        new Thread(() -> {
            String err = "";
            try {
                UsbSerial u = new UsbSerial(m, d);
                usb = u;
                Model.CoordInfo ci = new Model.CoordInfo();
                ci.usbName = UsbSerial.label(d); ci.usbIds = UsbSerial.ids(d); ci.usbChip = u.chipName();
                log("USB: " + u.describe());
                Radio r = Radio.detect(u, ci.usbName, ci, this::log);
                if (r == null) { err = "The stick did not answer as a Zigbee coordinator (EZSP or Z-Stack).\n\nIs it a coordinator firmware (not a router/Thread/Matter firmware)? Close Home Assistant's use of it first – it should be unplugged from the server."; u.close(); usb = null; }
                else {
                    radio = r;
                    StringBuilder sb = new StringBuilder();
                    for (String[] row : ci.rows()) sb.append(row[0]).append(": ").append(row[1]).append("\n");
                    site.coordinatorText = sb.toString().trim();
                    saveSite();
                    log("detected: " + ci.summary());
                }
            } catch (Exception e) { err = e.getMessage(); log("connect: " + e); closeUsb(); }
            busy = false;
            final String fe = err;
            ui.post(() -> { hideProgress(); if (!fe.isEmpty()) dialog("Could not connect", fe); showTab(tab == 1 ? 1 : 0); });
        }, "connect").start();
    }

    private void closeUsb() {
        radio = null;
        if (usb != null) { try { usb.close(); } catch (Exception ignored) { } usb = null; }
    }

    // =========================================================================================== tab 0: coordinator
    private View coordinatorTab() {
        LinearLayout l = col();
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "Survey stick", 16, Ui.TEXT, true));
        if (radio == null) {
            c.addView(Ui.text(this, "Plug one of your Zigbee coordinators into the phone (USB-OTG). Silicon Labs (Sonoff ZBDongle-E, SkyConnect/ZBT-1, SLZB-06M…) and TI (ZBDongle-P, SLZB-06, CC2652…) are detected automatically.", 13, Ui.SUB, false));
            c.addView(Ui.gap(this, 10));
            TextView b = Ui.button(this, "Connect stick", true);
            b.setOnClickListener(v -> pickAndConnect());
            c.addView(b);
        } else {
            for (String[] row : radio.info.rows()) c.addView(Ui.kv(this, row[0], row[1]));
            c.addView(Ui.gap(this, 10));
            LinearLayout r = Ui.row(this);
            TextView q = Ui.button(this, "Quick scan here", true);
            q.setOnClickListener(v -> quickScan());
            TextView d = Ui.button(this, "Disconnect", false);
            d.setOnClickListener(v -> { closeUsb(); showTab(0); });
            r.addView(q, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); r.addView(Ui.gap(this, 1), Ui.lp(Ui.dp(this, 8), 1, 0));
            r.addView(d, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            c.addView(r);
        }
        add(l, c);

        LinearLayout w = Ui.card(this);
        w.addView(Ui.text(this, "Good to know", 14, Ui.AMBER, true));
        w.addView(Ui.text(this, "• The app only reads the stick (version, scans). It never forms, joins or changes a network, so Home Assistant's network stored on it stays as it is.\n"
                + "• While the stick is in the phone, the Home Assistant network that uses it is offline. Plug it back into the server when you are done (ZHA / Zigbee2MQTT reconnect by themselves).\n"
                + "• Survey with the phone held where devices will be (wall height); a device in a metal box is 5–10 dB worse.", 13, Ui.SUB, false));
        add(l, w);

        if (lastQuick != null) add(l, quickCard(lastQuick));

        LinearLayout lg = Ui.card(this);
        lg.addView(Ui.text(this, "Log", 14, Ui.TEXT, true));
        StringBuilder sb = new StringBuilder();
        synchronized (log) { for (int i = Math.max(0, log.size() - 25); i < log.size(); i++) sb.append(log.get(i)).append('\n'); }
        TextView lt = Ui.text(this, sb.length() == 0 ? "–" : sb.toString(), 11, Ui.MUTED, false);
        lt.setTypeface(android.graphics.Typeface.MONOSPACE);
        lt.setTextIsSelectable(true);
        lg.addView(lt);
        add(l, lg);
        return scroll(l);
    }

    private void quickScan() {
        if (radio == null || busy) return;
        busy = true;
        progress("Scanning all 16 channels…", "Listening for Zigbee networks and measuring channel noise (about 5–10 s).");
        new Thread(() -> {
            String err = "";
            try { lastQuick = radio.measure(Radio.ALL_CHANNELS, 2, radio.info.canEnergyScan); }
            catch (Exception e) { err = e.getMessage(); log("scan: " + e); }
            busy = false;
            final String fe = err;
            ui.post(() -> { hideProgress(); if (!fe.isEmpty()) dialog("Scan failed", fe); showTab(0); });
        }, "scan").start();
    }

    /** networks heard in a quick scan, grouped by extended PAN */
    private View quickCard(Model.ScanResult r) {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "Networks heard here", 16, Ui.TEXT, true));
        Map<String, List<Model.Beacon>> by = new LinkedHashMap<>();
        for (Model.Beacon b : r.beacons) { String k = b.extPan + "/" + b.channel; List<Model.Beacon> l = by.get(k); if (l == null) by.put(k, l = new ArrayList<>()); l.add(b); }
        if (by.isEmpty()) c.addView(Ui.text(this, "No Zigbee network answered. (Networks only answer on their own channel; a sleepy-only network or a coordinator that is unplugged sends nothing.)", 13, Ui.SUB, false));
        for (List<Model.Beacon> l : by.values()) {
            Model.Beacon f = l.get(0);
            int best = Integer.MIN_VALUE, lqi = 0;
            for (Model.Beacon b : l) { if (b.hasRssi()) best = Math.max(best, b.rssi); lqi = Math.max(lqi, b.lqi); }
            String name = netName(f);
            LinearLayout row = Ui.row(this);
            TextView t = Ui.text(this, (name.isEmpty() ? "Unknown network" : name) + "\nch " + f.channel + String.format(Locale.US, " · PAN %04X", f.pan)
                    + " · " + (best != Integer.MIN_VALUE ? best + " dBm" : "LQI " + lqi) + String.format(Locale.US, " · ≈%.0f nodes", l.size() / 2f)
                    + (f.permitJoin ? " · open for joining" : ""), 13, Ui.TEXT, false);
            row.addView(t, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            boolean isT = false; for (Site.Target x : site.targets) if (x.matches(f)) isT = true;
            TextView b = Ui.button(this, isT ? "Target ✓" : "Survey this", !isT);
            b.setOnClickListener(v -> { addTarget(name.isEmpty() ? "Network " + (site.targets.size() + 1) : name, f.extPan, f.pan, f.channel, "scan"); showTab(0); });
            row.addView(b);
            row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            c.addView(row);
        }
        if (!r.energy.isEmpty()) {
            c.addView(Ui.gap(this, 8));
            c.addView(Ui.text(this, "Channel noise (lower = quieter)", 14, Ui.TEXT, true));
            for (Map.Entry<Integer, Integer> e : r.energy.entrySet()) {
                int v = e.getValue();
                int len = Math.max(0, Math.min(40, (v + 100) / 2));
                StringBuilder bar = new StringBuilder(); for (int i = 0; i < len; i++) bar.append('█');
                TextView t = Ui.text(this, String.format(Locale.US, "%2d  %4d dBm  %s", e.getKey(), v, bar), 12, v > -75 ? Ui.RED : v > -85 ? Ui.AMBER : Ui.GREEN, false);
                t.setTypeface(android.graphics.Typeface.MONOSPACE);
                c.addView(t);
            }
        }
        return c;
    }

    /** name of a heard network if Home Assistant knows it */
    private String netName(Model.Beacon b) {
        for (Net.Network n : nets) if (Model.sameExtPan(n.extPan, b.extPan) || n.extPan.isEmpty() && n.pan == b.pan && n.channel == b.channel) return n.label();
        for (Site.Target t : site.targets) if (t.matches(b)) return t.name;
        return "";
    }

    private void addTarget(String name, String ext, int pan, int ch, String src) {
        for (Site.Target t : site.targets) if (Model.sameExtPan(t.extPan, ext) || ext.isEmpty() && t.pan == pan && pan >= 0) { t.channel = ch; t.pan = pan; saveSite(); return; }
        Site.Target t = new Site.Target();
        t.name = name; t.extPan = Model.normExtPan(ext); t.pan = pan; t.channel = ch; t.source = src;
        site.targets.add(t);
        saveSite();
        toast("Survey target: " + t.label());
    }

    // =========================================================================================== tab 1: survey
    private Site.Floor floor() {
        if (site.floors.isEmpty()) { Site.Floor f = new Site.Floor(); site.floors.add(f); saveSite(); }
        if (floorIdx >= site.floors.size()) floorIdx = 0;
        return site.floors.get(floorIdx);
    }

    private View surveyTab() {
        LinearLayout l = col();
        l.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 6));
        LinearLayout top = Ui.row(this);
        TextView sb = Ui.button(this, "📁 " + site.name, false);
        sb.setOnClickListener(v -> siteMenu());
        Site.Floor f = floor();
        TextView fb = Ui.button(this, "▤ " + f.name, false);
        fb.setOnClickListener(v -> floorMenu());
        top.addView(sb, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(Ui.gap(this, 1), Ui.lp(Ui.dp(this, 6), 1, 0));
        top.addView(fb, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        l.addView(top);

        LinearLayout opts = Ui.row(this);
        opts.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 2));
        opts.addView(chip("Wi-Fi scan", optWifi, v -> { optWifi = !optWifi; prefs.edit().putBoolean("wifi", optWifi).apply(); showTab(1); }));
        opts.addView(chip("Noise", optNoise, v -> { optNoise = !optNoise; prefs.edit().putBoolean("noise", optNoise).apply(); showTab(1); }));
        opts.addView(chip("Heat map", plan == null || plan.showHeat, v -> { if (plan != null) { plan.showHeat = !plan.showHeat; plan.invalidate(); showTab(1); } }));
        opts.addView(chip("Targets", !site.targets.isEmpty(), v -> targetsMenu()));
        l.addView(opts);

        String src = (radio != null ? "stick ✓" : "no stick") + (testIeee.isEmpty() ? "" : " · test device: " + testName) + (optWifi ? " · Wi-Fi" : "");
        String tg = site.targets.isEmpty() ? "all networks (pick a target in Coordinator → Quick scan or Network)" : targetsText();
        TextView info = Ui.text(this, "Measuring: " + tg + "\nSources: " + src, 12, Ui.SUB, false);
        info.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), 0, Ui.dp(this, 4));
        l.addView(info);

        boolean keepHeat = plan == null || plan.showHeat;
        plan = new PlanView(this);
        plan.showHeat = keepHeat;
        plan.setBackground(Ui.box(this, Ui.CARD, 12));
        plan.show(site, f, planBitmap(f));
        plan.setListener(new PlanView.Listener() {
            @Override public void tapEmpty(float x, float y) { measureAt(x, y, null); }
            @Override public void tapPoint(Site.Point p) { pointDialog(p); }
            @Override public void tapMarker(Site.Marker m) { markerDialog(m); }
            @Override public void longPress(float x, float y) { placeMarker(x, y); }
        });
        l.addView(plan, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView hint = Ui.text(this, f.points.isEmpty()
                ? "Stand still where a device will be and tap that spot on the plan to measure. Long-press to mark the coordinator or existing routers. Pinch to zoom."
                : summaryLine(f), 12, Ui.SUB, false);
        hint.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), 0, 0);
        l.addView(hint);
        return l;
    }

    private String summaryLine(Site.Floor f) {
        int g = 0, fa = 0, p = 0;
        for (Site.Point pt : f.points) { Analyzer.Grade gr = Analyzer.overall(site, pt).grade; if (gr == Analyzer.Grade.GOOD) g++; else if (gr == Analyzer.Grade.FAIR) fa++; else p++; }
        int sug = Analyzer.suggestRouters(site, f).size();
        return f.points.size() + " spots: " + g + " good · " + fa + " fair · " + p + " poor" + (sug > 0 ? " · ★ " + sug + " router spot(s) suggested" : "") + ". Tap a spot for details.";
    }

    private String targetsText() {
        StringBuilder s = new StringBuilder();
        for (Site.Target t : site.targets) { if (s.length() > 0) s.append(", "); s.append(t.label()); }
        return s.toString();
    }

    private TextView chip(String s, boolean on, View.OnClickListener l) {
        TextView t = Ui.text(this, (on ? "✓ " : "") + s, 12, on ? Ui.NAVY : Ui.SUB, true);
        t.setBackground(Ui.press(this, on ? Ui.GREEN : Ui.CARD2, 14));
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, Ui.dp(this, 6), 0);
        t.setLayoutParams(p);
        return t;
    }

    private void targetsMenu() {
        if (site.targets.isEmpty()) { dialog("No target network", "Pick the network to survey:\n• Coordinator tab → Quick scan → 'Survey this', or\n• Network tab → load Home Assistant → 'Survey this network'.\n\nWithout a target every network heard counts."); return; }
        String[] items = new String[site.targets.size()];
        for (int i = 0; i < items.length; i++) items[i] = "Remove: " + site.targets.get(i).label();
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Target networks")
                .setItems(items, (d, w) -> { site.targets.remove(w); saveSite(); showTab(1); }).setNegativeButton("Close", null).show();
    }

    // ---------------- measuring ----------------
    private void measureAt(float x, float y, Site.Point replace) {
        if (busy) return;
        if (radio == null && testIeee.isEmpty() && !optWifi) {
            dialog("Nothing to measure with", "Connect a stick (Coordinator tab), pick a test device (Network tab) or switch on Wi-Fi scan.");
            return;
        }
        busy = true;
        progress("Measuring…", "Hold the phone still at this spot.");
        new Thread(() -> {
            Site.Point p = replace != null ? replace : new Site.Point();
            p.x = x; p.y = y; p.time = System.currentTimeMillis();
            p.beacons.clear(); p.energy.clear(); p.wifi.clear(); p.test = null; p.measuredEmpty = false;
            StringBuilder errs = new StringBuilder();
            // Wi-Fi scan runs while the stick scans
            final List<Site.Wifi>[] wifi = new List[]{null};
            Thread wt = null;
            if (optWifi) { wt = new Thread(() -> wifi[0] = wifiScan()); wt.start(); }
            if (radio != null) {
                try {
                    int mask = 0;
                    for (Site.Target t : site.targets) if (t.channel >= 11) mask |= 1 << t.channel;
                    boolean all = mask == 0;
                    int rounds = all ? 2 : 3;
                    Model.ScanResult r = radio.measure(all ? Radio.ALL_CHANNELS : mask, rounds, optNoise && radio.info.canEnergyScan);
                    p.rounds = rounds;
                    p.beacons.addAll(r.beacons);
                    p.energy.putAll(r.energy);
                    p.measuredEmpty = r.beacons.isEmpty();
                } catch (Exception e) { errs.append("Stick: ").append(e.getMessage()).append('\n'); log("measure: " + e); }
            }
            if (!testIeee.isEmpty()) {
                try { p.test = readTestDevice(); } catch (Exception e) { errs.append("Test device: ").append(e.getMessage()).append('\n'); }
            }
            if (wt != null) { try { wt.join(12000); } catch (InterruptedException ignored) { } if (wifi[0] != null) p.wifi.addAll(wifi[0]); }
            Site.Floor f = floor();
            if (replace == null) { p.label = String.valueOf(nextLabel(f)); f.points.add(p); }
            saveSite();
            busy = false;
            final String fe = errs.toString().trim();
            ui.post(() -> { hideProgress(); showTab(1); pointDialog(p); if (!fe.isEmpty()) toast(fe); });
        }, "measure").start();
    }

    private int nextLabel(Site.Floor f) {
        int m = 0; for (Site.Point p : f.points) { try { m = Math.max(m, Integer.parseInt(p.label)); } catch (Exception ignored) { } }
        return m + 1;
    }

    private Site.Reading readTestDevice() throws Exception {
        Site.Reading r = new Site.Reading();
        r.source = testSource; r.ieee = testIeee; r.name = testName;
        if (testSource.equals("ZHA")) {
            if (ha == null) throw new Exception("Home Assistant not connected (Network tab)");
            ha.zhaPoke(testIeee);
            Thread.sleep(1500);
            Object o = ha.call(new JSONObject().put("type", "zha/device").put("ieee", testIeee), 15);
            if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                r.lqi = j.isNull("lqi") ? -1 : j.optInt("lqi", -1);
                r.rssi = j.isNull("rssi") ? Integer.MIN_VALUE : j.optInt("rssi", Integer.MIN_VALUE);
                long ls = Net.parseTime(j.optString("last_seen"));
                r.age = ls > 0 ? (System.currentTimeMillis() - ls) / 1000 : -1;
            }
        } else {
            Net.Z2m z = null;
            for (Net.Z2m x : z2ms) if (("Zigbee2MQTT " + x.url).equals(testSource) || testSource.equals("Zigbee2MQTT")) z = x;
            if (z == null && !z2ms.isEmpty()) z = z2ms.get(0);
            if (z == null) throw new Exception("Zigbee2MQTT not connected (Network tab)");
            long before = System.currentTimeMillis();
            z.poke(testName);
            Thread.sleep(3000);
            Net.Network n = z.snapshot();
            Net.Device d = n.find(testIeee);
            if (d != null) { r.lqi = d.lqi; r.age = d.lastSeen > 0 ? (System.currentTimeMillis() - d.lastSeen) / 1000 : -1; if (d.lastSeen < before - 60000) r.age = Math.max(r.age, 60); }
        }
        return r;
    }

    private List<Site.Wifi> wifiScan() {
        List<Site.Wifi> out = new ArrayList<>();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm == null) return out;
        final Object lock = new Object();
        final boolean[] got = {false};
        BroadcastReceiver r = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { synchronized (lock) { got[0] = true; lock.notifyAll(); } } };
        IntentFilter f = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(r, f);
            @SuppressWarnings("deprecation") boolean started = wm.startScan();
            if (started) synchronized (lock) { long dl = System.currentTimeMillis() + 8000; while (!got[0] && System.currentTimeMillis() < dl) lock.wait(500); }
            List<ScanResult> res = wm.getScanResults();
            for (ScanResult s : res) {
                Site.Wifi w = new Site.Wifi();
                @SuppressWarnings("deprecation") String ssid = s.SSID;
                w.ssid = ssid == null ? "" : ssid; w.bssid = s.BSSID; w.freq = s.frequency; w.level = s.level;
                out.add(w);
            }
            if (!started) log("Wi-Fi: Android throttled the scan – using the last results (" + out.size() + ")");
        } catch (SecurityException e) { log("Wi-Fi: location permission missing"); }
        catch (Exception e) { log("Wi-Fi: " + e.getMessage()); }
        finally { try { unregisterReceiver(r); } catch (Exception ignored) { } }
        return out;
    }

    private void pointDialog(Site.Point p) {
        StringBuilder s = new StringBuilder();
        if (site.targets.isEmpty()) s.append(Analyzer.cover(p, null).text()).append('\n');
        else for (Site.Target t : site.targets) s.append(t.name).append(": ").append(Analyzer.cover(p, t).text()).append('\n');
        if (!p.beacons.isEmpty()) {
            Map<String, int[]> by = new LinkedHashMap<>();
            for (Model.Beacon b : p.beacons) {
                String k = b.extPan + " ch" + b.channel;
                int[] v = by.get(k); if (v == null) by.put(k, v = new int[]{Integer.MIN_VALUE, 0, 0});
                if (b.hasRssi()) v[0] = Math.max(v[0], b.rssi); v[1] = Math.max(v[1], b.lqi); v[2]++;
            }
            s.append("\nHeard:\n");
            for (Map.Entry<String, int[]> e : by.entrySet()) {
                Model.Beacon any = null; for (Model.Beacon b : p.beacons) if ((b.extPan + " ch" + b.channel).equals(e.getKey())) { any = b; break; }
                String nm = any != null ? netName(any) : "";
                s.append("• ").append(nm.isEmpty() ? e.getKey() : nm + " (ch " + (any != null ? any.channel : 0) + ")")
                        .append(e.getValue()[0] != Integer.MIN_VALUE ? " best " + e.getValue()[0] + " dBm" : "").append(" LQI ").append(e.getValue()[1])
                        .append(" · ").append(e.getValue()[2]).append(" beacons\n");
            }
        } else if (p.measuredEmpty) s.append("\nNo Zigbee beacon heard here.\n");
        if (p.test != null) s.append("\nTest device ").append(p.test.name).append(": LQI ").append(p.test.lqi)
                .append(p.test.rssi != Integer.MIN_VALUE ? ", " + p.test.rssi + " dBm" : "").append(p.test.age >= 0 ? " (reported " + p.test.age + " s ago)" : "").append('\n');
        if (!p.energy.isEmpty()) {
            int q = 0, qv = 0; for (Map.Entry<Integer, Integer> e : p.energy.entrySet()) if (q == 0 || e.getValue() < qv) { q = e.getKey(); qv = e.getValue(); }
            s.append("\nQuietest channel here: ").append(q).append(" (").append(qv).append(" dBm)\n");
        }
        if (!p.wifi.isEmpty()) {
            int n24 = 0; Site.Wifi best = null;
            for (Site.Wifi w : p.wifi) if (w.freq < 2500) { n24++; if (best == null || w.level > best.level) best = w; }
            s.append("Wi-Fi 2.4 GHz: ").append(n24).append(" APs").append(best != null ? ", strongest " + best.ssid + " ch " + Analyzer.wifiChannel(best.freq) + " " + best.level + " dBm" : "").append('\n');
        }
        if (!p.note.isEmpty()) s.append("\nNote: ").append(p.note);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Spot " + p.label)
                .setMessage(s.toString().trim())
                .setPositiveButton("Measure again", (d, w) -> measureAt(p.x, p.y, p))
                .setNeutralButton("Delete", (d, w) -> confirm("Delete spot " + p.label + "?", () -> { floor().points.remove(p); saveSite(); showTab(1); }))
                .setNegativeButton("Note…", (d, w) -> ask("Note for spot " + p.label, p.note, "e.g. kitchen, behind fridge", v -> { p.note = v; saveSite(); }))
                .show();
    }

    private void placeMarker(float x, float y) {
        String[] k = {"Coordinator is here", "Existing router is here", "Cancel"};
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Mark this spot").setItems(k, (d, w) -> {
            if (w == 2) return;
            Site.Marker m = new Site.Marker(); m.kind = w == 0 ? "coordinator" : "router"; m.x = x; m.y = y;
            ask(w == 0 ? "Coordinator name" : "Router name", "", w == 0 ? "e.g. ZBDongle-E on the Pi" : "e.g. kitchen plug", v -> { m.label = v; floor().markers.add(m); saveSite(); showTab(1); });
        }).show();
    }

    private void markerDialog(Site.Marker m) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(m.kind + (m.label.isEmpty() ? "" : ": " + m.label))
                .setPositiveButton("Rename", (d, w) -> ask("Name", m.label, "", v -> { m.label = v; saveSite(); showTab(1); }))
                .setNeutralButton("Delete", (d, w) -> { floor().markers.remove(m); saveSite(); showTab(1); })
                .setNegativeButton("Close", null).show();
    }

    // ---------------- sites & floors ----------------
    private Site newSite(String name) {
        Site s = new Site(); s.name = name;
        s.floors.add(new Site.Floor());
        sites.add(0, s);
        site = s; floorIdx = 0;
        saveSite();
        return s;
    }

    private void saveSite() {
        try { site.save(getFilesDir()); prefs.edit().putString("site", site.id).apply(); } catch (Exception e) { log("save: " + e); }
    }

    private void siteMenu() {
        List<String> items = new ArrayList<>();
        items.add("➕ New site…"); items.add("✎ Rename / customer / notes…"); items.add("🗑 Delete this site");
        for (Site s : sites) if (s != site) items.add("Open: " + s.name);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Sites").setItems(items.toArray(new String[0]), (d, w) -> {
            if (w == 0) ask("Site name", "", "e.g. Villa Al-Nakheel", v -> { newSite(v.isEmpty() ? "Site " + (sites.size() + 1) : v); showTab(1); });
            else if (w == 1) editSite();
            else if (w == 2) confirm("Delete " + site.name + " with all its spots?", () -> {
                site.delete(getFilesDir()); sites.remove(site);
                if (sites.isEmpty()) newSite("Site 1"); else { site = sites.get(0); floorIdx = 0; saveSite(); }
                showTab(1);
            });
            else {
                String name = items.get(w).substring(6);
                for (Site s : sites) if (s.name.equals(name) && s != site) { site = s; floorIdx = 0; saveSite(); break; }
                showTab(1);
            }
        }).show();
    }

    private void editSite() {
        LinearLayout l = col(); l.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        EditText n = Ui.field(this, "Site name", site.name, false), c = Ui.field(this, "Customer", site.customer, false), o = Ui.field(this, "Notes for the report", site.notes, false);
        o.setSingleLine(false); o.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        add(l, n); add(l, c); add(l, o);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Site").setView(l)
                .setPositiveButton("Save", (d, w) -> { site.name = n.getText().toString().trim(); site.customer = c.getText().toString().trim(); site.notes = o.getText().toString().trim(); saveSite(); showTab(tab); })
                .setNegativeButton("Cancel", null).show();
    }

    private void floorMenu() {
        List<String> items = new ArrayList<>();
        items.add("➕ Add floor…"); items.add("🖼 Set floor plan (image or PDF)…"); items.add("📏 Plan width / area / walls…"); items.add("✎ Rename floor…"); items.add("🗑 Delete floor");
        for (int i = 0; i < site.floors.size(); i++) if (i != floorIdx) items.add("Go to: " + site.floors.get(i).name);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Floors").setItems(items.toArray(new String[0]), (d, w) -> {
            Site.Floor f = floor();
            if (w == 0) ask("Floor name", "", "e.g. First floor", v -> { Site.Floor nf = new Site.Floor(); nf.name = v.isEmpty() ? "Floor " + (site.floors.size() + 1) : v; site.floors.add(nf); floorIdx = site.floors.size() - 1; saveSite(); showTab(1); });
            else if (w == 1) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
                startActivityForResult(i, REQ_PLAN);
            } else if (w == 2) floorSize(f);
            else if (w == 3) ask("Floor name", f.name, "", v -> { f.name = v; saveSite(); showTab(1); });
            else if (w == 4) confirm("Delete " + f.name + " with its spots?", () -> { site.floors.remove(f); floorIdx = 0; floor(); saveSite(); showTab(1); });
            else {
                String name = items.get(w).substring(7);
                for (int i = 0; i < site.floors.size(); i++) if (site.floors.get(i).name.equals(name)) { floorIdx = i; break; }
                showTab(1);
            }
        }).show();
    }

    private void floorSize(Site.Floor f) {
        LinearLayout l = col(); l.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        l.addView(Ui.text(this, "Width of the plan in metres (makes the heat map and router spacing realistic). Area and wall type give a planning estimate.", 13, Ui.SUB, false));
        EditText m = Ui.field(this, "Plan width (m)", f.metersAcross > 0 ? String.valueOf(f.metersAcross) : "", false);
        EditText a = Ui.field(this, "Floor area (m²)", f.areaM2 > 0 ? String.valueOf(f.areaM2) : "", false);
        m.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); a.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        add(l, m); add(l, a);
        final String[] walls = {"drywall", "brick", "concrete"};
        final int[] sel = {Math.max(0, java.util.Arrays.asList(walls).indexOf(f.walls))};
        LinearLayout r = Ui.row(this);
        for (int i = 0; i < 3; i++) {
            final int k = i;
            r.addView(chip(walls[i], sel[0] == i, v -> { sel[0] = k; for (int j = 0; j < 3; j++) { TextView t = (TextView) r.getChildAt(j); boolean on = j == k; t.setText((on ? "✓ " : "") + walls[j]); t.setTextColor(on ? Ui.NAVY : Ui.SUB); t.setBackground(Ui.press(this, on ? Ui.GREEN : Ui.CARD2, 14)); } }));
        }
        add(l, r);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(f.name).setView(l)
                .setPositiveButton("Save", (d, w) -> {
                    try { f.metersAcross = Float.parseFloat(m.getText().toString().replace(',', '.')); } catch (Exception e) { f.metersAcross = 0; }
                    try { f.areaM2 = Float.parseFloat(a.getText().toString().replace(',', '.')); } catch (Exception e) { f.areaM2 = 0; }
                    f.walls = walls[sel[0]]; saveSite(); showTab(1);
                }).setNegativeButton("Cancel", null).show();
    }

    private Bitmap planBitmap(Site.Floor f) {
        if (f.image.isEmpty()) return null;
        Bitmap b = planCache.get(f.image);
        if (b != null) return b;
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.image, o);
        int s = 1; while (Math.max(o.outWidth, o.outHeight) / s > 2400) s *= 2;
        o = new BitmapFactory.Options(); o.inSampleSize = s;
        b = BitmapFactory.decodeFile(f.image, o);
        if (b != null) { if (planCache.size() > 4) planCache.clear(); planCache.put(f.image, b); }
        return b;
    }

    private void importPlan(Uri uri) {
        Site.Floor f = floor();
        progress("Loading plan…", "");
        new Thread(() -> {
            String err = "";
            try {
                File dir = new File(getFilesDir(), "plans"); dir.mkdirs();
                File out = new File(dir, f.id + "_" + System.currentTimeMillis() + ".png");
                String type = getContentResolver().getType(uri);
                Bitmap b;
                if (type != null && type.contains("pdf")) {
                    try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r"); PdfRenderer r = new PdfRenderer(pfd)) {
                        PdfRenderer.Page pg = r.openPage(0);
                        int w = 2200, h = Math.round(w * pg.getHeight() / (float) pg.getWidth());
                        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        b.eraseColor(Color.WHITE);
                        pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        pg.close();
                    }
                } else {
                    BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
                    try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
                    int s = 1; while (Math.max(o.outWidth, o.outHeight) / s > 2400) s *= 2;
                    o = new BitmapFactory.Options(); o.inSampleSize = s;
                    try (InputStream in = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o); }
                }
                if (b == null) throw new Exception("could not read the picture");
                try (FileOutputStream fo = new FileOutputStream(out)) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
                if (!f.image.isEmpty()) new File(f.image).delete();
                f.image = out.getPath();
                planCache.put(f.image, b);
                saveSite();
            } catch (Exception e) { err = e.getMessage(); }
            final String fe = err;
            ui.post(() -> { hideProgress(); if (fe != null && !fe.isEmpty()) dialog("Plan not loaded", fe); showTab(1); });
        }).start();
    }

    // =========================================================================================== tab 2: network (HA)
    private View networkTab() {
        LinearLayout l = col();
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "Home Assistant – ZHA", 16, Ui.TEXT, true));
        c.addView(Ui.text(this, "Address like http://192.168.8.5:8123 and a long-lived access token (HA → your profile → Security → Long-lived access tokens).", 12, Ui.SUB, false));
        EditText url = Ui.field(this, "http://192.168.8.5:8123", prefs.getString("ha_url", ""), false);
        EditText tok = Ui.field(this, "Long-lived access token", prefs.getString("ha_token", ""), true);
        add(c, url); add(c, tok);
        TextView b = Ui.button(this, ha != null ? "Reload ZHA" : "Connect ZHA", true);
        b.setOnClickListener(v -> {
            prefs.edit().putString("ha_url", url.getText().toString().trim()).putString("ha_token", tok.getText().toString().trim()).apply();
            loadHa();
        });
        c.addView(b);
        add(l, c);

        LinearLayout z = Ui.card(this);
        z.addView(Ui.text(this, "Zigbee2MQTT", 16, Ui.TEXT, true));
        z.addView(Ui.text(this, "Frontend address, e.g. http://192.168.8.5:8080 (HA add-on: map port 8080 in the add-on's Network settings). Token only if the frontend has one. One line per instance.", 12, Ui.SUB, false));
        JSONArray saved = z2mSaved();
        List<EditText[]> fields = new ArrayList<>();
        for (int i = 0; i < Math.max(1, saved.length()) + (saved.length() > 0 && saved.length() < 3 ? 1 : 0); i++) {
            JSONObject o = saved.optJSONObject(i);
            EditText u = Ui.field(this, "http://…:8080  (instance " + (i + 1) + ")", o != null ? o.optString("url") : "", false);
            EditText t = Ui.field(this, "frontend token (optional)", o != null ? o.optString("token") : "", true);
            add(z, u); add(z, t);
            fields.add(new EditText[]{u, t});
        }
        TextView zb = Ui.button(this, z2ms.isEmpty() ? "Connect Zigbee2MQTT" : "Reload Zigbee2MQTT", true);
        zb.setOnClickListener(v -> {
            JSONArray a = new JSONArray();
            for (EditText[] f : fields) {
                String u = f[0].getText().toString().trim();
                if (!u.isEmpty()) try { a.put(new JSONObject().put("url", u).put("token", f[1].getText().toString().trim())); } catch (Exception ignored) { }
            }
            prefs.edit().putString("z2m", a.toString()).apply();
            loadZ2m();
        });
        z.addView(zb);
        add(l, z);

        if (!testIeee.isEmpty()) {
            LinearLayout t = Ui.card(this);
            t.addView(Ui.text(this, "Test device: " + testName + " (" + testSource + ")", 14, Ui.GREEN, true));
            t.addView(Ui.text(this, "Carry this mains device (e.g. a smart plug) to each spot and plug it in before you measure; its signal as Home Assistant sees it is stored with the spot.", 12, Ui.SUB, false));
            TextView x = Ui.button(this, "Stop using a test device", false);
            x.setOnClickListener(v -> { setTest("", "", ""); showTab(2); });
            t.addView(Ui.gap(this, 6)); t.addView(x);
            add(l, t);
        }

        for (Net.Network n : nets) add(l, networkCard(n));
        return scroll(l);
    }

    private JSONArray z2mSaved() { try { return new JSONArray(prefs.getString("z2m", "[]")); } catch (Exception e) { return new JSONArray(); } }

    private void setTest(String src, String ieee, String name) {
        testSource = src; testIeee = ieee; testName = name;
        prefs.edit().putString("test_src", src).putString("test_ieee", ieee).putString("test_name", name).apply();
    }

    private View networkCard(Net.Network n) {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, n.label(), 16, Ui.TEXT, true));
        c.addView(Ui.kv(this, "Channel", n.channel > 0 ? String.valueOf(n.channel) : "?"));
        c.addView(Ui.kv(this, "PAN / ext. PAN", (n.pan >= 0 ? String.format(Locale.US, "0x%04X", n.pan) : "?") + "\n" + Model.ieeeColons(n.extPan)));
        if (!n.coordIeee.isEmpty()) c.addView(Ui.kv(this, "Coordinator", n.coordIeee));
        if (!n.radio.isEmpty() || !n.coordInfo.isEmpty()) c.addView(Ui.kv(this, "Radio", (n.radio + " " + n.coordInfo).trim()));
        if (!n.busyPercent.isEmpty()) {
            StringBuilder s = new StringBuilder();
            for (Map.Entry<Integer, Double> e : n.busyPercent.entrySet()) s.append(e.getKey()).append(':').append(String.format(Locale.US, "%.0f%% ", e.getValue()));
            c.addView(Ui.kv(this, "Channel use", s.toString().trim()));
        }
        if (!n.error.isEmpty()) c.addView(Ui.text(this, n.error, 12, Ui.AMBER, false));
        Analyzer.Health h = Analyzer.health(n);
        c.addView(Ui.gap(this, 6));
        for (String line : h.lines) c.addView(Ui.text(this, "• " + line, 13, Ui.SUB, false));
        c.addView(Ui.gap(this, 8));
        LinearLayout r = Ui.row(this);
        boolean isT = false; for (Site.Target t : site.targets) if (Model.sameExtPan(t.extPan, n.extPan) || n.extPan.isEmpty() && t.pan == n.pan && n.pan >= 0) isT = true;
        TextView tb = Ui.button(this, isT ? "Survey target ✓" : "Survey this network", !isT);
        tb.setOnClickListener(v -> { if (n.extPan.isEmpty() && n.pan < 0) toast("network id unknown"); else { addTarget(n.label(), n.extPan, n.pan, n.channel, n.source); showTab(2); } });
        r.addView(tb, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (n.source.equals("Zigbee2MQTT")) {
            r.addView(Ui.gap(this, 1), Ui.lp(Ui.dp(this, 8), 1, 0));
            TextView mb = Ui.button(this, "Network map", false);
            mb.setOnClickListener(v -> z2mMap(n));
            r.addView(mb, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        c.addView(r);
        String key = n.label();
        boolean open = openLists.getOrDefault(key, false);
        TextView dl = Ui.button(this, (open ? "▾ " : "▸ ") + n.devices.size() + " devices (weakest first) – tap one to use as test device", false);
        dl.setOnClickListener(v -> { openLists.put(key, !open); showTab(2); });
        c.addView(Ui.gap(this, 6)); c.addView(dl);
        if (open) {
            List<Net.Device> ds = new ArrayList<>(n.devices);
            ds.sort((a, b) -> { if (a.available != b.available) return a.available ? 1 : -1; return Integer.compare(a.lqi < 0 ? 999 : a.lqi, b.lqi < 0 ? 999 : b.lqi); });
            for (Net.Device d : ds) {
                if (d.isCoordinator()) continue;
                int col = !d.available ? Ui.RED : d.lqi < 0 ? Ui.MUTED : Analyzer.color(d.hasRssi() ? Analyzer.gradeRssi(d.rssi) : Analyzer.gradeLqi(d.lqi));
                String sig = (d.lqi >= 0 ? "LQI " + d.lqi : "LQI ?") + (d.hasRssi() ? " · " + d.rssi + " dBm" : "") + (d.available ? "" : " · offline");
                TextView t = Ui.text(this, "● " + d.name + "\n   " + d.type + (d.power.isEmpty() ? "" : " · " + d.power) + " · " + sig
                        + (h.singleList.contains(d) ? " · only 1 good link" : ""), 13, Ui.TEXT, false);
                t.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
                android.text.SpannableString sp = new android.text.SpannableString(t.getText());
                sp.setSpan(new android.text.style.ForegroundColorSpan(col), 0, 1, 0);
                t.setText(sp);
                t.setOnClickListener(v -> confirm("Use " + d.name + " as the test device?\n\nBest: a mains plug you carry and plug in at each spot.",
                        () -> { setTest(n.source.equals("ZHA") ? "ZHA" : "Zigbee2MQTT", d.ieee, d.name); showTab(2); }));
                c.addView(t);
            }
        }
        return c;
    }

    private void loadHa() {
        String url = prefs.getString("ha_url", ""), tok = prefs.getString("ha_token", "");
        if (url.isEmpty() || tok.isEmpty()) { dialog("Missing", "Enter the Home Assistant address and a long-lived access token."); return; }
        progress("Loading ZHA…", "Reading the network and devices from Home Assistant.");
        new Thread(() -> {
            String err = "";
            try {
                if (ha != null) ha.close();
                ha = new Net.Ha(url.startsWith("http") ? url : "http://" + url, tok);
                ha.connect();
                Net.Network n = ha.zha();
                synchronized (nets) { nets.removeIf(x -> x.source.equals("ZHA")); nets.add(0, n); }
                log("ZHA: " + n.devices.size() + " devices, channel " + n.channel);
            } catch (Exception e) {
                err = e.getMessage(); log("ZHA: " + e);
                if (err != null && err.contains("Unknown command")) err = "Home Assistant has no ZHA integration (are you using Zigbee2MQTT? use the section below).";
            }
            final String fe = err;
            ui.post(() -> { hideProgress(); if (fe != null && !fe.isEmpty()) dialog("ZHA", fe); showTab(2); });
        }).start();
    }

    private void loadZ2m() {
        JSONArray a = z2mSaved();
        if (a.length() == 0) { dialog("Missing", "Enter the Zigbee2MQTT frontend address."); return; }
        progress("Loading Zigbee2MQTT…", "");
        new Thread(() -> {
            StringBuilder err = new StringBuilder();
            for (Net.Z2m z : z2ms) z.close();
            z2ms.clear();
            synchronized (nets) { nets.removeIf(x -> x.source.equals("Zigbee2MQTT")); }
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                String u = o.optString("url");
                try {
                    Net.Z2m z = new Net.Z2m(u.startsWith("http") || u.startsWith("ws") ? u : "http://" + u, o.optString("token"));
                    z.connect();
                    z2ms.add(z);
                    Net.Network n = z.snapshot();
                    if (a.length() > 1) n.name = u.replaceAll("^\\w+://", "") + " " + n.name;
                    synchronized (nets) { nets.add(n); }
                    log("Z2M " + u + ": " + n.devices.size() + " devices, channel " + n.channel);
                } catch (Exception e) { err.append(u).append(": ").append(e.getMessage()).append('\n'); log("Z2M: " + e); }
            }
            ui.post(() -> { hideProgress(); if (err.length() > 0) dialog("Zigbee2MQTT", err.toString().trim()); showTab(2); });
        }).start();
    }

    private void z2mMap(Net.Network n) {
        int idx = 0, k = 0;
        for (Net.Network x : nets) { if (x.source.equals("Zigbee2MQTT")) { if (x == n) idx = k; k++; } }
        if (idx >= z2ms.size()) return;
        Net.Z2m z = z2ms.get(idx);
        progress("Asking Zigbee2MQTT for the network map…", "Every router reports its neighbours – this can take a minute.");
        new Thread(() -> {
            boolean ok = z.networkMap(120);
            Net.Network fresh = z.snapshot();
            fresh.name = n.name;
            synchronized (nets) { int i = nets.indexOf(n); if (i >= 0) nets.set(i, fresh); }
            ui.post(() -> { hideProgress(); if (!ok) dialog("Network map", "No answer in 2 minutes. Try again later."); showTab(2); });
        }).start();
    }

    // =========================================================================================== tab 3: report
    private View reportTab() {
        LinearLayout l = col();
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, site.name, 16, Ui.TEXT, true));
        int total = 0, good = 0, fair = 0, poor = 0;
        for (Site.Floor f : site.floors) for (Site.Point p : f.points) {
            total++; Analyzer.Grade g = Analyzer.overall(site, p).grade;
            if (g == Analyzer.Grade.GOOD) good++; else if (g == Analyzer.Grade.FAIR) fair++; else poor++;
        }
        c.addView(Ui.text(this, total + " spots on " + site.floors.size() + " floor(s): " + good + " good · " + fair + " fair · " + poor + " poor/none", 13, Ui.SUB, false));
        c.addView(Ui.gap(this, 8));
        c.addView(Ui.text(this, "Coordinators & routers", 14, Ui.GREEN, true));
        for (String s : Analyzer.coordinatorAdvice(site, nets)) c.addView(Ui.text(this, "• " + s, 13, Ui.TEXT, false));
        for (Site.Floor f : site.floors) {
            int pl = Analyzer.plannedRouters(f), sug = Analyzer.suggestRouters(site, f).size();
            if (pl > 0 || sug > 0) c.addView(Ui.text(this, "• " + f.name + ": " + (sug > 0 ? sug + " suggested router spot(s)" : "no weak spots") + (pl > 0 ? " · planning estimate " + pl + " router(s)" : ""), 13, Ui.TEXT, false));
        }
        c.addView(Ui.gap(this, 8));
        c.addView(Ui.text(this, "Channels", 14, Ui.GREEN, true));
        for (String s : Analyzer.channels(site, nets).lines) c.addView(Ui.text(this, "• " + s, 13, Ui.TEXT, false));
        for (Net.Network n : nets) {
            c.addView(Ui.gap(this, 8));
            c.addView(Ui.text(this, n.label(), 14, Ui.GREEN, true));
            for (String s : Analyzer.health(n).lines) c.addView(Ui.text(this, "• " + s, 13, Ui.TEXT, false));
        }
        add(l, c);

        TextView pdf = Ui.button(this, "Save PDF report…", true);
        pdf.setOnClickListener(v -> {
            String name = (site.name + "_zigbee_survey.pdf").replaceAll("[^A-Za-z0-9._-]+", "_");
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_TITLE, name), REQ_PDF);
        });
        add(l, pdf);
        if (lastReport != null) {
            TextView sh = Ui.button(this, "Share the report", false);
            sh.setOnClickListener(v -> share(lastReport, "application/pdf"));
            add(l, sh);
        }
        TextView js = Ui.button(this, "Export survey data (JSON)…", false);
        js.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, (site.name + "_survey.json").replaceAll("[^A-Za-z0-9._-]+", "_")), REQ_JSON));
        add(l, js);
        if (nets.isEmpty()) add(l, Ui.text(this, "Tip: load Home Assistant in the Network tab first – the report then includes network health and every device's signal.", 12, Ui.SUB, false));
        return scroll(l);
    }

    private void share(Uri u, String type) {
        Intent i = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share"));
    }

    private void writeReport(Uri uri) {
        progress("Creating the report…", "");
        new Thread(() -> {
            String err = "";
            try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                List<Net.Network> copy; synchronized (nets) { copy = new ArrayList<>(nets); }
                Report.write(this, site, copy, this::planBitmap, o);
                lastReport = uri;
            } catch (Exception e) { err = e.toString(); log("report: " + e); }
            final String fe = err;
            ui.post(() -> {
                hideProgress();
                if (!fe.isEmpty()) { dialog("Report failed", fe); return; }
                showTab(3);
                new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Report saved")
                        .setPositiveButton("Open", (d, w) -> { try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception e) { toast("No PDF viewer installed"); } })
                        .setNeutralButton("Share", (d, w) -> share(uri, "application/pdf"))
                        .setNegativeButton("Close", null).show();
            });
        }).start();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == REQ_PLAN) importPlan(u);
        else if (req == REQ_PDF) writeReport(u);
        else if (req == REQ_JSON) {
            try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(site.toJson().toString(2).getBytes("UTF-8")); toast("Saved"); }
            catch (Exception e) { dialog("Export failed", e.getMessage()); }
        }
    }

    // =========================================================================================== dialogs
    private AlertDialog prog;

    private void progress(String title, String msg) {
        hideProgress();
        LinearLayout l = Ui.row(this); l.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20));
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        l.addView(pb);
        TextView t = Ui.text(this, msg, 14, Ui.SUB, false); t.setPadding(Ui.dp(this, 16), 0, 0, 0);
        l.addView(t);
        prog = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(title).setView(l).setCancelable(false).show();
    }

    private void hideProgress() { if (prog != null) { try { prog.dismiss(); } catch (Exception ignored) { } prog = null; } }

    private void dialog(String title, String msg) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void confirm(String msg, Runnable yes) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setMessage(msg).setPositiveButton("Yes", (d, w) -> yes.run()).setNegativeButton("Cancel", null).show();
    }

    interface OnText { void text(String s); }

    private void ask(String title, String value, String hint, OnText ok) {
        EditText e = Ui.field(this, hint, value, false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        FrameLayout f = new FrameLayout(this); f.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0); f.addView(e);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(title).setView(f)
                .setPositiveButton("OK", (d, w) -> ok.text(e.getText().toString().trim())).setNegativeButton("Cancel", null).show();
    }
}
