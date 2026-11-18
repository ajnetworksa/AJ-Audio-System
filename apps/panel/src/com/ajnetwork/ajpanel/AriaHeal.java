package com.ajnetwork.ajpanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Self-healing for Music Assistant's AriaCast Receiver plugin (the way phones/panels send "any app" sound).
 *
 * The plugin accepts one sender and never checks whether that sender is still alive. A phone or panel that
 * vanished (app killed, Wi-Fi drop, reboot while sharing) leaves a dead connection behind, and everyone else is
 * refused with HTTP 403 until the plugin is reloaded. Also, right after a reload/restart the port is closed for a
 * few seconds. This class:
 *   - retries the connection while the plugin (re)starts,
 *   - on 403 checks whether anybody really plays the AriaCast sound; if nobody does, the connection is a leftover
 *     and the plugin is reloaded (config/providers/reload, needs an admin token: "repair_token" that the Installer
 *     gives the panel; else the panel's own token is tried),
 *   - offers the same repair to phones over the panel's LAN API (/api/repair_aria), so a phone whose user isn't
 *     admin is healed too. Reloads are rate-limited (one per 45 s).
 */
final class AriaHeal {
    /** AJ Cast (our plugin in AJ Music Assistant, port 12899) is preferred; the official AriaCast Receiver (12889) otherwise */
    static volatile int PORT = 12889;
    private static long lastRepair;

    /** instance id of the AriaCast Receiver plugin, or an explanation */
    static String instance(Ma ma) throws Exception {
        Object provs = ma.cmd("providers", new JSONObject());
        if (provs instanceof JSONArray) {
            JSONArray a = (JSONArray) provs;
            for (String dom : new String[]{"ajcast", "ariacast_receiver"})
                for (int i = 0; i < a.length(); i++) {
                    JSONObject p = a.optJSONObject(i);
                    if (p != null && dom.equals(p.optString("domain"))) { PORT = dom.equals("ajcast") ? 12899 : 12889; return p.optString("instance_id"); }
                }
        }
        throw new IllegalStateException("the music server needs the \"AJ Cast\" plugin (AJ Music Assistant) or the official \"AriaCast Receiver\" (Music Assistant → Settings → Plugins → Add a plugin)");
    }

    static boolean busy(Exception e) { return e.getMessage() != null && e.getMessage().contains("still connected"); }

    /** name of a player that really plays the AriaCast sound right now, or null */
    static String activeUser(Ma ma, String inst) {
        try {
            Object all = ma.cmd("players/all", new JSONObject());
            if (!(all instanceof JSONArray)) return null;
            JSONArray a = (JSONArray) all;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p == null) continue;
                if (inst.equals(p.optString("active_source")) && "playing".equals(p.optString("playback_state")))
                    return p.optString("display_name", p.optString("name", "another device"));
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Reload the plugin if its sender slot is held by a dead connection.
     * @return "" when repaired, "busy:<name>" when somebody really shares, or a reason why it could not repair
     */
    static synchronized String repair(Cfg cfg, Ma ma, String inst) {
        String user = activeUser(ma, inst);
        if (user != null) return "busy:" + user;
        long now = System.currentTimeMillis();
        if (now - lastRepair < 45_000) return "";             // just repaired – give it time
        String[] tokens = cfg.repairToken().isEmpty() ? new String[]{cfg.token()} : new String[]{cfg.repairToken(), cfg.token()};
        Exception last = null;
        for (String t : tokens) {
            try {
                new Ma(cfg.maBase(), t).cmd("config/providers/reload", new JSONObject().put("instance_id", inst));
                lastRepair = now;
                Log.i("aria repair: sharing plugin reloaded (a dead sender connection blocked it)");
                return "";
            } catch (Exception e) { last = e; }
        }
        Log.i("aria repair: reload not allowed – " + (last == null ? "" : last.getMessage()));
        return "no admin right to reload the AriaCast Receiver";
    }

    /** connect /audio; waits for a restarting plugin and repairs a blocked one */
    static AriaWs connectAudio(Cfg cfg, Ma ma, String inst, Runnable repairing) throws Exception {
        Exception last = null;
        boolean repaired = false;
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                return new AriaWs(cfg.host(), PORT, "/audio", s -> Log.i("share audio: " + s));
            } catch (IOException e) {
                last = e;
                if (busy(e)) {
                    if (repaired) { Thread.sleep(1500); continue; }   // reload in progress
                    String r = repair(cfg, ma, inst);
                    if (r.startsWith("busy:")) throw new IllegalStateException(r.substring(5) + " is already sharing its sound – only one at a time. Stop it there first.");
                    if (!r.isEmpty()) throw new IllegalStateException("The AriaCast Receiver is blocked by an old connection and this panel may not reload it (" + r + "). In Music Assistant: Settings → Plugins → AriaCast Receiver → ⋮ → Reload.");
                    repaired = true;
                    if (repairing != null) repairing.run();
                    Thread.sleep(3000);
                } else {
                    Thread.sleep(1500);                         // plugin (re)starting: port closed for a moment
                }
            }
        }
        throw last;
    }
}
