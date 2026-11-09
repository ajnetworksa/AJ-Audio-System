package com.ajnetwork.ajpanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Music Assistant HTTP API client (POST /api, Bearer token). Call from a background thread. */
public class Ma {
    public volatile String base;     // e.g. http://192.168.8.5:8095
    public volatile String token;

    public Ma(String base, String token) { this.base = base; this.token = token; }

    public static class ApiException extends Exception {
        public final int code;
        ApiException(int code, String msg) { super(code + ": " + msg); this.code = code; }
    }

    private String http(String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(20000);
        c.setRequestMethod(body == null ? "GET" : "POST");
        c.setRequestProperty("Content-Type", "application/json");
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close(); }
        String s = b.toString("UTF-8");
        if (code >= 400) throw new ApiException(code, s.isEmpty() ? c.getResponseMessage() : s);
        return s;
    }

    /** run an API command; returns parsed JSON (JSONObject, JSONArray, or primitive) */
    public Object cmd(String command, JSONObject args) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message_id", String.valueOf(System.nanoTime()));
        body.put("command", command);
        body.put("args", args == null ? new JSONObject() : args);
        String r = http("/api", body.toString()).trim();
        if (r.isEmpty() || r.equals("null")) return null;
        if (r.startsWith("[")) return new JSONArray(r);
        if (r.startsWith("{")) return new JSONObject(r);
        return r;
    }

    /** login with Music Assistant username/password; returns token */
    public String login(String user, String pass, String deviceName) throws Exception {
        JSONObject a = new JSONObject().put("username", user).put("password", pass).put("device_name", deviceName);
        Object r = cmd("auth/login", a);
        String t = null;
        if (r instanceof JSONObject) {
            JSONObject o = (JSONObject) r;
            t = o.optString("token", o.optString("access_token", null));
            if (t == null && o.has("result")) t = o.optJSONObject("result") != null ? o.getJSONObject("result").optString("token", null) : null;
        } else if (r instanceof String) t = ((String) r).replace("\"", "");
        if (t == null || t.isEmpty()) throw new Exception("login returned no token: " + r);
        token = t;
        return t;
    }

    public JSONArray players() throws Exception {
        Object r = cmd("players/all", new JSONObject().put("return_unavailable", false));
        return r instanceof JSONArray ? (JSONArray) r : new JSONArray();
    }

    public void setMembers(String leader, JSONArray add, JSONArray remove) throws Exception {
        JSONObject a = new JSONObject().put("target_player", leader);
        if (add != null) a.put("player_ids_to_add", add);
        if (remove != null) a.put("player_ids_to_remove", remove);
        cmd("players/cmd/set_members", a);
    }
    public void ungroup(String player) throws Exception { cmd("players/cmd/ungroup", new JSONObject().put("player_id", player)); }
    public void volume(String player, int v) throws Exception { cmd("players/cmd/volume_set", new JSONObject().put("player_id", player).put("volume_level", v)); }
    public void groupVolume(String player, int v) throws Exception { cmd("players/cmd/group_volume", new JSONObject().put("player_id", player).put("volume_level", v)); }
    public void simple(String c, String player) throws Exception { cmd("players/cmd/" + c, new JSONObject().put("player_id", player)); }
    public void playUrl(String queueId, String url) throws Exception {
        cmd("player_queues/play_media", new JSONObject().put("queue_id", queueId).put("media", url).put("option", "replace"));
    }
    public void stop(String player) throws Exception { simple("stop", player); }

    // ---- AJ Panel additions ----
    public JSONObject player(String id) throws Exception {
        Object r = cmd("players/get", new JSONObject().put("player_id", id));
        return r instanceof JSONObject ? (JSONObject) r : null;
    }
    public JSONObject queue(String id) throws Exception {
        Object r = cmd("player_queues/get", new JSONObject().put("queue_id", id));
        return r instanceof JSONObject ? (JSONObject) r : null;
    }
    public void mute(String player, boolean m) throws Exception { cmd("players/cmd/volume_mute", new JSONObject().put("player_id", player).put("muted", m)); }
    /** long-lived token for this panel (needs a logged-in session token first) */
    public String longToken(String name) throws Exception {
        Object r = cmd("auth/token/create", new JSONObject().put("name", name));
        String t = r == null ? null : r.toString().replace("\"", "").trim();
        if (r instanceof JSONObject) t = ((JSONObject) r).optString("token", null);
        if (t == null || t.isEmpty()) throw new Exception("no long-lived token: " + r);
        token = t; return t;
    }
    /** AJ server extension (ajsnap provider). Returns null if the server doesn't have it. */
    public JSONObject ajNowPlaying(String id) {
        try { Object r = cmd("ajsnap/now_playing", new JSONObject().put("player_id", id)); return r instanceof JSONObject ? (JSONObject) r : null; }
        catch (Exception e) { return null; }
    }
}
