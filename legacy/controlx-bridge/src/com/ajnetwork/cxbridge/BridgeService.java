package com.ajnetwork.cxbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pretends to be Auxdio DM858 room controllers (one per YJY zone) on the Control X
 * UDP protocol (port 40188) and forwards every command to the YJY music player.
 * State changes in the YJY player are pushed back to Control X.
 */
public class BridgeService extends Service {
    public static final int PORT = 40188;          // device listens here
    public static final int BROAD_PORT = 40189;    // control points listen here
    static final String MCAST = "234.1.1.1";
    static final int MODEL = 8;                    // DM858
    static final int LANG = 1;                     // 1 = English firmware
    static final int ROOM_ID = 1;
    static final int SRC_MUSIC = 129;              // "local music" source id used by DM858
    /** Android audio stream used by each YJY zone on the U6/PX30 firmware */
    static final int[] ZONE_STREAM = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION};

    public static volatile boolean running;
    // ---- group (multi-room) server ----
    public static volatile SnapServer snap;
    public static volatile GroupPlayer group;
    public static volatile DlnaRenderer dlna;
    private android.net.nsd.NsdManager.RegistrationListener nsdListener;
    public static volatile String statusLine = "stopped";

    static class Zone {
        final int id;
        String name;
        int power = 1, source = SRC_MUSIC, eq = 1, mode = 4, state = 2;
        boolean muted; int volBeforeMute = 50;
        String title = "", artist = "";
        int durSec, posSec;
        List<JSONObject> songs = new ArrayList<>();
        int songsHash;
        Zone(int id) { this.id = id; name = "Zone " + id; }
    }

    private int zoneCount = 4;
    private long tick;
    private final Zone[] zones = new Zone[5];
    private final AtomicInteger seq = new AtomicInteger(1);
    private int songInfoSeq = 1;
    private final Map<String, Long> recent = Collections.synchronizedMap(new HashMap<>());

    private MulticastSocket sock;
    private Thread rxThread, pollThread;
    private Yjy yjy;
    private AudioManager am;
    private WifiManager.MulticastLock mlock;
    private PowerManager.WakeLock wlock;
    private String myIp = "0.0.0.0";
    private byte[] myMac = {2, 0, 0, 0, 0, 1};

    // ======================================================================= lifecycle
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startFg();
        if (!running) start();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        stopGroupServer();
        statusLine = "stopped";
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        if (yjy != null) yjy.unbind();
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Exception ignored) {}
        try { if (wlock != null && wlock.isHeld()) wlock.release(); } catch (Exception ignored) {}
        Log.i("bridge stopped");
        super.onDestroy();
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("cx", "ControlX Bridge", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, "cx");
        } else b = new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("ControlX Bridge").setContentText("Control X <-> YJY player");
        startForeground(1, b.build());
    }

    private void start() {
        running = true;
        zoneCount = getSharedPreferences("cx", MODE_PRIVATE).getInt("zones", 4);
        for (int i = 1; i <= 4; i++) zones[i] = new Zone(i);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        yjy = new Yjy(this);
        yjy.bind();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            mlock = wm.createMulticastLock("cx"); mlock.setReferenceCounted(false); mlock.acquire();
        } catch (Exception e) { Log.e("multicast lock", e); }
        try {
            wlock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cx:bridge");
            wlock.acquire();
        } catch (Exception e) { Log.e("wakelock", e); }
        refreshNet();
        if (getSharedPreferences("cx", MODE_PRIVATE).getBoolean("server", false)) startGroupServer();
        String join = getSharedPreferences("cx", MODE_PRIVATE).getString("join", "");
        if (!join.isEmpty()) joinGroup(this, join);
        rxThread = new Thread(this::rxLoop, "cx-rx"); rxThread.start();
        pollThread = new Thread(this::pollLoop, "cx-poll"); pollThread.start();
    }

    // ======================================================================= group server
    private void startGroupServer() {
        try {
            snap = new SnapServer(48000);
            snap.start();
            group = new GroupPlayer(snap);
            group.startPacer();
            String mac = String.format("%02x%02x%02x", myMac[3], myMac[4], myMac[5]);
            dlna = new DlnaRenderer(group, "All Rooms (" + android.os.Build.MODEL + ")", myIp, mac);
            dlna.start();
            android.net.nsd.NsdServiceInfo si = new android.net.nsd.NsdServiceInfo();
            si.setServiceName("Snapcast " + android.os.Build.MODEL);
            si.setServiceType("_snapcast._tcp");
            si.setPort(SnapServer.PORT);
            nsdListener = new android.net.nsd.NsdManager.RegistrationListener() {
                public void onServiceRegistered(android.net.nsd.NsdServiceInfo i) { Log.i("mDNS: " + i.getServiceName()); }
                public void onRegistrationFailed(android.net.nsd.NsdServiceInfo i, int e) { Log.i("mDNS register failed " + e); }
                public void onServiceUnregistered(android.net.nsd.NsdServiceInfo i) {}
                public void onUnregistrationFailed(android.net.nsd.NsdServiceInfo i, int e) {}
            };
            ((android.net.nsd.NsdManager) getSystemService(NSD_SERVICE)).registerService(si, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, nsdListener);
            // this panel also plays the group stream
            joinGroup(this, "127.0.0.1");
        } catch (Exception e) { Log.e("group server", e); }
    }

    private void stopGroupServer() {
        try { if (nsdListener != null) ((android.net.nsd.NsdManager) getSystemService(NSD_SERVICE)).unregisterService(nsdListener); } catch (Exception ignored) {}
        nsdListener = null;
        if (dlna != null) dlna.stop();
        if (group != null) group.shutdown();
        if (snap != null) snap.stop();
        dlna = null; group = null; snap = null;
    }

    /** start the Snapcast app's player on this panel, pointed at the group server */
    public static void joinGroup(Context c, String host) {
        try {
            Intent i = new Intent("ACTION_START");
            i.setComponent(new android.content.ComponentName("de.badaix.snapcast", "de.badaix.snapcast.SnapclientService"));
            i.putExtra("EXTRA_HOST", host);
            i.putExtra("EXTRA_PORT", SnapServer.PORT);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            Log.i("joined group server " + host);
        } catch (Exception e) { Log.e("join group (is the Snapcast app installed?)", e); }
    }

    public static void leaveGroup(Context c) {
        try {
            Intent i = new Intent("ACTION_STOP");
            i.setComponent(new android.content.ComponentName("de.badaix.snapcast", "de.badaix.snapcast.SnapclientService"));
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            Log.i("left group");
        } catch (Exception e) { Log.e("leave group", e); }
    }

    // ======================================================================= network
    private void openSocket() throws Exception {
        if (sock != null) try { sock.close(); } catch (Exception ignored) {}
        refreshNet();
        sock = new MulticastSocket(null);
        sock.setReuseAddress(true);
        sock.bind(new InetSocketAddress(PORT));
        sock.setBroadcast(true);
        try { sock.joinGroup(InetAddress.getByName(MCAST)); } catch (Exception e) { Log.e("join " + MCAST, e); }
        Log.i("listening on UDP " + PORT + "  ip=" + myIp + "  zones=" + zoneCount);
        statusLine = "running  ip=" + myIp + "  zones=" + zoneCount;
    }

    private void rxLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                if (sock == null || sock.isClosed()) { openSocket(); announceAll(); }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                String ip = p.getAddress().getHostAddress();
                if (ip.equals(myIp)) continue;               // our own broadcast
                byte[] d = new byte[p.getLength()];
                System.arraycopy(p.getData(), 0, d, 0, d.length);
                if (d.length < 9) continue;
                handle(d, ip, p.getPort());
            } catch (Exception e) {
                if (!running) break;
                Log.e("rx", e);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                sock = null;
            }
        }
    }

    private void refreshNet() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        myIp = a.getHostAddress();
                        byte[] hw = ni.getHardwareAddress();
                        if (hw != null && hw.length == 6) myMac = hw;
                        else readMac(ni.getName());
                        return;
                    }
                }
            }
        } catch (Exception e) { Log.e("net", e); }
    }

    private void readMac(String ifname) {
        try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/net/" + ifname + "/address"))) {
            String[] s = r.readLine().trim().split(":");
            byte[] m = new byte[6];
            for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(s[i], 16);
            myMac = m;
        } catch (Exception ignored) {}
    }

    private void send(byte[] pkt, String ip, int port) {
        try {
            if (port == BROAD_PORT || ip == null) {
                sock.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName("255.255.255.255"), BROAD_PORT));
            } else {
                sock.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(ip), port));
            }
        } catch (Exception e) { Log.e("send", e); }
    }

    // ======================================================================= packets
    /** Auxdio frame: cmd(2) seq devModel/devID b5 b6 room len payload xor */
    private byte[] frame(int cmd, int dev, int sq, int b5, int enc, byte[] pl) {
        int len = pl == null ? 0 : pl.length;
        byte[] a = new byte[len + 10];
        a[0] = (byte) (cmd >> 8); a[1] = (byte) cmd; a[2] = (byte) sq;
        a[3] = (byte) ((MODEL & 4095) >> 8);
        a[4] = (byte) (((MODEL & 15) << 4) + dev);
        a[5] = (byte) b5;
        a[6] = (byte) ((enc << 5) | 1);
        a[7] = (byte) ROOM_ID;
        a[8] = (byte) len;
        if (len > 0) System.arraycopy(pl, 0, a, 9, len);
        byte x = a[9];
        for (int i = 10; i < 9 + len; i++) x ^= a[i];
        a[9 + len] = len == 0 ? 0 : x;
        return a;
    }
    /** normal response / notification */
    private byte[] resp(int cmd, int dev, int enc, byte[] pl) { return frame(cmd, dev, seq.getAndIncrement(), (LANG & 255) << 4, enc, pl); }
    /** acknowledgement that echoes the request serial number */
    private byte[] ack(int cmd, int dev, int reqSeq, byte[] pl) { return frame(cmd, dev, reqSeq, 0, 0, pl); }

    private static String charset(int code) {
        switch (code) { case 2: return "GB2312"; case 3: return "UTF-8"; case 5: return "Big5"; default: return "GBK"; }
    }
    private static byte[] str(String s, String cs) { try { return s.getBytes(cs); } catch (Exception e) { return s.getBytes(); } }
    private static byte[] lstr(String s, String cs) { byte[] b = str(s, cs); if (b.length > 200) { byte[] c = new byte[200]; System.arraycopy(b, 0, c, 0, 200); b = c; } byte[] o = new byte[b.length + 1]; o[0] = (byte) b.length; System.arraycopy(b, 0, o, 1, b.length); return o; }
    private static byte[] cat(byte[]... parts) { int n = 0; for (byte[] p : parts) n += p.length; byte[] o = new byte[n]; int i = 0; for (byte[] p : parts) { System.arraycopy(p, 0, o, i, p.length); i += p.length; } return o; }
    private static String rstr(byte[] d, int off, String cs) { try { int n = d[off] & 255; return new String(d, off + 1, Math.min(n, d.length - off - 1), cs); } catch (Exception e) { return ""; } }

    private byte[] deviceHandle(Zone z) {
        byte[] name = str(z.name, "GB2312"), ip = str(myIp, "GB2312");
        byte[] mac = myMac.clone(); mac[5] = (byte) (mac[5] + z.id - 1);
        return cat(new byte[]{(byte) name.length}, name, new byte[]{(byte) ip.length}, ip,
                new byte[]{1, 0, 0, 0, 0, 0}, mac, new byte[]{0, 0});
    }

    private void announceAll() {
        for (int i = 1; i <= zoneCount; i++) send(resp(0xFFFD, i, 2, deviceHandle(zones[i])), null, BROAD_PORT);
    }

    // ======================================================================= request handling
    private void handle(byte[] d, String ip, int port) {
        int cmd = ((d[0] & 255) << 8) | (d[1] & 255);
        int rseq = d[2] & 255;
        int dev = d[4] & 15;
        boolean query = d.length > 9 && (d[9] & 255) == 128;
        int v = d.length > 10 ? d[10] & 255 : 0;
        String reqCs = charset((d[6] & 14) >> 1);

        if (cmd == 0xFFFE) {                       // search
            Log.i("search from " + ip + ":" + port);
            for (int i = 1; i <= zoneCount; i++) send(resp(0xFFFE, i, 2, deviceHandle(zones[i])), ip, port);
            return;
        }
        if (dev < 1 || dev > zoneCount) dev = 1;
        Zone z = zones[dev];

        // duplicate (retransmitted) set commands: acknowledge only
        String key = ip + "/" + cmd + "/" + rseq + "/" + dev;
        long now = System.currentTimeMillis();
        Long last = recent.put(key, now);
        boolean dup = !query && last != null && now - last < 3000;
        if (recent.size() > 300) recent.clear();

        Log.i("cmd " + cmd + " zone" + dev + (query ? " query" : " set " + v) + (dup ? " (dup)" : "") + " from " + ip);
        try {
            switch (cmd) {
                case 17: send(resp(17, dev, 0, cat(lstr("DM858_V_0.0.1", "GB2312"), lstr("Auxdio_V_1.0.5", "GB2312"))), ip, port); break;
                case 14:
                    if (!query && d.length > 10) { String n = rstr(d, 10, reqCs); if (!n.isEmpty()) { z.name = n; yjy.setZoneName(dev, n); } }
                    send(resp(14, dev, 2, cat(new byte[]{1, ROOM_ID}, lstr(z.name, "GB2312"))), ip, port);
                    break;
                case 16: {
                    byte[] l = lstr("L", "GB2312"), r = lstr("R", "GB2312");
                    byte[] pl = cat(new byte[]{1, ROOM_ID, (byte) z.source, (byte) roomVolume(z), (byte) z.power, 7, 7, (byte) roomVolume(z), (byte) roomVolume(z)}, l, r);
                    send(frame(16, dev, seq.getAndIncrement(), 1 << 4, 2, pl), ip, port);
                    break;
                }
                case 1:
                    if (query) send(ack(1, dev, rseq, new byte[]{(byte) roomVolume(z)}), ip, port);
                    else { if (!dup) setVolume(z, v); send(ack(1, dev, rseq, null), ip, port); }
                    break;
                case 49: if (!dup) setVolume(z, Math.max(0, Math.min(100, roomVolume(z) + ((d[9] & 255) == 1 ? 5 : -5)))); break;
                case 86:
                    if (!dup && (d[9] & 255) == 2) setVolume(z, v);
                    send(ack(86, dev, rseq, null), ip, port);
                    break;
                case 7:
                    if (query) send(ack(7, dev, rseq, new byte[]{(byte) (z.muted || roomVolume(z) == 0 ? 16 : 1)}), ip, port);
                    else { if (!dup) setMute(z, v == 16); send(ack(7, dev, rseq, null), ip, port); }
                    break;
                case 6:
                    if (query) send(ack(6, dev, rseq, new byte[]{(byte) z.power}), ip, port);
                    else { if (!dup) { z.power = v; if (v == 0) yjy.pause(dev); } send(ack(6, dev, rseq, null), ip, port); }
                    break;
                case 2:
                    if (query) send(ack(2, dev, rseq, new byte[]{(byte) z.source}), ip, port);
                    else { if (!dup) z.source = v; send(ack(2, dev, rseq, null), ip, port); }
                    break;
                case 34: if (query) send(resp(34, dev, 2, cat(new byte[]{(byte) SRC_MUSIC}, lstr("Music", "GB2312"))), ip, port); break;
                case 3:
                    if (query) send(resp(3, dev, 0, new byte[]{(byte) z.eq}), ip, port);
                    else if (d.length > 10) z.eq = v;
                    break;
                case 4:
                    if (query) send(resp(4, dev, 0, new byte[]{(byte) (z.mode == 0 ? 1 : z.mode)}), ip, port);
                    else if (!dup) { z.mode = v; yjy.setMode(dev, auxToYjyMode(v)); notifyMode(z); }
                    break;
                case 5:
                    if (query) send(resp(5, dev, 0, new byte[]{(byte) (z.state == 0 ? 2 : z.state)}), ip, port);
                    else {
                        if (!dup) { if (v == 1) yjy.play(dev); else yjy.pause(dev); z.state = v == 1 ? 1 : 2; }
                        send(ack(5, dev, rseq, null), ip, port);
                    }
                    break;
                case 10:
                    if (!dup) { if ((d[9] & 255) == 1) yjy.previous(dev); else yjy.next(dev); }
                    send(ack(10, dev, rseq, null), ip, port);
                    break;
                case 13: {
                    int code = (d[6] & 14) >> 1;
                    send(resp(13, dev, code, str(z.title.isEmpty() ? "Music" : z.title, charset(code))), ip, port);
                    break;
                }
                case 15:
                    if (query) send(resp(15, dev, 2, new byte[]{(byte) (z.durSec >> 8), (byte) z.durSec, (byte) (z.posSec >> 8), (byte) z.posSec}), ip, port);
                    else if (!dup && d.length > 13) { int pos = ((d[12] & 255) << 8) | (d[13] & 255); yjy.seek(dev, pos * 1000); }
                    break;
                case 27: contentList(z, (d[6] & 14) >> 1, ip, port); break;
                case 36: songPage(z, d, (d[6] & 14) >> 1, ip, port); break;
                case 35: if (!dup) playSong(z, d, reqCs); break;
                default: break;                     // talk/intercom, playlists etc. not supported
            }
        } catch (Exception e) { Log.e("handle cmd " + cmd, e); }
    }

    // ======================================================================= helpers: volume / mode
    private int roomVolume(Zone z) {
        try {
            int s = ZONE_STREAM[z.id - 1];
            int max = am.getStreamMaxVolume(s);
            return max <= 0 ? 0 : Math.round(am.getStreamVolume(s) * 100f / max);
        } catch (Exception e) { return 0; }
    }
    private void setVolume(Zone z, int pct) {
        try {
            int s = ZONE_STREAM[z.id - 1];
            int max = am.getStreamMaxVolume(s);
            am.setStreamVolume(s, Math.round(Math.max(0, Math.min(100, pct)) * max / 100f), 0);
            if (pct > 0) z.muted = false;
        } catch (Exception e) { Log.e("volume", e); }
    }
    private void setMute(Zone z, boolean mute) {
        if (mute) { if (!z.muted) { z.volBeforeMute = Math.max(roomVolume(z), 10); setVolume(z, 0); z.muted = true; } }
        else { setVolume(z, z.muted ? z.volBeforeMute : Math.max(roomVolume(z), 10)); z.muted = false; }
    }
    /** Auxdio: 1 one, 2 one-repeat, 3 list-once, 4 list-repeat, 5 random  ->  YJY: 1 shuffle, 2 single, 3 single-stop, 4 loop */
    private static int auxToYjyMode(int m) { switch (m) { case 1: return 3; case 2: return 2; case 5: return 1; default: return 4; } }
    private static int yjyToAuxMode(int m) { switch (m) { case 1: return 5; case 2: return 2; case 3: return 1; default: return 4; } }

    // ======================================================================= songs
    private void loadSongs(Zone z) {
        String js = yjy.listJson(z.id, 0);
        List<JSONObject> list = new ArrayList<>();
        int h = js == null ? 0 : js.hashCode();
        if (h == z.songsHash) return;
        try {
            if (js != null) { JSONArray a = new JSONArray(js); for (int i = 0; i < a.length(); i++) list.add(a.getJSONObject(i)); }
        } catch (Exception e) { Log.e("song list", e); }
        z.songs = list; z.songsHash = h;
        songInfoSeq++;
        send(resp(44, z.id, 0, null), null, BROAD_PORT);   // tell Control X the content changed
        Log.i("zone" + z.id + " playlist: " + list.size() + " songs");
    }
    private static String songName(JSONObject o) {
        String t = o.optString("title", ""); if (t.isEmpty()) t = o.optString("fileName", "Track");
        String a = o.optString("artist", "");
        return a.isEmpty() || a.equals("<unknown>") ? t : t + " - " + a;
    }
    private void contentList(Zone z, int code, String ip, int port) {
        String cs = charset(code);
        int pages = (z.songs.size() + 4) / 5;
        byte[] name = str("Play list", cs);
        byte[] pl = cat(new byte[]{1, 1, (byte) name.length}, name, new byte[]{(byte) pages});
        send(frame(27, z.id, seq.getAndIncrement(), (songInfoSeq & 255) << 4, code, pl), ip, port);
    }
    private void songPage(Zone z, byte[] d, int code, String ip, int port) {
        String cs = charset(code);
        int page = d.length > 10 ? d[10] & 255 : 255;
        int pages = (z.songs.size() + 4) / 5;
        for (int p = 1; p <= pages; p++) {
            if (page != 255 && page != p) continue;
            List<byte[]> parts = new ArrayList<>();
            parts.add(new byte[]{1, (byte) p});
            for (int i = (p - 1) * 5; i < Math.min(p * 5, z.songs.size()); i++) {
                parts.add(lstr(songName(z.songs.get(i)), cs));
                parts.add(lstr(String.valueOf(i + 1), cs));
            }
            send(resp(36, z.id, code, cat(parts.toArray(new byte[0][]))), ip, port);
        }
    }
    private void playSong(Zone z, byte[] d, String cs) {
        int tagOff = 9 + 1 + (d[9] & 255);
        String tag = rstr(d, tagOff, cs);
        try {
            int idx = Integer.parseInt(tag.trim()) - 1;
            if (idx >= 0 && idx < z.songs.size()) {
                Log.i("zone" + z.id + " play song #" + (idx + 1) + " " + songName(z.songs.get(idx)));
                yjy.playThis(z.id, z.songs.get(idx).toString());
            }
        } catch (Exception e) { Log.e("play song tag=" + tag, e); }
    }

    // ======================================================================= notifications to Control X
    private void notifyProgram(Zone z) {
        byte[] pl = cat(new byte[]{(byte) z.source}, lstr(z.title.isEmpty() ? "Music" : z.title, "UTF-8"),
                new byte[]{(byte) (z.state == 0 ? 2 : z.state), 0, 0, 0});
        send(resp(37, z.id, 3, pl), null, BROAD_PORT);
    }
    private void notifyMode(Zone z) {
        send(resp(38, z.id, 0, new byte[]{(byte) z.source, (byte) (z.mode == 0 ? 1 : z.mode)}), null, BROAD_PORT);
    }

    // ======================================================================= poll YJY state
    private void pollLoop() {
        long lastAnnounce = 0, lastBind = 0, lastList = 0;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (!yjy.playerReady() && now - lastBind > 5000) { yjy.bind(); lastBind = now; }
                if (yjy.playerReady()) {
                    tick++;
                    for (int i = 1; i <= zoneCount; i++) {
                        Zone z = zones[i];
                        // idle zones are only checked every 5 s to keep load on the YJY player low
                        if (z.state != 1 && tick % 5 != 0) continue;
                        int st = yjy.getState(i);
                        if (st == 0) st = 2;
                        String title = "", artist = "";
                        String pj = yjy.getPlayingJson(i);
                        if (pj != null) try { JSONObject o = new JSONObject(pj); title = o.optString("title", o.optString("fileName", "")); artist = o.optString("artist", ""); } catch (Exception ignored) {}
                        int mode = yjyToAuxMode(yjy.getMode(i));
                        z.durSec = (int) (yjy.getDuration(i) / 1000);
                        z.posSec = (int) (yjy.getTime(i) / 1000);
                        if (z.name.startsWith("Zone ") ) { String n = yjy.getZoneName(i); if (n != null && !n.trim().isEmpty()) z.name = n.trim(); }
                        boolean changed = st != z.state || !title.equals(z.title);
                        z.state = st; z.title = title; z.artist = artist;
                        if (changed) { notifyProgram(z); Log.i("zone" + i + " " + (z.state == 1 ? "playing" : "paused") + ": " + title); }
                        if (mode != z.mode) { z.mode = mode; notifyMode(z); }
                    }
                    if (now - lastList > 30000) { for (int i = 1; i <= zoneCount; i++) loadSongs(zones[i]); lastList = now; }
                }
                if (sock != null && now - lastAnnounce > 30000) { announceAll(); lastAnnounce = now; }
                StringBuilder sb = new StringBuilder("running  ip=" + myIp + "  YJY " + (yjy.playerReady() ? "connected" : "NOT connected") + "\n");
                if (snap != null) sb.append("GROUP SERVER: ").append(snap.clientCount()).append(" panel(s): ").append(snap.clientList())
                        .append("\n  now: ").append(group.state()).append("  ").append(group.title()).append("  ").append(group.positionMs() / 1000).append("/").append(group.durationMs() / 1000).append(" s\n");
                for (int i = 1; i <= zoneCount; i++) { Zone z = zones[i]; sb.append(z.name).append(": ").append(z.state == 1 ? "playing " : "paused ").append(z.title).append("  vol ").append(roomVolume(z)).append('\n'); }
                statusLine = sb.toString();
                Thread.sleep(1000);
            } catch (InterruptedException e) { break; }
            catch (Exception e) { Log.e("poll", e); try { Thread.sleep(2000); } catch (InterruptedException ie) { break; } }
        }
    }
}
