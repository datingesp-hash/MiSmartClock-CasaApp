#!/usr/bin/env bash
# Build Casa, install it on the clock, copy config.json into the app's private storage (root),
# AOT-compile it (installd can't on this kernel) and make it the home screen.
# usage: ./deploy.sh path/to/config.json   (APK=path/to/Casa.apk to skip building)
set -euo pipefail
cd "$(dirname "$0")"
CFG=${1:?usage: ./deploy.sh config.json}
SU=/system/xbin/su
PKG=com.archie.casa

APK=${APK:-}
if [ -z "$APK" ]; then ./build.sh; APK=Casa.apk; fi
adb install -r "$APK"
adb push "$CFG" /data/local/tmp/casa.json >/dev/null
adb shell "$SU -c 'U=\$(stat -c %u /data/data/$PKG); D=/data/data/$PKG/files; mkdir -p \$D;
  cat /data/local/tmp/casa.json > \$D/config.json; rm -f /data/local/tmp/casa.json;
  chown -R \$U:\$U \$D; chmod 700 \$D; chmod 600 \$D/config.json;
  chcon \$(stat -c %C /data/data/$PKG) \$D \$D/config.json;
  sh /data/adb/aot.sh $PKG speed'"
adb shell cmd package set-home-activity $PKG/.MainActivity
adb shell pm disable-user --user 0 com.android.launcher3 >/dev/null 2>&1 || true
adb shell am start -n $PKG/.MainActivity >/dev/null
echo "Casa deployed."
