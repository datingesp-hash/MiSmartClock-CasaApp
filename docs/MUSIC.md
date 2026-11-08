# Sending music to the clock

Casa's DLNA receiver ("Mi Smart Clock") accepts audio from anything that speaks DLNA/UPnP.
Discovery uses UDP 1900 (multicast) and control uses TCP 49494, so the sender must be on the same LAN.

## Home Assistant

Add **Mi Smart Clock** from the "new device discovered" notification (*DLNA Digital Media Renderer*).
It becomes a `media_player`: send radio stations, files from the Media browser, TTS announcements, or use it
in automations.

## Music Assistant (recommended)

[Music Assistant](https://music-assistant.io) gives you a proper music app (search, queues, artwork,
gapless playback) and detects the clock on its own. Example stack in
[`examples/music-assistant/compose.yaml`](../examples/music-assistant/compose.yaml):

- `network_mode: host` — required to discover speakers over multicast (DLNA, Chromecast, AirPlay).
- Web UI on `http://<server>:8095`; audio is streamed to the speakers from port 8097.
- Image `ghcr.io/sproft/ytmusic-free-provider:edge`: official Music Assistant plus the community
  [YouTube Music (Free)](https://github.com/sproft/ytmusic-free-provider) provider — YouTube Music **with no
  account or cookie** (ytmusicapi + yt-dlp). Unofficial and unsupported by Music Assistant; it may break when
  YouTube changes. To use the official image: `ghcr.io/music-assistant/server:latest`.
- `yt-potoken` (bgutil 1.2.1, the version Music Assistant supports) generates the *PO tokens* YouTube requires.
  Published only on `127.0.0.1:4416`, the default URL of Music Assistant's YouTube Music provider.

Setup (once):

1. `docker compose up -d` in `examples/music-assistant/`.
2. Open `http://<server>:8095` and create the user.
3. *Settings → Music sources → add* **YouTube Music (Free)**: nothing to fill in, save.
4. In Home Assistant: *Integrations → add* **Music Assistant** (`http://<server>:8095`). Its players appear as
   `media_player` entities for automations (e.g. `music_assistant.play_media` as an alarm).

Daily use from the phone: open the Music Assistant web UI and "Add to home screen" (it works like an app),
search a song or playlist and choose **Mi Smart Clock** as the player. Casa's Now Playing card follows it.

YouTube streams go through Casa's caching proxy, so they start and seek in ~2 s (see [APP.md](APP.md)).

## Phone apps

Any app that casts over DLNA/UPnP: BubbleUPnP, Hi-Fi Cast, VLC, many music players.

Google Cast is **not** supported and won't be: Cast senders require receivers to authenticate with a
certificate signed by Google, which only licensed devices have.

## Firewall

Since 1.19 the clock answers SSDP searches from port 1900, so stateful firewalls (ufw…) accept the reply.
If a sender still can't find it, direct control works: `http://<clock-ip>:49494/desc.xml`.

## The clock disappears when the screen is off

Fixed by `device/install.sh` (boot script `97-smartclock-wifi.sh`): the Wi-Fi driver dropped multicast with
the screen off. Details in [DEVICE.md](DEVICE.md#wi-fi-multicast-with-the-screen-off).
