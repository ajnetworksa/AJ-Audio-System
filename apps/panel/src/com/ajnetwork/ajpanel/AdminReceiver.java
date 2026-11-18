package com.ajnetwork.ajpanel;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

/**
 * Lets AJ Panel be the panel's "device owner" – set once from a PC:
 *   adb shell dpm set-device-owner com.ajnetwork.ajpanel/.AdminReceiver   (tools\panel_silent_updates.bat)
 * Android then installs AJ Panel updates (Installer app, GitHub) without anyone tapping "Install" on the panel.
 * No other policy is used. Undo: panel_silent_updates.bat -off  (AJ Panel gives the role back itself).
 */
public class AdminReceiver extends DeviceAdminReceiver {
    static boolean owner(Context c) {
        try { return ((DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE)).isDeviceOwnerApp(c.getPackageName()); }
        catch (Exception e) { return false; }
    }

    /** give the device-owner role back (e.g. before uninstalling AJ Panel) */
    @SuppressWarnings("deprecation")
    static boolean release(Context c) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (!dpm.isDeviceOwnerApp(c.getPackageName())) return true;
            dpm.clearDeviceOwnerApp(c.getPackageName());
            return true;
        } catch (Exception e) { Log.e("release device owner", e); return false; }
    }

    static ComponentName component(Context c) { return new ComponentName(c, AdminReceiver.class); }
}
