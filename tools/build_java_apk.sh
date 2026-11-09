#!/bin/bash
# Gradle-free APK build for our plain-Java apps.   usage: tools/build_java_apk.sh <app dir>
# <app dir> has AndroidManifest.xml, src/**.java and optionally lib/<abi>/*.so
# Env: SDK (Android SDK with platforms/android-35 + build-tools/35.0.0), AAPT2, UAS (uber-apk-signer jar)
set -e
APP=$(cd "$1" && pwd)
SDK=${SDK:-/mnt/user-data/uploads/Sdk}
AJAR=$SDK/platforms/android-35/android.jar
BT=$SDK/build-tools/35.0.0
D8=$BT/lib/d8.jar
STUBS=$BT/core-lambda-stubs.jar
AAPT2=${AAPT2:-$BT/aapt2}
UAS=${UAS:-/home/claude/tools/uas.jar}
M=$APP/AndroidManifest.xml
VER=$(grep -o 'versionName="[^"]*"' "$M" | cut -d'"' -f2)
MIN=$(grep -o 'minSdkVersion="[0-9]*"' "$M" | cut -d'"' -f2)
TGT=$(grep -o 'targetSdkVersion="[0-9]*"' "$M" | cut -d'"' -f2)
NAME=$(grep -o 'android:label="[^"]*"' "$M" | head -1 | cut -d'"' -f2 | tr ' ' '_')
B=$APP/build
rm -rf "$B"; mkdir -p "$B/classes" "$B/dex" "$B/apk"
javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath "$AJAR:$STUBS" -d "$B/classes" $(find "$APP/src" -name '*.java') 2>&1 | grep -v "^warning\|^Note\|bootstrap class path\|JAVA_TOOL" || true
[ -n "$(find "$B/classes" -name '*.class' | head -1)" ] || { echo "javac failed"; exit 1; }
java -cp "$D8" com.android.tools.r8.D8 --release --min-api "$MIN" --lib "$AJAR" --output "$B/dex" $(find "$B/classes" -name '*.class') 2>&1 | grep -v JAVA_TOOL || true
"$AAPT2" link -o "$B/base.apk" -I "$AJAR" --manifest "$M" --min-sdk-version "$MIN" --target-sdk-version "$TGT"
cd "$B/apk"; unzip -q ../base.apk; cp ../dex/classes.dex .
[ -d "$APP/lib" ] && cp -r "$APP/lib" .
zip -q -0 ../unsigned.apk resources.arsc; zip -q -r -9 ../unsigned.apk . -x resources.arsc
cd ..; java -jar "$UAS" -a unsigned.apk -o signed 2>&1 | grep -v JAVA_TOOL >/dev/null
OUT="$B/${NAME}_v$VER.apk"; cp signed/*.apk "$OUT"; ls -la "$OUT"
