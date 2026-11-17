package com.ajnetwork.ajzigbee;

import com.ajnetwork.ajzigbee.radio.Model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A survey site (customer / building): floors with a plan image, measured points, target networks. Saved as JSON. */
public final class Site {
    public String id = UUID.randomUUID().toString(), name = "New site", customer = "", notes = "";
    public long created = System.currentTimeMillis();
    public final List<Floor> floors = new ArrayList<>();
    public final List<Target> targets = new ArrayList<>();
    public String coordinatorText = "";            // last plugged-in coordinator (rows, for the report)

    public static final class Target {
        public String name = "", extPan = "", source = "";
        public int pan = -1, channel = -1;
        public boolean matches(Model.Beacon b) {
            if (!extPan.isEmpty()) return Model.sameExtPan(extPan, b.extPan);
            return pan >= 0 && pan == b.pan && (channel < 0 || channel == b.channel);
        }
        public String label() { return (name.isEmpty() ? "Network" : name) + (channel > 0 ? " · ch " + channel : "") + (pan >= 0 ? String.format(" · PAN %04X", pan) : ""); }
    }

    public static final class Wifi { public String ssid = "", bssid = ""; public int freq, level; }

    public static final class Reading { public String source = "", name = "", ieee = ""; public int lqi = -1, rssi = Integer.MIN_VALUE; public long age = -1; }

    public static final class Point {
        public String id = UUID.randomUUID().toString().substring(0, 8), label = "", note = "";
        public float x, y;                          // 0..1 on the plan
        public long time = System.currentTimeMillis();
        public int rounds = 1;
        public final List<Model.Beacon> beacons = new ArrayList<>();
        public final Map<Integer, Integer> energy = new LinkedHashMap<>();
        public final List<Wifi> wifi = new ArrayList<>();
        public Reading test;                        // signal of a test device (from HA) at this spot
        public boolean measured() { return !beacons.isEmpty() || rounds > 0 && time > 0 && (energy.size() > 0 || test != null || measuredEmpty); }
        public boolean measuredEmpty;               // scan ran, nothing heard
    }

    public static final class Marker { public String kind = "router", label = ""; public float x, y; }   // kind: coordinator / router / suggested

    public static final class Floor {
        public String id = UUID.randomUUID().toString().substring(0, 8), name = "Ground floor", image = "";
        public float metersAcross;                  // real width of the plan in metres (0 = unknown)
        public float areaM2; public String walls = "brick";   // for the planning estimate
        public final List<Point> points = new ArrayList<>();
        public final List<Marker> markers = new ArrayList<>();
    }

    // ---------------- JSON ----------------
    public JSONObject toJson() throws Exception {
        JSONObject j = new JSONObject();
        j.put("id", id).put("name", name).put("customer", customer).put("notes", notes).put("created", created).put("coordinator", coordinatorText);
        JSONArray ts = new JSONArray();
        for (Target t : targets) ts.put(new JSONObject().put("name", t.name).put("ext", t.extPan).put("pan", t.pan).put("ch", t.channel).put("src", t.source));
        j.put("targets", ts);
        JSONArray fs = new JSONArray();
        for (Floor f : floors) {
            JSONObject o = new JSONObject().put("id", f.id).put("name", f.name).put("image", f.image).put("m", f.metersAcross).put("area", f.areaM2).put("walls", f.walls);
            JSONArray ps = new JSONArray();
            for (Point p : f.points) {
                JSONObject q = new JSONObject().put("id", p.id).put("label", p.label).put("note", p.note).put("x", p.x).put("y", p.y)
                        .put("t", p.time).put("rounds", p.rounds).put("empty", p.measuredEmpty);
                JSONArray bs = new JSONArray();
                for (Model.Beacon b : p.beacons) bs.put(new JSONObject().put("ch", b.channel).put("pan", b.pan).put("ext", b.extPan).put("lqi", b.lqi)
                        .put("rssi", b.hasRssi() ? b.rssi : JSONObject.NULL).put("src", b.src).put("pj", b.permitJoin));
                q.put("beacons", bs);
                JSONObject en = new JSONObject();
                for (Map.Entry<Integer, Integer> e : p.energy.entrySet()) en.put(String.valueOf(e.getKey()), e.getValue());
                q.put("energy", en);
                JSONArray ws = new JSONArray();
                for (Wifi w : p.wifi) ws.put(new JSONObject().put("ssid", w.ssid).put("bssid", w.bssid).put("f", w.freq).put("l", w.level));
                q.put("wifi", ws);
                if (p.test != null) q.put("test", new JSONObject().put("src", p.test.source).put("name", p.test.name).put("ieee", p.test.ieee)
                        .put("lqi", p.test.lqi).put("rssi", p.test.rssi == Integer.MIN_VALUE ? JSONObject.NULL : p.test.rssi).put("age", p.test.age));
                ps.put(q);
            }
            o.put("points", ps);
            JSONArray ms = new JSONArray();
            for (Marker m : f.markers) ms.put(new JSONObject().put("k", m.kind).put("l", m.label).put("x", m.x).put("y", m.y));
            o.put("markers", ms);
            fs.put(o);
        }
        j.put("floors", fs);
        return j;
    }

    public static Site fromJson(JSONObject j) {
        Site s = new Site();
        s.id = j.optString("id", s.id); s.name = j.optString("name"); s.customer = j.optString("customer"); s.notes = j.optString("notes");
        s.created = j.optLong("created"); s.coordinatorText = j.optString("coordinator");
        JSONArray ts = j.optJSONArray("targets");
        for (int i = 0; ts != null && i < ts.length(); i++) {
            JSONObject o = ts.optJSONObject(i); Target t = new Target();
            t.name = o.optString("name"); t.extPan = o.optString("ext"); t.pan = o.optInt("pan", -1); t.channel = o.optInt("ch", -1); t.source = o.optString("src");
            s.targets.add(t);
        }
        JSONArray fs = j.optJSONArray("floors");
        for (int i = 0; fs != null && i < fs.length(); i++) {
            JSONObject o = fs.optJSONObject(i); Floor f = new Floor();
            f.id = o.optString("id", f.id); f.name = o.optString("name"); f.image = o.optString("image");
            f.metersAcross = (float) o.optDouble("m", 0); f.areaM2 = (float) o.optDouble("area", 0); f.walls = o.optString("walls", "brick");
            JSONArray ps = o.optJSONArray("points");
            for (int k = 0; ps != null && k < ps.length(); k++) {
                JSONObject q = ps.optJSONObject(k); Point p = new Point();
                p.id = q.optString("id", p.id); p.label = q.optString("label"); p.note = q.optString("note");
                p.x = (float) q.optDouble("x"); p.y = (float) q.optDouble("y"); p.time = q.optLong("t"); p.rounds = q.optInt("rounds", 1);
                p.measuredEmpty = q.optBoolean("empty");
                JSONArray bs = q.optJSONArray("beacons");
                for (int m = 0; bs != null && m < bs.length(); m++) {
                    JSONObject b = bs.optJSONObject(m); Model.Beacon be = new Model.Beacon();
                    be.channel = b.optInt("ch"); be.pan = b.optInt("pan"); be.extPan = b.optString("ext"); be.lqi = b.optInt("lqi");
                    be.rssi = b.isNull("rssi") ? Integer.MIN_VALUE : b.optInt("rssi"); be.src = b.optInt("src", -1); be.permitJoin = b.optBoolean("pj");
                    p.beacons.add(be);
                }
                JSONObject en = q.optJSONObject("energy");
                if (en != null) for (java.util.Iterator<String> it = en.keys(); it.hasNext(); ) { String key = it.next(); p.energy.put(Integer.parseInt(key), en.optInt(key)); }
                JSONArray ws = q.optJSONArray("wifi");
                for (int m = 0; ws != null && m < ws.length(); m++) {
                    JSONObject w = ws.optJSONObject(m); Wifi wi = new Wifi();
                    wi.ssid = w.optString("ssid"); wi.bssid = w.optString("bssid"); wi.freq = w.optInt("f"); wi.level = w.optInt("l");
                    p.wifi.add(wi);
                }
                JSONObject t = q.optJSONObject("test");
                if (t != null) {
                    Reading r = new Reading(); r.source = t.optString("src"); r.name = t.optString("name"); r.ieee = t.optString("ieee");
                    r.lqi = t.optInt("lqi", -1); r.rssi = t.isNull("rssi") ? Integer.MIN_VALUE : t.optInt("rssi"); r.age = t.optLong("age", -1);
                    p.test = r;
                }
                f.points.add(p);
            }
            JSONArray ms = o.optJSONArray("markers");
            for (int k = 0; ms != null && k < ms.length(); k++) {
                JSONObject q = ms.optJSONObject(k); Marker m = new Marker();
                m.kind = q.optString("k", "router"); m.label = q.optString("l"); m.x = (float) q.optDouble("x"); m.y = (float) q.optDouble("y");
                f.markers.add(m);
            }
            s.floors.add(f);
        }
        return s;
    }

    // ---------------- storage ----------------
    public static File dir(File filesDir) { File d = new File(filesDir, "sites"); d.mkdirs(); return d; }

    public void save(File filesDir) throws Exception {
        File f = new File(dir(filesDir), id + ".json");
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(toJson().toString().getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    public static List<Site> loadAll(File filesDir) {
        List<Site> r = new ArrayList<>();
        File[] fs = dir(filesDir).listFiles();
        if (fs == null) return r;
        for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()]; int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                r.add(fromJson(new JSONObject(new String(b, StandardCharsets.UTF_8))));
            } catch (Exception ignored) { }
        }
        r.sort((a, b) -> Long.compare(b.created, a.created));
        return r;
    }

    public void delete(File filesDir) {
        new File(dir(filesDir), id + ".json").delete();
        for (Floor f : floors) if (!f.image.isEmpty()) new File(f.image).delete();
    }
}
