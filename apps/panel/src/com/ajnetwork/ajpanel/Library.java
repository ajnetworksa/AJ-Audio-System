package com.ajnetwork.ajpanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** The Music Assistant library as the panel shows it (Music page): favourites, playlists, albums, artists, radio, search. */
public final class Library {
    private Library() { }

    public static class Item {
        public String type = "", name = "", sub = "", uri = "", image = "";
    }

    public static final String RECENT = "recent", PLAYLISTS = "playlists", ALBUMS = "albums", ARTISTS = "artists",
            RADIO = "radios", SONGS = "tracks";

    /** blocking – call from a background thread */
    public static List<Item> load(Ma ma, String tab) throws Exception {
        Object r;
        if (RECENT.equals(tab)) {
            r = ma.cmd("music/recently_played_items", new JSONObject().put("limit", 40));
        } else {
            JSONObject a = new JSONObject().put("limit", 120).put("order_by", SONGS.equals(tab) ? "timestamp_added_desc" : "sort_name");
            if (SONGS.equals(tab)) a.put("favorite", true);
            r = ma.cmd("music/" + tab + "/library_items", a);
            // nothing marked as favourite yet → just the newest songs
            if (SONGS.equals(tab) && r instanceof JSONArray && ((JSONArray) r).length() == 0)
                r = ma.cmd("music/tracks/library_items", new JSONObject().put("limit", 120).put("order_by", "timestamp_added_desc"));
        }
        return parse(r instanceof JSONArray ? (JSONArray) r : r instanceof JSONObject ? ((JSONObject) r).optJSONArray("items") : null);
    }

    /** search everything; returns sections in display order */
    public static List<List<Item>> search(Ma ma, String q) throws Exception {
        Object r = ma.cmd("music/search", new JSONObject().put("search_query", q).put("limit", 12)
                .put("media_types", new JSONArray().put("track").put("album").put("artist").put("playlist").put("radio")));
        List<List<Item>> out = new ArrayList<>();
        if (!(r instanceof JSONObject)) return out;
        JSONObject o = (JSONObject) r;
        for (String k : new String[]{"tracks", "artists", "albums", "playlists", "radio"}) out.add(parse(o.optJSONArray(k)));
        return out;
    }

    static List<Item> parse(JSONArray a) {
        List<Item> l = new ArrayList<>();
        if (a == null) return l;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optString("uri", "").isEmpty()) continue;
            Item it = new Item();
            it.type = o.optString("media_type", "");
            it.name = o.optString("name", "");
            it.uri = o.optString("uri");
            it.image = imageUrl(o);
            JSONArray ar = o.optJSONArray("artists");
            StringBuilder sb = new StringBuilder();
            if (ar != null) for (int k = 0; k < ar.length() && k < 2; k++) {
                JSONObject x = ar.optJSONObject(k);
                if (x != null) sb.append(sb.length() > 0 ? ", " : "").append(x.optString("name"));
            }
            if (sb.length() == 0 && !o.isNull("owner")) sb.append(o.optString("owner", ""));
            if (sb.length() == 0 && "radio".equals(it.type)) sb.append("Radio");
            if (sb.length() == 0 && "playlist".equals(it.type)) sb.append("Playlist");
            it.sub = sb.toString();
            l.add(it);
        }
        return l;
    }

    /** image of a media item / item mapping → URL the panel can load (relative URLs go to the MA server) */
    static String imageUrl(JSONObject o) {
        JSONObject img = o.optJSONObject("image");
        if (img == null) {
            JSONObject md = o.optJSONObject("metadata");
            JSONArray imgs = md == null ? null : md.optJSONArray("images");
            if (imgs != null) for (int i = 0; i < imgs.length(); i++) {
                JSONObject x = imgs.optJSONObject(i);
                if (x != null && "thumb".equals(x.optString("type"))) { img = x; break; }
            }
            if (img == null && imgs != null && imgs.length() > 0) img = imgs.optJSONObject(0);
        }
        if (img == null) return "";
        String proxy = img.isNull("proxy_id") ? "" : img.optString("proxy_id", "");
        if (!proxy.isEmpty()) return "/imageproxy/" + proxy + "?size=256";
        String path = img.optString("path", "");
        if (path.isEmpty()) return "";
        if (img.optBoolean("remotely_accessible", false) && path.startsWith("http")) return path;
        try {
            return "/imageproxy?path=" + URLEncoder.encode(path, "UTF-8") + "&provider=" + URLEncoder.encode(img.optString("provider", ""), "UTF-8") + "&size=256";
        } catch (Exception e) { return ""; }
    }
}
