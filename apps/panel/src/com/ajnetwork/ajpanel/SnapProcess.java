package com.ajnetwork.ajpanel;

import android.content.Context;
import android.media.AudioManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs and supervises the bundled snapclient (libsnapclient.so, Snapcast 0.29, GPLv3).
 * - one instance only: kills a leftover child from a previous run (fixes BUG-015 orphan players)
 * - restarts with back-off when it exits (Wi-Fi drop, server restart)
 */
public class SnapProcess {
    public interface Listener { void onSnapState(boolean running, String line); }

    private final Context ctx;
    private final Cfg cfg;
    private final Listener listener;
    private volatile boolean wanted;
    private volatile Process proc;
    private Thread loop;
    public volatile String lastLine = "";
    public volatile boolean connected;
    public volatile int restarts;

    public SnapProcess(Context c, Cfg cfg, Listener l) { this.ctx = c; this.cfg = cfg; this.listener = l; }

    private File pidFile() { return new File(ctx.getFilesDir(), "snapclient.pid"); }

    public synchronized void start() {
        if (wanted) return;
        wanted = true;
        killLeftover();
        loop = new Thread(this::runLoop, "snapclient-supervisor");
        loop.start();
    }

    public synchronized void stop() {
        wanted = false;
        Process p = proc;
        if (p != null) p.destroy();
        if (loop != null) loop.interrupt();
        connected = false;
    }

    public void restart() { stop(); try { Thread.sleep(300); } catch (InterruptedException ignored) { } start(); }

    private void killLeftover() {
        try {
            File f = pidFile();
            if (!f.exists()) return;
            BufferedReader r = new BufferedReader(new FileReader(f));
            int pid = Integer.parseInt(r.readLine().trim()); r.close();
            File cmd = new File("/proc/" + pid + "/cmdline");
            if (cmd.exists()) {
                BufferedReader c = new BufferedReader(new FileReader(cmd));
                String s = c.readLine(); c.close();
                if (s != null && s.contains("libsnapclient")) {
                    android.os.Process.sendSignal(pid, 9);
                    Log.i("killed leftover snapclient pid " + pid);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Exception e) { Log.e("leftover check", e); }
    }

    private static int pidOf(Process p) {
        try { java.lang.reflect.Field f = p.getClass().getDeclaredField("pid"); f.setAccessible(true); return f.getInt(p); }
        catch (Exception e) { return -1; }
    }

    private void runLoop() {
        long backoff = 1000;
        while (wanted) {
            long started = System.currentTimeMillis();
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(ctx.getApplicationInfo().nativeLibraryDir + "/libsnapclient.so");
                cmd.add("-h"); cmd.add(cfg.host());
                cmd.add("-p"); cmd.add(String.valueOf(cfg.snapPort()));
                cmd.add("--hostID"); cmd.add(cfg.hostId());
                cmd.add("--player"); cmd.add(cfg.engine());
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                String rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                String fpb = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
                cmd.add("--sampleformat"); cmd.add(cfg.resample() && rate != null ? rate + ":*:*" : "*:16:*");
                if (cfg.latencyMs() != 0) { cmd.add("--latency"); cmd.add(String.valueOf(cfg.latencyMs())); }
                cmd.add("--logfilter"); cmd.add("*:info,Stats:debug");
                ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                if (cfg.resample() && rate != null) env.put("SAMPLE_RATE", rate);
                if (cfg.resample() && fpb != null) env.put("FRAMES_PER_BUFFER", fpb);
                Log.i("start snapclient " + cmd.subList(1, cmd.size()));
                proc = pb.start();
                int pid = pidOf(proc);
                if (pid > 0) try (FileWriter w = new FileWriter(pidFile())) { w.write(String.valueOf(pid)); }
                listener.onSnapState(true, "started");
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    lastLine = line;
                    if (line.contains("Connected to") || line.contains("ServerSettings")) connected = true;
                    if (line.contains("onError") || line.contains("Failed to connect") || line.contains("Connection refused")) connected = false;
                    // "No chunks available" = idle, once per second -> don't flood the log
                    if (!line.contains("Stats") && !line.contains("No chunks available")) Log.i("snap: " + line);
                    listener.onSnapState(true, line);
                }
                int code = proc.waitFor();
                Log.i("snapclient exited " + code);
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                Log.e("snapclient", e);
            }
            connected = false;
            proc = null;
            //noinspection ResultOfMethodCallIgnored
            pidFile().delete();
            listener.onSnapState(false, "stopped");
            if (!wanted) break;
            restarts++;
            if (System.currentTimeMillis() - started > 30000) backoff = 1000;
            try { Thread.sleep(backoff); } catch (InterruptedException e) { break; }
            backoff = Math.min(backoff * 2, 15000);
        }
    }
}
