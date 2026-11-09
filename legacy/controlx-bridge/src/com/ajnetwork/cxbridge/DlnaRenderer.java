package com.ajnetwork.cxbridge;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal UPnP/DLNA MediaRenderer ("All Rooms"). Whatever a phone casts here is played
 * by the GroupPlayer, i.e. on every panel that is in the group.
 */
public class DlnaRenderer {
    public static final int HTTP_PORT = 49494;
    private final GroupPlayer player;
    private final String name, uuid;
    private volatile String ip;
    private volatile boolean running;
    private ServerSocket http;
    private MulticastSocket ssdp;
    private String metadata = "";

    public DlnaRenderer(GroupPlayer p, String friendlyName, String ip, String uuidSeed) {
        player = p; name = friendlyName; this.ip = ip;
        uuid = UUID.nameUUIDFromBytes(("cxbridge-" + uuidSeed).getBytes()).toString();
    }

    public void setIp(String ip) { this.ip = ip; }

    public void start() throws Exception {
        running = true;
        http = new ServerSocket();
        http.setReuseAddress(true);
        http.bind(new InetSocketAddress(HTTP_PORT));
        new Thread(this::httpLoop, "dlna-http").start();
        try {
            ssdp = new MulticastSocket(null);
            ssdp.setReuseAddress(true);
            ssdp.bind(new InetSocketAddress(1900));
            ssdp.joinGroup(InetAddress.getByName("239.255.255.250"));
            new Thread(this::ssdpLoop, "dlna-ssdp").start();
        } catch (Exception e) {
            Log.e("SSDP port 1900 busy - only announcing, not answering searches", e);
            ssdp = new MulticastSocket();
        }
        new Thread(this::notifyLoop, "dlna-notify").start();
        Log.i("DLNA renderer '" + name + "' on http://" + ip + ":" + HTTP_PORT);
    }

    public void stop() {
        running = false;
        try { sendNotify("ssdp:byebye"); } catch (Exception ignored) {}
        try { http.close(); } catch (Exception ignored) {}
        try { ssdp.close(); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ SSDP
    private static final String[] TYPES = {
            "upnp:rootdevice", "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1", "urn:schemas-upnp-org:service:RenderingControl:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1"};

    private String location() { return "http://" + ip + ":" + HTTP_PORT + "/desc.xml"; }
    private String usn(String t) { return "uuid:" + uuid + "::" + t; }

    private void ssdpLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ssdp.receive(p);
                String m = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (!m.startsWith("M-SEARCH")) continue;
                String st = header(m, "ST");
                if (st == null) continue;
                for (String t : TYPES) {
                    if (st.equals("ssdp:all") || st.equals(t) || st.equals("uuid:" + uuid)) {
                        String r = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: " + location()
                                + "\r\nSERVER: Android/1 UPnP/1.0 CXBridge/2.0\r\nST: " + t + "\r\nUSN: " + usn(t) + "\r\n\r\n";
                        byte[] b = r.getBytes(StandardCharsets.UTF_8);
                        ssdp.send(new DatagramPacket(b, b.length, p.getAddress(), p.getPort()));
                    }
                }
            } catch (Exception e) { if (running) Log.e("ssdp", e); }
        }
    }

    private void sendNotify(String nts) throws Exception {
        InetAddress grp = InetAddress.getByName("239.255.255.250");
        for (String t : TYPES) {
            String m = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: " + location()
                    + "\r\nNT: " + t + "\r\nNTS: " + nts + "\r\nSERVER: Android/1 UPnP/1.0 CXBridge/2.0\r\nUSN: " + usn(t) + "\r\n\r\n";
            byte[] b = m.getBytes(StandardCharsets.UTF_8);
            ssdp.send(new DatagramPacket(b, b.length, grp, 1900));
        }
    }

    private void notifyLoop() {
        while (running) {
            try { sendNotify("ssdp:alive"); Thread.sleep(30000); } catch (InterruptedException e) { break; } catch (Exception e) { try { Thread.sleep(5000); } catch (InterruptedException ie) { break; } }
        }
    }

    private static String header(String msg, String key) {
        for (String l : msg.split("\r\n")) {
            int c = l.indexOf(':');
            if (c > 0 && l.substring(0, c).trim().equalsIgnoreCase(key)) return l.substring(c + 1).trim();
        }
        return null;
    }

    // ------------------------------------------------------------------ HTTP / SOAP
    private void httpLoop() {
        while (running) {
            try {
                final Socket s = http.accept();
                new Thread(() -> serve(s), "dlna-req").start();
            } catch (Exception e) { if (running) Log.e("dlna http", e); }
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(10000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream hb = new ByteArrayOutputStream();
            int c, state = 0;
            while ((c = in.read()) != -1) { hb.write(c); state = (c == '\r' || c == '\n') ? state + 1 : 0; if (state == 4) break; if (hb.size() > 16384) break; }
            String head = hb.toString("UTF-8");
            String[] first = head.split("\r\n")[0].split(" ");
            String method = first[0], path = first.length > 1 ? first[1] : "/";
            String cl = header(head, "Content-Length");
            int len = cl == null ? 0 : Integer.parseInt(cl.trim());
            byte[] body = new byte[len];
            int r = 0; while (r < len) { int n = in.read(body, r, len - r); if (n < 0) break; r += n; }
            String soapAction = header(head, "SOAPACTION");
            OutputStream os = s.getOutputStream();
            if (method.equals("GET")) {
                String xml = null;
                if (path.startsWith("/desc.xml")) xml = deviceXml();
                else if (path.startsWith("/AVTransport.xml")) xml = scpdAvt();
                else if (path.startsWith("/RenderingControl.xml")) xml = scpdRc();
                else if (path.startsWith("/ConnectionManager.xml")) xml = scpdCm();
                if (xml == null) reply(os, "404 Not Found", "text/plain", "", null);
                else reply(os, "200 OK", "text/xml; charset=\"utf-8\"", xml, null);
            } else if (method.equals("SUBSCRIBE") || method.equals("UNSUBSCRIBE")) {
                Map<String, String> h = new HashMap<>();
                h.put("SID", "uuid:" + UUID.randomUUID()); h.put("TIMEOUT", "Second-1800");
                reply(os, "200 OK", "text/plain", "", h);
            } else if (method.equals("POST") && soapAction != null) {
                String act = soapAction.replace("\"", "");
                act = act.substring(act.indexOf('#') + 1);
                String svc = path.contains("AVTransport") ? "AVTransport" : path.contains("Rendering") ? "RenderingControl" : "ConnectionManager";
                String res = soap(svc, act, new String(body, StandardCharsets.UTF_8));
                if (res == null) reply(os, "500 Internal Server Error", "text/xml; charset=\"utf-8\"", fault(), null);
                else reply(os, "200 OK", "text/xml; charset=\"utf-8\"", envelope(svc, act, res), null);
            } else reply(os, "405 Method Not Allowed", "text/plain", "", null);
            os.flush();
        } catch (Exception e) { Log.e("dlna serve", e); }
        try { s.close(); } catch (Exception ignored) {}
    }

    private void reply(OutputStream os, String status, String type, String body, Map<String, String> extra) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nServer: Android/1 UPnP/1.0 CXBridge/2.0\r\nConnection: close\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        h.append("\r\n");
        os.write(h.toString().getBytes(StandardCharsets.UTF_8)); os.write(b);
    }

    private static String arg(String body, String name) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + "(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?" + name + ">", Pattern.DOTALL).matcher(body);
        return m.find() ? unesc(m.group(1).trim()) : "";
    }
    private static String unesc(String s) { return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&"); }
    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String hms(long ms) { long s = ms / 1000; return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60); }
    private static long parseHms(String t) {
        try { String[] p = t.split(":"); double sec = 0; for (String x : p) sec = sec * 60 + Double.parseDouble(x); return (long) (sec * 1000); } catch (Exception e) { return 0; }
    }

    private String soap(String svc, String act, String body) {
        switch (act) {
            case "SetAVTransportURI": {
                String uri = arg(body, "CurrentURI"); metadata = arg(body, "CurrentURIMetaData");
                String title = "";
                Matcher m = Pattern.compile("<dc:title>(.*?)</dc:title>", Pattern.DOTALL).matcher(metadata);
                if (m.find()) title = unesc(m.group(1));
                player.setUri(uri, title);
                return "";
            }
            case "SetNextAVTransportURI": return "";
            case "Play": Log.i("DLNA Play"); player.play(); return "";
            case "Pause": player.pause(); return "";
            case "Stop": player.stop(); return "";
            case "Seek": {
                String unit = arg(body, "Unit"), target = arg(body, "Target");
                if (unit.contains("TIME")) player.seekMs(parseHms(target));
                return "";
            }
            case "GetTransportInfo": {
                String st;
                switch (player.state()) { case PLAYING: st = "PLAYING"; break; case PAUSED: st = "PAUSED_PLAYBACK"; break; case TRANSITIONING: st = "TRANSITIONING"; break; default: st = player.url() == null ? "NO_MEDIA_PRESENT" : "STOPPED"; }
                return "<CurrentTransportState>" + st + "</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>";
            }
            case "GetPositionInfo":
                return "<Track>1</Track><TrackDuration>" + hms(player.durationMs()) + "</TrackDuration><TrackMetaData>" + esc(metadata)
                        + "</TrackMetaData><TrackURI>" + esc(player.url()) + "</TrackURI><RelTime>" + hms(player.positionMs())
                        + "</RelTime><AbsTime>" + hms(player.positionMs()) + "</AbsTime><RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>";
            case "GetMediaInfo":
                return "<NrTracks>1</NrTracks><MediaDuration>" + hms(player.durationMs()) + "</MediaDuration><CurrentURI>" + esc(player.url())
                        + "</CurrentURI><CurrentURIMetaData>" + esc(metadata) + "</CurrentURIMetaData><NextURI></NextURI><NextURIMetaData></NextURIMetaData>"
                        + "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>";
            case "GetTransportSettings": return "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>";
            case "GetCurrentTransportActions": return "<Actions>Play,Pause,Stop,Seek</Actions>";
            case "GetDeviceCapabilities": return "<PlayMedia>NETWORK</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia><RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>";
            case "GetVolume": return "<CurrentVolume>" + player.volume() + "</CurrentVolume>";
            case "SetVolume": try { player.setVolume(Integer.parseInt(arg(body, "DesiredVolume"))); } catch (Exception ignored) {} return "";
            case "GetMute": return "<CurrentMute>" + (player.muted() ? 1 : 0) + "</CurrentMute>";
            case "SetMute": { String v = arg(body, "DesiredMute"); player.setMuted(v.equals("1") || v.equalsIgnoreCase("true")); return ""; }
            case "ListPresets": return "<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>";
            case "SelectPreset": return "";
            case "GetProtocolInfo":
                return "<Source></Source><Sink>" + esc("http-get:*:audio/mpeg:*,http-get:*:audio/mp3:*,http-get:*:audio/mp4:*,http-get:*:audio/x-m4a:*,"
                        + "http-get:*:audio/aac:*,http-get:*:audio/flac:*,http-get:*:audio/x-flac:*,http-get:*:audio/wav:*,http-get:*:audio/x-wav:*,"
                        + "http-get:*:audio/L16:*,http-get:*:audio/ogg:*,http-get:*:audio/opus:*,http-get:*:audio/*:*,http-get:*:video/mp4:*") + "</Sink>";
            case "GetCurrentConnectionIDs": return "<ConnectionIDs>0</ConnectionIDs>";
            case "GetCurrentConnectionInfo":
                return "<RcsID>0</RcsID><AVTransportID>0</AVTransportID><ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager>"
                        + "<PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction><Status>OK</Status>";
            default: Log.i("DLNA unknown action " + act); return null;
        }
    }

    private static String envelope(String svc, String act, String inner) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + act + "Response xmlns:u=\"urn:schemas-upnp-org:service:" + svc + ":1\">" + inner + "</u:" + act + "Response></s:Body></s:Envelope>";
    }
    private static String fault() {
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>"
                + "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>401</errorCode><errorDescription>Invalid Action</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>";
    }

    private String deviceXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion><device>"
                + "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>" + esc(name) + "</friendlyName>"
                + "<manufacturer>AJ Network</manufacturer><modelName>ControlX Bridge Group</modelName><modelNumber>2.0</modelNumber>"
                + "<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><UDN>uuid:" + uuid + "</UDN><serviceList>"
                + svc("AVTransport") + svc("RenderingControl") + svc("ConnectionManager") + "</serviceList></device></root>";
    }
    private static String svc(String n) {
        return "<service><serviceType>urn:schemas-upnp-org:service:" + n + ":1</serviceType><serviceId>urn:upnp-org:serviceId:" + n + "</serviceId>"
                + "<SCPDURL>/" + n + ".xml</SCPDURL><controlURL>/" + n + "/control</controlURL><eventSubURL>/" + n + "/event</eventSubURL></service>";
    }

    // ---- service descriptions (SCPD) ---------------------------------------------------
    private static String action(String name, String... args) {
        StringBuilder b = new StringBuilder("<action><name>" + name + "</name><argumentList>");
        for (String a : args) {
            String[] p = a.split(":");   // name:in|out:relatedStateVariable
            b.append("<argument><name>").append(p[0]).append("</name><direction>").append(p[1]).append("</direction><relatedStateVariable>").append(p[2]).append("</relatedStateVariable></argument>");
        }
        return b.append("</argumentList></action>").toString();
    }
    private static String var(String name, String type, boolean events, String... allowed) {
        StringBuilder b = new StringBuilder("<stateVariable sendEvents=\"" + (events ? "yes" : "no") + "\"><name>" + name + "</name><dataType>" + type + "</dataType>");
        if (allowed.length > 0) { b.append("<allowedValueList>"); for (String a : allowed) b.append("<allowedValue>").append(a).append("</allowedValue>"); b.append("</allowedValueList>"); }
        return b.append("</stateVariable>").toString();
    }
    private static String scpd(String actions, String vars) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>"
                + actions + "</actionList><serviceStateTable>" + vars + "</serviceStateTable></scpd>";
    }
    private static String scpdAvt() {
        String I = "InstanceID:in:A_ARG_TYPE_InstanceID";
        return scpd(
                action("SetAVTransportURI", I, "CurrentURI:in:AVTransportURI", "CurrentURIMetaData:in:AVTransportURIMetaData")
                        + action("SetNextAVTransportURI", I, "NextURI:in:NextAVTransportURI", "NextURIMetaData:in:NextAVTransportURIMetaData")
                        + action("Play", I, "Speed:in:TransportPlaySpeed") + action("Pause", I) + action("Stop", I)
                        + action("Seek", I, "Unit:in:A_ARG_TYPE_SeekMode", "Target:in:A_ARG_TYPE_SeekTarget")
                        + action("GetTransportInfo", I, "CurrentTransportState:out:TransportState", "CurrentTransportStatus:out:TransportStatus", "CurrentSpeed:out:TransportPlaySpeed")
                        + action("GetPositionInfo", I, "Track:out:CurrentTrack", "TrackDuration:out:CurrentTrackDuration", "TrackMetaData:out:CurrentTrackMetaData",
                        "TrackURI:out:CurrentTrackURI", "RelTime:out:RelativeTimePosition", "AbsTime:out:AbsoluteTimePosition", "RelCount:out:RelativeCounterPosition", "AbsCount:out:AbsoluteCounterPosition")
                        + action("GetMediaInfo", I, "NrTracks:out:NumberOfTracks", "MediaDuration:out:CurrentMediaDuration", "CurrentURI:out:AVTransportURI",
                        "CurrentURIMetaData:out:AVTransportURIMetaData", "NextURI:out:NextAVTransportURI", "NextURIMetaData:out:NextAVTransportURIMetaData",
                        "PlayMedium:out:PlaybackStorageMedium", "RecordMedium:out:RecordStorageMedium", "WriteStatus:out:RecordMediumWriteStatus")
                        + action("GetTransportSettings", I, "PlayMode:out:CurrentPlayMode", "RecQualityMode:out:CurrentRecordQualityMode")
                        + action("GetCurrentTransportActions", I, "Actions:out:CurrentTransportActions")
                        + action("GetDeviceCapabilities", I, "PlayMedia:out:PossiblePlaybackStorageMedia", "RecMedia:out:PossibleRecordStorageMedia", "RecQualityModes:out:PossibleRecordQualityModes"),
                var("A_ARG_TYPE_InstanceID", "ui4", false) + var("AVTransportURI", "string", false) + var("AVTransportURIMetaData", "string", false)
                        + var("NextAVTransportURI", "string", false) + var("NextAVTransportURIMetaData", "string", false)
                        + var("TransportPlaySpeed", "string", false, "1") + var("A_ARG_TYPE_SeekMode", "string", false, "REL_TIME", "ABS_TIME", "TRACK_NR")
                        + var("A_ARG_TYPE_SeekTarget", "string", false)
                        + var("TransportState", "string", false, "STOPPED", "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING", "NO_MEDIA_PRESENT")
                        + var("TransportStatus", "string", false, "OK", "ERROR_OCCURRED") + var("CurrentTrack", "ui4", false)
                        + var("CurrentTrackDuration", "string", false) + var("CurrentTrackMetaData", "string", false) + var("CurrentTrackURI", "string", false)
                        + var("RelativeTimePosition", "string", false) + var("AbsoluteTimePosition", "string", false)
                        + var("RelativeCounterPosition", "i4", false) + var("AbsoluteCounterPosition", "i4", false)
                        + var("NumberOfTracks", "ui4", false) + var("CurrentMediaDuration", "string", false)
                        + var("PlaybackStorageMedium", "string", false) + var("RecordStorageMedium", "string", false) + var("RecordMediumWriteStatus", "string", false)
                        + var("CurrentPlayMode", "string", false, "NORMAL") + var("CurrentRecordQualityMode", "string", false)
                        + var("CurrentTransportActions", "string", false) + var("PossiblePlaybackStorageMedia", "string", false)
                        + var("PossibleRecordStorageMedia", "string", false) + var("PossibleRecordQualityModes", "string", false)
                        + var("LastChange", "string", true));
    }
    private static String scpdRc() {
        String I = "InstanceID:in:A_ARG_TYPE_InstanceID", C = "Channel:in:A_ARG_TYPE_Channel";
        return scpd(
                action("GetVolume", I, C, "CurrentVolume:out:Volume") + action("SetVolume", I, C, "DesiredVolume:in:Volume")
                        + action("GetMute", I, C, "CurrentMute:out:Mute") + action("SetMute", I, C, "DesiredMute:in:Mute")
                        + action("ListPresets", I, "CurrentPresetNameList:out:PresetNameList") + action("SelectPreset", I, "PresetName:in:A_ARG_TYPE_PresetName"),
                var("A_ARG_TYPE_InstanceID", "ui4", false) + var("A_ARG_TYPE_Channel", "string", false, "Master")
                        + "<stateVariable sendEvents=\"no\"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>"
                        + var("Mute", "boolean", false) + var("PresetNameList", "string", false) + var("A_ARG_TYPE_PresetName", "string", false, "FactoryDefaults")
                        + var("LastChange", "string", true));
    }
    private static String scpdCm() {
        return scpd(
                action("GetProtocolInfo", "Source:out:SourceProtocolInfo", "Sink:out:SinkProtocolInfo")
                        + action("GetCurrentConnectionIDs", "ConnectionIDs:out:CurrentConnectionIDs")
                        + action("GetCurrentConnectionInfo", "ConnectionID:in:A_ARG_TYPE_ConnectionID", "RcsID:out:A_ARG_TYPE_RcsID", "AVTransportID:out:A_ARG_TYPE_AVTransportID",
                        "ProtocolInfo:out:A_ARG_TYPE_ProtocolInfo", "PeerConnectionManager:out:A_ARG_TYPE_ConnectionManager", "PeerConnectionID:out:A_ARG_TYPE_ConnectionID",
                        "Direction:out:A_ARG_TYPE_Direction", "Status:out:A_ARG_TYPE_ConnectionStatus"),
                var("SourceProtocolInfo", "string", true) + var("SinkProtocolInfo", "string", true) + var("CurrentConnectionIDs", "string", true)
                        + var("A_ARG_TYPE_ConnectionID", "i4", false) + var("A_ARG_TYPE_RcsID", "i4", false) + var("A_ARG_TYPE_AVTransportID", "i4", false)
                        + var("A_ARG_TYPE_ProtocolInfo", "string", false) + var("A_ARG_TYPE_ConnectionManager", "string", false)
                        + var("A_ARG_TYPE_Direction", "string", false, "Input", "Output") + var("A_ARG_TYPE_ConnectionStatus", "string", false, "OK", "Unknown"));
    }
}
