#!/usr/bin/env python3
"""Adds the "Play my phone" parts to the AndroidManifest that `flutter create` generates for AJ Audio Client.
usage: python3 tools/patch_client_manifest.py apps/phone-flutter/android/app/src/main/AndroidManifest.xml"""
import sys

p = sys.argv[1]
s = open(p, encoding="utf-8").read()
perms = [
    "android.permission.INTERNET",
    "android.permission.RECORD_AUDIO",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.WAKE_LOCK",
]
add = "".join(f'    <uses-permission android:name="{x}"/>\n' for x in perms if x not in s)
s = s.replace("<application", add + "    <application", 1)
if "usesCleartextTraffic" not in s:
    s = s.replace("<application", '<application android:usesCleartextTraffic="true"', 1)
components = """
        <activity android:name=".CaptureActivity" android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" android:excludeFromRecents="true"/>
        <activity android:name=".PickerActivity" android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" android:excludeFromRecents="true"
            android:taskAffinity="" android:launchMode="singleInstance"/>
        <service android:name=".CaptureService" android:exported="false"
            android:foregroundServiceType="mediaProjection"/>
        <service android:name=".CaptureTile" android:exported="true" android:label="Play my phone"
            android:icon="@mipmap/ic_launcher"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter><action android:name="android.service.quicksettings.action.QS_TILE"/></intent-filter>
        </service>
"""
if "CaptureService" not in s:
    i = s.rindex("</application>")
    s = s[:i] + components + "    " + s[i:]
# song title + cover of the playing app for the panels (optional notification access)
if "MediaWatch" not in s:
    i = s.rindex("</application>")
    s = s[:i] + """        <service android:name=".MediaWatch" android:exported="true" android:label="AJ Audio – song info for panels"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter><action android:name="android.service.notification.NotificationListenerService"/></intent-filter>
        </service>
    """ + s[i:]
open(p, "w", encoding="utf-8").write(s)
print("patched", p)
