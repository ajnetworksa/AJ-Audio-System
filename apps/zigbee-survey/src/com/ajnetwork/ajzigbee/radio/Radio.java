package com.ajnetwork.ajzigbee.radio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Auto-detects the coordinator type on a serial transport and runs read-only scans on it. */
public final class Radio {
    public final Transport t;
    public final Model.CoordInfo info;
    private final Ezsp ezsp;
    private final Znp znp;
    private final Transport.Log log;

    private Radio(Transport t, Model.CoordInfo info, Ezsp e, Znp z, Transport.Log log) {
        this.t = t; this.info = info; this.ezsp = e; this.znp = z; this.log = log;
    }

    public static final int ALL_CHANNELS = 0x07FFF800;   // 11..26

    public static int mask(int... channels) { int m = 0; for (int c : channels) if (c >= 11 && c <= 26) m |= 1 << c; return m; }

    /**
     * Tries EZSP (Silicon Labs) and ZNP (TI) at the usual speeds. usbHint is the USB product/maker text and is only
     * used to try the likely protocol first. Returns null if nothing answered.
     */
    public static Radio detect(Transport t, String usbHint, Model.CoordInfo base, Transport.Log log) throws IOException {
        String h = usbHint == null ? "" : usbHint.toLowerCase(Locale.US);
        boolean tiFirst = h.contains("texas") || h.contains("cc26") || h.contains("cc13") || h.contains("cc253") || h.contains("dongle-p")
                || h.contains("zigstar") || h.contains("znp") || (h.contains("dongle plus") && !h.contains("v2"));   // Sonoff "Dongle Plus" = P (TI), "Plus V2" = E (EFR32)
        List<Runnable> order = new ArrayList<>();
        final Radio[] found = {null};
        final IOException[] err = {null};
        Runnable ez115 = () -> tryEzsp(t, 115200, base, log, found, err);
        Runnable ez460 = () -> tryEzsp(t, 460800, base, log, found, err);
        Runnable ez230 = () -> tryEzsp(t, 230400, base, log, found, err);
        Runnable ti115 = () -> tryZnp(t, 115200, base, log, found, err);
        if (tiFirst) { order.add(ti115); order.add(ez115); } else { order.add(ez115); order.add(ti115); }
        order.add(ez460); order.add(ez230);
        for (Runnable r : order) { if (found[0] != null) break; r.run(); }
        if (found[0] == null && err[0] != null && err[0].getMessage() != null && err[0].getMessage().contains("USB")) throw err[0];
        return found[0];
    }

    private static void tryEzsp(Transport t, int baud, Model.CoordInfo base, Transport.Log log, Radio[] found, IOException[] err) {
        try {
            log.i("trying Silicon Labs EZSP at " + baud);
            t.setBaud(baud);
            t.setLines(true, true);
            Ezsp e = new Ezsp(t, log);
            if (!e.start(1500)) return;
            Model.CoordInfo ci = base;
            ci.baud = baud;
            ci.protocol = "EZSP v" + e.protocol + " (Silicon Labs EmberZNet)";
            ci.family = "Silicon Labs EFR32 (EmberZNet NCP)";
            String vi = e.versionInfo();
            ci.firmware = "EmberZNet " + (vi.isEmpty() ? e.stackString() : vi);
            try { ci.ieee = Model.ieeeColons(e.eui64()); } catch (IOException x) { log.i("ezsp: eui64 " + x.getMessage()); }
            String mfg = e.mfgString(0x01), board = e.mfgString(0x02);
            ci.board = (mfg + (board.isEmpty() || board.equals(mfg) ? "" : " / " + board)).trim();
            ci.canEnergyScan = true;
            ci.beaconHasRssi = true;
            ci.beaconHasSource = false;
            ci.extra.put("Works with ZHA", e.protocol >= 4 ? "yes (EZSP v4+)" : "no");
            ci.extra.put("Works with Zigbee2MQTT", e.protocol >= 13 ? "yes – adapter: ember" : e.protocol >= 8 ? "only with the old ezsp driver – update firmware for 'ember'" : "no – needs newer firmware (EZSP 13+)");
            found[0] = new Radio(t, ci, e, null, log);
        } catch (IOException x) { err[0] = x; log.i("ezsp@" + baud + ": " + x.getMessage()); }
    }

    private static void tryZnp(Transport t, int baud, Model.CoordInfo base, Transport.Log log, Radio[] found, IOException[] err) {
        try {
            log.i("trying TI Z-Stack ZNP at " + baud);
            t.setBaud(baud);
            t.setLines(false, false);
            Znp z = new Znp(t, log);
            if (!z.start()) return;
            Model.CoordInfo ci = base;
            ci.baud = baud;
            z.fillInfo(ci);
            if (!ci.ieee.isEmpty()) ci.ieee = Model.ieeeColons(ci.ieee);
            ci.extra.put("Works with ZHA", "yes (radio type ZNP)");
            ci.extra.put("Works with Zigbee2MQTT", "yes – adapter: zstack");
            found[0] = new Radio(t, ci, null, z, log);
        } catch (IOException x) { err[0] = x; log.i("znp@" + baud + ": " + x.getMessage()); }
    }

    /**
     * One measurement: optional energy scan of all channels, then `rounds` beacon scans of the given channels.
     * Beacons of all rounds are kept (the analyzer averages them per network).
     */
    public Model.ScanResult measure(int beaconMask, int rounds, boolean energy) throws IOException {
        Model.ScanResult r = new Model.ScanResult();
        if (energy && ezsp != null) {
            try { r.energy.putAll(ezsp.energyScan(ALL_CHANNELS, 3)); } catch (IOException e) { log.i("energy scan: " + e.getMessage()); }
        }
        for (int i = 0; i < Math.max(1, rounds); i++) {
            List<Model.Beacon> b = ezsp != null ? ezsp.activeScan(beaconMask, 3) : znp.activeScan(beaconMask, 3);
            r.beacons.addAll(b);
        }
        log.i("measure: " + r.beacons.size() + " beacons, " + r.energy.size() + " energy channels");
        return r;
    }

    public boolean isEzsp() { return ezsp != null; }
}
