package com.ajnetwork.ajpanel;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows the Music Assistant song in the panel's OWN music player / home screen (Judian "smart home" UI).
 *
 * The Judian launcher only shows songs from its own player app (com.judian.jdmediarender). That app is also a
 * DLNA renderer, and a DLNA "push" (SetAVTransportURI + DIDL metadata) makes its player + home screen show the
 * title / artist / cover. So we push the MA metadata to the panel's own renderer on 127.0.0.1 together with a
 * SILENT mp3 served by us. The real (synced) audio still comes from our snapclient.
 * If someone presses pause on the Judian screen, we see it via GetTransportInfo and treat it as "pause here".
 */
public class DlnaMetaBridge {
    public interface UserAction { void onLocalPause(boolean paused); }

    private final Context ctx;
    private final UserAction cb;
    private volatile String controlUrl;     // AVTransport control URL of the local renderer
    private ServerSocket silence;
    private int silencePort;
    private String lastKey = "", lastImg = "";
    private boolean coverPushed;
    private long lastPushAt;
    private int misses;
    private String expected = "";           // PLAYING / PAUSED_PLAYBACK / STOPPED that WE asked for
    private long lastDiscover;
    private int seq;
    private long expectedSince;

    public DlnaMetaBridge(Context c, UserAction cb) { this.ctx = c.getApplicationContext(); this.cb = cb; }

    /** true on panels that have the Judian player app */
    public static boolean available(Context c) {
        try { c.getPackageManager().getPackageInfo("com.judian.jdmediarender", 0); return true; } catch (Exception e) { return false; }
    }

    /** called from the poll thread every second */
    public void update(NowPlaying n) {
        try {
            if (controlUrl == null) {
                // panels without the Judian player (px30, DSPPA…): try 3× every 30 s, then every 10 min, log once
                if (System.currentTimeMillis() - lastDiscover < (misses < 3 ? 30_000 : 600_000)) return;
                lastDiscover = System.currentTimeMillis();
                controlUrl = discover();
                if (controlUrl == null) { if (++misses == 3) Log.i("dlna bridge: no local renderer on this panel – checking again every 10 min"); return; }
                misses = 0;
                Log.i("dlna bridge: renderer " + controlUrl);
                startSilenceServer();
            }
            // 0.8.10: the song is title+artist. The cover URL alone changes every few seconds while a phone shares
            // (new art.jpg?v=…), and every push restarts the Judian player (SetAVTransportURI + Play) – logs showed
            // 4–7 pushes a minute for the same song and audible dropouts on X8/P8. A new cover for the same song is
            // pushed at most once, and only 20 s after the last push.
            String key = n.active() ? n.title + "|" + n.artist : "";
            String img = n.active() ? n.imageUrl : "";
            boolean coverOnly = key.equals(lastKey) && !img.equals(lastImg) && !key.isEmpty();
            if (coverOnly && !coverPushed && System.currentTimeMillis() - lastPushAt > 20_000) {
                coverPushed = true; lastImg = img;
                push(n);
            } else if (!key.equals(lastKey)) {
                lastKey = key; lastImg = img; coverPushed = false;
                if (n.active()) push(n); else { soap("Stop", ""); expect("STOPPED"); }
            } else if (n.active()) {
                String want = "playing".equals(n.state) && !n.muted ? "PLAYING" : "PAUSED_PLAYBACK";
                if (!want.equals(expected)) {
                    soap(want.equals("PLAYING") ? "Play" : "Pause", want.equals("PLAYING") ? "<Speed>1</Speed>" : "");
                    expect(want);
                } else if (System.currentTimeMillis() - expectedSince > 3000) {
                    String st = transportState();
                    if (st != null && !st.equals(expected) && !st.startsWith("TRANSITION")) {
                        // somebody used the panel's own play/pause
                        Log.i("dlna bridge: panel UI changed state to " + st);
                        boolean paused = !st.equals("PLAYING");
                        expect(st);
                        cb.onLocalPause(paused);
                    }
                }
            }
        } catch (Exception e) {
            Log.e("dlna bridge", e);
            controlUrl = null;  // rediscover (renderer restarted?)
        }
    }

    private void expect(String s) { expected = s; expectedSince = System.currentTimeMillis(); }

    private void push(NowPlaying n) throws Exception {
        int dur = n.duration > 0 ? (int) n.duration : 6 * 3600;
        String uri = "http://127.0.0.1:" + silencePort + "/aj/" + (++seq) + "/" + dur + "/silence.mp3";
        String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"aj" + seq + "\" parentID=\"0\" restricted=\"1\">"
                + "<dc:title>" + t(n.title) + "</dc:title><dc:creator>" + t(n.artist) + "</dc:creator>"
                + "<upnp:artist>" + t(n.artist) + "</upnp:artist><upnp:album>" + t(n.album) + "</upnp:album>"
                + (n.imageUrl.startsWith("http") ? "<upnp:albumArtURI>" + t(n.imageUrl) + "</upnp:albumArtURI>" : "")
                + "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
                + "<res protocolInfo=\"http-get:*:audio/mpeg:*\" duration=\"" + hms(dur) + "\">" + uri + "</res></item></DIDL-Lite>";
        // the Judian parser replaces every '&' by '&amp;' before parsing, so the metadata is sent with raw '&'
        soap("SetAVTransportURI", "<CurrentURI>" + uri + "</CurrentURI><CurrentURIMetaData>" + x(didl) + "</CurrentURIMetaData>");
        soap("Play", "<Speed>1</Speed>");
        expect("PLAYING");
        lastPushAt = System.currentTimeMillis();
        Log.i("dlna bridge: pushed '" + n.title + "'");
    }

    /** text for inside DIDL: no '<' '>' (they break the parser); '&' stays raw (see above) */
    private static String t(String s) { return s == null ? "" : s.replace("<", "(").replace(">", ")"); }
    /** XML-escape for the SOAP body */
    private static String x(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String hms(int s) { return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60); }

    // ---------------- SOAP ----------------
    private String soap(String action, String args) throws Exception {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action
                + " xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID>" + args
                + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) new URL(controlUrl).openConnection();
        c.setConnectTimeout(2000); c.setReadTimeout(4000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#" + action + "\"");
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        String r = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) throw new Exception(action + " HTTP " + code + " " + r);
        return r;
    }

    private String transportState() {
        try {
            Matcher m = Pattern.compile("<CurrentTransportState>([^<]*)<").matcher(soap("GetTransportInfo", ""));
            return m.find() ? m.group(1) : null;
        } catch (Exception e) { return null; }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close(); return b.toString("UTF-8");
    }

    // ---------------- discovery (SSDP) ----------------
    private String discover() {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wm.createMulticastLock("ajpanel-ssdp");
        lock.acquire();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1500);
            String q = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n";
            byte[] d = q.getBytes(StandardCharsets.US_ASCII);
            String me = localIp();
            for (String target : new String[]{"127.0.0.1", "239.255.255.250"}) {
                s.send(new DatagramPacket(d, d.length, InetAddress.getByName(target), 1900));
            }
            long end = System.currentTimeMillis() + 3000;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (Exception t) { break; }
                String r = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("(?im)^LOCATION:\\s*(\\S+)").matcher(r);
                if (!m.find()) continue;
                String loc = m.group(1);
                String host = new URL(loc).getHost();
                if (!host.equals("127.0.0.1") && !host.equals(me)) continue;   // only THIS panel's renderer
                // M20: connecting to its own LAN IP times out, 127.0.0.1 works -> try loopback first
                String ctl = null;
                try { ctl = controlUrlFrom(loc.replace("//" + host + ":", "//127.0.0.1:")); } catch (Exception e) { Log.i("ssdp via 127.0.0.1: " + e.getMessage()); }
                if (ctl == null) ctl = controlUrlFrom(loc);
                if (ctl != null) return ctl.replace("//" + host + ":", "//127.0.0.1:");
            }
        } catch (Exception e) { Log.e("ssdp", e); }
        finally { lock.release(); }
        return null;
    }

    private static String controlUrlFrom(String location) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(location).openConnection();
        c.setConnectTimeout(2000); c.setReadTimeout(3000);
        String xml = read(c.getInputStream());
        Matcher svc = Pattern.compile("(?s)<service>(.*?)</service>").matcher(xml);
        while (svc.find()) {
            String s = svc.group(1);
            if (!s.contains("AVTransport")) continue;
            Matcher cu = Pattern.compile("<controlURL>\\s*([^<\\s]+)\\s*</controlURL>").matcher(s);
            if (!cu.find()) continue;
            return new URL(new URL(location), cu.group(1)).toString();
        }
        return null;
    }

    private static String localIp() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("192.168.8.1"), 9);
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) { return ""; }
    }

    // ---------------- silent mp3 ----------------
    /** one MPEG-1 Layer III frame, 128 kbit/s, 44.1 kHz, stereo, all-zero side info = silence (417 bytes, 26.1 ms) */
    private static byte[] silentFrame() {
        byte[] f = new byte[417];
        f[0] = (byte) 0xFF; f[1] = (byte) 0xFB; f[2] = (byte) 0x90; f[3] = (byte) 0x00;
        return f;
    }

    private void startSilenceServer() throws Exception {
        if (silence != null) return;
        silence = new ServerSocket();
        silence.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        silencePort = silence.getLocalPort();
        Thread t = new Thread(() -> {
            while (!silence.isClosed()) {
                try {
                    Socket c = silence.accept();
                    new Thread(() -> serve(c), "silence-client").start();
                } catch (Exception e) { if (!silence.isClosed()) Log.e("silence server", e); }
            }
        }, "silence-server");
        t.setDaemon(true);
        t.start();
        Log.i("dlna bridge: silence server on 127.0.0.1:" + silencePort);
    }

    private static void serve(Socket c) {
        try (Socket s = c) {
            InputStream in = s.getInputStream();
            StringBuilder req = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1) { req.append((char) ch); if (req.length() > 4 && req.toString().endsWith("\r\n\r\n")) break; if (req.length() > 8192) break; }
            String first = req.toString().split("\r\n")[0];
            int dur = 6 * 3600;
            Matcher m = Pattern.compile("/aj/\\d+/(\\d+)/").matcher(first);
            if (m.find()) dur = Math.max(5, Integer.parseInt(m.group(1)) + 2);
            byte[] f = silentFrame();
            long frames = (long) Math.ceil(dur / 0.026122);
            long len = frames * f.length;
            long start = 0;
            Matcher r = Pattern.compile("(?im)^Range:\\s*bytes=(\\d+)-").matcher(req);
            if (r.find()) start = Math.min(len, Long.parseLong(r.group(1)) / f.length * f.length);
            OutputStream o = s.getOutputStream();
            boolean head = first.startsWith("HEAD");
            String h = (start > 0 ? "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes " + start + "-" + (len - 1) + "/" + len + "\r\n" : "HTTP/1.1 200 OK\r\n")
                    + "Content-Type: audio/mpeg\r\nAccept-Ranges: bytes\r\nContent-Length: " + (len - start) + "\r\nConnection: close\r\n\r\n";
            o.write(h.getBytes(StandardCharsets.US_ASCII));
            if (head) return;
            byte[] block = new byte[f.length * 64];
            for (int i = 0; i < 64; i++) System.arraycopy(f, 0, block, i * f.length, f.length);
            long left = len - start;
            while (left > 0) {
                int n = (int) Math.min(block.length, left);
                o.write(block, 0, n);
                left -= n;
            }
        } catch (Exception ignored) { /* player closed the connection - normal */ }
    }

    public void stop() {
        try { if (controlUrl != null) soap("Stop", ""); } catch (Exception ignored) { }
        try { if (silence != null) silence.close(); } catch (Exception ignored) { }
    }
}
