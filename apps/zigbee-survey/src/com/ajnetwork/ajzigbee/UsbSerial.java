package com.ajnetwork.ajzigbee;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.ajnetwork.ajzigbee.radio.Transport;

import java.io.IOException;
import java.util.Locale;

/**
 * USB-OTG serial for the common Zigbee sticks, no root and no extra library:
 * CP210x (Sonoff ZBDongle-P, HA SkyConnect/ZBT-1, Elelabs), CH34x / CH9102 (Sonoff ZBDongle-E, SLZB, many clones),
 * CDC-ACM (CH9102F, CC2531, nRF/TI LaunchPads) and FTDI.
 */
public final class UsbSerial implements Transport {
    public enum Chip { CP210X, CH34X, CDC, FTDI }

    private final UsbDevice dev;
    private final UsbDeviceConnection con;
    private final Chip chip;
    private UsbInterface dataIf, ctrlIf;
    private UsbEndpoint in, out;
    private boolean dtr, rts;
    private final byte[] ring = new byte[16384];
    private int head, tail;            // bytes [tail, head) are waiting to be read
    private volatile boolean closed;
    private Thread reader;

    public static boolean looksLikeSerial(UsbDevice d) { return chipOf(d) != null; }

    public static Chip chipOf(UsbDevice d) {
        int v = d.getVendorId();
        if (v == 0x10C4) return Chip.CP210X;
        if (v == 0x0403) return Chip.FTDI;
        if (v == 0x1A86 && (d.getProductId() == 0x7523 || d.getProductId() == 0x5523 || d.getProductId() == 0x7522)) return Chip.CH34X;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA || f.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) return Chip.CDC;
        }
        if (v == 0x1A86) return Chip.CDC;
        return null;
    }

    public static String label(UsbDevice d) {
        String p = d.getProductName(), m = d.getManufacturerName();
        String s = ((m != null ? m.trim() + " " : "") + (p != null ? p.trim() : "")).trim();
        return s.isEmpty() ? d.getDeviceName() : s;
    }

    public static String ids(UsbDevice d) { return String.format(Locale.US, "%04X:%04X", d.getVendorId(), d.getProductId()); }

    public UsbSerial(UsbManager m, UsbDevice d) throws IOException {
        dev = d; chip = chipOf(d);
        if (chip == null) throw new IOException("USB: not a serial device");
        con = m.openDevice(d);
        if (con == null) throw new IOException("USB: could not open (permission?)");
        pickInterfaces();
        if (!con.claimInterface(dataIf, true)) throw new IOException("USB: interface busy");
        if (ctrlIf != null && ctrlIf != dataIf) con.claimInterface(ctrlIf, true);
        init();
        reader = new Thread(this::readLoop, "usb-rx");
        reader.setDaemon(true);
        reader.start();
    }

    public Chip chip() { return chip; }
    public String chipName() {
        switch (chip) { case CP210X: return "Silicon Labs CP210x"; case CH34X: return "WCH CH34x"; case FTDI: return "FTDI"; default: return "CDC-ACM (e.g. WCH CH9102)"; }
    }

    private void pickInterfaces() throws IOException {
        for (int i = 0; i < dev.getInterfaceCount() && dataIf == null; i++) {
            UsbInterface f = dev.getInterface(i);
            UsbEndpoint bi = null, bo = null;
            for (int e = 0; e < f.getEndpointCount(); e++) {
                UsbEndpoint ep = f.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) bi = ep; else bo = ep;
            }
            if (bi != null && bo != null) { dataIf = f; in = bi; out = bo; }
        }
        if (dataIf == null) throw new IOException("USB: no data endpoints");
        if (chip == Chip.CDC) {
            for (int i = 0; i < dev.getInterfaceCount(); i++)
                if (dev.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_COMM) { ctrlIf = dev.getInterface(i); break; }
            if (ctrlIf == null) ctrlIf = dataIf;
        }
    }

    // ---------- chip specific ----------
    private int ctl(int type, int req, int value, int index, byte[] data) {
        return con.controlTransfer(type, req, value, index, data, data == null ? 0 : data.length, 1000);
    }

    private int ifIndex() { return dataIf.getId(); }

    private void init() throws IOException {
        switch (chip) {
            case CP210X:
                ctl(0x41, 0x00, 0x0001, ifIndex(), null);             // IFC_ENABLE
                ctl(0x41, 0x03, 0x0800, ifIndex(), null);             // 8N1
                break;
            case CH34X:
                byte[] b = new byte[2];
                ctl(0xC0, 0x5F, 0, 0, b);
                ctl(0x40, 0xA1, 0, 0, null);
                setBaud(115200);
                ctl(0xC0, 0x95, 0x2518, 0, b);
                ctl(0x40, 0x9A, 0x2518, 0x00C3, null);                // RX + TX enabled, 8 bit
                ctl(0xC0, 0x95, 0x0706, 0, b);
                ctl(0x40, 0xA1, 0x501F, 0xD90A, null);
                setBaud(115200);
                break;
            case FTDI:
                ctl(0x40, 0x00, 0, 0, null);                          // reset
                ctl(0x40, 0x04, 0x0008, 0, null);                     // 8N1
                ctl(0x40, 0x02, 0, 0, null);                          // no flow control
                break;
            default:
                break;
        }
    }

    @Override public void setBaud(int baud) throws IOException {
        int r;
        switch (chip) {
            case CP210X: {
                byte[] d = {(byte) baud, (byte) (baud >> 8), (byte) (baud >> 16), (byte) (baud >> 24)};
                r = ctl(0x41, 0x1E, 0, ifIndex(), d);
                break;
            }
            case CH34X: {
                long factor, divisor;
                if (baud == 921600) { divisor = 7; factor = 0xF300; }
                else {
                    factor = 1532620800L / baud; divisor = 3;
                    while (factor > 0xFFF0 && divisor > 0) { factor >>= 3; divisor--; }
                    factor = 0x10000 - factor;
                }
                divisor |= 0x0080;
                r = ctl(0x40, 0x9A, 0x1312, (int) ((factor & 0xFF00) | divisor), null);
                if (r >= 0) r = ctl(0x40, 0x9A, 0x0F2C, (int) (factor & 0xFF), null);
                break;
            }
            case FTDI: {
                // FT232R/FT-X: 3 MHz / divisor with 1/8 fractions
                int div8 = Math.round(24000000f / baud);              // divisor * 8
                int whole = div8 >> 3, frac = div8 & 7;
                int[] code = {0, 3, 2, 4, 1, 5, 6, 7};
                int v = whole | (code[frac] << 14);
                r = ctl(0x40, 0x03, v & 0xFFFF, (v >> 16) & 0xFFFF, null);
                break;
            }
            default: {
                byte[] lc = {(byte) baud, (byte) (baud >> 8), (byte) (baud >> 16), (byte) (baud >> 24), 0, 0, 8};
                r = ctl(0x21, 0x20, 0, ctrlIf.getId(), lc);           // SET_LINE_CODING
                break;
            }
        }
        if (r < 0) throw new IOException("USB: could not set speed " + baud);
        sleep(30);
        drain();
    }

    @Override public void setLines(boolean dtr, boolean rts) throws IOException {
        this.dtr = dtr; this.rts = rts;
        switch (chip) {
            case CP210X: ctl(0x41, 0x07, (dtr ? 1 : 0) | (rts ? 2 : 0) | 0x0300, ifIndex(), null); break;
            case CH34X: ctl(0x40, 0xA4, ~((dtr ? 1 << 5 : 0) | (rts ? 1 << 6 : 0)) & 0xFFFF, 0, null); break;
            case FTDI:
                ctl(0x40, 0x01, (dtr ? 1 : 0) | 0x0100, 0, null);
                ctl(0x40, 0x01, (rts ? 2 : 0) | 0x0200, 0, null);
                break;
            default: ctl(0x21, 0x22, (dtr ? 1 : 0) | (rts ? 2 : 0), ctrlIf.getId(), null); break;
        }
    }

    @Override public void write(byte[] b) throws IOException {
        if (closed) throw new IOException("USB: closed");
        int off = 0;
        while (off < b.length) {
            int n = Math.min(b.length - off, out.getMaxPacketSize() * 4);
            byte[] part = new byte[n]; System.arraycopy(b, off, part, 0, n);
            int w = con.bulkTransfer(out, part, n, 1000);
            if (w < 0) throw new IOException("USB: write failed (stick unplugged?)");
            off += w;
        }
    }

    private void readLoop() {
        byte[] buf = new byte[Math.max(64, in.getMaxPacketSize()) * 8];
        int mps = in.getMaxPacketSize();
        while (!closed) {
            int n = con.bulkTransfer(in, buf, buf.length, 200);
            if (n <= 0) continue;
            if (chip == Chip.FTDI) {       // every packet starts with 2 modem-status bytes
                for (int p = 0; p < n; p += mps) {
                    int end = Math.min(n, p + mps);
                    if (end - p > 2) push(buf, p + 2, end - p - 2);
                }
            } else push(buf, 0, n);
        }
    }

    private synchronized void push(byte[] b, int off, int n) {
        for (int i = 0; i < n; i++) {
            ring[head] = b[off + i];
            head = (head + 1) % ring.length;
            if (head == tail) tail = (tail + 1) % ring.length;   // overflow: drop oldest
        }
        notifyAll();
    }

    @Override public synchronized int read(byte[] buf, int timeoutMs) throws IOException {
        long dl = System.currentTimeMillis() + timeoutMs;
        while (head == tail) {
            if (closed) throw new IOException("USB: closed");
            long left = dl - System.currentTimeMillis();
            if (left <= 0) return 0;
            try { wait(left); } catch (InterruptedException e) { return 0; }
        }
        int n = 0;
        while (tail != head && n < buf.length) { buf[n++] = ring[tail]; tail = (tail + 1) % ring.length; }
        return n;
    }

    @Override public synchronized void drain() { tail = head; }

    @Override public String describe() { return label(dev) + " (" + ids(dev) + ", " + chipName() + ")"; }

    public void close() {
        closed = true;
        try { if (reader != null) reader.join(500); } catch (InterruptedException ignored) { }
        try { con.releaseInterface(dataIf); } catch (Exception ignored) { }
        try { con.close(); } catch (Exception ignored) { }
    }

    private static void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
}
