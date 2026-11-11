package com.ajnetwork.ajpanel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Take a break from the group" – never permanent (D-016).
 * Saved groups are MA dynamic sync groups: members are a preset, removable per session.
 *  start: remove this room from the group's session (set_members remove)
 *  while on break: if a new session pulls the room back in, remove it again
 *  end: time reached / own music stopped for 5 min / group music ended -> rejoin if the group still plays
 * State is stored in prefs, so a panel reboot keeps the break.
 */
public class Breaks {
    public static final String TIME = "time", MINE = "mine", GROUP_END = "groupend";
    private static final long MAX_MS = 12 * 3600_000L, OWN_IDLE_MS = 5 * 60_000L;

    private final SharedPreferences p;
    private final Ma ma;
    private final Cfg cfg;
    private long ownIdleSince = 0;

    public Breaks(Context c, Ma ma, Cfg cfg) {
        p = c.getSharedPreferences("ajpanel_break", Context.MODE_PRIVATE);
        this.ma = ma; this.cfg = cfg;
    }

    public boolean active() { return !p.getString("group", "").isEmpty(); }
    public String groupId() { return p.getString("group", ""); }
    public String mode() { return p.getString("mode", TIME); }
    public long until() { return p.getLong("until", 0); }
    public boolean quiet() { return p.getBoolean("quiet", true); }

    /** start a break; untilMs = absolute time for TIME mode (0 for the other modes) */
    public void start(String groupId, String mode, long untilMs, boolean quiet) throws Exception {
        long now = System.currentTimeMillis();
        p.edit().putString("group", groupId).putString("mode", mode).putLong("until", untilMs)
                .putLong("started", now).putBoolean("quiet", quiet).putBoolean("own", false).apply();
        ownIdleSince = 0;
        leave(groupId);
        Log.i("break: left " + groupId + " mode=" + mode + (untilMs > 0 ? " until " + new java.util.Date(untilMs) : ""));
    }

    public void extend(long ms) {
        long base = Math.max(System.currentTimeMillis(), until());
        p.edit().putString("mode", TIME).putLong("until", base + ms).apply();
        Log.i("break: extended to " + new java.util.Date(base + ms));
    }

    /** end the break now; rejoin when the group still plays */
    public void end(Site site, String why) {
        String g = groupId();
        p.edit().clear().apply();
        Log.i("break: end (" + why + ")");
        try {
            if (site != null && playing(site, g)) {
                ma.setMembers(g, new JSONArray().put(cfg.maPlayerId()), null);
                Log.i("break: rejoined " + site.nameOf(g));
            }
        } catch (Exception e) { Log.e("break: rejoin", e); }
    }

    private void leave(String g) throws Exception {
        ma.setMembers(g, null, new JSONArray().put(cfg.maPlayerId()));
    }

    /** called from the poll loop */
    public void update(Site site, NowPlaying n) {
        if (!active() || site == null) return;
        long now = System.currentTimeMillis();
        String g = groupId();
        if (site.group(g) == null && site.room(g) == null) { p.edit().clear().apply(); Log.i("break: group gone"); return; }
        if (now - p.getLong("started", now) > MAX_MS) { end(site, "max 12 h"); return; }
        switch (mode()) {
            case TIME:
                if (now >= until()) { end(site, "time is up"); return; }
                break;
            case MINE: {
                boolean myOwn = n != null && n.groupName.isEmpty() && "playing".equals(n.state);
                if (myOwn) { p.edit().putBoolean("own", true).apply(); ownIdleSince = 0; }
                else if (p.getBoolean("own", false)) {
                    if (ownIdleSince == 0) ownIdleSince = now;
                    if (now - ownIdleSince > OWN_IDLE_MS) { end(site, "own music stopped"); return; }
                }
                break;
            }
            case GROUP_END:
                if (!playing(site, g)) { p.edit().clear().apply(); Log.i("break: group music ended – next session includes this room"); return; }
                break;
        }
        // a new session pulled us back in -> step out again
        if (playing(site, g) && member(site, g)) {
            try { leave(g); Log.i("break: stepped out of " + site.nameOf(g) + " again"); } catch (Exception e) { Log.e("break: leave", e); }
        }
    }

    /** a saved group, or a room that leads a temporary group */
    private static boolean playing(Site site, String id) {
        Site.Group grp = site.group(id);
        if (grp != null) return grp.playing();
        Site.Room r = site.room(id);
        return r != null && ("playing".equals(r.state) || "paused".equals(r.state));
    }
    private boolean member(Site site, String id) {
        Site.Group grp = site.group(id);
        if (grp != null) return site.inGroup(cfg.maPlayerId(), grp);
        Site.Room me = site.room(cfg.maPlayerId());
        return me != null && id.equals(me.syncedTo);
    }

    /** "back in 42 min" / "until your music stops" */
    public String describe() {
        switch (mode()) {
            case MINE: return "Rejoins 5 min after your own music stops";
            case GROUP_END: return "Rejoins when the group plays again next time";
            default:
                long min = Math.max(0, (until() - System.currentTimeMillis() + 59_999) / 60_000);
                String at = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(until()));
                return min >= 60 ? "Back at " + at + " (in " + (min / 60) + " h " + (min % 60) + " min)" : "Back in " + min + " min (at " + at + ")";
        }
    }
}
