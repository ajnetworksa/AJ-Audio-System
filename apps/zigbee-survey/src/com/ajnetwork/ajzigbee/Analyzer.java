package com.ajnetwork.ajzigbee;

import com.ajnetwork.ajzigbee.radio.Model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns measurements into grades and advice. Thresholds (indoor 2.4 GHz Zigbee, rule-of-thumb):
 * RSSI ≥ −70 dBm good · −70…−85 fair · below −85 poor; LQI ≥ 150 good · 80…149 fair · below 80 poor.
 */
public final class Analyzer {
    private Analyzer() { }

    public enum Grade { GOOD, FAIR, POOR, NONE, UNKNOWN }

    public static final int RSSI_GOOD = -70, RSSI_FAIR = -85, LQI_GOOD = 150, LQI_FAIR = 80;

    public static int color(Grade g) {
        switch (g) { case GOOD: return 0xFF4CC25A; case FAIR: return 0xFFF2B54A; case POOR: return 0xFFE5533D; case NONE: return 0xFF9B1C1C; default: return 0xFF6B6B73; }
    }

    public static String word(Grade g) {
        switch (g) { case GOOD: return "good"; case FAIR: return "fair"; case POOR: return "poor"; case NONE: return "no signal"; default: return "not measured"; }
    }

    public static Grade gradeRssi(int rssi) { return rssi >= RSSI_GOOD ? Grade.GOOD : rssi >= RSSI_FAIR ? Grade.FAIR : Grade.POOR; }
    public static Grade gradeLqi(int lqi) { return lqi >= LQI_GOOD ? Grade.GOOD : lqi >= LQI_FAIR ? Grade.FAIR : Grade.POOR; }

    /** coverage of one network at one point */
    public static final class Cover {
        public Grade grade = Grade.UNKNOWN;
        public int bestRssi = Integer.MIN_VALUE, secondRssi = Integer.MIN_VALUE, bestLqi = -1, secondLqi = -1;
        public float nodesHeard;          // beacons per scan round ≈ routers + coordinator in range
        public int sources = -1;          // distinct senders (TI sticks report the address)
        public double score;              // 0..1 for the heat map
        public String text() {
            if (grade == Grade.UNKNOWN) return "not measured";
            if (grade == Grade.NONE) return "no beacon heard";
            StringBuilder s = new StringBuilder(word(grade));
            if (bestRssi != Integer.MIN_VALUE) s.append(String.format(Locale.US, " · best %d dBm", bestRssi));
            if (secondRssi != Integer.MIN_VALUE) s.append(String.format(Locale.US, ", 2nd %d", secondRssi));
            if (bestLqi >= 0) s.append(" · LQI ").append(bestLqi);
            if (sources > 0) s.append(" · ").append(sources).append(sources == 1 ? " node" : " nodes");
            else if (nodesHeard > 0) s.append(String.format(Locale.US, " · ≈%.0f node%s heard", nodesHeard, nodesHeard >= 1.5 ? "s" : ""));
            return s.toString();
        }
    }

    public static Cover cover(Site.Point p, Site.Target t) {
        Cover c = new Cover();
        if (p.beacons.isEmpty() && !p.measuredEmpty) {
            if (p.test != null && p.test.lqi >= 0) return fromTest(p.test);
            return c;
        }
        List<Integer> rss = new ArrayList<>(), lq = new ArrayList<>();
        Set<Integer> src = new HashSet<>();
        int n = 0;
        for (Model.Beacon b : p.beacons) {
            if (t != null && !t.matches(b)) continue;
            n++;
            if (b.hasRssi()) rss.add(b.rssi);
            lq.add(b.lqi);
            if (b.src >= 0) src.add(b.src);
        }
        if (n == 0) { c.grade = Grade.NONE; c.score = 0; if (p.test != null && p.test.lqi >= 0) { Cover tc = fromTest(p.test); tc.text(); return tc; } return c; }
        rss.sort((a, b) -> b - a); lq.sort((a, b) -> b - a);
        c.nodesHeard = n / (float) Math.max(1, p.rounds);
        c.sources = src.isEmpty() ? -1 : src.size();
        c.bestLqi = lq.get(0);
        if (lq.size() > 1) c.secondLqi = lq.get(1);
        if (!rss.isEmpty()) {
            c.bestRssi = rss.get(0);
            // the second strongest *different* node: skip repeats of the best one from other rounds
            int rounds = Math.max(1, p.rounds);
            if (rss.size() > rounds) c.secondRssi = rss.get(rounds);
            c.grade = gradeRssi(c.bestRssi);
            c.score = clamp((c.bestRssi + 95) / 30.0);
        } else {
            c.grade = gradeLqi(c.bestLqi);
            c.score = clamp((c.bestLqi - 40) / 150.0);
        }
        // only one node barely in range -> no redundancy: one step down from good
        if (c.grade == Grade.GOOD && c.nodesHeard < 1.5 && c.bestRssi != Integer.MIN_VALUE && c.bestRssi < -62) c.grade = Grade.FAIR;
        return c;
    }

    static Cover fromTest(Site.Reading r) {
        Cover c = new Cover();
        if (r.rssi != Integer.MIN_VALUE) { c.bestRssi = r.rssi; c.grade = gradeRssi(r.rssi); c.score = clamp((r.rssi + 95) / 30.0); }
        if (r.lqi >= 0) { c.bestLqi = r.lqi; if (r.rssi == Integer.MIN_VALUE) { c.grade = gradeLqi(r.lqi); c.score = clamp((r.lqi - 40) / 150.0); } }
        return c;
    }

    static double clamp(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** worst grade over all target networks (a point must be covered by the network it is meant for) */
    public static Cover overall(Site s, Site.Point p) {
        if (s.targets.isEmpty()) return cover(p, null);
        Cover best = null;
        for (Site.Target t : s.targets) {
            Cover c = cover(p, t);
            if (best == null || c.score > best.score) best = c;
        }
        return best;
    }

    // ---------------- channels ----------------
    public static int zigbeeMhz(int ch) { return 2405 + 5 * (ch - 11); }
    public static int wifiChannel(int mhz) { return mhz == 2484 ? 14 : (mhz - 2407) / 5; }

    /** Wi-Fi power falling on each Zigbee channel (dBm, summed over all APs heard at all points) */
    public static Map<Integer, Double> wifiLoad(Site s) {
        Map<String, Integer> strongest = new HashMap<>();   // bssid -> strongest level seen
        Map<String, Integer> freq = new HashMap<>();
        for (Site.Floor f : s.floors) for (Site.Point p : f.points) for (Site.Wifi w : p.wifi) {
            if (w.freq < 2400 || w.freq > 2500) continue;
            Integer o = strongest.get(w.bssid);
            if (o == null || w.level > o) { strongest.put(w.bssid, w.level); freq.put(w.bssid, w.freq); }
        }
        Map<Integer, Double> r = new LinkedHashMap<>();
        for (int ch = 11; ch <= 26; ch++) {
            double mw = 0;
            for (Map.Entry<String, Integer> e : strongest.entrySet()) {
                int d = Math.abs(zigbeeMhz(ch) - freq.get(e.getKey()));
                if (d <= 11) mw += Math.pow(10, e.getValue() / 10.0) * (d <= 8 ? 1.0 : 0.3);
            }
            r.put(ch, mw > 0 ? 10 * Math.log10(mw) : -100.0);
        }
        return r;
    }

    /** average energy-scan level per channel over all points (dBm) */
    public static Map<Integer, Double> noise(Site s) {
        Map<Integer, double[]> acc = new LinkedHashMap<>();
        for (Site.Floor f : s.floors) for (Site.Point p : f.points) for (Map.Entry<Integer, Integer> e : p.energy.entrySet()) {
            double[] a = acc.get(e.getKey()); if (a == null) acc.put(e.getKey(), a = new double[2]);
            a[0] += e.getValue(); a[1]++;
        }
        Map<Integer, Double> r = new LinkedHashMap<>();
        for (int ch = 11; ch <= 26; ch++) { double[] a = acc.get(ch); if (a != null) r.put(ch, a[0] / a[1]); }
        return r;
    }

    public static final class ChannelAdvice { public int best = -1; public final List<String> lines = new ArrayList<>(); public final Map<Integer, Double> score = new LinkedHashMap<>(); }

    /** lower score = quieter. Prefers 15, 20, 25 (between the common Wi-Fi channels 1/6/11); 26 is low-power on some radios */
    public static ChannelAdvice channels(Site s, List<Net.Network> nets) {
        ChannelAdvice a = new ChannelAdvice();
        Map<Integer, Double> wifi = wifiLoad(s), noise = noise(s);
        boolean anyWifi = false; for (double v : wifi.values()) if (v > -100) anyWifi = true;
        for (int ch = 11; ch <= 26; ch++) {
            double w = wifi.get(ch), n = noise.containsKey(ch) ? noise.get(ch) : -100;
            double mw = Math.pow(10, w / 10) + Math.pow(10, n / 10);
            double sc = 10 * Math.log10(mw);
            if (ch == 26) sc += 3;                         // reduced TX power on many sticks
            if (ch != 15 && ch != 20 && ch != 25 && ch != 11) sc += 1;   // mild preference for the classic gaps
            if (ch == 11) sc += 0.5;                       // 11 sits on Wi-Fi channel 1
            a.score.put(ch, sc);
        }
        int best = 15; double bv = 1e9;
        for (Map.Entry<Integer, Double> e : a.score.entrySet()) if (e.getValue() < bv) { bv = e.getValue(); best = e.getKey(); }
        a.best = best;
        if (!anyWifi && noise.isEmpty()) { a.lines.add("No Wi-Fi or channel-noise data yet – measure some points with Wi-Fi scan / an EZSP stick."); return a; }
        a.lines.add(String.format(Locale.US, "Quietest Zigbee channel here: %d (%d MHz).", best, zigbeeMhz(best)));
        Set<Integer> wch = new HashSet<>();
        for (Site.Floor f : s.floors) for (Site.Point p : f.points) for (Site.Wifi w : p.wifi) if (w.freq < 2500 && w.level > -75) wch.add(wifiChannel(w.freq));
        if (!wch.isEmpty()) a.lines.add("Strong 2.4 GHz Wi-Fi on channel(s) " + wch + ".");
        for (Net.Network n : nets) {
            if (n.channel < 11) continue;
            double sc = a.score.containsKey(n.channel) ? a.score.get(n.channel) : -100;
            String v = sc - bv < 4 ? "fine" : sc - bv < 10 ? "busier than " + best + " – change only if devices drop out" : "busy – consider moving to " + best;
            a.lines.add(n.label() + " uses channel " + n.channel + ": " + v + ".");
        }
        a.lines.add("Changing a live network's channel: ZHA → Configure → Change channel; Zigbee2MQTT needs a re-pair. Battery devices may need a wake-up.");
        return a;
    }

    // ---------------- routers ----------------
    /** suggested router spots: clusters of poor/none points, placed 40% of the way towards the nearest good point */
    public static List<Site.Marker> suggestRouters(Site s, Site.Floor f) {
        List<Site.Marker> r = new ArrayList<>();
        List<Site.Point> bad = new ArrayList<>(), good = new ArrayList<>();
        for (Site.Point p : f.points) {
            Cover c = overall(s, p);
            if (c.grade == Grade.POOR || c.grade == Grade.NONE) bad.add(p);
            else if (c.grade == Grade.GOOD) good.add(p);
        }
        double radius = f.metersAcross > 0 ? 8.0 / f.metersAcross : 0.15;   // 8 m cluster radius
        boolean[] used = new boolean[bad.size()];
        for (int i = 0; i < bad.size(); i++) {
            if (used[i]) continue;
            float sx = 0, sy = 0; int n = 0;
            for (int k = i; k < bad.size(); k++) {
                if (used[k]) continue;
                if (Math.hypot(bad.get(k).x - bad.get(i).x, bad.get(k).y - bad.get(i).y) <= radius) { used[k] = true; sx += bad.get(k).x; sy += bad.get(k).y; n++; }
            }
            float cx = sx / n, cy = sy / n;
            Site.Point near = null; double nd = 1e9;
            for (Site.Point g : good) { double d = Math.hypot(g.x - cx, g.y - cy); if (d < nd) { nd = d; near = g; } }
            Site.Marker m = new Site.Marker();
            m.kind = "suggested";
            if (near != null) { m.x = cx + (near.x - cx) * 0.4f; m.y = cy + (near.y - cy) * 0.4f; }
            else { m.x = cx; m.y = cy; }
            m.label = "R" + (r.size() + 1);
            r.add(m);
        }
        return r;
    }

    /** rough planning estimate: one mains router per cell, cell size by wall type */
    public static int plannedRouters(Site.Floor f) {
        if (f.areaM2 <= 0) return -1;
        double cell = f.walls.equals("drywall") ? 120 : f.walls.equals("concrete") ? 50 : 80;   // m² per router
        return (int) Math.max(1, Math.ceil(f.areaM2 / cell));
    }

    // ---------------- network health (from HA) ----------------
    public static final class Health {
        public int routers, ends, weak, offline, singlePath, coordChildren = -1;
        public final List<Net.Device> weakList = new ArrayList<>(), singleList = new ArrayList<>(), offlineList = new ArrayList<>();
        public final List<String> lines = new ArrayList<>();
    }

    public static Health health(Net.Network n) {
        Health h = new Health();
        Map<String, Integer> links = new HashMap<>();      // device -> number of good links (lqi >= 100) in neighbour tables
        int withTables = 0;
        for (Net.Device d : n.devices) if (!d.neighbors.isEmpty()) withTables++;
        boolean haveTables = withTables >= 2 && withTables * 2 >= n.count("Router") + 1;   // most routers reported their table
        for (Net.Device d : n.devices) for (Net.Neighbor nb : d.neighbors) {
            if (nb.lqi < 100) continue;
            links.merge(Net.norm(d.ieee), 1, Integer::sum);
            links.merge(Net.norm(nb.ieee), 1, Integer::sum);
        }
        Net.Device coord = null;
        for (Net.Device d : n.devices) if (d.isCoordinator()) coord = d;
        if (coord != null) {
            int ch = 0; for (Net.Neighbor nb : coord.neighbors) if (nb.relation.toLowerCase(Locale.US).contains("child") || nb.relation.equals("1")) ch++;
            if (!coord.neighbors.isEmpty()) h.coordChildren = ch;
        }
        for (Net.Device d : n.devices) {
            if (d.isCoordinator()) continue;
            if (d.isRouter()) h.routers++; else h.ends++;
            if (!d.available) { h.offline++; h.offlineList.add(d); }
            if (d.lqi >= 0 && d.lqi < LQI_FAIR) { h.weak++; h.weakList.add(d); }
            if (haveTables && d.isRouter() && links.getOrDefault(Net.norm(d.ieee), 0) <= 1) { h.singlePath++; h.singleList.add(d); }
        }
        h.weakList.sort((a, b) -> a.lqi - b.lqi);
        int total = h.routers + h.ends;
        h.lines.add(String.format(Locale.US, "%d devices: %d routers, %d end devices (battery).", total, h.routers, h.ends));
        if (total > 0 && h.routers * 4 < h.ends) h.lines.add("Few routers for this many battery devices – add mains-powered routers (smart plugs, switches with neutral).");
        if (h.weak > 0) h.lines.add(h.weak + " device(s) with weak link (LQI < " + LQI_FAIR + ").");
        if (h.offline > 0) h.lines.add(h.offline + " device(s) offline / unavailable.");
        if (h.singlePath > 0) h.lines.add(h.singlePath + " router(s) with only one good link – a single failure cuts them off.");
        if (!haveTables) h.lines.add(n.source.equals("Zigbee2MQTT") ? "Tap 'Network map' to load neighbour tables (link redundancy)." : "No neighbour tables yet (ZHA updates them every few hours).");
        if (h.coordChildren >= 0) h.lines.add("Coordinator neighbour table: " + h.coordChildren + " direct children.");
        if (total > 150) h.lines.add("Large network (>150): consider splitting into two networks (second coordinator) by building or floor.");
        return h;
    }

    /** coordinator sizing advice for the report */
    public static List<String> coordinatorAdvice(Site s, List<Net.Network> nets) {
        List<String> r = new ArrayList<>();
        int devices = 0; for (Net.Network n : nets) devices += n.devices.size();
        int suggested = 0; for (Site.Floor f : s.floors) suggested += suggestRouters(s, f).size();
        r.add("One coordinator runs one Zigbee network; coverage comes from routers (mains-powered devices), not from more coordinators.");
        if (nets.size() > 1) r.add(nets.size() + " networks found in Home Assistant – each needs its own coordinator, and devices cannot move between them without re-pairing.");
        if (devices > 0) r.add(devices + " devices in Home Assistant today." + (devices > 150 ? " Above ~150 devices a second network/coordinator per building or wing is reasonable." : " One modern coordinator (EFR32MG21/24 or CC2652) handles this size."));
        if (suggested > 0) r.add("Add about " + suggested + " router(s) at the marked spots (★) to fix weak areas.");
        else r.add("No extra routers needed at the measured spots.");
        r.add("Keep the coordinator on a USB extension cable (0.5–1 m) away from the server, SSDs and USB 3 ports.");
        return r;
    }
}
