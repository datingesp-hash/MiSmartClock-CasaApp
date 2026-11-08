#!/usr/bin/env bash
# Build Casa.apk without Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Bump the VC/VN defaults on every release: Android refuses to install a lower versionCode.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar
# Signing: debug key by default; releases use KS=... KS_PASS=... KS_ALIAS=...
KS=${KS:-$HOME/.android/debug.keystore}
KS_PASS=${KS_PASS:-android}
KS_ALIAS=${KS_ALIAS:-}
[ -f "$KS" ] || [ "$KS_PASS" != android ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
  -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null
rm -rf build && mkdir -p build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets -o build/base.apk --java build/gen \
  --min-sdk-version 26 --target-sdk-version 33 --version-code "${VC:-20}" --version-name "${VN:-1.19}" build/res.zip
javac -nowarn -Xlint:-options --release 8 -classpath "$JAR" -encoding UTF-8 -d build/classes \
  $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -9 ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" ${KS_ALIAS:+--ks-key-alias "$KS_ALIAS"} --out Casa.apk build/aligned.apk
ls -l Casa.apk
