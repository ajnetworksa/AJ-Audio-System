package com.ajnetwork.ajzigbee.radio;

import java.io.IOException;

/** Byte pipe to the radio (USB serial on the phone, a fake in tests). Pure Java – no Android imports in this package. */
public interface Transport {
    void setBaud(int baud) throws IOException;
    void setLines(boolean dtr, boolean rts) throws IOException;
    void write(byte[] b) throws IOException;
    /** reads up to buf.length bytes, waits at most timeoutMs; returns 0 on timeout */
    int read(byte[] buf, int timeoutMs) throws IOException;
    /** throw away anything already received */
    void drain();
    String describe();

    /** log sink used by the radio code */
    interface Log { void i(String s); }
}
