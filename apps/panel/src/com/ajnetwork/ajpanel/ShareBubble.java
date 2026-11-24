package com.ajnetwork.ajpanel;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Floating "Share" button over other apps (YouTube, Spotify, a browser …): when another app plays on this panel,
 * a small button sits at the right edge; tap = pick a group or rooms right there, without leaving the app
 * (YouTube would stop if we switched to AJ Panel). While sharing it shows "Sharing" with Change / Stop.
 * Hidden while AJ Panel's own screen is open (it has its own Share banner). Needs "display over other apps"
 * (deploy_ajpanel.bat grants it) and Android 10+. Setting: share_bubble (default on).
 */
public class ShareBubble {
    private final PanelService svc;
    private final Cfg cfg;
    private final WindowManager wm;
    private TextView bubble;
    private View menu;
    private WindowManager.LayoutParams blp;
    private String group = "";
    private final Set<String> rooms = new LinkedHashSet<>();
    private String lastSig = "";

    ShareBubble(PanelService s, Cfg c) {
        svc = s; cfg = c;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
    }

    private int dp(float v) { return Ui.dp(svc, v); }

    private static int type() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** called about once a second on the main thread */
    void update() {
        boolean allowed = cfg.shareBubble() && Build.VERSION.SDK_INT >= 29 && Settings.canDrawOverlays(svc);
        MediaListener.Local l = allowed && !ShareService.running ? MediaListener.playing(svc) : null;
        boolean want = allowed && !MainActivity.visible && !HomeActivity.visible && (ShareService.running || l != null);
        if (!want) { remove(); return; }
        if (bubble == null) addBubble();
        String label = ShareService.running ? "● Sharing" : "Share";
        if (!label.equals(bubble.getText().toString())) {
            bubble.setText(label);
            bubble.setBackground(Ui.round(svc, ShareService.running ? 0xEE418331 : 0xEE6BBC46, 26));
        }
        String sig = ShareService.running + ShareService.sharingTo + (l == null ? "" : l.app);
        if (menu != null && !sig.equals(lastSig)) showMenu();   // state changed while the menu is open
        lastSig = sig;
    }

    void remove() {
        closeMenu();
        if (bubble != null) { try { wm.removeView(bubble); } catch (Exception ignored) { } bubble = null; }
    }

    private void addBubble() {
        bubble = Ui.text(svc, "Share", 16, 0xFF0B1220, true);
        bubble.setGravity(Gravity.CENTER);
        bubble.setPadding(dp(20), dp(12), dp(20), dp(12));
        bubble.setBackground(Ui.round(svc, 0xEE6BBC46, 26));
        bubble.setElevation(dp(6));
        blp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        blp.gravity = Gravity.END | Gravity.TOP;
        blp.x = dp(12);
        blp.y = cfg.shareBubbleY() < 0 ? dp(140) : cfg.shareBubbleY();
        // tap = open the picker, drag = move up/down (position is remembered)
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downY; int startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: downY = e.getRawY(); startY = blp.y; moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dy) > dp(8)) moved = true;
                        if (moved) { blp.y = Math.max(0, startY + dy); try { wm.updateViewLayout(bubble, blp); } catch (Exception ignored) { } }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) cfg.edit().putInt("share_bubble_y", blp.y).apply();
                        else if (menu == null) showMenu(); else closeMenu();
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(bubble, blp); } catch (Exception e) { Log.i("share bubble: " + e.getMessage()); bubble = null; }
    }

    private void closeMenu() {
        if (menu != null) { try { wm.removeView(menu); } catch (Exception ignored) { } menu = null; }
    }

    private void showMenu() {
        closeMenu();
        Site st = svc.site;
        String me = cfg.maPlayerId();
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(svc, 0xF50B1220, 22));
        box.setPadding(dp(20), dp(18), dp(20), dp(16));
        box.setElevation(dp(10));

        LinearLayout head = new LinearLayout(svc);
        head.setGravity(Gravity.CENTER_VERTICAL);
        MediaListener.Local l = ShareService.running ? null : MediaListener.playing(svc);
        String what = l == null || l.app.isEmpty() ? "this panel" : l.app;
        head.addView(Ui.text(svc, ShareService.running ? "Sharing" : "Play " + what + " in…", 19, 0xFFFFFFFF, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView x = Ui.text(svc, "✕", 20, 0xFFA1A1A8, false);
        x.setPadding(dp(12), dp(4), dp(4), dp(4));
        x.setOnClickListener(v -> closeMenu());
        head.addView(x);
        box.addView(head);

        if (ShareService.running) {
            TextView to = Ui.text(svc, "Playing in: " + ShareService.sharingTo, 15, 0xFFA1A1A8, false);
            to.setPadding(0, dp(8), 0, dp(14));
            box.addView(to);
            LinearLayout row = new LinearLayout(svc);
            TextView change = button("Change", false);
            change.setOnClickListener(v -> { ShareService.stop(svc); group = ""; rooms.clear(); });   // menu re-renders as the picker when sharing has stopped
            TextView stop = button("Stop", true);
            stop.setOnClickListener(v -> { ShareService.stop(svc); closeMenu(); });
            row.addView(change, new LinearLayout.LayoutParams(0, dp(48), 1));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(48), 1); sp.leftMargin = dp(10);
            row.addView(stop, sp);
            box.addView(row);
        } else if (st == null) {
            box.addView(Ui.text(svc, "No connection to the music server yet.", 15, 0xFFA1A1A8, false));
        } else {
            ScrollView sv = new ScrollView(svc);
            LinearLayout list = new LinearLayout(svc);
            list.setOrientation(LinearLayout.VERTICAL);
            sv.addView(list);
            TextView go = button("Pick rooms", true);
            if (!st.groups.isEmpty()) list.addView(label("Groups"));
            for (Site.Group g : st.groups) {
                TextView r = row(g.name + "  ·  " + g.members.size() + " rooms", false);
                r.setText("▶  " + g.name + "  ·  " + g.members.size() + " rooms");
                r.setOnClickListener(v -> start(g.id, new ArrayList<>(), g.name));   // one tap = go
                list.addView(r);
            }
            list.addView(label("Or rooms (this panel plays too)"));
            for (Site.Room rm : st.rooms) {
                if (rm.id.equals(me)) continue;
                TextView r = row(rm.name + (rm.available ? "" : "  ·  offline"), rooms.contains(rm.id));
                if (!rm.available) { r.setAlpha(0.4f); list.addView(r); continue; }
                r.setOnClickListener(v -> { if (!rooms.remove(rm.id)) rooms.add(rm.id); showMenu(); });
                list.addView(r);
            }
            int h = Math.min(dp(380), dp(60) * (st.groups.size() + st.rooms.size() + 2));
            box.addView(sv, new LinearLayout.LayoutParams(-1, h));
            go.setText(rooms.isEmpty() ? "Pick rooms" : "Play in " + (rooms.size() + 1) + " rooms");
            go.setAlpha(rooms.isEmpty() ? 0.5f : 1f);
            go.setOnClickListener(v -> {
                if (rooms.isEmpty()) return;
                List<String> names = new ArrayList<>();
                names.add(cfg.name());
                for (String id : rooms) names.add(st.nameOf(id));
                start("", new ArrayList<>(rooms), TextUtils.join(", ", names));
            });
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(50)); gp.topMargin = dp(12);
            box.addView(go, gp);
        }

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(dp(380), WindowManager.LayoutParams.WRAP_CONTENT, type(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.END | Gravity.TOP;
        p.x = dp(12);
        p.y = (blp == null ? dp(140) : blp.y) + dp(58);
        box.setOnTouchListener((v, e) -> { if (e.getAction() == MotionEvent.ACTION_OUTSIDE) { closeMenu(); return true; } return false; });
        menu = box;
        try { wm.addView(box, p); } catch (Exception e) { Log.i("share menu: " + e.getMessage()); menu = null; }
    }

    private void start(String groupId, List<String> roomIds, String label) {
        closeMenu();
        ShareService.request(svc, groupId, roomIds, label);   // ShareActivity is see-through, so the app keeps playing
    }

    private TextView label(String s) {
        TextView t = Ui.text(svc, s, 13, 0xFFA1A1A8, true);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private TextView row(String s, boolean ticked) {
        TextView t = Ui.text(svc, (ticked ? "☑  " : "☐  ") + s, 16, 0xFFFFFFFF, false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setBackground(Ui.round(svc, ticked ? 0x336BBC46 : 0x14FFFFFF, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView button(String s, boolean primary) {
        TextView b = Ui.text(svc, s, 16, primary ? 0xFF0B1220 : 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(Ui.round(svc, primary ? 0xFF6BBC46 : 0x22FFFFFF, 24));
        return b;
    }
}
