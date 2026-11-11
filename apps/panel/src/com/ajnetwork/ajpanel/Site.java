package com.ajnetwork.ajpanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Snapshot of all Music Assistant players: saved groups and rooms (for tiles, "Play this on…", breaks). */
public class Site {
    public static class Room {
        public String id, name, state = "idle", title = "", syncedTo = "", activeGroup = "";
        public boolean available = true;
        public List<String> members = new ArrayList<>();
    }
    public static class Group {
        public String id, name, state = "idle", title = "", artist = "";
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
            JSONObject m = p.optJSONObject("current_media");
            if ("group".equals(s(p, "type"))) {
                Group g = new Group();
                g.id = s(p, "player_id"); g.name = s(p, "name"); g.state = s(p, "playback_state");
                g.title = s(m, "title"); g.artist = s(m, "artist");
                g.members = list(p.optJSONArray("group_members"));
                site.groups.add(g);
            } else {
                Room x = new Room();
                x.id = s(p, "player_id"); x.name = s(p, "name"); x.state = s(p, "playback_state");
                x.title = s(m, "title"); x.syncedTo = s(p, "synced_to"); x.activeGroup = s(p, "active_group");
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
    /** is this room currently part of that group (session member)? */
    public boolean inGroup(String roomId, Group g) {
        Room x = room(roomId);
        return g.members.contains(roomId) && (x == null || g.id.equals(x.activeGroup) || !g.playing());
    }
}
