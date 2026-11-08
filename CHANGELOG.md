# Changelog

## v1.19

- **Fix: the music receiver disappeared while the screen was off.** The MTK Wi-Fi driver drops all multicast
  in suspend mode; new boot script `device/service.d/97-smartclock-wifi.sh` turns suspend mode back off
  (re-run `device/install.sh`).
- SSDP replies are sent from port 1900, so senders behind a stateful firewall (ufw) see the clock.
- New `keep_screen_on` config option: any entities (e.g. your PC) that keep the screen on, dimmed when idle.

## v1.18 — first public release

- English UI (follows the system language; Spanish still available, or set `"language"` in `config.json`).
- `install.sh` uses your computer's time zone (or `TZ_ID=...`) instead of a hard-coded one.
- `deploy.sh` can install a prebuilt APK (`APK=...`); `build.sh` can sign with your own key.
- Documentation in English: install guide, before/after comparison, known issues.

## History before the public release (private project, newest first)

- **1.17** — Glanceable screen (dim with the room light, off in the dark, wake on hand proximity),
  top button = play/pause, bigger Now Playing controls.
- Local caching proxy: YouTube streams start and seek in ~2 s instead of ~46 s.
- Decode numeric XML character references in track metadata.
- Clock and Now Playing cards stacked in one slot, swipe to swap with an animation.
- Music Assistant example stack (free YouTube Music provider + PO token server).
- Built-in DLNA/UPnP music receiver with a Now Playing card.
- Casa: native Home Assistant panel and launcher. Device tuning: boot animation removed,
  manual AOT compilation, VM/lmkd tuning, unused packages disabled.
