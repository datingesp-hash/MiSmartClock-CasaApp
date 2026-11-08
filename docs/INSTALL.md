# Installation

Everything is done from a computer over `adb`, with root through **phh-su**
(`adb shell /system/xbin/su -c '...'`). Read [DEVICE.md](DEVICE.md) before applying the device fixes:
it explains every change and how to revert it.

## 0. Before you start

- The clock runs an Android 13 GSI with phh-su and Magisk (see [Requirements](../README.md#requirements)).
- `adb devices` lists the clock and `adb shell /system/xbin/su -c id` prints `uid=0`.
  Magisk's own `su` refuses adb and warns about a "competing su binary" — that warning is harmless.
- Install **SmartTube** (`org.smarttube.stable`) if you want YouTube: Casa's red button opens it.

## 1. Device fixes

```bash
device/install.sh
adb reboot
```

What it does (all reversible, details in [DEVICE.md](DEVICE.md)):

1. Boot script `/data/adb/service.d/99-smartclock-tune.sh` (VM + lmkd tuning, AOT compilation of key apps)
   and `/data/adb/aot.sh`.
2. Top button: `98-smartclock-buttons.sh` + a key layout so 1 press = play/pause, 2 = screen on/off.
3. Time zone: your computer's, or `TZ_ID=Area/City device/install.sh`.
4. Magisk module `smartclock-noboot`: no boot animation + overlay for instant brightness changes
   (builds `device/overlay/SmartClockOverlay.apk` — needs the Android SDK; if you don't have it, comment
   out the overlay block in `install.sh`).
5. Disables the packages in `device/disabled-packages.txt` and removes the two in
   `device/uninstalled-user0-packages.txt` for user 0.
6. System settings: no animations, no location/background scans, 1 min screen timeout, brightness 190/255,
   SmartTube allowed in the background.

> ⚠️ **Never disable `com.android.sdksandbox`, `com.android.adservices.api` or
> `com.android.ondevicepersonalization.services`**: Android 13 will bootloop. The fix is in
> [DEVICE.md](DEVICE.md#careful).

## 2. Casa

1. Create a **long-lived access token** in Home Assistant (*Profile → Security*).
2. Copy the example config and edit it (reference in [APP.md](APP.md#configuration)):

   ```bash
   cp app/config.example.json ~/casa-config.json
   ```

3. Deploy. Either with the APK from [Releases](https://github.com/datingesp-hash/MiSmartClock-Light/releases/latest):

   ```bash
   APK=~/Downloads/Casa-v1.18.apk app/deploy.sh ~/casa-config.json
   ```

   or building from source (needs the Android SDK and JDK 17+):

   ```bash
   app/deploy.sh ~/casa-config.json
   ```

`deploy.sh` installs the APK, copies `config.json` into the app's **private** storage
(`/data/data/com.archie.casa/files/config.json`, never inside the APK), AOT-compiles Casa (installd can't on
this kernel), makes it the home screen and disables Launcher3.

The config holds your HA token: keep it out of git (`config.json` is in `.gitignore`).

### Changing the config later

Edit your file and run the same `deploy.sh` command again. Casa reloads it on start.

## 3. Music (optional)

The receiver runs as soon as Casa is installed. Home Assistant shows a "discovered" notification for
**Mi Smart Clock** (DLNA Digital Media Renderer). See [MUSIC.md](MUSIC.md) for Music Assistant and phone apps.

## Updating

Download the new APK and run the `deploy.sh` line again (it reinstalls keeping data).
The boot script re-compiles Casa if an update leaves it uncompiled.

> Releases are signed with the project's release key. If you previously installed a build you made yourself
> (signed with your debug key), uninstall it first: `adb uninstall com.archie.casa`.

## Uninstalling

See [DEVICE.md → Undo](DEVICE.md#undo). To remove only Casa:

```bash
adb shell pm enable com.android.launcher3
adb shell cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher
adb uninstall com.archie.casa
```
