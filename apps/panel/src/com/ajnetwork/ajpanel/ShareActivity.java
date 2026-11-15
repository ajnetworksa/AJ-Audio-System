package com.ajnetwork.ajpanel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible step before sharing: Android needs the microphone permission and a "capture" grant from an
 * activity. deploy_ajpanel.bat pre-grants both (pm grant RECORD_AUDIO, appops PROJECT_MEDIA allow),
 * so on the panels this closes immediately without asking.
 */
public class ShareActivity extends Activity {
    private static final int REQ_MIC = 1, REQ_CAPTURE = 2;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "This panel's Android is too old to share its sound (needs Android 10)", Toast.LENGTH_LONG).show();
            finish(); return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        else capture();
    }

    private void capture() {
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) capture();
        else { Toast.makeText(this, "Sharing needs the audio permission", Toast.LENGTH_LONG).show(); finish(); }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_CAPTURE && result == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, ShareService.class).putExtra("code", result).putExtra("data", data));
        } else if (code == REQ_CAPTURE) {
            Toast.makeText(this, "Sharing was not allowed", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
