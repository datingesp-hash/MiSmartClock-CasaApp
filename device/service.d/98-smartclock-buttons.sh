#!/system/bin/sh
# Xiaomi Mi Smart Clock - top button (mic-mute key, scancode 248 on "mtk-pmic-keys").
#   1 press  -> play/pause Casa's music player
#   2 presses -> screen on/off
# Android ignores the key thanks to /data/system/devices/keylayout/mtk-pmic-keys.kl (otherwise it is POWER).
# Installed in /data/adb/service.d (run by magiskd at boot). Log: /data/local/tmp/smartclock-buttons.log
LOG=/data/local/tmp/smartclock-buttons.log
exec >"$LOG" 2>&1
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
DEV=$(for e in /sys/class/input/event*; do
  [ "$(cat $e/device/name 2>/dev/null)" = mtk-pmic-keys ] && echo /dev/input/${e##*/}; done)
[ -n "$DEV" ] || { echo "mtk-pmic-keys not found"; exit 1; }
echo "start $(date) dev=$DEV"
WINDOW=0.4 # seconds to wait for a second press

pend=
getevent -l "$DEV" | while read -r line; do
  case "$line" in *KEY_MICMUTE*DOWN*) ;; *) continue ;; esac
  if [ -n "$pend" ] && kill "$pend" 2>/dev/null; then
    pend= # second press while the first was still waiting
    echo "$(date +%T) double: screen"
    input keyevent POWER
  else
    ( sleep $WINDOW
      echo "$(date +%T) single: play/pause"
      am broadcast -a com.archie.casa.PLAY_PAUSE -n com.archie.casa/.Remote >/dev/null ) &
    pend=$!
  fi
done
