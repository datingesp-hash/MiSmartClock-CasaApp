#!/system/bin/sh
# Xiaomi Mi Smart Clock (MT8167S, 1GB) - low-RAM tuning for SmartTube
# Installed in /data/adb/service.d (run by magiskd at boot). Log: /data/local/tmp/smartclock-tune.log
LOG=/data/local/tmp/smartclock-tune.log
exec >"$LOG" 2>&1
echo "start $(date)"

# --- kernel VM: reserve more free memory so kswapd/lmkd act before the system stalls
echo 8192 > /proc/sys/vm/min_free_kbytes
echo 50   > /proc/sys/vm/watermark_scale_factor
echo 0    > /proc/sys/vm/page-cluster
echo 100  > /proc/sys/vm/swappiness
echo 3    > /proc/sys/vm/dirty_ratio
echo 1    > /proc/sys/vm/dirty_background_ratio

# --- lmkd: react to memory pressure earlier, kill the heaviest cached app instead of stalling
R=/sbin/resetprop
[ -x $R ] || R="magisk resetprop"
$R ro.lmk.kill_heaviest_task true
$R ro.lmk.psi_partial_stall_ms 100
$R ro.lmk.psi_complete_stall_ms 500
$R ro.lmk.thrashing_limit 25
$R ro.lmk.swap_free_low_percentage 20
# dex2oat: keep it from eating all RAM
$R dalvik.vm.dex2oat-threads 1
$R dalvik.vm.dex2oat-Xmx 256m

until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
setprop lmkd.reinit 1
echo "lmkd reinit, vm: $(cat /proc/sys/vm/min_free_kbytes)"

# --- installd can't run dex2oat on this 4.14 kernel (no pidfd_open), so AOT key apps by hand
PKGS="org.smarttube.stable com.android.bluetooth com.android.providers.media.module com.android.permissioncontroller com.android.networkstack com.android.networkstack.tethering com.android.phone me.phh.treble.app com.android.settings com.archie.casa"
sleep 180
while true; do
  for p in $PKGS; do
    st=$(dumpsys package dexopt | grep -A2 "\[$p\]" | grep -o "status=[a-z-]*")
    prof=/data/misc/profiles/cur/0/$p/primary.prof
    # uncompiled, or only verified but a JIT profile now exists -> (re)compile with speed-profile
    if [ "$st" = "status=run-from-apk" ] || { [ "$st" = "status=verify" ] && [ -s "$prof" ]; }; then
      sh /data/adb/aot.sh "$p"
    fi
  done
  sleep 21600
done
