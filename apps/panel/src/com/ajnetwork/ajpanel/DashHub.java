package com.ajnetwork.ajpanel;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the Home Assistant connection and the generated dashboard for the panel (lives in PanelService, so the
 * dashboard is ready the moment the screen opens and the LAN API can serve it to the Installer).
 *
 * Dashboard settings: the panel's own ("dash_panel": room, Home tiles, hidden devices, look, language, show owner name)
 * and the site's ("dash_site": owner name EN/AR, defaults). Both are stored in Home Assistant (frontend user data of the
 * token's user) and cached on the panel. HA is the master: what HA has replaces the local copy; a panel that had settings
 * before HA knew them pushes them up once.
 */
public class DashHub implements Ha.Listener {
    public interface L { void onDash(String what, String entityId); }
    public static final String REBUILT = "rebuilt";

    private final PanelService svc;
    private final Cfg cfg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<L> listeners = new CopyOnWriteArrayList<>();
    public volatile Ha ha;
    public volatile Dash dash = new Dash();
    private final Runnable rebuild = this::rebuildNow;

    DashHub(PanelService svc, Cfg cfg) { this.svc = svc; this.cfg = cfg; }

    public void addListener(L l) { listeners.add(l); }
    public void removeListener(L l) { listeners.remove(l); }
    private void fire(String what, String id) { for (L l : listeners) l.onDash(what, id); }

    /** (re)start when address/token changed; stop when there is no token */
    void sync() {
        String url = cfg.haUrl(), tok = cfg.haToken();
        String want = tok.isEmpty() ? "" : Ha.wsUrl(url) + "|" + tok.hashCode();
        Ha cur = ha;
        if (cur != null && cur.sig().equals(want)) return;
        if (cur != null) { cur.removeListener(this); cur.stop(); ha = null; Log.i("ha: stopped"); }
        if (!want.isEmpty()) {
            Ha h = new Ha(url, tok, cfg.hostId(), main::post);
            h.addListener(this);
            ha = h;
            h.start();
            Log.i("ha: connecting to " + Ha.wsUrl(url));
        }
        rebuildNow();
        fire(Ha.STATUS, null);
    }

    void stop() { Ha h = ha; if (h != null) { h.removeListener(this); h.stop(); } ha = null; }

    @Override public void onHa(String what, String id) {
        Ha h = ha;
        if (h == null) return;
        switch (what) {
            case Ha.STATES: case Ha.REGISTRY: rebuildNow(); return;
            case Ha.DATA: takeData(h); rebuildNow(); fire(Ha.DATA, null); return;
            case Ha.STATE:
                if (id != null && !dash.seen.contains(id) && id.indexOf('.') > 0) later();
                if (Dash.ROOM_ACCESS.equals(id)) later();   // AJ Netweb Room Access changed what this panel may show
                fire(what, id); return;
            default: fire(what, id);
        }
    }

    private void later() { main.removeCallbacks(rebuild); main.postDelayed(rebuild, 2500); }

    public void rebuildNow() {
        main.removeCallbacks(rebuild);
        Ha h = ha;
        if (h == null) { dash = new Dash(); fire(REBUILT, null); return; }
        long t0 = System.currentTimeMillis();
        dash = Dash.build(h, Dash.merge(cfg.siteData(), cfg.panelData()), cfg.name(), cfg.hostId(), cfg.haUser());
        long ms = System.currentTimeMillis() - t0;
        if (ms > 40) Log.i("dashboard built in " + ms + " ms (" + dash.tiles.size() + " tiles)");
        fire(REBUILT, null);
    }

    /** HA's copy wins; if HA has nothing yet, upload what the panel has */
    private void takeData(Ha h) {
        JSONObject p = h.panelData, s = h.siteData;
        if (p != null) cfg.edit().putString("dash_panel", p.toString()).apply();
        else if (cfg.panelData().length() > 0) h.savePanelData(cfg.panelData(), null);
        if (s != null) cfg.edit().putString("dash_site", s.toString()).apply();
        else if (cfg.siteData().length() > 0) h.saveSiteData(cfg.siteData(), null);
        applyLang();
    }

    void applyLang() { Lang.ar = "ar".equals(cfg.lang()); }

    /** change settings: panel = this panel only, site = every panel of this home. Values null = remove key */
    public void set(boolean site, String key, Object value) {
        JSONObject o = site ? cfg.siteData() : cfg.panelData();
        try { if (value == null) o.remove(key); else o.put(key, value); } catch (Exception ignored) { }
        cfg.edit().putString(site ? "dash_site" : "dash_panel", o.toString()).apply();
        Ha h = ha;
        if (h != null && h.connected) { if (site) h.saveSiteData(o, null); else h.savePanelData(o, null); }
        applyLang();
        rebuildNow();
        fire(Ha.DATA, null);
    }
    /** several keys at once (Settings screen, LAN API): one rebuild, one save per scope */
    public void setAll(java.util.Map<String, Object> site, java.util.Map<String, Object> panel) {
        Ha h = ha;
        for (int k = 0; k < 2; k++) {
            java.util.Map<String, Object> m = k == 0 ? site : panel;
            if (m == null || m.isEmpty()) continue;
            JSONObject o = k == 0 ? cfg.siteData() : cfg.panelData();
            for (java.util.Map.Entry<String, Object> e : m.entrySet()) try { if (e.getValue() == null) o.remove(e.getKey()); else o.put(e.getKey(), e.getValue()); } catch (Exception ignored) { }
            cfg.edit().putString(k == 0 ? "dash_site" : "dash_panel", o.toString()).apply();
            if (h != null && h.connected) { if (k == 0) h.saveSiteData(o, null); else h.savePanelData(o, null); }
        }
        applyLang(); rebuildNow(); fire(Ha.DATA, null);
    }
    /** replace the panel's dashboard changes as a whole (Installer editor) */
    public void replacePanel(JSONObject o) {
        cfg.edit().putString("dash_panel", o.toString()).apply();
        Ha h = ha; if (h != null && h.connected) h.savePanelData(o, null);
        applyLang(); rebuildNow(); fire(Ha.DATA, null);
    }
    public void replaceSite(JSONObject o) {
        cfg.edit().putString("dash_site", o.toString()).apply();
        Ha h = ha; if (h != null && h.connected) h.saveSiteData(o, null);
        applyLang(); rebuildNow(); fire(Ha.DATA, null);
    }

    // ------------------------------------------------------------------ actions → HA services
    public void act(Dash.Tile t, String action, double v) {
        Ha h = ha;
        if (h == null) return;
        String d = t.domain(), id = t.id;
        Ha.JSONObjectX x = new Ha.JSONObjectX();
        switch (action) {
            case "toggle":
                switch (d) {
                    case "climate": case "water_heater": h.call(d, Dash.isOn(h, t) ? "turn_off" : "turn_on", id, null); return;
                    case "vacuum": h.call(d, "cleaning".equals(h.stateOf(id)) ? "return_to_base" : "start", id, null); return;
                    case "lawn_mower": h.call(d, "mowing".equals(h.stateOf(id)) ? "dock" : "start_mowing", id, null); return;
                    case "lock": h.call(d, Dash.isOn(h, t) ? "unlock" : "lock", id, null); return;
                    case "scene": case "script": h.call(d, "turn_on", id, null); return;
                    default: h.call(d, "toggle", id, null); return;
                }
            case "on": h.call(d, "turn_on", id, null); return;
            case "off": h.call(d, "turn_off", id, null); return;
            case "brightness": h.call("light", "turn_on", id, x.put("brightness_pct", (int) Math.round(v)).o); return;
            case "percentage": h.call("fan", "set_percentage", id, x.put("percentage", (int) Math.round(v)).o); return;
            case "open": h.call(d, d.equals("valve") ? "open_valve" : "open_cover", id, null); return;
            case "close": h.call(d, d.equals("valve") ? "close_valve" : "close_cover", id, null); return;
            case "stop": h.call(d, d.equals("valve") ? "stop_valve" : "stop_cover", id, null); return;
            case "position": h.call("cover", "set_cover_position", id, x.put("position", (int) Math.round(v)).o); return;
            case "temp": {
                org.json.JSONObject a = h.attrs(id);
                double lo = a.optDouble("min_temp", 7), hi = a.optDouble("max_temp", 35);
                h.call(d, "set_temperature", id, x.put("temperature", Math.max(lo, Math.min(hi, v))).o); return;
            }
            case "lock": h.call("lock", "lock", id, null); return;
            case "unlock": h.call("lock", "unlock", id, null); return;
            case "run":
                if (d.equals("button") || d.equals("input_button")) h.call(d, "press", id, null);
                else h.call(d, "turn_on", id, null);
                return;
            default:
                if (action.startsWith("hvac:")) { h.call("climate", "set_hvac_mode", id, x.put("hvac_mode", action.substring(5)).o); return; }
                Log.i("dashboard: unknown action " + action);
        }
    }
}
