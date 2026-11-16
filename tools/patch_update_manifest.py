#!/usr/bin/env python3
"""Adds in-app update support (AppUpdater) to a Flutter app's generated AndroidManifest.
usage: python3 tools/patch_update_manifest.py <AndroidManifest.xml>"""
import sys

p = sys.argv[1]
s = open(p, encoding="utf-8").read()
perm = "android.permission.REQUEST_INSTALL_PACKAGES"
if perm not in s:
    s = s.replace("<application", f'<uses-permission android:name="{perm}"/>\n    <application', 1)
if "AppUpdater$Result" not in s:
    i = s.rindex("</application>")
    s = s[:i] + '        <receiver android:name=".AppUpdater$Result" android:exported="false"/>\n    ' + s[i:]
open(p, "w", encoding="utf-8").write(s)
print("update support added to", p)
