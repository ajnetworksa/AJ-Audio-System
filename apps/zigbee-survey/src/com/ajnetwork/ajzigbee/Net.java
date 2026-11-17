package com.ajnetwork.ajzigbee;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Live Zigbee networks from Home Assistant: ZHA (through the HA websocket API) and Zigbee2MQTT (through its frontend
 * websocket). Everything is normalised into {@link Network} / {@link Device}. Read-only except optional attribute
 * reads / state gets that make a test device report its signal now.
 */
public final class Net {
    private Net() { }

    public static final class Neighbor { public String ieee = ""; public int lqi = -1; public String relation = ""; }

    public static final class Device {
        public String ieee = "", name = "", type = "", model = "", maker = "", power = "";
        public int lqi = -1, rssi = Integer.MIN_VALUE, nwk = -1;
        public boolean available = true;
        public long lastSeen;                  // ms epoch, 0 = unknown
        public final List<Neighbor> neighbors = new ArrayList<>();
        public boolean isRouter() { return type.equalsIgnoreCase("router"); }
        public boolean isCoordinator() { return type.equalsIgnoreCase("coordinator"); }
        public boolean hasRssi() { return rssi != Integer.MIN_VALUE; }
    }

    public static final class Network {
        public String source = "", name = "";            // "ZHA" / "Zigbee2MQTT"
        public int channel = -1, pan = -1;
        public String extPan = "";                       // hex, MSB first, no separators
        public String coordIeee = "", coordInfo = "", radio = "";
        public final List<Device> devices = new ArrayList<>();
        public final Map<Integer, Double> busyPercent = new LinkedHashMap<>();   // ZHA diagnostics energy scan
        public long fetched = System.currentTimeMillis();
        public String error = "";

        public int count(String type) { int n = 0; for (Device d : devices) if (d.type.equalsIgnoreCase(type)) n++; return n; }
        public Device find(String ieee) { for (Device d : devices) if (d.ieee.equalsIgnoreCase(ieee)) return d; return null; }
        public String label() { return name.isEmpty() ? source : source + " · " + name; }
    }

    static String norm(String ieee) { return ieee == null ? "" : ieee.toLowerCase(Locale.US).replace("0x", "").replaceAll("[^0-9a-f]", ""); }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    static long parseTime(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            String t = s.replace("Z", "+00:00").replace(' ', 'T');
            if (t.length() >= 19 && !t.substring(19).matches(".*[+-]\\d\\d:?\\d\\d$")) {
                // no time zone (ZHA last_seen): the server's local time – assume the phone is in the same zone
                return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(t.substring(0, 19)).getTime();
            }
            if (t.length() > 19) {
                String main = t.substring(0, 19);
                String tz = t.substring(19).replaceAll("^\\.\\d+", "");
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
                return f.parse(main + (tz.isEmpty() ? "+00:00" : tz)).getTime();
            }
        } catch (Exception ignored) { }
        return 0;
    }

    // =====================================================================================================
    /** Home Assistant websocket client (auth with a long-lived access token). */
    public static final class Ha {
        public final String base, token;
        private Ws ws;
        private int nextId = 1;
        private final Map<Integer, JSONObject[]> waiting = new ConcurrentHashMap<>();
        private final Map<Integer, CountDownLatch> latches = new ConcurrentHashMap<>();
        private final CountDownLatch authed = new CountDownLatch(1);
        private volatile String authError = "";

        public Ha(String base, String token) { this.base = base.replaceAll("/+$", ""); this.token = token.trim(); }

        public void connect() throws IOException {
            String wsUrl = base.replaceFirst("^http", "ws") + "/api/websocket";
            if (!wsUrl.startsWith("ws")) wsUrl = "ws://" + wsUrl;
            ws = new Ws(wsUrl, this::onText, why -> { authError = authError.isEmpty() ? why : authError; authed.countDown(); for (CountDownLatch l : latches.values()) l.countDown(); });
            ws.start();
            try { if (!authed.await(10, TimeUnit.SECONDS)) throw new IOException("Home Assistant did not answer"); } catch (InterruptedException e) { throw new IOException("interrupted"); }
            if (!authError.isEmpty()) throw new IOException(authError);
        }

        private void onText(String s) {
            try {
                JSONObject m = new JSONObject(s);
                String type = m.optString("type");
                if (type.equals("auth_required")) ws.sendText(new JSONObject().put("type", "auth").put("access_token", token).toString());
                else if (type.equals("auth_ok")) authed.countDown();
                else if (type.equals("auth_invalid")) { authError = "Home Assistant refused the token (" + m.optString("message") + ")"; authed.countDown(); }
                else if (type.equals("result")) {
                    int id = m.optInt("id");
                    JSONObject[] box = waiting.get(id);
                    if (box != null) { box[0] = m; CountDownLatch l = latches.get(id); if (l != null) l.countDown(); }
                }
            } catch (Exception ignored) { }
        }

        public synchronized Object call(JSONObject msg, int timeoutS) throws IOException {
            int id = nextId++;
            JSONObject[] box = new JSONObject[1];
            CountDownLatch l = new CountDownLatch(1);
            waiting.put(id, box); latches.put(id, l);
            try {
                msg.put("id", id);
                ws.sendText(msg.toString());
                if (!l.await(timeoutS, TimeUnit.SECONDS) || box[0] == null) throw new IOException("Home Assistant: no answer to " + msg.optString("type"));
                JSONObject r = box[0];
                if (!r.optBoolean("success")) {
                    JSONObject e = r.optJSONObject("error");
                    throw new IOException(msg.optString("type") + ": " + (e != null ? e.optString("message", e.optString("code")) : "failed"));
                }
                return r.opt("result");
            } catch (org.json.JSONException e) { throw new IOException(e.getMessage()); }
            catch (InterruptedException e) { throw new IOException("interrupted"); }
            finally { waiting.remove(id); latches.remove(id); }
        }

        public Object call(String type, int timeoutS) throws IOException {
            try { return call(new JSONObject().put("type", type), timeoutS); } catch (org.json.JSONException e) { throw new IOException(e.getMessage()); }
        }

        public void close() { if (ws != null) ws.close(); }

        String get(String path) throws IOException {
            HttpURLConnection h = (HttpURLConnection) new URL(base + path).openConnection();
            h.setConnectTimeout(6000); h.setReadTimeout(30000);
            h.setRequestProperty("Authorization", "Bearer " + token);
            int code = h.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + path);
            return readAll(h.getInputStream());
        }

        /** ZHA network + devices (+ neighbour tables, + channel busy % from the ZHA diagnostics when available) */
        public Network zha() throws IOException {
            Network n = new Network();
            n.source = "ZHA";
            try {
                Object o = call("zha/network/settings", 20);
                if (o instanceof JSONObject) {
                    JSONObject r = (JSONObject) o;
                    JSONObject st = r.optJSONObject("settings");
                    JSONObject ni = st != null ? st.optJSONObject("network_info") : null;
                    JSONObject node = st != null ? st.optJSONObject("node_info") : null;
                    if (ni != null) {
                        n.channel = ni.optInt("channel", -1);
                        n.pan = parseHex(ni.optString("pan_id"));
                        n.extPan = norm(ni.optString("extended_pan_id"));
                    }
                    if (node != null) n.coordIeee = node.optString("ieee");
                    n.radio = r.optString("radio_type");
                    JSONObject dev = r.optJSONObject("device");
                    if (dev != null) n.coordInfo = dev.optString("path");
                }
            } catch (IOException e) { n.error = "network settings: " + e.getMessage(); }
            Object o = call("zha/devices", 30);
            if (o instanceof JSONArray) {
                JSONArray a = (JSONArray) o;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject j = a.optJSONObject(i); if (j == null) continue;
                    Device d = new Device();
                    d.ieee = j.optString("ieee");
                    d.name = j.optString("user_given_name", "");
                    if (d.name.isEmpty() || d.name.equals("null")) d.name = j.optString("name");
                    d.type = j.optString("device_type");
                    d.model = j.optString("model"); d.maker = j.optString("manufacturer");
                    d.power = j.optString("power_source");
                    d.lqi = j.isNull("lqi") ? -1 : j.optInt("lqi", -1);
                    d.rssi = j.isNull("rssi") ? Integer.MIN_VALUE : j.optInt("rssi", Integer.MIN_VALUE);
                    d.nwk = parseHex(j.optString("nwk"));
                    d.available = j.optBoolean("available", true);
                    d.lastSeen = parseTime(j.optString("last_seen"));
                    JSONArray nb = j.optJSONArray("neighbors");
                    for (int k = 0; nb != null && k < nb.length(); k++) {
                        JSONObject x = nb.optJSONObject(k); if (x == null) continue;
                        Neighbor e = new Neighbor();
                        e.ieee = x.optString("ieee"); e.lqi = parseIntish(x.opt("lqi")); e.relation = x.optString("relationship");
                        d.neighbors.add(e);
                    }
                    if (d.isCoordinator() && n.coordIeee.isEmpty()) n.coordIeee = d.ieee;
                    if (d.isCoordinator()) n.coordInfo = (d.maker + " " + d.model + " " + n.coordInfo).trim();
                    n.devices.add(d);
                }
            }
            try { zhaEnergy(n); } catch (Exception e) { /* optional */ }
            return n;
        }

        private void zhaEnergy(Network n) throws Exception {
            Object o = call(new JSONObject().put("type", "config_entries/get").put("domain", "zha"), 15);
            if (!(o instanceof JSONArray) || ((JSONArray) o).length() == 0) return;
            String entry = ((JSONArray) o).getJSONObject(0).optString("entry_id");
            JSONObject diag = new JSONObject(get("/api/diagnostics/config_entry/" + entry));
            JSONObject data = diag.optJSONObject("data");
            JSONObject es = data != null ? data.optJSONObject("energy_scan") : null;
            if (es == null) return;
            for (java.util.Iterator<String> it = es.keys(); it.hasNext(); ) {
                String k = it.next();
                try { n.busyPercent.put(Integer.parseInt(k), es.getDouble(k)); } catch (Exception ignored) { }
            }
        }

        /** best effort: read the Basic cluster of a ZHA device so its LQI/RSSI get refreshed now */
        public void zhaPoke(String ieee) {
            try {
                Object o = call(new JSONObject().put("type", "zha/devices/clusters").put("ieee", ieee), 15);
                if (!(o instanceof JSONArray)) return;
                JSONArray a = (JSONArray) o;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject c = a.getJSONObject(i);
                    if (c.optInt("id", -1) == 0 && "in".equals(c.optString("type"))) {
                        call(new JSONObject().put("type", "zha/devices/clusters/attributes/value").put("ieee", ieee)
                                .put("endpoint_id", c.optInt("endpoint_id", 1)).put("cluster_id", 0).put("cluster_type", "in").put("attribute", 0), 15);
                        return;
                    }
                }
            } catch (Exception ignored) { }
        }
    }

    static int parseHex(String s) {
        if (s == null || s.isEmpty() || s.equals("null")) return -1;
        // ZHA / zigpy send PAN ids and short addresses as hex text ("0x1A2B" or "1A2B"); Z2M sends numbers
        try { return Integer.parseInt(s.replaceFirst("^0[xX]", ""), 16); }
        catch (Exception e) { return -1; }
    }

    static int parseIntish(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return -1; }
    }

    // =====================================================================================================
    /**
     * Zigbee2MQTT frontend websocket: ws://<host>:8080/api (Z2M 1.x and 2.x), optional ?token=<frontend auth token>.
     * The frontend must be reachable from the phone (HA add-on: map port 8080 in the add-on network settings).
     */
    public static final class Z2m {
        public final String url, token;
        private Ws ws;
        private volatile JSONObject info;
        private volatile JSONArray devices;
        private final Map<String, JSONObject> states = new ConcurrentHashMap<>();
        private final Map<String, Long> stateTime = new ConcurrentHashMap<>();
        private volatile JSONObject netmap;
        private final Object lock = new Object();

        public Z2m(String url, String token) { this.url = url.trim().replaceAll("/+$", ""); this.token = token == null ? "" : token.trim(); }

        public void connect() throws IOException {
            String u = url.replaceFirst("^http", "ws");
            if (!u.startsWith("ws")) u = "ws://" + u;
            if (!u.matches("^wss?://[^/]+/.*")) u = u + "/api";
            if (!token.isEmpty()) u += (u.contains("?") ? "&" : "?") + "token=" + java.net.URLEncoder.encode(token, "UTF-8");
            ws = new Ws(u, this::onText, why -> { synchronized (lock) { lock.notifyAll(); } });
            ws.start();
            long dl = System.currentTimeMillis() + 10000;
            synchronized (lock) {
                while ((info == null || devices == null) && System.currentTimeMillis() < dl && !ws.isClosed()) {
                    try { lock.wait(300); } catch (InterruptedException e) { break; }
                }
            }
            if (info == null && devices == null) throw new IOException(ws.isClosed() ? "Zigbee2MQTT closed the connection (wrong token?)" : "no data from Zigbee2MQTT");
        }

        private void onText(String s) {
            try {
                JSONObject m = new JSONObject(s);
                String topic = m.optString("topic");
                Object p = m.opt("payload");
                if (topic.equals("bridge/info") && p instanceof JSONObject) info = (JSONObject) p;
                else if (topic.equals("bridge/devices") && p instanceof JSONArray) devices = (JSONArray) p;
                else if (topic.equals("bridge/response/networkmap") && p instanceof JSONObject) netmap = (JSONObject) p;
                else if (!topic.startsWith("bridge/") && p instanceof JSONObject) { states.put(topic, (JSONObject) p); stateTime.put(topic, System.currentTimeMillis()); }
                synchronized (lock) { lock.notifyAll(); }
            } catch (Exception ignored) { }
        }

        /** asks Z2M for the raw network map (neighbour tables); takes 10–60 s on bigger networks */
        public boolean networkMap(int timeoutS) {
            try {
                netmap = null;
                ws.sendText(new JSONObject().put("topic", "bridge/request/networkmap")
                        .put("payload", new JSONObject().put("type", "raw").put("routes", false)).toString());
                long dl = System.currentTimeMillis() + timeoutS * 1000L;
                synchronized (lock) { while (netmap == null && System.currentTimeMillis() < dl) lock.wait(500); }
                return netmap != null;
            } catch (Exception e) { return false; }
        }

        /** best effort: make a mains device answer now (its linkquality then updates) */
        public void poke(String friendlyName) {
            try { ws.sendText(new JSONObject().put("topic", friendlyName + "/get").put("payload", new JSONObject().put("state", "")).toString()); }
            catch (Exception ignored) { }
        }

        public void close() { if (ws != null) ws.close(); }

        public Network snapshot() {
            Network n = new Network();
            n.source = "Zigbee2MQTT";
            JSONObject i = info;
            if (i != null) {
                JSONObject nw = i.optJSONObject("network");
                if (nw != null) {
                    n.channel = nw.optInt("channel", -1);
                    n.pan = nw.optInt("pan_id", -1);
                    Object e = nw.opt("extended_pan_id");
                    if (e instanceof Long || e instanceof Integer) n.extPan = String.format(Locale.US, "%016x", ((Number) e).longValue());
                    else if (e instanceof Number) n.extPan = "";      // too big for JSON numbers -> match by PAN + channel
                    else if (e instanceof JSONArray) {
                        // some versions send the byte array (little endian)
                        JSONArray b = (JSONArray) e; StringBuilder s = new StringBuilder();
                        for (int k = b.length() - 1; k >= 0; k--) s.append(String.format(Locale.US, "%02x", b.optInt(k) & 0xFF));
                        n.extPan = s.toString();
                    } else n.extPan = norm(String.valueOf(e));
                }
                JSONObject co = i.optJSONObject("coordinator");
                if (co != null) {
                    n.coordIeee = co.optString("ieee_address");
                    JSONObject meta = co.optJSONObject("meta");
                    n.radio = co.optString("type");
                    n.coordInfo = (co.optString("type") + (meta != null ? " " + meta.toString() : "")).trim();
                }
                n.name = "v" + i.optString("version");
            }
            JSONArray a = devices;
            Map<String, Device> byIeee = new HashMap<>();
            for (int k = 0; a != null && k < a.length(); k++) {
                JSONObject j = a.optJSONObject(k); if (j == null) continue;
                Device d = new Device();
                d.ieee = j.optString("ieee_address");
                d.name = j.optString("friendly_name");
                d.type = j.optString("type");
                d.nwk = j.optInt("network_address", -1);
                d.power = j.optString("power_source");
                JSONObject def = j.optJSONObject("definition");
                if (def != null) { d.model = def.optString("model"); d.maker = def.optString("vendor"); }
                else { d.model = j.optString("model_id"); d.maker = j.optString("manufacturer"); }
                JSONObject st = states.get(d.name);
                if (st != null) {
                    d.lqi = st.optInt("linkquality", -1);
                    Long t = stateTime.get(d.name); d.lastSeen = t != null ? t : 0;
                    Object ls = st.opt("last_seen");
                    if (ls instanceof Number) d.lastSeen = ((Number) ls).longValue();
                    else if (ls instanceof String) { long v = parseTime((String) ls); if (v > 0) d.lastSeen = v; }
                }
                d.available = !j.optBoolean("disabled", false);
                n.devices.add(d);
                byIeee.put(norm(d.ieee), d);
            }
            JSONObject nm = netmap;
            if (nm != null) {
                JSONObject data = nm.optJSONObject("data");
                JSONObject val = data != null ? data.optJSONObject("value") : null;
                JSONArray links = val != null ? val.optJSONArray("links") : null;
                for (int k = 0; links != null && k < links.length(); k++) {
                    JSONObject l = links.optJSONObject(k); if (l == null) continue;
                    String src = l.optJSONObject("source") != null ? l.optJSONObject("source").optString("ieeeAddr") : l.optString("sourceIeeeAddr");
                    String dst = l.optJSONObject("target") != null ? l.optJSONObject("target").optString("ieeeAddr") : l.optString("targetIeeeAddr");
                    int lqi = l.has("lqi") ? l.optInt("lqi", -1) : l.optInt("linkquality", -1);
                    // Z2M: "source" is the device whose neighbour table lists "target"
                    Device d = byIeee.get(norm(dst));
                    if (d == null) continue;
                    Neighbor e = new Neighbor(); e.ieee = src; e.lqi = lqi; e.relation = String.valueOf(l.optInt("relationship", -1));
                    d.neighbors.add(e);
                }
            }
            return n;
        }
    }
}
