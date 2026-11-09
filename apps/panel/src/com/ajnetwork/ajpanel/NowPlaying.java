package com.ajnetwork.ajpanel;

import org.json.JSONArray;
import org.json.JSONObject;

/** What this panel is playing, derived from Music Assistant state. Immutable snapshot. */
public class NowPlaying {
    public boolean serverOk;        // MA reachable + token ok
    public String error = "";
    public boolean registered;      // MA knows this panel's player
    public String playerId = "";
    public String playerName = "";
    public String state = "idle";   // playing | paused | idle
    public String title = "", artist = "", album = "", imageUrl = "";
    public double duration, elapsed;
    public String groupName = "";   // "" = not grouped
    public int groupSize = 1;
    public String groupId = "";     // player to send play/pause/next to (leader / group player)
    public boolean isLeader, staticMember;
    public String user = "";        // who started playback (needs ajsnap server extension)
    public String source = "";      // e.g. AirPlay, phone, Music Assistant
    public int volume = -1;
    public boolean muted;

    public boolean active() { return registered && ("playing".equals(state) || "paused".equals(state)) && !title.isEmpty(); }

    public String key() { return state + "|" + title + "|" + artist + "|" + imageUrl + "|" + groupName + "|" + groupSize + "|" + user + "|" + muted + "|" + volume; }

    private static String s(JSONObject o, String k) { if (o == null || o.isNull(k)) return ""; return o.optString(k, ""); }

    /** Build from MA. Blocking; call from background thread. */
    public static NowPlaying fetch(Ma ma, String myId) {
        NowPlaying n = new NowPlaying();
        n.playerId = myId;
        try {
            JSONObject me = ma.player(myId);
            n.serverOk = true;
            if (me == null || !me.optBoolean("available", true)) { n.registered = false; return n; }
            n.registered = true;
            n.playerName = s(me, "name");
            n.volume = me.optInt("volume_level", -1);
            n.muted = me.optBoolean("volume_muted", false);

            String syncedTo = s(me, "synced_to");
            String activeGroup = s(me, "active_group");
            JSONArray members = me.optJSONArray("group_members");
            JSONObject lead = me;
            if (!activeGroup.isEmpty()) {
                JSONObject g = ma.player(activeGroup);
                if (g != null) {
                    lead = g;
                    n.groupName = s(g, "name");
                    JSONArray gm = g.optJSONArray("group_members");
                    n.groupSize = gm == null ? 1 : gm.length();
                    JSONArray st = g.optJSONArray("static_group_members");
                    if (st != null) for (int i = 0; i < st.length(); i++) if (myId.equals(st.optString(i))) n.staticMember = true;
                }
                n.groupId = activeGroup;
            } else if (!syncedTo.isEmpty()) {
                JSONObject l = ma.player(syncedTo);
                if (l != null) {
                    lead = l;
                    JSONArray gm = l.optJSONArray("group_members");
                    n.groupSize = gm == null ? 2 : gm.length();
                    n.groupName = s(l, "name") + " +" + (n.groupSize - 1);
                }
                n.groupId = syncedTo;
            } else {
                n.groupId = myId;
                if (members != null && members.length() > 1) {
                    n.isLeader = true;
                    n.groupSize = members.length();
                    n.groupName = n.playerName + " +" + (n.groupSize - 1);
                }
            }

            n.state = s(lead, "playback_state");
            if (n.state.isEmpty()) n.state = s(me, "playback_state");
            JSONObject media = lead.optJSONObject("current_media");
            if (media == null) media = me.optJSONObject("current_media");
            if (media != null) {
                n.title = s(media, "title");
                n.artist = s(media, "artist");
                n.album = s(media, "album");
                n.imageUrl = s(media, "image_url");
                n.duration = media.optDouble("duration", 0);
                n.elapsed = media.optDouble("elapsed_time", lead.optDouble("elapsed_time", 0));
                if (n.title.isEmpty()) n.title = s(media, "uri");
            }
            String src = s(lead, "active_source");
            if (src.contains("airplay")) n.source = "AirPlay";
            else if (src.contains("ariacast")) n.source = "AriaCast";
            else if (n.title.contains("live.aac") || n.title.startsWith("http")) n.source = "phone";

            // who is playing: AJ server extension (falls back silently on stock MA)
            JSONObject aj = ma.ajNowPlaying(myId);
            if (aj != null) {
                n.user = aj.optString("user_display_name", aj.optString("username", ""));
                if (n.user.equals("null")) n.user = "";
                String asrc = aj.optString("source", "");
                if (!asrc.isEmpty() && !asrc.equals("null")) n.source = asrc;
            }
            if (n.title.startsWith("http") && n.title.contains("live.aac")) n.title = "Phone audio";
        } catch (Ma.ApiException e) {
            n.serverOk = e.code != 401 && e.code != 403;
            n.error = e.code == 401 || e.code == 403 ? "login needed" : e.getMessage();
        } catch (Exception e) {
            n.serverOk = false;
            n.error = String.valueOf(e.getMessage());
        }
        return n;
    }
}
