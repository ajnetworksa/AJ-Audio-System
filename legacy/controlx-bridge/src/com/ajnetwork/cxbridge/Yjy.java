package com.ajnetwork.cxbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Talks to the YJY system apps through their public AIDL services using raw Binder transactions.
 *  - com.yjy.musicplayer  : com.yjy.musicplayer.IMusicplayerInterface (4 zones)
 *  - com.yjy.yjyhomeui    : com.yjy.yjyhomeui.IDeviceAidlInterface   (audio source per zone)
 */
public class Yjy {
    static final String PLAYER_PKG = "com.yjy.musicplayer";
    static final String PLAYER_ACTION = "com.yjy.musicplayer.ACTION.musicplayerAidl";
    static final String PLAYER_DESC = "com.yjy.musicplayer.IMusicplayerInterface";
    static final String HOME_PKG = "com.yjy.yjyhomeui";
    static final String HOME_ACTION = "com.yjy.yjyhomeui.action.ideviceAidlService";
    static final String HOME_DESC = "com.yjy.yjyhomeui.IDeviceAidlInterface";

    // transaction codes per zone (index 0 = zone 1 ... 3 = zone 4), taken from the YJY player's AIDL stub
    static final int[] GET_STATE   = {6, 24, 62, 80};
    static final int[] GET_PLAYING = {7, 25, 63, 81};
    static final int[] PLAY        = {8, 26, 64, 82};
    static final int[] PLAY_THIS   = {9, 27, 65, 83};
    static final int[] STOP        = {10, 28, 66, 84};
    static final int[] PAUSE       = {11, 29, 67, 85};
    static final int[] NEXT        = {12, 30, 68, 86};
    static final int[] PREVIOUS    = {13, 31, 69, 87};
    static final int[] SET_MODE    = {14, 32, 70, 88};
    static final int[] GET_MODE    = {15, 33, 71, 89};
    static final int[] GET_DURATION= {18, 36, 74, 92};
    static final int[] GET_TIME    = {19, 37, 75, 93};
    static final int[] SEEK        = {20, 38, 76, 94};
    static final int[] LIST_JSON   = {21, 39, 77, 95};
    static final int[] SET_NAME    = {41, 43, 58, 60};
    static final int[] GET_NAME    = {42, 44, 59, 61};

    private final Context ctx;
    private volatile IBinder player, home;

    private final ServiceConnection playerConn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) { player = b; Log.i("YJY player connected"); }
        public void onServiceDisconnected(ComponentName n) { player = null; Log.i("YJY player disconnected"); }
    };
    private final ServiceConnection homeConn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) { home = b; Log.i("YJY home connected"); }
        public void onServiceDisconnected(ComponentName n) { home = null; Log.i("YJY home disconnected"); }
    };

    public Yjy(Context c) { ctx = c.getApplicationContext(); }

    public void bind() {
        try {
            if (player == null || !player.isBinderAlive()) {
                boolean ok = ctx.bindService(new Intent(PLAYER_ACTION).setPackage(PLAYER_PKG), playerConn, Context.BIND_AUTO_CREATE);
                Log.i("bind YJY player: " + ok);
            }
        } catch (Exception e) { Log.e("bind player", e); }
        try {
            if (home == null || !home.isBinderAlive()) {
                boolean ok = ctx.bindService(new Intent(HOME_ACTION).setPackage(HOME_PKG), homeConn, Context.BIND_AUTO_CREATE);
                Log.i("bind YJY home: " + ok);
            }
        } catch (Exception e) { Log.e("bind home", e); }
    }

    public void unbind() {
        try { ctx.unbindService(playerConn); } catch (Exception ignored) {}
        try { ctx.unbindService(homeConn); } catch (Exception ignored) {}
        player = null; home = null;
    }

    public boolean playerReady() { return player != null && player.isBinderAlive(); }
    public boolean homeReady() { return home != null && home.isBinderAlive(); }

    // ---------------- generic transact helpers ----------------
    private interface Writer { void write(Parcel p); }
    private interface Reader<T> { T read(Parcel p); }

    private <T> T call(IBinder b, String desc, int code, Writer w, Reader<T> r, T def) {
        if (b == null) return def;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(desc);
            if (w != null) w.write(data);
            b.transact(code, data, reply, 0);
            reply.readException();
            return r == null ? def : r.read(reply);
        } catch (Exception e) {
            Log.e("transact " + desc + " #" + code, e);
            return def;
        } finally {
            data.recycle(); reply.recycle();
        }
    }

    private static int z(int zone) { return Math.max(1, Math.min(4, zone)) - 1; }

    // ---------------- player (zone = 1..4) ----------------
    /** 1 = playing, 2 = paused, 0 = nothing */
    public int getState(int zone) { return call(player, PLAYER_DESC, GET_STATE[z(zone)], null, Parcel::readInt, 0); }
    public String getPlayingJson(int zone) { return call(player, PLAYER_DESC, GET_PLAYING[z(zone)], null, Parcel::readString, null); }
    public void play(int zone) { call(player, PLAYER_DESC, PLAY[z(zone)], null, null, null); }
    public void pause(int zone) { call(player, PLAYER_DESC, PAUSE[z(zone)], null, null, null); }
    public void stop(int zone) { call(player, PLAYER_DESC, STOP[z(zone)], null, null, null); }
    public void next(int zone) { call(player, PLAYER_DESC, NEXT[z(zone)], null, null, null); }
    public void previous(int zone) { call(player, PLAYER_DESC, PREVIOUS[z(zone)], null, null, null); }
    public void playThis(int zone, final String musicInfoJson) { call(player, PLAYER_DESC, PLAY_THIS[z(zone)], p -> p.writeString(musicInfoJson), null, null); }
    /** YJY modes: 1 shuffle, 2 single repeat, 3 single then stop, other = list loop */
    public void setMode(int zone, final int m) { call(player, PLAYER_DESC, SET_MODE[z(zone)], p -> p.writeInt(m), null, null); }
    public int getMode(int zone) { return call(player, PLAYER_DESC, GET_MODE[z(zone)], null, Parcel::readInt, 0); }
    public long getDuration(int zone) { return call(player, PLAYER_DESC, GET_DURATION[z(zone)], null, Parcel::readLong, 0L); }
    public long getTime(int zone) { return call(player, PLAYER_DESC, GET_TIME[z(zone)], null, Parcel::readLong, 0L); }
    public void seek(int zone, final int ms) { call(player, PLAYER_DESC, SEEK[z(zone)], p -> p.writeInt(ms), null, null); }
    /** 0 = current play list */
    public String listJson(int zone, final int type) { return call(player, PLAYER_DESC, LIST_JSON[z(zone)], p -> p.writeInt(type), Parcel::readString, null); }
    public String getZoneName(int zone) { return call(player, PLAYER_DESC, GET_NAME[z(zone)], null, Parcel::readString, null); }
    public void setZoneName(int zone, final String n) { call(player, PLAYER_DESC, SET_NAME[z(zone)], p -> p.writeString(n), null, null); }

    // ---------------- home ui: audio source ----------------
    public int getAudioSource(final int zone) { return call(home, HOME_DESC, 1, p -> p.writeInt(zone), Parcel::readInt, -1); }
    public void setAudioSource(final int zone, final int src) { call(home, HOME_DESC, 2, p -> { p.writeInt(zone); p.writeInt(src); }, null, null); }
}
