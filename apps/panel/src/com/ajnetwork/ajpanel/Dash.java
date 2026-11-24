package com.ajnetwork.ajpanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The panel dashboard, generated from Home Assistant (areas → rooms, devices/entities → tiles) and then adjusted
 * by the owner's changes. Changes are stored as differences only, so new devices still appear by themselves:
 * <pre>
 * { "area": "living_room",                         this panel's room (default: matched by panel name)
 *   "hide": ["switch.led_indicator"],  "rooms_hide": ["garage"],
 *   "names": {"light.x": "Chandelier"}, "room_names": {"majlis": "Majlis"},
 *   "kinds": {"switch.porch": "light"},           show a switch as a light, …
 *   "order": {"living_room": ["light.a", "cover.b"]}, "rooms_order": ["living_room", "kitchen"],
 *   "favs": ["light.a", "climate.ac", …],         Home tiles (default: picked from this room)
 *   "move": {"switch.x": "kitchen"} }             put an entity into another room
 * </pre>
 * Site-wide settings come first, the panel's own settings override them.
 *
 * <p>What a panel may show is set in Home Assistant with labels (Settings → Labels), on entities, devices or areas:
 * <ul><li><b>AJ hide</b> (aj_hide) – never on any panel;</li>
 * <li><b>AJ &lt;panel user&gt;</b> (e.g. "AJ Kitchen" for the HA user "kitchen") – once that label is used anywhere,
 *     the panel signed in as that user shows ONLY what carries it (or <b>AJ all</b>);</li>
 * <li>no AJ label at all – the panel shows everything its user can see.</li></ul>
 * This hides things on the panel; Home Assistant itself does not limit what a normal user may switch.</p>
 *
 * <p><b>AJ Netweb Room Access</b> (add-on "Room &amp; Guest Pass Studio", User Room Matrix) is the main way:
 * it publishes {@code sensor.ajnetweb_room_access} with {@code users = {user_id: {areas: [...], home: area}}}.
 * A panel signed in as a listed (non-admin) user shows only those rooms – their devices, scenes, sensors – and opens
 * on its "device room". Admins and users without ticks see the whole home (same rule as the AJ Netweb dashboard).
 * Smoke / gas / leak alarms stay visible everywhere.</p>
 */
public class Dash {
    public static final String LIGHT = "light", DIMMER = "dimmer", SWITCH = "switch", FAN = "fan", COVER = "cover", CLIMATE = "climate",
            LOCK = "lock", SCENE = "scene", SCRIPT = "script", SENSOR = "sensor", ALARM = "alarm", MEDIA = "media", BUTTON = "button",
            VACUUM = "vacuum", OPENING = "opening", MOTION = "motion", SAFETY = "safety", VALVE = "valve", HEATER = "heater";

    public static class Tile {
        public final String id, kind;
        public String name, area = "";
        Tile(String id, String kind, String name) { this.id = id; this.kind = kind; this.name = name; }
        public String domain() { int i = id.indexOf('.'); return i < 0 ? id : id.substring(0, i); }
        @Override public String toString() { return kind + ":" + id; }
    }

    public static class Room {
        public final String id;
        public String name;
        public final List<Tile> tiles = new ArrayList<>();
        /** temperature / humidity sensors and door/window/motion sensors of this room (header and status, not tiles) */
        public final List<String> temps = new ArrayList<>(), hums = new ArrayList<>(), openings = new ArrayList<>(), motions = new ArrayList<>();
        Room(String id, String name) { this.id = id; this.name = name; }
    }

    public final List<Room> rooms = new ArrayList<>();
    public final Map<String, Room> byId = new HashMap<>();
    public final List<Tile> scenes = new ArrayList<>(), climate = new ArrayList<>(), security = new ArrayList<>(), favs = new ArrayList<>();
    public final Map<String, Tile> tiles = new HashMap<>();
    /** door/window/garage, smoke/leak/gas sensors anywhere (live alerts in the header) */
    public final List<String> openings = new ArrayList<>(), safety = new ArrayList<>();
    public Room mine;
    /** every entity id looked at in this build (a state for an id NOT in here = a new entity → rebuild) */
    public final Set<String> seen = new HashSet<>();
    public String weather;
    /** this panel's own entities (AJ Panel players etc.) and other noise never become tiles */
    private static final Set<String> SKIP_PLATFORMS = new HashSet<>(java.util.Arrays.asList(
            "music_assistant", "snapcast", "dlna_dmr", "cast", "hassio", "backup", "sun", "updater", "hacs", "mobile_app", "person", "zone"));

    static String s(JSONObject o, String k) { return o == null || o.isNull(k) ? "" : o.optString(k, ""); }
    private static List<String> list(JSONObject o, String k) {
        List<String> l = new ArrayList<>();
        JSONArray a = o == null ? null : o.optJSONArray(k);
        for (int i = 0; a != null && i < a.length(); i++) l.add(a.optString(i));
        return l;
    }
    private static String map(JSONObject o, String m, String k) { JSONObject x = o == null ? null : o.optJSONObject(m); return x == null ? "" : s(x, k); }

    /** site + panel settings, panel wins (key by key; maps merged one level) */
    public static JSONObject merge(JSONObject site, JSONObject panel) {
        JSONObject r = new JSONObject();
        try {
            for (JSONObject src : new JSONObject[]{site, panel}) {
                if (src == null) continue;
                for (java.util.Iterator<String> it = src.keys(); it.hasNext(); ) {
                    String k = it.next(); Object v = src.get(k);
                    if (v instanceof JSONObject && r.opt(k) instanceof JSONObject) {
                        JSONObject into = r.getJSONObject(k), from = (JSONObject) v;
                        for (java.util.Iterator<String> i2 = from.keys(); i2.hasNext(); ) { String k2 = i2.next(); into.put(k2, from.get(k2)); }
                    } else r.put(k, v);
                }
            }
        } catch (Exception ignored) { }
        return r;
    }

    static String norm(String n) { return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", ""); }

    /** room for this panel: setting, else the area whose name matches the panel name ("Living room" → living_room) */
    static String matchArea(JSONArray areas, String panelName) {
        String p = norm(panelName == null ? "" : panelName);
        if (p.isEmpty()) return "";
        String contains = "";
        for (int i = 0; i < areas.length(); i++) {
            JSONObject a = areas.optJSONObject(i);
            String n = norm(s(a, "name")), id = s(a, "area_id");
            if (n.isEmpty()) continue;
            if (n.equals(p) || norm(id).equals(p)) return id;
            if (contains.isEmpty() && (p.contains(n) || n.contains(p))) contains = id;
        }
        return contains;
    }

    /** tile kind for one entity, "" = not a tile (sensors etc. are used elsewhere) */
    static String kindOf(String id, JSONObject attrs) {
        String d = id.substring(0, id.indexOf('.'));
        String dc = s(attrs, "device_class");
        switch (d) {
            case "light": {
                JSONArray m = attrs.optJSONArray("supported_color_modes");
                if (m != null) for (int i = 0; i < m.length(); i++) if (!"onoff".equals(m.optString(i))) return DIMMER;
                if (attrs.has("brightness")) return DIMMER;
                return LIGHT;
            }
            case "switch": case "input_boolean": return dc.equals("outlet") ? SWITCH : SWITCH;
            case "fan": return FAN;
            case "cover": return COVER;
            case "climate": return CLIMATE;
            case "water_heater": return HEATER;
            case "humidifier": return FAN;
            case "lock": return LOCK;
            case "scene": return SCENE;
            case "script": return SCRIPT;
            case "alarm_control_panel": return ALARM;
            case "vacuum": case "lawn_mower": return VACUUM;
            case "valve": return VALVE;
            case "button": case "input_button": return BUTTON;
            case "media_player": return MEDIA;
            default: return "";
        }
    }

    static boolean isOpening(String dc) { return dc.equals("door") || dc.equals("window") || dc.equals("garage_door") || dc.equals("opening"); }
    static boolean isMotion(String dc) { return dc.equals("motion") || dc.equals("occupancy") || dc.equals("presence"); }
    static boolean isSafety(String dc) { return dc.equals("smoke") || dc.equals("gas") || dc.equals("moisture") || dc.equals("carbon_monoxide") || dc.equals("safety"); }

    private static final List<String> ORDER = java.util.Arrays.asList(DIMMER, LIGHT, COVER, CLIMATE, HEATER, FAN, SWITCH, VALVE, LOCK, ALARM, VACUUM, MEDIA, SCENE, SCRIPT, BUTTON);

    public static Dash build(Ha ha, JSONObject ov, String panelName, String myHostId) { return build(ha, ov, panelName, myHostId, ""); }

    /** HA's slugify for label ids: "AJ Kitchen" → aj_kitchen */
    static String slug(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", ""); }
    private static void labelsOf(JSONObject o, Set<String> into) { JSONArray a = o == null ? null : o.optJSONArray("labels"); for (int i = 0; a != null && i < a.length(); i++) into.add(a.optString(i)); }

    /** label id that limits a panel signed in as {@code haUser} ("" = none) */
    public String label = "";
    /** true = this panel shows only what carries its label (or AJ all) */
    public boolean restricted;
    public static final String ROOM_ACCESS = "sensor.ajnetweb_room_access";
    /** rooms allowed by AJ Netweb Room Access for this panel's user (null = whole home) and its device room */
    public Set<String> allowedRooms; public String homeRoom = "";

    /** Room Access entry of the signed-in user, or null (admin / not listed / add-on not installed) */
    static JSONObject roomAccess(Ha ha) {
        if (ha.userId.isEmpty() || ha.userAdmin) return null;
        JSONObject u = ha.attrs(ROOM_ACCESS).optJSONObject("users");
        JSONObject me = u == null ? null : u.optJSONObject(ha.userId);
        JSONArray a = me == null ? null : me.optJSONArray("areas");
        return a == null || a.length() == 0 ? null : me;
    }

    public static Dash build(Ha ha, JSONObject ov, String panelName, String myHostId, String haUser) {
        Dash d = new Dash();
        if (ov == null) ov = new JSONObject();
        Set<String> hide = new HashSet<>(list(ov, "hide")), roomsHide = new HashSet<>(list(ov, "rooms_hide"));
        // registries → lookups
        Map<String, String> areaName = new LinkedHashMap<>();
        for (int i = 0; i < ha.areas.length(); i++) { JSONObject a = ha.areas.optJSONObject(i); if (a != null) areaName.put(s(a, "area_id"), s(a, "name")); }
        Map<String, JSONObject> dev = new HashMap<>();
        for (int i = 0; i < ha.devices.length(); i++) { JSONObject x = ha.devices.optJSONObject(i); if (x != null) dev.put(s(x, "id"), x); }
        Map<String, JSONObject> reg = new HashMap<>();
        for (int i = 0; i < ha.entities.length(); i++) { JSONObject x = ha.entities.optJSONObject(i); if (x != null) reg.put(s(x, "entity_id"), x); }

        // HA labels: aj_hide everywhere; aj_<user> = allow-list for the panel signed in as <user>
        Map<String, JSONObject> areaObj = new HashMap<>();
        for (int i = 0; i < ha.areas.length(); i++) { JSONObject a = ha.areas.optJSONObject(i); if (a != null) areaObj.put(s(a, "area_id"), a); }
        String mine = haUser == null || slug(haUser).isEmpty() ? "" : "aj_" + slug(haUser);
        d.label = mine;
        if (!mine.isEmpty()) {
            Set<String> all = new HashSet<>();
            for (JSONObject x : reg.values()) labelsOf(x, all);
            for (JSONObject x : dev.values()) labelsOf(x, all);
            for (JSONObject x : areaObj.values()) labelsOf(x, all);
            d.restricted = all.contains(mine);
        }

        JSONObject access = roomAccess(ha);
        if (access != null) {
            d.allowedRooms = new HashSet<>(list(access, "areas"));
            d.homeRoom = s(access, "home");
        }

        String myArea = s(ov, "area");
        if ((myArea.isEmpty() || !areaName.containsKey(myArea)) && !d.homeRoom.isEmpty() && areaName.containsKey(d.homeRoom)) myArea = d.homeRoom;
        if (myArea.isEmpty() || !areaName.containsKey(myArea)) myArea = matchArea(ha.areas, panelName);
        if (d.allowedRooms != null && !d.allowedRooms.contains(myArea)) myArea = d.homeRoom;

        List<String> ids = new ArrayList<>(ha.states.keySet());
        Collections.sort(ids);
        d.seen.addAll(ids);
        for (String id : ids) {
            JSONObject st = ha.states.get(id);
            if (st == null || id.indexOf('.') < 0) continue;
            JSONObject attrs = st.optJSONObject("attributes"); if (attrs == null) attrs = new JSONObject();
            JSONObject r = reg.get(id);
            if (r != null && (!s(r, "hidden_by").isEmpty() || !s(r, "disabled_by").isEmpty() || !s(r, "entity_category").isEmpty())) continue;
            if (r != null && SKIP_PLATFORMS.contains(s(r, "platform"))) continue;
            if (hide.contains(id)) continue;
            String domain = id.substring(0, id.indexOf('.'));
            JSONObject device = r == null ? null : dev.get(s(r, "device_id"));
            // never show this panel's own player or other AJ panels' players
            if (id.contains("ajpanel") || (myHostId != null && !myHostId.isEmpty() && id.contains(myHostId))) continue;
            String area = map(ov, "move", id);
            if (area.isEmpty() && r != null) area = s(r, "area_id");
            if (area.isEmpty() && device != null) area = s(device, "area_id");
            if (!area.isEmpty() && !areaName.containsKey(area)) area = "";
            String dc = s(attrs, "device_class");
            if (!domain.equals("weather")) {
                Set<String> lb = new HashSet<>();
                labelsOf(r, lb); labelsOf(device, lb); labelsOf(areaObj.get(area), lb);
                if (lb.contains("aj_hide")) continue;
                if (d.restricted && !lb.contains(d.label) && !lb.contains("aj_all")) continue;
                // Room Access: only the allowed rooms (life-safety alarms stay, for the header alert)
                if (d.allowedRooms != null && !d.allowedRooms.contains(area)) {
                    if (domain.equals("binary_sensor") && isSafety(dc)) d.safety.add(id);
                    continue;
                }
            }

            if (domain.equals("weather")) { if (d.weather == null) d.weather = id; continue; }
            if (domain.equals("sensor") || domain.equals("binary_sensor")) {
                if (area.isEmpty() || roomsHide.contains(area)) {
                    if (domain.equals("binary_sensor") && isSafety(dc)) d.safety.add(id);
                    if (domain.equals("binary_sensor") && isOpening(dc)) d.openings.add(id);
                    continue;
                }
                Room room = d.room(area, areaName.get(area));
                if (domain.equals("sensor") && dc.equals("temperature")) room.temps.add(id);
                else if (domain.equals("sensor") && dc.equals("humidity")) room.hums.add(id);
                else if (domain.equals("binary_sensor") && isOpening(dc)) { room.openings.add(id); d.openings.add(id); }
                else if (domain.equals("binary_sensor") && isMotion(dc)) room.motions.add(id);
                else if (domain.equals("binary_sensor") && isSafety(dc)) d.safety.add(id);
                continue;
            }
            String kind = map(ov, "kinds", id);
            if (kind.isEmpty()) kind = kindOf(id, attrs);
            if (kind.isEmpty()) continue;
            if (kind.equals(MEDIA)) continue;            // music is AJ Panel's own page; TVs etc. come later
            if (kind.equals(SCRIPT) && attrs.has("fields") && attrs.optJSONObject("fields") != null && attrs.optJSONObject("fields").length() > 0) continue;
            String name = map(ov, "names", id);
            if (name.isEmpty()) name = cleanName(s(attrs, "friendly_name"), area.isEmpty() ? "" : areaName.get(area), id);
            Tile t = new Tile(id, kind, name);
            t.area = area;
            d.tiles.put(id, t);
            if (kind.equals(SCENE) || kind.equals(SCRIPT)) d.scenes.add(t);
            if (kind.equals(CLIMATE) || kind.equals(HEATER)) d.climate.add(t);
            if (kind.equals(LOCK) || kind.equals(ALARM)) d.security.add(t);
            if (area.isEmpty() || roomsHide.contains(area)) { if (!kind.equals(SCENE) && !kind.equals(SCRIPT)) d.room("", "").tiles.add(t); continue; }
            d.room(area, areaName.get(area)).tiles.add(t);
        }
        // names, order
        JSONObject roomNames = ov.optJSONObject("room_names");
        for (Room r : d.rooms) {
            if (roomNames != null && !s(roomNames, r.id).isEmpty()) r.name = s(roomNames, r.id);
            List<String> order = list(ov.optJSONObject("order"), r.id);
            Collections.sort(r.tiles, (a, b) -> {
                int ia = order.indexOf(a.id), ib = order.indexOf(b.id);
                if (ia >= 0 || ib >= 0) return ia < 0 ? 1 : ib < 0 ? -1 : ia - ib;
                int ka = ORDER.indexOf(a.kind), kb = ORDER.indexOf(b.kind);
                return ka != kb ? ka - kb : a.name.compareToIgnoreCase(b.name);
            });
        }
        // rooms in HA order (= the order the user sorted them in HA), unknown "Other" last; empty rooms dropped
        List<String> ro = list(ov, "rooms_order");
        List<String> haOrder = new ArrayList<>(areaName.keySet());
        Collections.sort(d.rooms, (a, b) -> {
            if (a.id.isEmpty() != b.id.isEmpty()) return a.id.isEmpty() ? 1 : -1;
            int ia = ro.indexOf(a.id), ib = ro.indexOf(b.id);
            if (ia >= 0 || ib >= 0) return ia < 0 ? 1 : ib < 0 ? -1 : ia - ib;
            return haOrder.indexOf(a.id) - haOrder.indexOf(b.id);
        });
        for (java.util.Iterator<Room> it = d.rooms.iterator(); it.hasNext(); ) {
            Room r = it.next();
            if (roomsHide.contains(r.id) || (r.tiles.isEmpty() && r.temps.isEmpty())) { it.remove(); d.byId.remove(r.id); }
        }
        d.mine = myArea.isEmpty() ? null : d.byId.get(myArea);
        // Home tiles: owner's choice, else this room first, then the rest of the home
        List<String> fav = list(ov, "favs");
        if (!fav.isEmpty()) { for (String f : fav) { Tile t = d.tiles.get(f); if (t != null) d.favs.add(t); } }
        else d.autoFavs();
        return d;
    }

    private Room room(String id, String name) {
        Room r = byId.get(id);
        if (r == null) { r = new Room(id, name == null ? "" : name); byId.put(id, r); rooms.add(r); }
        return r;
    }

    /** "Living Room Ceiling Light" in room "Living Room" → "Ceiling Light" */
    static String cleanName(String friendly, String room, String id) {
        String n = friendly == null || friendly.trim().isEmpty() ? id.substring(id.indexOf('.') + 1).replace('_', ' ') : friendly.trim();
        if (room != null && !room.isEmpty() && n.length() > room.length() + 1 && n.toLowerCase(Locale.ROOT).startsWith(room.toLowerCase(Locale.ROOT))) {
            String rest = n.substring(room.length()).trim();
            if (rest.startsWith("-") || rest.startsWith("·")) rest = rest.substring(1).trim();
            if (!rest.isEmpty()) n = Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
        }
        return n;
    }

    /** up to 8: this room's lights, climate, covers, a scene, a lock; then the same kinds from the rest of the home */
    private void autoFavs() {
        List<Tile> pool = new ArrayList<>();
        if (mine != null) pool.addAll(mine.tiles);
        for (Tile s : scenes) if (mine != null && mine.id.equals(s.area)) pool.add(s);
        for (Room r : rooms) if (r != mine) pool.addAll(r.tiles);
        pool.addAll(scenes);
        String[][] want = {{CLIMATE, HEATER}, {DIMMER, LIGHT}, {SCENE}, {COVER}, {LOCK}, {DIMMER, LIGHT}, {FAN, SWITCH}, {SCENE, SCRIPT}};
        Set<String> used = new HashSet<>();
        for (String[] w : want) {
            for (Tile t : pool) {
                if (used.contains(t.id)) continue;
                boolean ok = false; for (String k : w) if (k.equals(t.kind)) ok = true;
                if (ok) { favs.add(t); used.add(t.id); break; }
            }
        }
        for (Tile t : pool) { if (favs.size() >= 8) break; if (used.add(t.id) && !t.kind.equals(BUTTON)) favs.add(t); }
    }

    // ------------------------------------------------------------------ live state helpers (used by the UI)
    public static boolean isOn(Ha ha, Tile t) {
        String s = ha.stateOf(t.id);
        switch (t.kind) {
            case COVER: return s.equals("open") || s.equals("opening");
            case LOCK: return s.equals("locked");
            case CLIMATE: case HEATER: return !s.equals("off") && !s.equals("unavailable");
            case ALARM: return s.startsWith("armed");
            case VACUUM: return s.equals("cleaning");
            default: return s.equals("on") || s.equals("open");
        }
    }
    public static boolean unavailable(Ha ha, Tile t) { String s = ha.stateOf(t.id); return s.equals("unavailable") || s.equals("unknown") && !t.kind.equals(SCENE) && !t.kind.equals(BUTTON); }

    /** lights on in a room / in the home */
    public int lightsOn(Ha ha, Room r) {
        int n = 0;
        for (Tile t : r == null ? new ArrayList<>(tiles.values()) : r.tiles)
            if ((t.kind.equals(LIGHT) || t.kind.equals(DIMMER)) && "on".equals(ha.stateOf(t.id))) n++;
        return n;
    }
    public static String firstValue(Ha ha, List<String> ids) {
        for (String id : ids) { String v = ha.stateOf(id); if (!v.equals("unavailable") && !v.equals("unknown")) return v; }
        return "";
    }
    /** open doors/windows/garage (on) */
    public List<String> openNow(Ha ha) { List<String> l = new ArrayList<>(); for (String id : openings) if ("on".equals(ha.stateOf(id))) l.add(id); return l; }
    public List<String> alarmsNow(Ha ha) { List<String> l = new ArrayList<>(); for (String id : safety) if ("on".equals(ha.stateOf(id))) l.add(id); return l; }

    /** the layout as JSON for the Installer editor (GET /api/dashboard) */
    public JSONObject toJson(Ha ha) {
        JSONObject o = new JSONObject();
        try {
            JSONArray rs = new JSONArray();
            for (Room r : rooms) {
                JSONArray ts = new JSONArray();
                for (Tile t : r.tiles) ts.put(new JSONObject().put("id", t.id).put("kind", t.kind).put("name", t.name));
                rs.put(new JSONObject().put("id", r.id).put("name", r.name).put("tiles", ts));
            }
            JSONArray fv = new JSONArray(); for (Tile t : favs) fv.put(t.id);
            JSONArray sc = new JSONArray(); for (Tile t : scenes) sc.put(new JSONObject().put("id", t.id).put("name", t.name).put("area", t.area));
            o.put("rooms", rs).put("favs", fv).put("scenes", sc).put("area", mine == null ? "" : mine.id).put("weather", weather == null ? "" : weather);
        } catch (Exception ignored) { }
        return o;
    }
}
