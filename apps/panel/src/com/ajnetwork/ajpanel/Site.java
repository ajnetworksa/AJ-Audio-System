package com.ajnetwork.ajpanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Snapshot of all Music Assistant players: saved groups and rooms (for tiles, "Play this on…", breaks). */
public class Site {
    public static class Room {
        public String id, name, state = "idle", title = "", artist = "", image = "", syncedTo = "", activeGroup = "";
        public boolean available = true, muted;
        public int volume = -1;
        public boolean playing() { return "playing".equals(state) || "paused".equals(state); }
        public List<String> members = new ArrayList<>();
    }
    public static class Group {
        public String id, name, state = "idle", title = "", artist = "", image = "";
        public int volume = -1;
        public List<String> members = new ArrayList<>();
        public boolean playing() { return "playing".equals(state) || "paused".equals(state); }
    }

    public final List<Group> groups = new ArrayList<>();
    public final List<Room> rooms = new ArrayList<>();

    private static String s(JSONObject o, String k) { return o == null || o.isNull(k) ? "" : o.optString(k, ""); }
    private static List<String> list(JSONArray a) {
        List<String> r = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) r.add(a.optString(i));
        return r;
    }

    public static Site fetch(Ma ma) throws Exception {
        Site site = new Site();
        Object r = ma.cmd("players/all", new JSONObject().put("return_unavailable", true));
        if (!(r instanceof JSONArray)) return site;
        JSONArray all = (JSONArray) r;
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.optJSONObject(i);
            if (p == null || p.optBoolean("hide_in_ui", false) || !p.optBoolean("enabled", true)) continue;
            String pid = s(p, "player_id"), pname = s(p, "name");
            // Music Assistant's own AirPlay receivers ("Demo | Music Assistant") are found again by its AirPlay player
            // provider – a loop, never a real room
            if (pname.endsWith("| Music Assistant")) continue;
            // switched-off TVs, old Bluetooth speakers …: only our own panels are listed while offline
            if (!p.optBoolean("available", true) && !pid.startsWith("ma_ajpanel_") && !"group".equals(s(p, "type"))) continue;
            JSONObject m = p.optJSONObject("current_media");
            if ("group".equals(s(p, "type"))) {
                Group g = new Group();
                g.id = s(p, "player_id"); g.name = s(p, "name"); g.state = s(p, "playback_state");
                g.title = s(m, "title"); g.artist = s(m, "artist"); g.image = s(m, "image_url");
                g.volume = p.optInt("volume_level", -1);
                g.members = list(p.optJSONArray("group_members"));
                site.groups.add(g);
            } else {
                Room x = new Room();
                x.id = s(p, "player_id"); x.name = s(p, "name"); x.state = s(p, "playback_state");
                x.title = s(m, "title"); x.artist = s(m, "artist"); x.image = s(m, "image_url");
                x.volume = p.optInt("volume_level", -1); x.muted = p.optBoolean("volume_muted", false);
                x.syncedTo = s(p, "synced_to"); x.activeGroup = s(p, "active_group");
                x.available = p.optBoolean("available", true);
                x.members = list(p.optJSONArray("group_members"));
                site.rooms.add(x);
            }
        }
        Collections.sort(site.groups, (a, b) -> a.name.compareToIgnoreCase(b.name));
        Collections.sort(site.rooms, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return site;
    }

    public Group group(String id) { for (Group g : groups) if (g.id.equals(id)) return g; return null; }
    public Room room(String id) { for (Room x : rooms) if (x.id.equals(id)) return x; return null; }
    public String nameOf(String id) {
        Room x = room(id); if (x != null) return x.name;
        Group g = group(id); return g != null ? g.name : id;
    }
    /** the player that leads this room's music right now: active saved group, sync leader, or the room itself */
    public String zoneOf(Room r) {
        if (!r.activeGroup.isEmpty()) { Group g = group(r.activeGroup); if (g != null && g.members.contains(r.id)) return g.id; }
        if (!r.syncedTo.isEmpty() && room(r.syncedTo) != null) return r.syncedTo;
        return r.id;
    }
    /** rooms that play together right now, keyed by their zone leader (order = room order) */
    public java.util.LinkedHashMap<String, List<Room>> zones() {
        java.util.LinkedHashMap<String, List<Room>> z = new java.util.LinkedHashMap<>();
        for (Room r : rooms) {
            String k = zoneOf(r);
            List<Room> l = z.get(k); if (l == null) { l = new ArrayList<>(); z.put(k, l); }
            l.add(r);
        }
        return z;
    }
    /** rooms in the zone led by id (saved group members / leader + followers) */
    public List<String> zoneMembers(String id) {
        List<String> out = new ArrayList<>();
        for (Room r : rooms) if (zoneOf(r).equals(id)) out.add(r.id);
        if (out.isEmpty()) out.add(id);
        return out;
    }

    /** is this room currently part of that group (session member)? */
    public boolean inGroup(String roomId, Group g) {
        Room x = room(roomId);
        return g.members.contains(roomId) && (x == null || g.id.equals(x.activeGroup) || !g.playing());
    }
}
