package com.ajnetwork.ajzigbee.radio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Plain data classes shared by the radio drivers, the survey and the report. */
public final class Model {
    private Model() { }

    /** What we learned about the plugged-in coordinator (read-only). */
    public static final class CoordInfo {
        public String protocol = "";        // "EZSP (Silicon Labs)" / "Z-Stack ZNP (Texas Instruments)"
        public String family = "";          // "Silicon Labs EFR32" / "TI CC26xx/CC13xx"
        public int baud;
        public String usbName = "";         // USB product / manufacturer string
        public String usbIds = "";          // VID:PID
        public String usbChip = "";         // USB-serial bridge chip (CP210x, CH34x, CDC, FTDI)
        public String ieee = "";            // EUI64 of the radio
        public String firmware = "";        // human readable firmware/stack version
        public String board = "";           // EZSP manufacturing string / board name
        public final Map<String, String> extra = new LinkedHashMap<>();
        public boolean canEnergyScan;
        public boolean beaconHasRssi;       // EZSP gives RSSI; TI gives LQI only
        public boolean beaconHasSource;     // TI gives the short address of each beacon sender

        public String summary() {
            StringBuilder s = new StringBuilder();
            s.append(protocol);
            if (!firmware.isEmpty()) s.append(" · ").append(firmware);
            if (!usbName.isEmpty()) s.append(" · ").append(usbName);
            return s.toString();
        }

        public List<String[]> rows() {
            List<String[]> r = new ArrayList<>();
            r.add(new String[]{"Protocol", protocol});
            r.add(new String[]{"Radio family", family});
            if (!board.isEmpty()) r.add(new String[]{"Board / maker string", board});
            r.add(new String[]{"Firmware", firmware});
            if (!ieee.isEmpty()) r.add(new String[]{"Radio IEEE (EUI64)", ieee});
            r.add(new String[]{"USB device", (usbName + " " + usbIds).trim()});
            r.add(new String[]{"USB-serial chip", usbChip});
            r.add(new String[]{"Speed", baud + " baud"});
            for (Map.Entry<String, String> e : extra.entrySet()) r.add(new String[]{e.getKey(), e.getValue()});
            r.add(new String[]{"Channel noise scan", canEnergyScan ? "yes" : "no (not offered by this firmware without starting a network)"});
            return r;
        }
    }

    /** One beacon heard during an active scan (from the coordinator or a router of a network). */
    public static final class Beacon {
        public int channel, pan, lqi, rssi = Integer.MIN_VALUE, src = -1, depth = -1;
        public String extPan = "";          // hex, MSB first like HA / Z2M show it
        public boolean permitJoin, routerCapacity = true;

        public boolean hasRssi() { return rssi != Integer.MIN_VALUE; }

        @Override public String toString() {
            return String.format(Locale.US, "ch%d pan 0x%04X ext %s lqi %d%s%s", channel, pan, extPan, lqi,
                    hasRssi() ? " rssi " + rssi : "", src >= 0 ? String.format(Locale.US, " from 0x%04X", src) : "");
        }
    }

    /** Result of one measurement at one spot. */
    public static final class ScanResult {
        public final List<Beacon> beacons = new ArrayList<>();
        public final Map<Integer, Integer> energy = new LinkedHashMap<>();   // channel -> max RSSI dBm
        public long time = System.currentTimeMillis();
    }

    public static String hex(byte[] b, int off, int len, boolean reverse) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < len; i++) s.append(String.format(Locale.US, "%02x", b[off + (reverse ? len - 1 - i : i)] & 0xFF));
        return s.toString();
    }

    public static String ieeeColons(String hex) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i + 1 < hex.length(); i += 2) { if (s.length() > 0) s.append(':'); s.append(hex, i, i + 2); }
        return s.toString();
    }

    /** same extended PAN id, written in either byte order (tools differ in how they print it) */
    public static boolean sameExtPan(String a, String b) {
        a = normExtPan(a); b = normExtPan(b);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (a.length() != 16 || b.length() != 16) return false;
        StringBuilder r = new StringBuilder();
        for (int i = 14; i >= 0; i -= 2) r.append(b, i, i + 2);
        return a.equals(r.toString());
    }

    /** "dd:cc:..", "0xddcc..", "ddcc.." -> "ddcc.." lower case */
    public static String normExtPan(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replace("0x", "").replaceAll("[^0-9a-f]", "");
    }
}
