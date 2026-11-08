#!/system/bin/sh
# Xiaomi Mi Smart Clock - keep receiving multicast (SSDP) with the screen off.
# On screen off Android sends "SETSUSPENDMODE 1" to the MTK Wi-Fi driver, which then drops ALL multicast
# even with a MulticastLock held: Casa's DLNA receiver stops answering M-SEARCH and Music Assistant /
# Home Assistant lose it until the screen turns on. This turns suspend mode back off right after.
# Wi-Fi power save stays on (only the multicast filter / DTIM skipping goes away).
# Installed in /data/adb/service.d (run by magiskd at boot). Log: /data/local/tmp/smartclock-wifi.log
LOG=/data/local/tmp/smartclock-wifi.log
WPA="/vendor/bin/wpa_cli -g @android:wpa_wlan0 IFNAME=wlan0"
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
echo "$(date) start" > $LOG
off() { sleep 1; echo "$(date) $1: $($WPA DRIVER SETSUSPENDMODE 0)" >> $LOG; }
off boot
while true; do
  logcat -T 1 -s wpa_supplicant:I 2>/dev/null | while read -r l; do
    case "$l" in *"SETSUSPENDMODE 1"*) off "screen off" ;; esac
  done
  sleep 5 # logcat died (logd restart): reattach
done
