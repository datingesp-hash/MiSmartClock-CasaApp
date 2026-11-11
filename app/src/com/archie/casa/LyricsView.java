package com.archie.casa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.List;

/**
 * Synced lyrics, Apple Music style: big bold lines, the sung line bright and slightly larger, the
 * others dimmed; word-timed lines fill syllable by syllable with a soft edge; the list glides to keep
 * the current line at the upper third, lines further down following a beat later (the "wave").
 * Instrumental breaks show three breathing dots. Unsynced lyrics scroll along with the song.
 * Drag to look around (it returns after a few seconds); tap a line to jump there.
 *
 * Own frame loop (system animations are off on the clock); runs only while shown and moving.
 */
final class LyricsView extends View {
    private static final int DIM = 0x52FFFFFF, BRIGHT = 0xFFFFFFFF, PLAIN = 0xD9FFFFFF;
    private static final long FOLLOW_AFTER_DRAG = 3500;

    private final Renderer renderer;
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint info = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int padL = Ui.dp(6), padR = Ui.dp(54), lineGap = Ui.dp(16), slop;

    // content
    private String key = "";
    private int gen;
    private boolean loading, noMeta;
    private Lyrics lyrics;
    private StaticLayout[] layouts = new StaticLayout[0];
    private float[] tops = new float[0];       // content y of each line
    private float[] shown = new float[0];      // per-line displayed scroll (the wave)
    private float[] emph = new float[0];       // 0..1 per-line emphasis (active line)
    private float contentH;
    private int laidW;

    // time
    private long basePos, baseAt;
    private boolean playing;
    private long lastSample;

    // scroll / touch
    private float target, userOffset;
    private long lastTouch = -FOLLOW_AFTER_DRAG;
    private float downY, downOffset;
    private boolean dragging;
    private boolean looping;
    private long lastFrame;

    LyricsView(Context c, Renderer r) {
        super(c);
        renderer = r;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        tp.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        tp.setTextSize(Ui.dpf(27));
        tp.setLetterSpacing(-0.01f);
        info.setTypeface(Ui.MED);
        info.setTextSize(Ui.dpf(22));
        info.setColor(Ui.TXT2);
        dot.setColor(BRIGHT);
    }

    @Override public boolean hasOverlappingRendering() { return false; }

    // ------------------------------------------------------------------ data

    /** Called whenever the renderer changes (track, play/pause, seek). Fetches lyrics for a new track. */
    void update() {
        Renderer.Meta m = renderer.meta();
        String st = renderer.state();
        playing = Renderer.PLAYING.equals(st);
        sample();
        long dur = renderer.durationMs();
        String k = m.title + "\u0001" + m.artist;
        if (!k.equals(key)) {
            key = k;
            lyrics = null;
            layouts = new StaticLayout[0];
            userOffset = 0;
            noMeta = m.title.trim().isEmpty();
            loading = !noMeta;
            final int g = ++gen;
            if (!noMeta) {
                final String title = m.title, artist = m.artist, album = m.album;
                final long d = dur > 0 ? dur : m.durationMs;
                Thread t = new Thread(() -> {
                    Lyrics l = LyricsSources.lookup(title, artist, album, d);
                    main.post(() -> {
                        if (g != gen) return;
                        loading = false;
                        lyrics = l;
                        relayout(true);
                        kick();
                    });
                }, "lyrics-lookup");
                t.setDaemon(true);
                t.start();
            }
        }
        kick();
    }

    String source() { return lyrics == null ? null : lyrics.source; }

    private void sample() {
        basePos = renderer.positionMs();
        baseAt = SystemClock.uptimeMillis();
        lastSample = baseAt;
    }

    private long now() {
        long t = SystemClock.uptimeMillis();
        if (t - lastSample > 300) sample();
        return basePos + (playing ? t - baseAt : 0) + 120; // a hair early reads better
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { relayout(false); }

    private void relayout(boolean fresh) {
        int w = getWidth() - padL - padR;
        if (w <= 0 || lyrics == null) {
            layouts = new StaticLayout[0];
            return;
        }
        List<Lyrics.Line> ls = lyrics.lines;
        int n = ls.size();
        layouts = new StaticLayout[n];
        tops = new float[n];
        float y = 0;
        tp.setTextSize(Ui.dpf(lyrics.synced ? 27 : 23));
        for (int i = 0; i < n; i++) {
            Lyrics.Line l = ls.get(i);
            if (l.gap()) {
                tops[i] = y;
                y += Ui.dpf(30) + lineGap;
                continue;
            }
            layouts[i] = StaticLayout.Builder.obtain(l.text, 0, l.text.length(), tp, w)
                    .setAlignment(l.right ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0, 1.02f).setIncludePad(false).build();
            tops[i] = y;
            y += layouts[i].getHeight() + (lyrics.synced ? lineGap : Ui.dp(8));
        }
        contentH = y;
        laidW = w;
        if (fresh || shown.length != n) {
            shown = new float[n];
            emph = new float[n];
            float t = targetFor(active());
            for (int i = 0; i < n; i++) shown[i] = t;
            target = t;
        }
    }

    private int active() {
        if (lyrics == null || !lyrics.synced) return -1;
        long t = now();
        List<Lyrics.Line> ls = lyrics.lines;
        int a = -1;
        for (int i = 0; i < ls.size(); i++) {
            if (ls.get(i).time <= t) a = i;
            else break;
        }
        return a;
    }

    private float anchor() { return getHeight() * 0.30f; }

    private float targetFor(int a) {
        if (lyrics == null) return 0;
        if (!lyrics.synced) {
            long d = renderer.durationMs();
            float f = d > 0 ? Ui.clamp(now() / (float) d) : 0;
            return Math.max(0, f * (contentH - getHeight() * 0.55f));
        }
        if (a < 0) return -anchor() + Ui.dpf(8);
        return tops[a] - anchor();
    }

    // ------------------------------------------------------------------ frame loop

    void kick() {
        if (looping || getVisibility() != VISIBLE || !isAttachedToWindow()) return;
        looping = true;
        lastFrame = SystemClock.uptimeMillis();
        postOnAnimation(frame);
    }

    @Override protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        if (vis == VISIBLE) {
            sample();
            kick();
        }
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            looping = false;
            if (getVisibility() != VISIBLE || !isAttachedToWindow()) return;
            long t = SystemClock.uptimeMillis();
            float dt = Math.min(64, t - lastFrame);
            lastFrame = t;
            boolean moving = step(dt);
            invalidate();
            long delay = nextFrameIn(moving);
            if (delay < 0) return;
            looping = true;
            if (delay == 0) postOnAnimation(this);
            else postOnAnimationDelayed(this, delay);
        }
    };

    /**
     * Frame budget (the clock's CPU is small): 60 fps only while something glides, ~30 fps while a
     * line is being swept or a break's dots fill, and otherwise sleep until the next line starts.
     * -1 = stop until something changes.
     */
    private long nextFrameIn(boolean moving) {
        if (moving || dragging) return 0;
        if (loading) return 60;
        if (!playing || lyrics == null || layouts.length == 0) return -1;
        if (!lyrics.synced) return 250; // slow drift for unsynced text
        long t = now();
        int a = active();
        List<Lyrics.Line> ls = lyrics.lines;
        if (a >= 0) {
            Lyrics.Line l = ls.get(a);
            if (l.gap() || (l.wordSynced() && t < l.endOrWords() + 150)) return 33;
        }
        if (a + 1 < ls.size()) return Math.max(16, Math.min(1000, ls.get(a + 1).time - t));
        return 1000;
    }

    /** Advances the smoothing; true while something is still settling. */
    private boolean step(float dt) {
        if (lyrics == null || layouts.length == 0) return false;
        int a = active();
        boolean follow = !dragging && SystemClock.uptimeMillis() - lastTouch > FOLLOW_AFTER_DRAG;
        if (follow && userOffset != 0) {
            userOffset *= (float) Math.exp(-dt / 140f);
            if (Math.abs(userOffset) < 0.5f) userOffset = 0;
        }
        target = targetFor(a);
        boolean moving = userOffset != 0;
        for (int i = 0; i < shown.length; i++) {
            // lines below the current one follow a little later: a gentle wave
            int dist = a < 0 ? i : Math.max(0, i - a);
            float tau = 110f + Math.min(dist, 6) * 28f;
            float k = 1f - (float) Math.exp(-dt / tau);
            shown[i] += (target - shown[i]) * k;
            if (Math.abs(target - shown[i]) > 0.4f) moving = true;
            else shown[i] = target;
            float want = i == a ? 1f : 0f;
            float ke = 1f - (float) Math.exp(-dt / 160f);
            emph[i] += (want - emph[i]) * ke;
            if (Math.abs(want - emph[i]) > 0.01f) moving = true;
            else emph[i] = want;
        }
        return moving;
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (lyrics == null || layouts.length == 0) {
            String msg = noMeta ? Ui.t("No song information", "Sin datos de la canción")
                    : loading ? Ui.t("Looking for lyrics…", "Buscando letra…")
                    : Ui.t("No lyrics for this song", "No hay letra para esta canción");
            if (loading) info.setAlpha((int) (140 + 80 * Math.sin(SystemClock.uptimeMillis() / 380.0)));
            else info.setAlpha(255);
            c.drawText(msg, padL, anchor() + Ui.dpf(10), info);
            return;
        }
        if (laidW != w - padL - padR) relayout(false);
        List<Lyrics.Line> ls = lyrics.lines;
        long t = now();
        int a = active();
        float fade = Ui.dpf(46);
        for (int i = 0; i < ls.size(); i++) {
            float y = tops[i] - shown[i] - userOffset;
            float lh = layouts[i] != null ? layouts[i].getHeight() : Ui.dpf(30);
            if (y + lh < 0 || y > h) continue;
            // soft fade at the top and bottom edges
            float edge = Math.min(Ui.clamp((y + lh * 0.5f) / fade), Ui.clamp((h - y - lh * 0.5f) / fade));
            if (edge <= 0) continue;
            Lyrics.Line l = ls.get(i);
            if (l.gap()) {
                drawDots(c, l, y, i == a, t, edge);
                continue;
            }
            StaticLayout lay = layouts[i];
            float e = lyrics.synced ? emph[i] : 0;
            float s = 0.94f + 0.06f * e;
            int save = c.save();
            float px = l.right ? w - padR : padL;
            c.translate(padL, y);
            c.scale(s, s, px - padL, lh / 2f);
            if (!lyrics.synced) {
                drawLayout(c, lay, PLAIN, edge);
            } else if (i == a && l.wordSynced()) {
                drawSweep(c, lay, l, t, edge);
            } else {
                int base = blend(DIM, BRIGHT, e);
                drawLayout(c, lay, base, edge);
            }
            c.restoreToCount(save);
        }
        // source, bottom-right, very quiet
        info.setTextSize(Ui.dpf(11));
        info.setAlpha(110);
        info.setTextAlign(Paint.Align.RIGHT);
        c.drawText(lyrics.source, w - padR, h - Ui.dpf(8), info);
        info.setTextAlign(Paint.Align.LEFT);
        info.setTextSize(Ui.dpf(22));
    }

    private void drawLayout(Canvas c, StaticLayout lay, int color, float edge) {
        tp.setShader(null);
        tp.setColor(color);
        tp.setAlpha((int) (Color.alpha(color) * edge));
        lay.draw(c);
    }

    /** The sung line: bright up to the current syllable, with a soft edge, dim after it. */
    private void drawSweep(Canvas c, StaticLayout lay, Lyrics.Line l, long t, float edge) {
        float cp = charProgress(l, t);
        int bright = alpha(BRIGHT, edge), dim = alpha(0x73FFFFFF, edge);
        float soft = Ui.dpf(14);
        for (int li = 0; li < lay.getLineCount(); li++) {
            int s = lay.getLineStart(li), e = lay.getLineEnd(li);
            int save = c.save();
            c.clipRect(0, lay.getLineTop(li), laidW, lay.getLineBottom(li));
            tp.setShader(null);
            if (cp >= e) tp.setColor(bright);
            else if (cp <= s) tp.setColor(dim);
            else {
                int ci = (int) cp;
                float x0 = lay.getPrimaryHorizontal(ci);
                float x1 = ci + 1 <= e ? lay.getPrimaryHorizontal(Math.min(ci + 1, l.text.length())) : x0;
                if (ci + 1 >= e) x1 = lay.getLineRight(li);
                float x = x0 + (x1 - x0) * (cp - ci);
                tp.setColor(0xFFFFFFFF);
                tp.setShader(new LinearGradient(x - soft / 2, 0, x + soft / 2, 0, bright, dim, Shader.TileMode.CLAMP));
            }
            lay.draw(c);
            c.restoreToCount(save);
        }
        tp.setShader(null);
    }

    private static float charProgress(Lyrics.Line l, long t) {
        int prev = 0;
        for (int i = 0; i < l.rs.length; i++) {
            int end = l.rc[i];
            if (t < l.rs[i]) return prev;
            if (t < l.re[i]) {
                float f = (t - l.rs[i]) / (float) Math.max(1, l.re[i] - l.rs[i]);
                return prev + f * (end - prev);
            }
            prev = end;
        }
        return l.text.length();
    }

    /** Instrumental break: three dots that breathe and light up one by one as the break runs out. */
    private void drawDots(Canvas c, Lyrics.Line l, float y, boolean act, long t, float edge) {
        float r = Ui.dpf(act ? 6.5f : 5f), gap = Ui.dpf(act ? 22 : 18);
        float f = act && l.end > l.time ? Ui.clamp((t - l.time) / (float) (l.end - l.time)) : 0;
        float breathe = act ? 1f + 0.08f * (float) Math.sin(t / 260.0) : 1f;
        float cy = y + Ui.dpf(15);
        for (int k = 0; k < 3; k++) {
            float lit = Ui.clamp(f * 3 - k);
            int col = blend(act ? 0x66FFFFFF : 0x33FFFFFF, BRIGHT, lit);
            dot.setColor(col);
            dot.setAlpha((int) (Color.alpha(col) * edge));
            c.drawCircle(padL + r + k * gap, cy, r * breathe, dot);
        }
    }

    private static int alpha(int col, float f) { return ((int) (((col >>> 24) & 255) * f) << 24) | (col & 0xFFFFFF); }

    private static int blend(int a, int b, float f) {
        f = Ui.clamp(f);
        int r = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int x = (a >>> sh) & 255, y = (b >>> sh) & 255;
            r |= ((int) (x + (y - x) * f) & 255) << sh;
        }
        return r;
    }

    private static final class Color {
        static int alpha(int c) { return (c >>> 24) & 255; }
    }

    // ------------------------------------------------------------------ touch

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (lyrics == null || layouts.length == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                downOffset = userOffset;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getY() - downY) > slop) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    userOffset = downOffset - (e.getY() - downY);
                    lastTouch = SystemClock.uptimeMillis();
                    kick();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) tap(e.getY());
                dragging = false;
                lastTouch = SystemClock.uptimeMillis();
                kick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    /** Tap on a synced line: jump the song there and follow again right away. */
    private void tap(float y) {
        if (!lyrics.synced) return;
        List<Lyrics.Line> ls = lyrics.lines;
        for (int i = 0; i < ls.size(); i++) {
            float top = tops[i] - shown[i] - userOffset;
            float lh = layouts[i] != null ? layouts[i].getHeight() : Ui.dpf(30);
            if (y >= top - lineGap / 2f && y <= top + lh + lineGap / 2f) {
                Lyrics.Line l = ls.get(i);
                renderer.seek(Math.max(0, l.time));
                basePos = l.time;
                baseAt = SystemClock.uptimeMillis();
                lastSample = baseAt + 400; // let the player catch up before re-sampling
                userOffset = 0;
                lastTouch = -FOLLOW_AFTER_DRAG;
                kick();
                return;
            }
        }
    }
}
