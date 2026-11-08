#!/usr/bin/env bash
# Applies the MiSmartClock-Light device tuning over adb (needs root via phh-su).
# Safe to re-run. Time zone: TZ_ID=Area/City (default: this computer's time zone).
# Safe to re-run. See docs/DEVICE.md for what each step does and how to revert it.
set -euo pipefail
cd "$(dirname "$0")"
SU=/system/xbin/su

adb wait-for-device
adb shell "$SU -c id" | grep -q 'uid=0' || { echo "No root through $SU (phh-su). See docs/DEVICE.md"; exit 1; }

echo "== boot scripts"
adb push service.d/99-smartclock-tune.sh aot.sh /data/local/tmp/ >/dev/null
adb shell "$SU -c 'cat /data/local/tmp/99-smartclock-tune.sh > /data/adb/service.d/99-smartclock-tune.sh;
  cat /data/local/tmp/aot.sh > /data/adb/aot.sh;
  chmod 755 /data/adb/service.d/99-smartclock-tune.sh /data/adb/aot.sh'"

echo "== top button: 1 press = music play/pause, 2 = screen on/off"
adb push service.d/98-smartclock-buttons.sh keylayout/mtk-pmic-keys.kl /data/local/tmp/ >/dev/null
adb shell "$SU -c 'cat /data/local/tmp/98-smartclock-buttons.sh > /data/adb/service.d/98-smartclock-buttons.sh;
  chmod 755 /data/adb/service.d/98-smartclock-buttons.sh; K=/data/system/devices/keylayout; mkdir -p \$K;
  cat /data/local/tmp/mtk-pmic-keys.kl > \$K/mtk-pmic-keys.kl; chown -R system:system /data/system/devices;
  chmod 755 /data/system/devices \$K; chmod 644 \$K/mtk-pmic-keys.kl; restorecon -R /data/system/devices;
  rm /data/local/tmp/98-smartclock-buttons.sh /data/local/tmp/mtk-pmic-keys.kl'"

echo "== Wi-Fi: keep receiving multicast (DLNA discovery) with the screen off"
adb push service.d/97-smartclock-wifi.sh /data/local/tmp/ >/dev/null
adb shell "$SU -c 'cat /data/local/tmp/97-smartclock-wifi.sh > /data/adb/service.d/97-smartclock-wifi.sh;
  chmod 755 /data/adb/service.d/97-smartclock-wifi.sh; rm /data/local/tmp/97-smartclock-wifi.sh'"

echo "== time zone"
TZ_ID=${TZ_ID:-$(timedatectl show -p Timezone --value 2>/dev/null || readlink /etc/localtime | sed 's|.*zoneinfo/||')}
echo "   $TZ_ID"
adb shell "$SU -c 'cmd time_zone_detector suggest_manual_time_zone --zone_id $TZ_ID'" >/dev/null
adb shell settings put global auto_time_zone 0

echo "== Magisk module: no boot animation + instant brightness overlay"
adb push magisk/smartclock-noboot/module.prop magisk/smartclock-noboot/system.prop /data/local/tmp/ >/dev/null
adb shell "$SU -c 'M=/data/adb/modules/smartclock-noboot; mkdir -p \$M;
  cat /data/local/tmp/module.prop > \$M/module.prop; cat /data/local/tmp/system.prop > \$M/system.prop;
  chmod 755 \$M; chmod 644 \$M/module.prop \$M/system.prop'"
# framework overlay: instant brightness change when leaving the dimmed state (overlay/build.sh)
overlay/build.sh >/dev/null
adb push overlay/SmartClockOverlay.apk /data/local/tmp/ >/dev/null
adb shell "$SU -c 'O=/data/adb/modules/smartclock-noboot/system/product/overlay; mkdir -p \$O;
  cat /data/local/tmp/SmartClockOverlay.apk > \$O/SmartClockOverlay.apk; rm /data/local/tmp/SmartClockOverlay.apk;
  chmod 755 \$O \$O/.. \$O/../..; chmod 644 \$O/SmartClockOverlay.apk; chcon -R u:object_r:system_file:s0 \$O/../..'"

echo "== disabling unused packages"
# NEVER add com.android.sdksandbox / adservices / ondevicepersonalization: Android 13 will bootloop.
while read -r p; do
  [ -n "$p" ] && adb shell pm disable-user --user 0 "$p" >/dev/null 2>&1 || true
done < disabled-packages.txt
while read -r p; do
  [ -n "$p" ] && adb shell pm uninstall -k --user 0 "$p" >/dev/null 2>&1 || true
done < uninstalled-user0-packages.txt
adb shell pm clear com.android.providers.downloads >/dev/null 2>&1 || true
adb shell bmgr enable false >/dev/null 2>&1 || true
adb shell pm bg-dexopt-job --disable >/dev/null 2>&1 || true

echo "== system settings"
adb shell '
settings put global window_animation_scale 0
settings put global transition_animation_scale 0
settings put global animator_duration_scale 0
settings put global ble_scan_always_enabled 0
settings put global wifi_scan_always_enabled 0
settings put global stay_on_while_plugged_in 0
settings put system screen_off_timeout 60000
settings put system screen_brightness_mode 0
settings put system screen_brightness 190
settings put global network_recommendations_enabled 0
settings put global adaptive_battery_management_enabled 0
cmd location set-location-enabled false
device_config set_sync_disabled_for_tests persistent
device_config put activity_manager max_cached_processes 6
dumpsys deviceidle whitelist +org.smarttube.stable
cmd appops set org.smarttube.stable RUN_ANY_IN_BACKGROUND allow
' >/dev/null

echo "Done. Reboot the clock to apply the Magisk module and the boot script."
