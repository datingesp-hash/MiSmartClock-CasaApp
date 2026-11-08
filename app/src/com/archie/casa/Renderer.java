package com.archie.casa;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The music player behind the DLNA renderer. One instance per process.
 * Public methods are thread-safe (UPnP calls arrive on network threads); listeners run on the main thread.
 */
final class Renderer {
    interface Listener { void onRendererChanged(); }

    static final class Meta {
        String title = "", artist = "", album = "", art = "";
        long durationMs;
        String raw = "";
    }

    // UPnP transport states
    static final String NO_MEDIA = "NO_MEDIA_PRESENT", STOPPED = "STOPPED", PLAYING = "PLAYING",
            PAUSED = "PAUSED_PLAYBACK", TRANSITIONING = "TRANSITIONING";

    private static Renderer instance;

    static synchronized Renderer get(Context c) {
        if (instance == null) instance = new Renderer(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AudioFocusRequest focus;
    private final StreamProxy proxy;

    private MediaPlayer mp;
    private boolean prepared, playWhenPrepared;
    private String uri = "", nextUri = "";
    private Meta meta = new Meta(), nextMeta = new Meta();
    private String state = NO_MEDIA, status = "OK";
    private long pendingSeek = -1;

    private Renderer(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        proxy = new StreamProxy(c.getCacheDir());
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(fc -> {
                    if (fc == AudioManager.AUDIOFOCUS_LOSS || fc == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
                }, main)
                .build();
    }

    void addListener(Listener l) { listeners.addIfAbsent(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        main.post(() -> { for (Listener l : listeners) l.onRendererChanged(); });
    }

    // ---------------- transport

    synchronized void setUri(String u, String metaXml) {
        boolean wasPlaying = PLAYING.equals(state) || TRANSITIONING.equals(state);
        releasePlayer();
        uri = u == null ? "" : u;
        meta = parseMeta(metaXml);
        nextUri = "";
        nextMeta = new Meta();
        status = "OK";
        state = uri.isEmpty() ? NO_MEDIA : STOPPED;
        if (wasPlaying && !uri.isEmpty()) startPrepare(true);
        if (uri.isEmpty()) {
            am.abandonAudioFocusRequest(focus);
            proxy.clear();
        }
        changed();
    }

    synchronized void setNextUri(String u, String metaXml) {
        nextUri = u == null ? "" : u;
        nextMeta = parseMeta(metaXml);
        changed();
    }

    synchronized void play() {
        if (uri.isEmpty()) return;
        if (mp != null && prepared) {
            if (!requestFocus()) return;
            mp.start();
            state = PLAYING;
        } else if (mp == null) {
            startPrepare(true);
        } else {
            playWhenPrepared = true; // still preparing
        }
        changed();
    }

    synchronized void pause() {
        if (mp != null && prepared && mp.isPlaying()) {
            mp.pause();
            state = PAUSED;
            changed();
        } else if (mp != null && !prepared) {
            playWhenPrepared = false;
        }
    }

    /** Play/pause toggle (card button, double press of the top button). */
    synchronized void togglePlay() {
        if (PLAYING.equals(state) || TRANSITIONING.equals(state)) pause();
        else play();
    }

    synchronized void stop() {
        releasePlayer();
        state = uri.isEmpty() ? NO_MEDIA : STOPPED;
        am.abandonAudioFocusRequest(focus);
        changed();
    }

    synchronized void seek(long ms) {
        if (mp != null && prepared) mp.seekTo((int) ms);
        else pendingSeek = ms;
        changed();
    }

    /** Next: jump to the queued next track if the control point gave us one. */
    synchronized boolean next() {
        if (nextUri.isEmpty()) return false;
        advance();
        return true;
    }

    private void advance() {
        releasePlayer();
        uri = nextUri;
        meta = nextMeta;
        nextUri = "";
        nextMeta = new Meta();
        startPrepare(true);
        changed();
    }

    private boolean requestFocus() {
        return am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void startPrepare(boolean autoplay) {
        final MediaPlayer p = new MediaPlayer();
        mp = p;
        prepared = false;
        playWhenPrepared = autoplay;
        state = TRANSITIONING;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            // through the local cache: fast seeks in WebM/Opus and one full-speed download per track
            p.setDataSource(proxy.proxify(uri));
            p.setOnPreparedListener(x -> onPrepared(x));
            p.setOnCompletionListener(x -> onCompletion(x));
            p.setOnErrorListener((x, what, extra) -> { onError(x); return true; });
            p.prepareAsync();
        } catch (Exception e) {
            onError(p);
        }
    }

    private synchronized void onPrepared(MediaPlayer p) {
        if (p != mp) return;
        prepared = true;
        if (pendingSeek >= 0) {
            p.seekTo((int) pendingSeek);
            pendingSeek = -1;
        }
        if (playWhenPrepared && requestFocus()) {
            p.start();
            state = PLAYING;
        } else {
            state = PAUSED;
        }
        changed();
    }

    private synchronized void onCompletion(MediaPlayer p) {
        if (p != mp) return;
        if (!nextUri.isEmpty()) {
            advance();
        } else {
            releasePlayer();
            state = STOPPED;
            am.abandonAudioFocusRequest(focus);
            changed();
        }
    }

    private synchronized void onError(MediaPlayer p) {
        if (p != mp) return;
        releasePlayer();
        status = "ERROR_OCCURRED";
        state = STOPPED;
        changed();
    }

    private void releasePlayer() {
        if (mp != null) {
            try {
                mp.reset();
                mp.release();
            } catch (Exception ignored) {
            }
        }
        mp = null;
        prepared = false;
        playWhenPrepared = false;
    }

    // ---------------- volume (STREAM_MUSIC as 0..100)

    int volume() {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0 : Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    void setVolume(int v) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Math.max(0, Math.min(100, v)) * max / 100f), 0);
        changed();
    }

    boolean muted() { return am.isStreamMute(AudioManager.STREAM_MUSIC); }

    void setMute(boolean m) {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, m ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        changed();
    }

    // ---------------- snapshot getters

    synchronized String state() { return state; }
    synchronized String status() { return status; }
    synchronized String uri() { return uri; }
    synchronized String nextUri() { return nextUri; }
    synchronized Meta meta() { return meta; }
    synchronized Meta nextMeta() { return nextMeta; }

    synchronized long positionMs() {
        if (mp != null && prepared) {
            try { return mp.getCurrentPosition(); } catch (Exception ignored) { }
        }
        return 0;
    }

    synchronized long durationMs() {
        if (mp != null && prepared) {
            try {
                int d = mp.getDuration();
                if (d > 0) return d;
            } catch (Exception ignored) {
            }
        }
        return meta.durationMs;
    }

    synchronized String actions() {
        switch (state) {
            case PLAYING: return nextUri.isEmpty() ? "Pause,Stop,Seek" : "Pause,Stop,Seek,Next";
            case PAUSED: return "Play,Stop,Seek";
            case STOPPED: return "Play";
            default: return "";
        }
    }

    // ---------------- helpers

    private static final Pattern P_TITLE = Pattern.compile("<dc:title[^>]*>(.*?)</dc:title>", Pattern.DOTALL);
    private static final Pattern P_ARTIST = Pattern.compile("<upnp:artist[^>]*>(.*?)</upnp:artist>", Pattern.DOTALL);
    private static final Pattern P_CREATOR = Pattern.compile("<dc:creator[^>]*>(.*?)</dc:creator>", Pattern.DOTALL);
    private static final Pattern P_ALBUM = Pattern.compile("<upnp:album[^>]*>(.*?)</upnp:album>", Pattern.DOTALL);
    private static final Pattern P_ART = Pattern.compile("<upnp:albumArtURI[^>]*>(.*?)</upnp:albumArtURI>", Pattern.DOTALL);
    private static final Pattern P_DUR = Pattern.compile("<res[^>]*\\sduration=\"([^\"]+)\"", Pattern.DOTALL);

    static Meta parseMeta(String xml) {
        Meta m = new Meta();
        if (xml == null || xml.isEmpty() || "NOT_IMPLEMENTED".equals(xml)) return m;
        m.raw = xml;
        m.title = find(P_TITLE, xml);
        m.artist = find(P_ARTIST, xml);
        if (m.artist.isEmpty()) m.artist = find(P_CREATOR, xml);
        m.album = find(P_ALBUM, xml);
        m.art = find(P_ART, xml);
        String d = find(P_DUR, xml);
        if (!d.isEmpty()) m.durationMs = parseTime(d);
        return m;
    }

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? Upnp.unescape(m.group(1)).trim() : "";
    }

    /** "H:MM:SS(.mmm)" → ms. */
    static long parseTime(String t) {
        try {
            String[] p = t.trim().split(":");
            double s = 0;
            for (String x : p) s = s * 60 + Double.parseDouble(x);
            return (long) (s * 1000);
        } catch (Exception e) {
            return 0;
        }
    }

    /** ms → "H:MM:SS". */
    static String fmtTime(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
