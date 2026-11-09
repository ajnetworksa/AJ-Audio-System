package com.ajnetwork.cxbridge;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Minimal Snapcast stream server (protocol used by snapclient / the Snapcast Android app).
 * One PCM stream, sent with server timestamps; every client plays it at timestamp + bufferMs,
 * so all panels play in sync.
 *
 * Message header (26 bytes, little endian):
 *   uint16 type, uint16 id, uint16 refersTo, int32 sent.sec, int32 sent.usec,
 *   int32 received.sec, int32 received.usec, uint32 payloadSize
 * Types: 1 CodecHeader, 2 WireChunk, 3 ServerSettings, 4 Time, 5 Hello, 7 ClientInfo
 */
public class SnapServer {
    public static final int PORT = 1704;
    public final int rate, bits = 16, channels = 2;
    public volatile int bufferMs = 1000;

    private ServerSocket ss;
    private volatile boolean running;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final long t0 = System.nanoTime();

    public SnapServer(int rate) { this.rate = rate; }

    /** server clock in microseconds (monotonic) */
    public long nowUs() { return (System.nanoTime() - t0) / 1000 + 1_000_000_000L; }

    public int clientCount() { return clients.size(); }
    public String clientList() {
        StringBuilder b = new StringBuilder();
        for (Client c : clients) b.append(c.name).append(" (").append(c.ip).append(")  ");
        return b.toString();
    }

    public void start() throws IOException {
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new java.net.InetSocketAddress(PORT));
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    Socket s = ss.accept();
                    s.setTcpNoDelay(true);
                    Client c = new Client(s);
                    clients.add(c);
                    c.start();
                } catch (Exception e) { if (running) Log.e("snap accept", e); }
            }
        }, "snap-accept").start();
        Log.i("group server listening on TCP " + PORT + "  " + rate + " Hz");
    }

    public void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (Exception ignored) {}
        for (Client c : clients) c.close();
        clients.clear();
    }

    /** send one PCM chunk (16-bit LE stereo) captured at server time tsUs to every client */
    public void broadcast(long tsUs, byte[] pcm, int len) {
        if (clients.isEmpty()) return;
        ByteBuffer p = ByteBuffer.allocate(12 + len).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt((int) (tsUs / 1_000_000)).putInt((int) (tsUs % 1_000_000)).putInt(len).put(pcm, 0, len);
        byte[] payload = p.array();
        for (Client c : clients) c.queue(2, 0, payload);
    }

    // ---------------------------------------------------------------- framing
    private byte[] header(int type, int id, int refersTo, long sentUs, long recvUs, int size) {
        ByteBuffer b = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) type).putShort((short) id).putShort((short) refersTo);
        b.putInt((int) (sentUs / 1_000_000)).putInt((int) (sentUs % 1_000_000));
        b.putInt((int) (recvUs / 1_000_000)).putInt((int) (recvUs % 1_000_000));
        b.putInt(size);
        return b.array();
    }

    private byte[] codecHeader() {
        ByteBuffer w = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        int blockAlign = channels * bits / 8;
        w.put("RIFF".getBytes()).putInt(36).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * blockAlign)
                .putShort((short) blockAlign).putShort((short) bits).put("data".getBytes()).putInt(0);
        byte[] codec = "pcm".getBytes();
        ByteBuffer p = ByteBuffer.allocate(4 + codec.length + 4 + 44).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt(codec.length).put(codec).putInt(44).put(w.array());
        return p.array();
    }

    private byte[] serverSettings() {
        byte[] js = ("{\"bufferMs\":" + bufferMs + ",\"latency\":0,\"muted\":false,\"volume\":100}").getBytes(StandardCharsets.UTF_8);
        ByteBuffer p = ByteBuffer.allocate(4 + js.length).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt(js.length).put(js);
        return p.array();
    }

    // ---------------------------------------------------------------- client session
    private class Client {
        final Socket s; final String ip; String name = "?";
        final BlockingQueue<Object[]> out = new ArrayBlockingQueue<>(400);
        volatile boolean alive = true, ready;
        Client(Socket s) { this.s = s; this.ip = s.getInetAddress().getHostAddress(); }

        void start() {
            new Thread(this::readLoop, "snap-rx-" + ip).start();
            new Thread(this::writeLoop, "snap-tx-" + ip).start();
        }

        /** queue a message; type 2 (audio) only after the client said hello */
        void queue(int type, int refersTo, byte[] payload) { queue(type, refersTo, payload, 0); }
        void queue(int type, int refersTo, byte[] payload, long recvUs) {
            if (type == 2 && !ready) return;
            if (!out.offer(new Object[]{type, refersTo, payload, recvUs})) {
                // client too slow: drop oldest audio to keep up
                out.poll(); out.offer(new Object[]{type, refersTo, payload, recvUs});
            }
        }

        void readLoop() {
            try {
                DataInputStream in = new DataInputStream(s.getInputStream());
                byte[] h = new byte[26];
                while (alive) {
                    in.readFully(h);
                    long recvUs = nowUs();
                    ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
                    int type = b.getShort() & 0xffff, id = b.getShort() & 0xffff; b.getShort();
                    long sentUs = b.getInt() * 1_000_000L + b.getInt();
                    b.getInt(); b.getInt();
                    int size = b.getInt();
                    if (size < 0 || size > 1_000_000) throw new IOException("bad size " + size);
                    byte[] pl = new byte[size];
                    in.readFully(pl);
                    if (type == 4) {                      // Time: reply with latency = server recv - client sent
                        long lat = recvUs - sentUs;
                        ByteBuffer t = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                        t.putInt((int) Math.floorDiv(lat, 1_000_000L)).putInt((int) Math.floorMod(lat, 1_000_000L));
                        queueFront(4, id, t.array(), recvUs);
                    } else if (type == 5) {               // Hello
                        String js = new String(pl, 4, Math.max(0, size - 4), StandardCharsets.UTF_8);
                        try { org.json.JSONObject o = new org.json.JSONObject(js); name = o.optString("HostName", ip); } catch (Exception ignored) {}
                        Log.i("group client joined: " + name + " " + ip);
                        queue(3, id, serverSettings());
                        queue(1, 0, codecHeader());
                        ready = true;
                    }
                    // ClientInfo (7) and others are ignored
                }
            } catch (Exception e) {
                if (alive) Log.i("group client left: " + name + " " + ip + " (" + e.getMessage() + ")");
            }
            close();
        }

        private void queueFront(int type, int refersTo, byte[] payload, long recvUs) {
            // time replies must not wait behind audio
            timeReplies.offer(new Object[]{type, refersTo, payload, recvUs});
        }
        final BlockingQueue<Object[]> timeReplies = new ArrayBlockingQueue<>(50);

        void writeLoop() {
            try {
                OutputStream os = s.getOutputStream();
                while (alive) {
                    Object[] m = timeReplies.poll();
                    if (m == null) m = out.poll(20, TimeUnit.MILLISECONDS);
                    if (m == null) continue;
                    int type = (Integer) m[0], ref = (Integer) m[1];
                    byte[] pl = (byte[]) m[2];
                    long recv = (Long) m[3];
                    os.write(header(type, 0, ref, nowUs(), recv, pl.length));
                    os.write(pl);
                    if (timeReplies.isEmpty() && out.isEmpty()) os.flush();
                }
            } catch (Exception e) { /* handled by reader */ }
            close();
        }

        void close() {
            if (!alive) return;
            alive = false;
            clients.remove(this);
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}
