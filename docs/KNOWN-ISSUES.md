# Known issues

## Occasional reboot after long SmartTube sessions

After a long while watching videos in SmartTube the clock can reboot. The previous boot's kernel log
(`/proc/last_kmsg`, root only) says:

```
Kernel panic - not syncing: Out of memory and no killable processes...
```

- **"no killable processes"**: the kernel OOM killer found nothing to kill — lmkd had already killed everything
  killable and RAM was still full. So the memory was held by something that **doesn't belong to any process**:
  driver memory (PowerVR GPU, ION video buffers), zram or kernel memory (*slab*).
- The panic memory dump didn't make it into the log (the buffer is truncated), so the culprit is unknown.
- Normal state measured with SmartTube open: GPU ~11 MB in SmartTube, ION ~24 MB, zram ~54 MB of RAM,
  ~430 MB available. Nothing abnormal at rest.

### Suspects

1. **Leak in the GPU/video driver** (PowerVR GE8300 / MTK decoder) with old vendor blobs under an Android 13
   GSI: video buffers (ION/gralloc) or GPU memory growing during playback and never freed. Fits "after a while
   watching videos" and "no killable".
2. **zram too large** (880 MB on 1 GB): when full, the compressed data also takes real RAM.
3. **lowmem exhausted**: on 32-bit the kernel can only use ~500 MB (*lowmem*) for itself. There can be free
   highmem and still a failed kernel allocation.
4. Codec or resolution: AV1 in software or more than 480p multiplies the buffers.

### Help debugging it

[`device/tools/memmon.sh`](../device/tools/memmon.sh) logs every 5 s to `/data/local/tmp/memlog.txt`:
available RAM, free lowmem, swap used, zram RAM, ION total, current and peak GPU and the top 3 GPU users,
calling `sync` so it survives a panic. It **doesn't start on boot**; when relaunched it moves the previous log
to `memlog.txt.old`.

```bash
adb push device/tools/memmon.sh /data/local/tmp/
adb shell /system/xbin/su -c 'nohup sh /data/local/tmp/memmon.sh >/dev/null 2>&1 &'
# after a hang/reboot, BEFORE relaunching it:
adb shell tail -60 /data/local/tmp/memlog.txt
adb shell /system/xbin/su -c 'cat /proc/last_kmsg' > last_kmsg.txt
```

Columns: `t avail_mb lowfree_mb swapused_mb zram_mb ion_mb gpu_mb gpumax_mb top_gpu`.

| If this grows… | Next step |
|---|---|
| `gpu_mb` / `top_gpu` (SmartTube or surfaceflinger) | GPU leak: try another video renderer in SmartTube (SurfaceView/TextureView), turn off UI animations, cap at 480p; check `debug.sf.latch_unsignaled` / `disable_backpressure` (set by phh) |
| `ion_mb` | decoder buffer leak: compare AVC vs VP9, software decoder just to compare |
| `zram_mb` / `swapused_mb` | shrink zram to ~512 MB in the boot script (`swapoff`, `echo 1 > reset`, `disksize`, `mkswap`, `swapon`) |
| `lowfree_mb` drops with everything else normal | lowmem pressure: raise `min_free_kbytes`, check `lowmem_reserve_ratio` |
| none of the above | check *slab*: `/proc/slabinfo` (root) for a growing kernel cache |

Also look in `last_kmsg` for `Mem-Info`, `ion`, `pvr`, `lowmemorykiller` and `Killed process` right before the
panic. If you capture one, please open an issue with the log.

## Smaller limitations

- **Recents** doesn't work without Launcher3 (a simple switcher could be added to Casa).
- Proximity only detects a hand a few cm away (IR sensor). Detecting someone in the room would need another
  sensor (e.g. an mmWave radar in Home Assistant), used like `room_lights`.
- No power menu on long-press: the top button is remapped (see [DEVICE.md](DEVICE.md#top-button)).
- Google Cast receiving is not possible (see [MUSIC.md](MUSIC.md#phone-apps)).

## Ideas

- Colour picker in the light panel (the accent already follows `rgb_color`).
- Artwork (`entity_picture`) in the media panel, cached at low resolution.
- Measure temperature, CPU and GPU with a SmartTube video playing for a few minutes.
