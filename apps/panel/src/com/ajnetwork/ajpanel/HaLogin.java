package com.ajnetwork.ajpanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Panel sign-in to Home Assistant with a username + password (an HA user per panel or per home), the same way HA's
 * own apps sign in: login flow → authorization code → tokens. The panel then makes itself a long-lived token
 * ("AJ Panel · <name> · <id>") and keeps ONLY that; the password is never stored or logged, the short-lived
 * session is revoked, older AJ Panel tokens of this panel are deleted. What the panel may show is then decided in
 * Home Assistant by the user it signed in as (labels, see Dash) – change it in HA without touching the panel.
 */
public final class HaLogin {
    private HaLogin() { }
    /** HA treats this as the app's identity (must be a URL; redirect on the same host = no lookup needed) */
    static final String CLIENT = "https://ajnetwork.app/ajpanel/";

    public static final class Result { public String token, user, error; }

    /** blocking; base = http://host:8123 */
    public static Result login(String base, String user, String pass, String panelName, String hostId) {
        Result r = new Result(); r.user = user;
        base = base.trim(); if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.startsWith("http")) base = "http://" + base;
        try {
            JSONObject f = json("POST", base + "/auth/login_flow", new JSONObject().put("client_id", CLIENT)
                    .put("handler", new JSONArray().put("homeassistant").put(JSONObject.NULL)).put("redirect_uri", CLIENT).toString(), "application/json");
            String flow = f.optString("flow_id");
            if (flow.isEmpty()) { r.error = "Home Assistant refused the login (" + f.optString("message", f.toString()) + ")"; return r; }
            JSONObject s = json("POST", base + "/auth/login_flow/" + flow, new JSONObject().put("username", user).put("password", pass).put("client_id", CLIENT).toString(), "application/json");
            if ("form".equals(s.optString("type"))) {
                JSONObject e = s.optJSONObject("errors");
                r.error = "mfa".equals(s.optString("step_id")) ? "this user has two-step verification – use a user without it, or a token"
                        : e != null && "invalid_auth".equals(e.optString("base")) ? "wrong username or password" : "login refused (" + (e == null ? s.optString("step_id") : e.toString()) + ")";
                return r;
            }
            String code = s.optString("result");
            if (!"create_entry".equals(s.optString("type")) || code.isEmpty()) { r.error = "login refused (" + s.optString("type") + ")"; return r; }
            JSONObject t = json("POST", base + "/auth/token", "grant_type=authorization_code&code=" + URLEncoder.encode(code, "UTF-8")
                    + "&client_id=" + URLEncoder.encode(CLIENT, "UTF-8"), "application/x-www-form-urlencoded");
            String access = t.optString("access_token"), refresh = t.optString("refresh_token");
            if (access.isEmpty()) { r.error = "no token from Home Assistant"; return r; }
            String tag = "· " + (hostId.length() > 6 ? hostId.substring(hostId.length() - 6) : hostId) + " ·";
            String name = "AJ Panel · " + panelName + " " + tag + " " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
            r.token = longLived(Ha.wsUrl(base), access, name, tag);
            if (r.token == null) r.error = "Home Assistant did not give a long-lived token";
            // the short session is not needed any more
            try { json("POST", base + "/auth/revoke", "token=" + URLEncoder.encode(refresh, "UTF-8"), "application/x-www-form-urlencoded"); } catch (Exception ignored) { }
        } catch (Exception e) { r.error = "cannot reach Home Assistant at " + base + " (" + e.getMessage() + ")"; }
        return r;
    }

    /** over the WebSocket: make the long-lived token, delete this panel's older ones */
    private static String longLived(String ws, String access, String name, String tag) throws Exception {
        try (Ws w = new Ws(ws, 10_000)) {
            w.read();   // auth_required
            w.sendText(new JSONObject().put("type", "auth").put("access_token", access).toString());
            JSONObject ok = new JSONObject(w.read());
            if (!"auth_ok".equals(ok.optString("type"))) return null;
            w.sendText(new JSONObject().put("id", 1).put("type", "auth/long_lived_access_token").put("client_name", name).put("lifespan", 3650).toString());
            String token = null;
            for (int i = 0; i < 5 && token == null; i++) {
                JSONObject m = new JSONObject(w.read());
                if (m.optInt("id") == 1) { if (!m.optBoolean("success")) return null; token = m.optString("result"); }
            }
            if (token == null) return null;
            // tidy up: older long-lived tokens of THIS panel (same tag), keep the new one (newest)
            try {
                w.sendText(new JSONObject().put("id", 2).put("type", "auth/refresh_tokens").toString());
                JSONArray list = null;
                for (int i = 0; i < 5 && list == null; i++) { JSONObject m = new JSONObject(w.read()); if (m.optInt("id") == 2) list = m.optJSONArray("result"); }
                int id = 3;
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject t = list.optJSONObject(i);
                    String cn = t == null ? "" : t.optString("client_name");
                    if (cn.contains(tag) && !cn.equals(name) && "long_lived_access_token".equals(t.optString("type")))
                        w.sendText(new JSONObject().put("id", id++).put("type", "auth/delete_refresh_token").put("refresh_token_id", t.optString("id")).toString());
                }
                if (id > 3) Log.i("ha login: removed " + (id - 3) + " older AJ Panel token(s) of this panel");
            } catch (Exception ignored) { }
            return token;
        }
    }

    private static JSONObject json(String method, String url, String body, String type) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(15000);
        c.setRequestMethod(method); c.setDoOutput(body != null);
        c.setRequestProperty("Content-Type", type);
        if (body != null) try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) try (InputStream i = in) { byte[] buf = new byte[8192]; int n; while ((n = i.read(buf)) > 0) b.write(buf, 0, n); }
        String s = b.toString("UTF-8").trim();
        if (s.startsWith("{")) return new JSONObject(s);
        return new JSONObject().put("http", code).put("message", s.length() > 120 ? s.substring(0, 120) : s);
    }
}
