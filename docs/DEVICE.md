# Changes made to the clock

Everything is done over `adb` with root through **phh-su**: `adb shell /system/xbin/su -c '...'`.
Magisk 25.2 is installed (its daemon `magiskd` runs `/data/adb/service.d` and the modules), but its `su`
refuses adb and warns "su binary not from Magisk" because the GSI ships phh-su. The warning is harmless.

[`device/install.sh`](../device/install.sh) applies everything below in one go.

## Device

| | |
|---|---|
| Model | Xiaomi Mi Smart Clock (LX04) |
| SoC | MediaTek MT8167S, 4× Cortex-A53, kernel 4.14, **32-bit armv7** userspace |
| GPU | PowerVR Rogue GE8300 |
| RAM | 1 GB (only ~500 MB of *lowmem* usable by the kernel) |
| Screen | 800×480 |
| ROM | LineageOS 20 (Android 13), phh GSI `lineage_a64_bvS` userdebug |
| Root | Magisk 25.2 + phh-su (`/system/xbin/su`) |

## Initial diagnosis

| Symptom / data | Meaning |
|---|---|
| Memory PSI `full avg300 ≈ 20 %` almost idle, 350 MB in swap | thrashing: the system spends its time moving pages around |
| `min_free_kbytes = 2832` | tiny kernel reserve: it stalls instead of freeing memory in time |
| Boot history `kernel_panic,oom` | out-of-memory reboots |
| `dumpsys package dexopt`: 47 apps `run-from-apk` | **installd can't compile anything** (see below) |
| `android.process.media` crash loop: `Can't downgrade database from version 115 to 114` | downloads DB from a newer version |
| `MemoryUsageAllocGPUMemUMAMax ≈ 765 MiB` after boot | the boot animation filled the GPU (see below) |

### installd can't compile on this kernel

```
installd: pidfd_open failed for pid NNNN: Function not implemented
installd: Dex2oat invocation ... failed with 0x0009
```

Android 13 uses `pidfd_open` (kernel ≥ 5.3) to watch `dex2oat`; the clock's 4.14 kernel doesn't have it, so
**no app is ever compiled** and everything runs on JIT (more CPU and more anonymous RAM). Fix: run
`dex2oat32` by hand as root with [`device/aot.sh`](../device/aot.sh):

```bash
adb shell /system/xbin/su -c 'sh /data/adb/aot.sh org.smarttube.stable'   # speed-profile with the JIT profile
adb shell /system/xbin/su -c 'sh /data/adb/aot.sh com.archie.casa speed'
```

- Uses `speed-profile` with the app's JIT profile (`/data/misc/profiles/cur/0/<pkg>/primary.prof`); without a
  profile, `verify`. SmartTube with full `speed` took 59 MB of odex; with `speed-profile`, 3 MB.
- Apps in `/data/app` → `oat/arm/base.odex`; system/APEX apps → `/data/dalvik-cache/arm/<path@>@classes.dex`.
- Class loader context `PCL[]{}` (Android 13 no longer accepts `&`).
- Compiled: SmartTube, Casa, Bluetooth, MediaProvider, PermissionController, NetworkStack, Tethering, Phone,
  Settings, phh Treble app.
- Android's background compilation (`bg-dexopt-job`) is disabled: it always failed.

### The boot animation filled the GPU

`/product/media/bootanimation.zip`: `1080 360 60`, 480 frames in 5 parts. Looping parts are kept as GPU
textures: the PowerVR driver reached **765 MiB** during boot. Result: lmkd killed almost everything,
`system_server` hung for ~2 min and restarted. With the animation disabled the peak is **33 MB**, it boots in
~2 min and `system_server` doesn't restart.

## What gets installed (persistent)

### 1. Boot script `/data/adb/service.d/99-smartclock-tune.sh`

[Source](../device/service.d/99-smartclock-tune.sh). Run by `magiskd` on every boot; log in
`/data/local/tmp/smartclock-tune.log`.

| Setting | Before | After | Why |
|---|---|---|---|
| `vm.min_free_kbytes` | 2832 | 8192 | reserve so kswapd/lmkd act before stalling |
| `vm.watermark_scale_factor` | 10 | 50 | kswapd starts freeing earlier |
| `vm.swappiness` | 150 | 100 | less pressure on zram |
| `vm.page-cluster` | 0 | 0 | (already right for zram) |
| `vm.dirty_ratio` / `dirty_background_ratio` | — | 3 / 1 | small, frequent writes |
| `ro.lmk.kill_heaviest_task` | false | true | frees more with each kill |
| `ro.lmk.psi_partial_stall_ms` | 200 (low_ram) | 100 | lmkd reacts earlier |
| `ro.lmk.psi_complete_stall_ms` | 700 | 500 | |
| `ro.lmk.thrashing_limit` | 30 | 25 | |
| `ro.lmk.swap_free_low_percentage` | 10 | 20 | kills before running out of swap |
| `dalvik.vm.dex2oat-threads` / `-Xmx` | (default) / 512m | 1 / 256m | so dex2oat doesn't take the system down |

`ro.lmk.*` are changed with `resetprop` and applied with `setprop lmkd.reinit 1`.
Every 6 h it also checks whether a key app lost its compilation (e.g. after an update) and recompiles it.

### 2. Magisk module `smartclock-noboot`

`/data/adb/modules/smartclock-noboot/system.prop` → `debug.sf.nobootanimation=1`. Black screen while booting.

It also carries `system/product/overlay/SmartClockOverlay.apk` ([source](../device/overlay/),
`overlay/build.sh`): a framework RRO with `config_brightness_ramp_rate_fast/slow = 0`. Android took
**~2.5 s** to go from dimmed to normal brightness (60/255 per second ramp, and any value is clamped to
1.0/s, ~0.6 s); with 0 there is no ramp and the change is instant. It is preinstalled (mounted by Magisk)
with `targetSdk 28`, which lets it change framework resources that aren't *overlayable*. Needs a reboot.

### Top button

The mic-mute button is `KEY_MICMUTE` (code 248) on `mtk-pmic-keys`, and the vendor `Generic.kl` maps it to
**POWER**. To give it another use:

- [`device/keylayout/mtk-pmic-keys.kl`](../device/keylayout/mtk-pmic-keys.kl) in
  `/data/system/devices/keylayout/` (Android looks it up by device name before `Generic.kl`): leaves 248
  unmapped, so Android ignores it. Loaded when the input device is opened (reboot).
- [`/data/adb/service.d/98-smartclock-buttons.sh`](../device/service.d/98-smartclock-buttons.sh) reads the
  button with `getevent` and waits 0.4 s for a second press: **1 press** = Casa play/pause
  (`am broadcast ... com.archie.casa/.Remote`, ~1 s on this clock), **2 presses** = `input keyevent POWER`
  (screen on/off). Log in `/data/local/tmp/smartclock-buttons.log`.
- Without POWER there is no long-press power menu: shut down or reboot from Settings or with
  `adb shell reboot -p` / `adb reboot`.

### 3. Disabled packages

[`device/disabled-packages.txt`](../device/disabled-packages.txt) with `pm disable-user --user 0`
(re-enable: `pm enable <pkg>`): cell broadcasts, backups (Seedvault, CalyxOS contacts backup, local transport;
`bmgr enable false`), calendar, SMS/MMS/STK, printing, Jelly browser, gallery, recorder, MTP, NFC, live
wallpapers, wallet, Fully Kiosk, **Launcher3**, etc.

[`device/uninstalled-user0-packages.txt`](../device/uninstalled-user0-packages.txt) with
`pm uninstall -k --user 0` (restore: `cmd package install-existing <pkg>`): `com.android.emulator.multidisplay`
and `org.lineageos.audiofx`. They are **persistent**: even disabled, Android starts them anyway
(~100 MB between the two).

### 4. Android settings

- Animations at 0 (window, transition, animator).
- No location, no background Wi-Fi/BLE scanning, no network recommendations or adaptive battery.
- `device_config activity_manager max_cached_processes 6` (device_config sync disabled so it persists).
- SmartTube on the deviceidle whitelist and with `RUN_ANY_IN_BACKGROUND`.
- Screen timeout **1 min** (with the room light on Casa keeps it dimmed, see [APP.md](APP.md));
  `stay_on_while_plugged_in 0`; manual brightness **190/255** (at maximum the backlight heats the front).
- Manual time zone and `auto_time_zone 0` (without telephony or location it stayed on GMT; the time itself
  still comes from NTP).
- Casa as launcher (`cmd package set-home-activity com.archie.casa/.MainActivity`); 3-button navigation.
  **Recents doesn't work** without Launcher3.

### 5. Recommended SmartTube settings

Max quality 480p (the screen is 800×480), **AVC or VP9** codec (the MT8167 has hardware decoders for both;
**not AV1**, which would run in software and saturate the CPU), low buffer, no animated previews or chat.

## Careful

- **Don't disable `com.android.sdksandbox`** (nor `com.android.adservices.api` nor
  `com.android.ondevicepersonalization.services`). Nothing happens while running, but on reboot
  `system_server` fails with `There should exactly one sdk sandbox package; found 0` and the clock bootloops.
  Fix: with root, `abx2xml /data/system/users/0/package-restrictions.xml`, remove `enabled="3"` from those
  entries, `xml2abx`, delete `package-restrictions-backup.xml` and `setprop ctl.restart zygote`.
- `mv` between `/data/local/tmp` and `/data/data` fails with phh-su: copy with `cat source > dest`.
- `pkill -f name` inside `su -c "..."` kills itself if the command line contains that name.

## Performance (measured)

Idle with Casa on screen: CPU ~1 % at 598 MHz (the minimum; `ondemand`, cores power down on their own),
48–49 °C (the MT8167 doesn't throttle until ~85 °C), no memory pressure (PSI ~0). The ~5 *load average* is
misleading: it counts kernel threads in uninterruptible wait. When measuring: every adb command (more so with
`su`, which sends an `am broadcast`) wakes a core to 1.5 GHz; read `scaling_cur_freq` right after a `sleep`.
Heat comes from the backlight, Wi-Fi and, above all, video decoding in SmartTube.

## Undo

```bash
adb shell /system/xbin/su -c 'rm /data/adb/service.d/99-smartclock-tune.sh /data/adb/service.d/98-smartclock-buttons.sh /data/adb/aot.sh; rm -r /data/adb/modules/smartclock-noboot; rm /data/system/devices/keylayout/mtk-pmic-keys.kl'
while read p; do adb shell pm enable "$p"; done < device/disabled-packages.txt
while read p; do adb shell cmd package install-existing "$p"; done < device/uninstalled-user0-packages.txt
adb shell cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher
adb shell bmgr enable true
adb reboot
```
