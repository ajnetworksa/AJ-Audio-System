package com.ajnetwork.aj_audio;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile "Play my phone": one tap from anywhere (pull down the notification shade). */
@TargetApi(34)
public class CaptureTile extends TileService {
    @Override public void onStartListening() { refresh(); }

    @Override public void onClick() {
        CaptureService.restoreIfNeeded(this);
        // like AirPlay / Media output: show the rooms and groups to pick from (and Stop when playing)
        Intent i = new Intent(this, PickerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapseLegacy(i);
        }
    }

    @SuppressWarnings("deprecation")
    private void startActivityAndCollapseLegacy(Intent i) { startActivityAndCollapse(i); }

    private void refreshLater() { new android.os.Handler(getMainLooper()).postDelayed(this::refresh, 600); }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        String name = Capture.prefs(this).getString("targetName", "");
        t.setState(CaptureService.running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(CaptureService.running ? "Playing on " + name : "Play my phone");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(CaptureService.running ? "tap to stop" : (name.isEmpty() ? "AJ Audio" : name));
        t.updateTile();
    }
}
