package com.ajnetwork.ajpanel;
import com.ajnetwork.ajpanel.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** JVM test: Ha (WebSocket + HA protocol) against a mock Home Assistant, Dash generation, Skin/Lang. */
public class HaTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }
    static void waitFor(java.util.function.BooleanSupplier c, int ms) throws Exception { long t = System.currentTimeMillis(); while (!c.getAsBoolean() && System.currentTimeMillis() - t < ms) Thread.sleep(20); }

    // ------------------------------------------------------------------ mock HA
    static class Mock {
        final ServerSocket ss; final String token;
        final List<JSONObject> calls = new CopyOnWriteArrayList<>();
        final Map<String, Object> userData = new ConcurrentHashMap<>();
        volatile Socket current; volatile int connections;
        JSONArray states, areas, devices, entities;
        Mock(String token) throws Exception { ss = new ServerSocket(0); this.token = token; fixtures(); new Thread(this::accept).start(); }
        int port() { return ss.getLocalPort(); }
        void accept() { try { while (true) { Socket s = ss.accept(); new Thread(() -> serve(s)).start(); } } catch (Exception e) { } }
        static JSONObject o(Object... kv) throws Exception { JSONObject j = new JSONObject(); for (int i = 0; i < kv.length; i += 2) j.put((String) kv[i], kv[i + 1]); return j; }
        static JSONArray a(Object... v) { JSONArray j = new JSONArray(); for (Object x : v) j.put(x); return j; }
        void fixtures() throws Exception {
            areas = a(o("area_id", "living_room", "name", "Living Room"), o("area_id", "kitchen", "name", "Kitchen"), o("area_id", "majlis", "name", "Majlis"), o("area_id", "garage", "name", "Garage"));
            devices = a(o("id", "d1", "area_id", "living_room", "name", "Dimmer"), o("id", "d2", "area_id", "kitchen", "name", "Kettle plug"));
            entities = a(
                o("entity_id", "light.living_room_ceiling", "device_id", "d1", "area_id", JSONObject.NULL, "platform", "zha"),
                o("entity_id", "light.kitchen", "area_id", "kitchen", "platform", "zha"),
                o("entity_id", "switch.kitchen_kettle", "device_id", "d2", "platform", "zha"),
                o("entity_id", "switch.led_indicator", "device_id", "d2", "entity_category", "config", "platform", "zha"),
                o("entity_id", "light.hidden", "area_id", "kitchen", "hidden_by", "user", "platform", "zha"),
                o("entity_id", "cover.living_room_curtain", "area_id", "living_room", "platform", "zha"),
                o("entity_id", "climate.living_ac", "area_id", "living_room", "platform", "tuya"),
                o("entity_id", "scene.movie", "area_id", "living_room", "platform", "homeassistant"),
                o("entity_id", "sensor.living_temp", "area_id", "living_room", "platform", "zha"),
                o("entity_id", "sensor.living_humidity", "area_id", "living_room", "platform", "zha"),
                o("entity_id", "binary_sensor.garage_door", "area_id", "garage", "platform", "zha"),
                o("entity_id", "binary_sensor.kitchen_motion", "area_id", "kitchen", "platform", "zha"),
                o("entity_id", "media_player.ajpanel_aabbccddeeff", "platform", "music_assistant"));
            states = a(
                st("light.living_room_ceiling", "on", o("friendly_name", "Living Room Ceiling", "brightness", 204, "supported_color_modes", a("brightness"))),
                st("light.kitchen", "off", o("friendly_name", "Kitchen Light", "supported_color_modes", a("onoff"))),
                st("light.hidden", "off", o("friendly_name", "Hidden")),
                st("switch.kitchen_kettle", "off", o("friendly_name", "Kettle")),
                st("switch.led_indicator", "on", o("friendly_name", "LED")),
                st("cover.living_room_curtain", "open", o("friendly_name", "Living Room Curtain", "current_position", 60, "supported_features", 15)),
                st("climate.living_ac", "cool", o("friendly_name", "AC", "temperature", 23, "current_temperature", 25, "hvac_action", "cooling", "hvac_modes", a("off", "cool", "fan_only"), "target_temp_step", 1)),
                st("lock.front_door", "locked", o("friendly_name", "Front Door")),
                st("scene.movie", "unknown", o("friendly_name", "Movie", "entity_id", a("light.living_room_ceiling", "cover.living_room_curtain"))),
                st("scene.welcome", "unknown", o("friendly_name", "Welcome")),
                st("script.goodnight", "off", o("friendly_name", "Good night")),
                st("script.with_fields", "off", o("friendly_name", "Ask", "fields", o("x", o()))),
                st("sensor.living_temp", "24.5", o("friendly_name", "Temp", "device_class", "temperature")),
                st("sensor.living_humidity", "41", o("friendly_name", "Hum", "device_class", "humidity")),
                st("binary_sensor.garage_door", "on", o("friendly_name", "Garage Door", "device_class", "garage_door")),
                st("binary_sensor.kitchen_motion", "off", o("friendly_name", "Kitchen Motion", "device_class", "motion")),
                st("binary_sensor.smoke", "off", o("friendly_name", "Smoke", "device_class", "smoke")),
                st("media_player.ajpanel_aabbccddeeff", "playing", o("friendly_name", "Panel")),
                st("weather.home", "sunny", o("friendly_name", "Home", "temperature", 31, "humidity", 22)),
                st("sun.sun", "above_horizon", o()),
                st("sensor.energy_total", "1234.5", o("device_class", "energy", "state_class", "total_increasing")));
            // filler: make get_states > 64 KB (127-length frames)
            StringBuilder pad = new StringBuilder(); for (int i = 0; i < 300; i++) pad.append("x");
            for (int i = 0; i < 400; i++) states.put(st("sensor.filler_" + i, "1", o("friendly_name", "F" + i, "note", pad.toString() + "ü")));
        }
        static JSONObject st(String id, String s, JSONObject attrs) throws Exception { return o("entity_id", id, "state", s, "attributes", attrs); }

        void serve(Socket s) {
            try {
                current = s; connections++;
                InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
                String key = null; String line;
                while (!(line = readLine(in)).isEmpty()) if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(18).trim();
                String acc = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes()));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n").getBytes()); out.flush();
                send(out, o("type", "auth_required", "ha_version", "2026.11.0").toString(), false);
                JSONObject auth = new JSONObject(read(in));
                if (!token.equals(auth.optString("access_token"))) { send(out, o("type", "auth_invalid", "message", "bad").toString(), false); s.close(); return; }
                send(out, o("type", "auth_ok").toString(), false);
                int stateSub = -1;
                String msg;
                while ((msg = read(in)) != null) {
                    JSONObject m = new JSONObject(msg); int id = m.optInt("id"); String t = m.optString("type");
                    Object res = null; boolean ok = true; boolean frag = false;
                    switch (t) {
                        case "subscribe_events": if ("state_changed".equals(m.optString("event_type"))) stateSub = id; break;
                        case "get_config": res = o("location_name", "Riyadh", "version", "2026.11.0"); break;
                        case "get_states": res = states; frag = true; break;
                        case "config/area_registry/list": res = areas; break;
                        case "config/device_registry/list": res = devices; break;
                        case "config/entity_registry/list": res = entities; break;
                        case "frontend/get_user_data": res = o("value", userData.containsKey(m.optString("key")) ? userData.get(m.optString("key")) : JSONObject.NULL); break;
                        case "frontend/set_user_data": userData.put(m.optString("key"), m.opt("value")); break;
                        case "ping": send(out, o("id", id, "type", "pong").toString(), false); continue;
                        case "energy/get_prefs": res = o("energy_sources", a(o("type", "grid", "flow_from", a(o("stat_energy_from", "sensor.energy_total"))))); break;
                        case "recorder/statistics_during_period": res = o("sensor.energy_total", a(o("change", 1.5), o("change", 2.0), o("change", 0.5))); break;
                        case "recorder/statistic_during_period": res = o("change", 16.0); break;
                        case "call_service": {
                            calls.add(m);
                            if (m.optBoolean("return_response")) { res = o("response", o("weather.home", o("forecast", a(o("datetime", "2026-11-24T22:00:00+03:00", "temperature", 29, "condition", "clear-night"), o("datetime", "2026-11-24T23:00:00+03:00", "temperature", 28, "condition", "sunny"))))); break; }
                            String eid = m.optJSONObject("target") == null ? "" : m.optJSONObject("target").optString("entity_id");
                            res = o("context", o("id", "c1"));
                            send(out, o("id", id, "type", "result", "success", true, "result", res).toString(), false);
                            if (stateSub > 0 && eid.equals("light.living_room_ceiling")) {
                                JSONObject ns = st(eid, "on", o("friendly_name", "Living Room Ceiling", "brightness", 102, "supported_color_modes", a("brightness")));
                                send(out, o("id", stateSub, "type", "event", "event", o("event_type", "state_changed", "data", o("entity_id", eid, "new_state", ns))).toString(), false);
                            }
                            continue;
                        }
                        default: ok = false;
                    }
                    JSONObject r = o("id", id, "type", "result", "success", ok);
                    if (ok) r.put("result", res == null ? JSONObject.NULL : res); else r.put("error", o("code", "unknown_command", "message", "Unknown command."));
                    send(out, r.toString(), frag);
                }
            } catch (Exception e) { }
        }
        static String readLine(InputStream in) throws IOException { StringBuilder b = new StringBuilder(); int c; while ((c = in.read()) != -1 && c != '\n') if (c != '\r') b.append((char) c); return b.toString(); }
        static String read(InputStream in) throws IOException {
            int b0 = in.read(), b1 = in.read(); if (b0 < 0) return null;
            if ((b0 & 0x0F) == 8) return null;
            if ((b1 & 0x80) == 0) throw new IOException("client frame not masked");
            long len = b1 & 0x7F;
            if (len == 126) len = (in.read() << 8) | in.read(); else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
            byte[] mk = new byte[4]; new DataInputStream(in).readFully(mk);
            byte[] p = new byte[(int) len]; new DataInputStream(in).readFully(p);
            for (int i = 0; i < p.length; i++) p[i] ^= mk[i & 3];
            int op = b0 & 0x0F;
            if (op == 9 || op == 10) return read(in);
            return new String(p, StandardCharsets.UTF_8);
        }
        static synchronized void send(OutputStream out, String s, boolean fragment) throws IOException {
            byte[] p = s.getBytes(StandardCharsets.UTF_8);
            if (!fragment || p.length < 3000) { frame(out, 0x81, p, 0, p.length); }
            else {
                int a = p.length / 3, b = 2 * p.length / 3;
                frame(out, 0x01, p, 0, a); frame(out, 0x89, new byte[0], 0, 0); frame(out, 0x00, p, a, b - a); frame(out, 0x80, p, b, p.length - b);
            }
            out.flush();
        }
        static void frame(OutputStream out, int b0, byte[] p, int off, int len) throws IOException {
            out.write(b0);
            if (len < 126) out.write(len); else if (len < 65536) { out.write(126); out.write(len >> 8); out.write(len); } else { out.write(127); for (int i = 7; i >= 0; i--) out.write((int) ((long) len >> (8 * i))); }
            out.write(p, off, len);
        }
        void drop() { try { current.close(); } catch (Exception e) { } }
    }

    public static void main(String[] args) throws Exception {
        Mock mock = new Mock("tok123");
        List<String> events = new CopyOnWriteArrayList<>();
        Ha ha = new Ha("http://127.0.0.1:" + mock.port(), "tok123", "ajpanel_aabbccddeeff", Runnable::run);
        ha.addListener((w, id) -> events.add(w + ":" + id));
        ha.start();
        waitFor(() -> ha.connected && ha.entities.length() > 0 && ha.dataLoaded, 6000);
        Thread.sleep(300);
        check(ha.connected, "connects + authenticates");
        check(ha.states.size() == 421, "get_states (fragmented, >64 KB, ping between fragments): " + ha.states.size());
        check(ha.areas.length() == 4 && ha.devices.length() == 2 && ha.entities.length() == 13, "registries loaded");
        check("Riyadh".equals(ha.config.optString("location_name")), "get_config");
        check(ha.attrs("sensor.filler_3").optString("note").endsWith("ü"), "UTF-8 across frames");
        check(events.contains("status:null") && events.contains("states:null") && events.contains("registry:null") && events.contains("data:null"), "listener events " + events);

        Dash d = Dash.build(ha, Dash.merge(null, null), "Living room", "ajpanel_aabbccddeeff");
        List<String> roomIds = new ArrayList<>(); for (Dash.Room r : d.rooms) roomIds.add(r.id);
        check(roomIds.equals(Arrays.asList("living_room", "kitchen", "")), "rooms in HA order, empty dropped, Other last: " + roomIds);
        check(d.mine != null && d.mine.id.equals("living_room"), "panel room matched by name");
        List<String> lt = new ArrayList<>(); for (Dash.Tile t : d.byId.get("living_room").tiles) lt.add(t.kind + ":" + t.name);
        check(lt.equals(Arrays.asList("dimmer:Ceiling", "cover:Curtain", "climate:AC", "scene:Movie")), "living room tiles (device area, kind order, names cleaned): " + lt);
        List<String> kt = new ArrayList<>(); for (Dash.Tile t : d.byId.get("kitchen").tiles) kt.add(t.id);
        check(kt.equals(Arrays.asList("light.kitchen", "switch.kitchen_kettle")), "kitchen: config/hidden skipped, plain light: " + kt);
        check(d.tiles.get("light.kitchen").kind.equals(Dash.LIGHT), "on/off light = LIGHT");
        check(!d.tiles.containsKey("media_player.ajpanel_aabbccddeeff") && !d.tiles.containsKey("script.with_fields"), "own player + scripts with fields skipped");
        check(d.byId.get("living_room").temps.contains("sensor.living_temp") && d.byId.get("living_room").hums.contains("sensor.living_humidity"), "room sensors");
        check(d.openNow(ha).equals(Collections.singletonList("binary_sensor.garage_door")) && d.alarmsNow(ha).isEmpty(), "open garage alert, smoke ok");
        check("weather.home".equals(d.weather), "weather entity");
        List<String> fv = new ArrayList<>(); for (Dash.Tile t : d.favs) fv.add(t.id);
        System.out.println("favs " + fv);
        check(fv.size() == 8 && fv.get(0).equals("climate.living_ac") && fv.get(1).equals("light.living_room_ceiling") && fv.get(2).equals("scene.movie") && fv.get(3).equals("cover.living_room_curtain") && fv.get(4).equals("lock.front_door"), "auto Home tiles");
        check(d.scenes.size() == 3, "scenes + scripts: " + d.scenes);
        check(Dash.isOn(ha, d.tiles.get("climate.living_ac")) && Dash.isOn(ha, d.tiles.get("lock.front_door")), "isOn climate/lock");

        // overrides
        JSONObject ov = new JSONObject("{\"hide\":[\"scene.movie\"],\"names\":{\"light.living_room_ceiling\":\"Chandelier\"},\"favs\":[\"switch.kitchen_kettle\",\"nope.x\"],\"rooms_order\":[\"kitchen\"],\"move\":{\"switch.kitchen_kettle\":\"majlis\"},\"room_names\":{\"kitchen\":\"Matbakh\"}}");
        Dash d2 = Dash.build(ha, Dash.merge(new JSONObject("{\"owner_en\":\"Al Rashid\",\"names\":{\"light.kitchen\":\"Spots\"}}"), ov), "Living room", "x");
        List<String> r2 = new ArrayList<>(); for (Dash.Room r : d2.rooms) r2.add(r.id + "=" + r.name);
        check(r2.get(0).equals("kitchen=Matbakh") && r2.contains("majlis=Majlis"), "rooms_order + room_names + move: " + r2);
        check(d2.tiles.get("light.living_room_ceiling").name.equals("Chandelier") && d2.tiles.get("light.kitchen").name.equals("Spots"), "names (panel + site merged)");
        check(!d2.tiles.containsKey("scene.movie"), "hide");
        check(d2.favs.size() == 1 && d2.favs.get(0).id.equals("switch.kitchen_kettle"), "favs override");

        // service call + live state
        events.clear();
        ha.call("light", "turn_on", "light.living_room_ceiling", new JSONObject().put("brightness_pct", 40));
        waitFor(() -> ha.attrs("light.living_room_ceiling").optInt("brightness") == 102, 3000);
        JSONObject call = mock.calls.get(mock.calls.size() - 1);
        check(call.optString("domain").equals("light") && call.optJSONObject("service_data").optInt("brightness_pct") == 40 && call.optJSONObject("target").optString("entity_id").equals("light.living_room_ceiling"), "call_service message");
        check(ha.attrs("light.living_room_ceiling").optInt("brightness") == 102 && events.contains("state:light.living_room_ceiling"), "state_changed event applied");

        // extras
        ha.refreshExtras();
        waitFor(() -> ha.forecast.length() == 2 && ha.energyToday > 0 && ha.energyYesterday > 0, 3000);
        check(ha.forecast.length() == 2, "weather.get_forecasts with return_response");
        check(Math.abs(ha.energyToday - 4.0) < 1e-9 && ha.energyHours.length == 3 && ha.energyYesterday == 16.0, "energy today from statistics: " + ha.energyToday);

        // settings stored in HA, survive reconnect
        ha.savePanelData(new JSONObject("{\"area\":\"kitchen\",\"look\":\"pearl\"}"), null);
        ha.saveSiteData(new JSONObject("{\"owner_en\":\"Al Rashid\",\"owner_ar\":\"آل راشد\"}"), null);
        Thread.sleep(300);
        int before = mock.connections;
        ha.panelData = null; ha.siteData = null;
        mock.drop();
        waitFor(() -> mock.connections > before && ha.connected && ha.panelData != null, 8000);
        check(mock.connections > before && ha.connected, "reconnects after drop");
        check(ha.panelData != null && "kitchen".equals(ha.panelData.optString("area")) && ha.siteData != null && "آل راشد".equals(ha.siteData.optString("owner_ar")), "panel + site data read back from HA (Arabic intact)");
        ha.stop();

        // bad token
        Ha bad = new Ha("127.0.0.1:" + mock.port(), "wrong", "p", Runnable::run);
        bad.start();
        waitFor(() -> bad.status.startsWith("token"), 4000);
        check(bad.status.equals("token rejected") && !bad.connected, "bad token -> 'token rejected'");
        bad.stop();

        // misc
        check(Ha.wsUrl("http://192.168.8.5:8123/").equals("ws://192.168.8.5:8123/api/websocket") && Ha.wsUrl("192.168.8.5").equals("ws://192.168.8.5:8123/api/websocket") && Ha.wsUrl("https://ha.example.com").equals("wss://ha.example.com/api/websocket"), "wsUrl");
        check(Skin.pick("auto", "above_horizon") == Skin.PEARL && Skin.pick("auto", "below_horizon") == Skin.GRAPHITE && Skin.pick("graphite", "above_horizon") == Skin.GRAPHITE && Skin.pick("pearl", null) == Skin.PEARL, "Skin.pick");
        Lang.ar = true; check(Lang.s("home").equals("الرئيسية") && Lang.f("lights_on", 3).startsWith("3 "), "Arabic strings"); Lang.ar = false;
        check(Dash.cleanName("Living Room Ceiling Light", "Living Room", "light.x").equals("Ceiling Light") && Dash.cleanName("Living Room", "Living Room", "light.x").equals("Living Room"), "cleanName");
        check(Dash.matchArea(ha.areas, "AJ Kitchen panel").equals("kitchen") && Dash.matchArea(ha.areas, "majlis").equals("majlis"), "matchArea");
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
