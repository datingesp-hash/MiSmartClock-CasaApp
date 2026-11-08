#!/usr/bin/env bash
# Build SmartClockOverlay.apk (framework RRO) with aapt2 + apksigner.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; BT=$SDK/build-tools/36.0.0; JAR=$SDK/platforms/android-36/android.jar
KS=${KS:-$HOME/.android/debug.keystore}
rm -rf build && mkdir build
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o build/unsigned.apk --min-sdk-version 28 --target-sdk-version 28 build/res.zip
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out SmartClockOverlay.apk build/aligned.apk
ls -l SmartClockOverlay.apk
