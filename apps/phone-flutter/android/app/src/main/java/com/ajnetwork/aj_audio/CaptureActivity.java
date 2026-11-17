package com.ajnetwork.aj_audio;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Invisible step between the app/tile and the capture service:
 * 1. microphone permission (Android requires it for capturing other apps' sound),
 * 2. Android's "start recording or casting?" consent (shown every time – Android rule),
 * 3. starts CaptureService with the consent.
 */
@TargetApi(29)
public class CaptureActivity extends Activity {
    private static final int REQ_PERM = 1, REQ_PROJECTION = 2;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Play my phone needs Android 10 or newer", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (Capture.prefs(this).getString("target", "").isEmpty()) {
            Toast.makeText(this, "Open AJ Audio Client and pick a room or group first", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            need.add("android.permission.POST_NOTIFICATIONS");
        if (need.isEmpty()) askProjection();
        else requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Sound permission is needed to send your phone's audio", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        askProjection();
    }

    private void askProjection() {
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        // Android 14+: ask only for the entire screen (no "Share one app" choice) – a single-app share
        // would only hear that one app. Still Android's own consent dialog; skipped when the installer
        // allowed PROJECT_MEDIA for this phone (tools\\phone_allow_capture.bat).
        Intent i = Build.VERSION.SDK_INT >= 34
                ? m.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                : m.createScreenCaptureIntent();
        startActivityForResult(i, REQ_PROJECTION);
    }

    @Override protected void onActivityResult(int req, int code, Intent data) {
        if (req == REQ_PROJECTION && code == RESULT_OK && data != null) {
            Intent s = new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra("code", code)
                    .putExtra("data", data);
            startForegroundService(s);
        } else if (req == REQ_PROJECTION) {
            Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
