package com.ajnetwork.ajpanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Home Assistant client for the panel dashboard: one WebSocket (/api/websocket) with a long-lived access token.
 * Keeps a live copy of all entity states and the area / device / entity registries, runs service calls,
 * and stores the panel's dashboard settings in HA itself (frontend user data), so a panel that is replaced
 * gets its layout back. Reconnects by itself (2 s … 60 s). Listeners are called on the {@code ui} executor.
 * The token is never logged.
 */
public class Ha {
    public interface Listener { void onHa(String what, String entityId); }
    public interface Reply { void done(boolean ok, Object result, String error); }

    /** what = STATUS (connected/lost), STATES (full refresh), STATE (one entity), REGISTRY (areas/devices/entities), DATA (dashboard config), EXTRA (weather/energy) */
    public static final String STATUS = "status", STATES = "states", STATE = "state", REGISTRY = "registry", DATA = "data", EXTRA = "extra";

    private final String url, token;
    private final Executor ui;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Integer, Reply> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger(1);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ha-timer"); t.setDaemon(true); return t; });
    private volatile Ws ws;
    private volatile boolean run;
    private Thread thread;
    private volatile int stateSub = -1;
    private final java.util.Set<Integer> regSubs = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private ScheduledFuture<?> regRefresh;
    private volatile long lastRx;

    public volatile boolean connected;
    public volatile String status = "starting", haVersion = "";
    public final ConcurrentHashMap<String, JSONObject> states = new ConcurrentHashMap<>();
    public volatile JSONArray areas = new JSONArray(), devices = new JSONArray(), entities = new JSONArray();
    public volatile JSONObject config = new JSONObject();
    /** dashboard settings stored in HA: this panel's own and the site-wide ones (owner name, look, language) */
    public volatile JSONObject panelData, siteData;
    public volatile boolean dataLoaded;
    /** hourly weather forecast of the main weather entity and energy use today (filled by {@link #refreshExtras}) */
    public volatile JSONArray forecast = new JSONArray();
    public volatile double energyToday = -1, energyYesterday = -1;
    public volatile double[] energyHours = new double[0];
    private final String panelKey;

    public Ha(String url, String token, String panelKey, Executor ui) {
        this.url = wsUrl(url); this.token = token; this.ui = ui; this.panelKey = panelKey;
    }

    /** http://host:8123 → ws://host:8123/api/websocket */
    static String wsUrl(String u) {
        u = u.trim();
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.startsWith("https://")) u = "wss://" + u.substring(8);
        else if (u.startsWith("http://")) u = "ws://" + u.substring(7);
        else if (!u.startsWith("ws://") && !u.startsWith("wss://")) u = "ws://" + u;
        if (!u.substring(u.indexOf("//") + 2).contains(":") && u.startsWith("ws://")) u += ":8123";
        if (!u.endsWith("/api/websocket")) u += "/api/websocket";
        return u;
    }

    public String sig() { return url + "|" + token.hashCode(); }
    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    private void notify(String what, String id) { ui.execute(() -> { for (Listener l : listeners) l.onHa(what, id); }); }

    public synchronized void start() {
        if (run) return;
        run = true;
        thread = new Thread(this::loop, "ha");
        thread.setDaemon(true);
        thread.start();
        timer.scheduleWithFixedDelay(this::keepAlive, 25, 25, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        run = false;
        Ws w = ws; if (w != null) w.close();
        if (thread != null) thread.interrupt();
        timer.shutdownNow();
        connected = false;
    }

    private void keepAlive() {
        Ws w = ws;
        if (w == null || !connected) return;
        if (System.currentTimeMillis() - lastRx > 70_000) { Log.i("ha: no answer for 70 s – reconnecting"); w.close(); return; }
        send(new JSONObjectX().put("type", "ping").o, null);
    }

    private void loop() {
        int backoff = 2;
        while (run) {
            long t0 = System.currentTimeMillis();
            try (Ws w = new Ws(url, 10_000)) {
                ws = w;
                JSONObject m = new JSONObject(w.read());
                if (!"auth_required".equals(m.optString("type"))) throw new Exception("unexpected " + m.optString("type"));
                haVersion = m.optString("ha_version");
                w.sendText(new JSONObject().put("type", "auth").put("access_token", token).toString());
                m = new JSONObject(w.read());
                if ("auth_invalid".equals(m.optString("type"))) {
                    status = "token rejected"; connected = false; notify(STATUS, null);
                    Log.i("ha: token rejected by Home Assistant – set a new token");
                    sleep(300_000); continue;
                }
                if (!"auth_ok".equals(m.optString("type"))) throw new Exception("auth: " + m.optString("type"));
                w.timeout(90_000);
                lastRx = System.currentTimeMillis();
                pending.clear(); regSubs.clear();
                // start the reader before asking for anything
                Thread rd = new Thread(() -> readLoop(w), "ha-read"); rd.setDaemon(true); rd.start();
                stateSub = send(new JSONObjectX().put("type", "subscribe_events").put("event_type", "state_changed").o, null);
                for (String ev : new String[]{"area_registry_updated", "device_registry_updated", "entity_registry_updated"})
                    regSubs.add(send(new JSONObjectX().put("type", "subscribe_events").put("event_type", ev).o, null));
                send(new JSONObjectX().put("type", "get_config").o, (ok, r, e) -> { if (ok && r instanceof JSONObject) config = (JSONObject) r; });
                loadData();
                loadRegistries();
                send(new JSONObjectX().put("type", "get_states").o, (ok, r, e) -> {
                    if (!ok || !(r instanceof JSONArray)) return;
                    JSONArray a = (JSONArray) r;
                    states.clear();
                    for (int i = 0; i < a.length(); i++) { JSONObject s = a.optJSONObject(i); if (s != null) states.put(s.optString("entity_id"), s); }
                    connected = true; status = "connected";
                    Log.i("ha: connected (HA " + haVersion + ", " + states.size() + " entities)");
                    notify(STATUS, null); notify(STATES, null);
                    refreshExtras();
                });
                rd.join();
                backoff = 2;
            } catch (InterruptedException e) { return;
            } catch (Exception e) {
                status = "no connection: " + shortMsg(e);
                if (System.currentTimeMillis() - t0 > 30_000) backoff = 2;
            } finally {
                ws = null;
                boolean was = connected; connected = false;
                for (Reply r : pending.values()) try { r.done(false, null, "disconnected"); } catch (Exception ignored) { }
                pending.clear();
                if (was) { Log.i("ha: connection lost"); notify(STATUS, null); }
            }
            if (!run) return;
            sleep(backoff * 1000L);
            backoff = Math.min(60, backoff * 2);
        }
    }

    private static String shortMsg(Exception e) { String m = e.getMessage(); return m == null ? e.getClass().getSimpleName() : m.length() > 80 ? m.substring(0, 80) : m; }
    private void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    private void readLoop(Ws w) {
        try {
            String t;
            while (run && (t = w.read()) != null) {
                lastRx = System.currentTimeMillis();
                if (t.startsWith("[")) { JSONArray a = new JSONArray(t); for (int i = 0; i < a.length(); i++) dispatch(a.getJSONObject(i)); }
                else dispatch(new JSONObject(t));
            }
        } catch (Exception e) { if (run) status = "connection lost: " + shortMsg(e); }
        finally { w.close(); }
    }

    private void dispatch(JSONObject m) {
        String type = m.optString("type");
        int id = m.optInt("id", -1);
        if ("result".equals(type) || "pong".equals(type)) {
            Reply r = pending.remove(id);
            if (r == null) return;
            boolean ok = "pong".equals(type) || m.optBoolean("success");
            JSONObject err = m.optJSONObject("error");
            try { r.done(ok, m.opt("result"), err == null ? "" : err.optString("message")); } catch (Exception e) { Log.e("ha reply", e); }
            return;
        }
        if (!"event".equals(type)) return;
        JSONObject ev = m.optJSONObject("event");
        if (ev == null) return;
        if (id == stateSub) {
            JSONObject d = ev.optJSONObject("data");
            if (d == null) return;
            String eid = d.optString("entity_id");
            JSONObject ns = d.optJSONObject("new_state");
            if (ns == null) states.remove(eid); else states.put(eid, ns);
            notify(STATE, eid);
        } else if (regSubs.contains(id)) {
            // registries change in bursts (integration reload): fetch once, 3 s after the last change
            synchronized (this) {
                if (regRefresh != null) regRefresh.cancel(false);
                regRefresh = timer.schedule(this::loadRegistries, 3, TimeUnit.SECONDS);
            }
        }
    }

    private void loadRegistries() {
        send(new JSONObjectX().put("type", "config/area_registry/list").o, (ok, r, e) -> { if (ok && r instanceof JSONArray) areas = (JSONArray) r; });
        send(new JSONObjectX().put("type", "config/device_registry/list").o, (ok, r, e) -> { if (ok && r instanceof JSONArray) devices = (JSONArray) r; });
        send(new JSONObjectX().put("type", "config/entity_registry/list").o, (ok, r, e) -> {
            if (ok && r instanceof JSONArray) { entities = (JSONArray) r; notify(REGISTRY, null); }
        });
    }

    // ------------------------------------------------------------------ sending
    /** sends a message with a new id; returns the id (-1 = not connected) */
    public int send(JSONObject msg, Reply r) {
        Ws w = ws;
        if (w == null) { if (r != null) r.done(false, null, "not connected"); return -1; }
        int id = ids.getAndIncrement();
        try {
            msg.put("id", id);
            if (r != null) pending.put(id, r);
            w.sendText(msg.toString());
        } catch (Exception e) {
            pending.remove(id);
            if (r != null) r.done(false, null, shortMsg(e));
            w.close();
        }
        return id;
    }

    /** domain.service on one entity, e.g. call("light", "turn_on", "light.ceiling", {"brightness_pct": 60}) */
    public void call(String domain, String service, String entityId, JSONObject data, Reply r) {
        JSONObjectX m = new JSONObjectX().put("type", "call_service").put("domain", domain).put("service", service)
                .put("service_data", data == null ? new JSONObject() : data);
        if (entityId != null) m.put("target", new JSONObjectX().put("entity_id", entityId).o);
        Log.i("ha: " + domain + "." + service + (entityId == null ? "" : " " + entityId));
        send(m.o, r != null ? r : (ok, res, e) -> { if (!ok) Log.i("ha: " + domain + "." + service + " failed: " + e); });
    }
    public void call(String domain, String service, String entityId, JSONObject data) { call(domain, service, entityId, data, null); }

    // ------------------------------------------------------------------ dashboard settings stored in HA
    private String dataKey() { return "aj_panel_" + panelKey; }

    private void loadData() {
        final int[] left = {2};
        Reply both = (ok, r, e) -> { };
        send(new JSONObjectX().put("type", "frontend/get_user_data").put("key", dataKey()).o, (ok, r, e) -> {
            if (ok && r instanceof JSONObject) { Object v = ((JSONObject) r).opt("value"); panelData = v instanceof JSONObject ? (JSONObject) v : null; }
            if (--left[0] == 0) { dataLoaded = true; notify(DATA, null); }
        });
        send(new JSONObjectX().put("type", "frontend/get_user_data").put("key", "aj_panel_site").o, (ok, r, e) -> {
            if (ok && r instanceof JSONObject) { Object v = ((JSONObject) r).opt("value"); siteData = v instanceof JSONObject ? (JSONObject) v : null; }
            if (--left[0] == 0) { dataLoaded = true; notify(DATA, null); }
        });
    }

    /** saves this panel's dashboard settings in HA (the panel keeps a local copy for offline start) */
    public void savePanelData(JSONObject v, Reply r) {
        panelData = v;
        send(new JSONObjectX().put("type", "frontend/set_user_data").put("key", dataKey()).put("value", v).o, r);
    }
    public void saveSiteData(JSONObject v, Reply r) {
        siteData = v;
        send(new JSONObjectX().put("type", "frontend/set_user_data").put("key", "aj_panel_site").put("value", v).o, r);
    }

    // ------------------------------------------------------------------ weather forecast + energy (Home cards)
    private volatile long extrasAt;
    /** at most every 15 min; cheap queries, answers come in the background */
    public void refreshExtras() {
        if (!connected || System.currentTimeMillis() - extrasAt < 15 * 60_000L) return;
        extrasAt = System.currentTimeMillis();
        String w = firstOf("weather.");
        if (w != null) {
            JSONObjectX m = new JSONObjectX().put("type", "call_service").put("domain", "weather").put("service", "get_forecasts")
                    .put("service_data", new JSONObjectX().put("type", "hourly").o)
                    .put("target", new JSONObjectX().put("entity_id", w).o).put("return_response", true);
            send(m.o, (ok, r, e) -> {
                if (!ok || !(r instanceof JSONObject)) return;
                JSONObject resp = ((JSONObject) r).optJSONObject("response");
                JSONObject one = resp == null ? null : resp.optJSONObject(w);
                JSONArray f = one == null ? null : one.optJSONArray("forecast");
                if (f != null) { forecast = f; notify(EXTRA, w); }
            });
        }
        send(new JSONObjectX().put("type", "energy/get_prefs").o, (ok, r, e) -> {
            String stat = null;
            if (ok && r instanceof JSONObject) {
                JSONArray src = ((JSONObject) r).optJSONArray("energy_sources");
                for (int i = 0; src != null && i < src.length() && stat == null; i++) {
                    JSONObject s = src.optJSONObject(i);
                    if (s == null || !"grid".equals(s.optString("type"))) continue;
                    JSONArray from = s.optJSONArray("flow_from");
                    if (from != null && from.length() > 0) stat = from.optJSONObject(0).optString("stat_energy_from", null);
                }
            }
            if (stat == null) stat = energySensor();
            if (stat != null) loadEnergy(stat);
        });
    }

    /** first sensor that counts energy (kWh, total_increasing) */
    private String energySensor() {
        for (Map.Entry<String, JSONObject> e : states.entrySet()) {
            JSONObject a = e.getValue().optJSONObject("attributes");
            if (e.getKey().startsWith("sensor.") && a != null && "energy".equals(a.optString("device_class"))
                    && a.optString("state_class").startsWith("total")) return e.getKey();
        }
        return null;
    }

    private void loadEnergy(String stat) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US);
        JSONObjectX m = new JSONObjectX().put("type", "recorder/statistics_during_period").put("start_time", iso.format(c.getTime()))
                .put("statistic_ids", new JSONArray().put(stat)).put("period", "hour").put("types", new JSONArray().put("change"));
        send(m.o, (ok, r, e) -> {
            if (!ok || !(r instanceof JSONObject)) return;
            JSONArray rows = ((JSONObject) r).optJSONArray(stat);
            if (rows == null) return;
            double[] h = new double[rows.length()]; double sum = 0;
            for (int i = 0; i < rows.length(); i++) { h[i] = Math.max(0, rows.optJSONObject(i).optDouble("change", 0)); sum += h[i]; }
            energyHours = h; energyToday = sum;
            notify(EXTRA, stat);
        });
        JSONObjectX y = new JSONObjectX().put("type", "recorder/statistic_during_period").put("statistic_id", stat)
                .put("calendar", new JSONObjectX().put("period", "day").put("offset", -1).o).put("types", new JSONArray().put("change"));
        send(y.o, (ok, r, e) -> { if (ok && r instanceof JSONObject) energyYesterday = ((JSONObject) r).optDouble("change", -1); });
    }

    // ------------------------------------------------------------------ helpers
    public String firstOf(String prefix) {
        String best = null;
        for (String k : states.keySet()) if (k.startsWith(prefix) && (best == null || k.compareTo(best) < 0)) best = k;
        return best;
    }
    public JSONObject state(String id) { return id == null ? null : states.get(id); }
    public String stateOf(String id) { JSONObject s = state(id); return s == null ? "unavailable" : s.optString("state"); }
    public JSONObject attrs(String id) { JSONObject s = state(id); JSONObject a = s == null ? null : s.optJSONObject("attributes"); return a == null ? new JSONObject() : a; }

    /** JSONObject.put without the checked exception, for one-line message building */
    static final class JSONObjectX {
        final JSONObject o = new JSONObject();
        JSONObjectX put(String k, Object v) { try { o.put(k, v); } catch (Exception ignored) { } return this; }
    }
}
