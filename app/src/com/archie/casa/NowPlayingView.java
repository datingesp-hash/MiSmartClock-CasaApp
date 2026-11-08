package com.archie.casa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** "Now playing" card for the DLNA renderer: artwork, title, artist, progress and controls. */
final class NowPlayingView extends View {
    private final Renderer r;
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint artist = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable note, play, pause, stop, next, cast;
    private final RectF rf = new RectF(), art = new RectF(), bar = new RectF();
    private final RectF bStop = new RectF(), bPlay = new RectF(), bNext = new RectF();
    private RectF pressed;
    private Bitmap bitmap;
    private String bitmapUrl = "";
    private volatile String loadingUrl = "";

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            invalidate();
            if (Renderer.PLAYING.equals(r.state()) && isShown()) postDelayed(this, 1000);
        }
    };

    NowPlayingView(Context c, Renderer r) {
        super(c);
        this.r = r;
        note = Ui.icon(c, "music-note");
        play = Ui.icon(c, "play");
        pause = Ui.icon(c, "pause");
        stop = Ui.icon(c, "stop");
        next = Ui.icon(c, "skip-next");
        cast = Ui.icon(c, "cast-audio");
        label.setTypeface(Ui.MED);
        label.setTextSize(Ui.dpf(11));
        label.setLetterSpacing(0.08f);
        label.setColor(Ui.TXT2);
        title.setTypeface(Ui.MED);
        title.setTextSize(Ui.dpf(16));
        title.setColor(Ui.TXT);
        artist.setTypeface(Ui.REG);
        artist.setTextSize(Ui.dpf(13));
        artist.setColor(Ui.TXT2);
        small.setTypeface(Ui.REG);
        small.setTextSize(Ui.dpf(11));
        small.setColor(Ui.TXT2);
    }

    /** Called when the renderer changes. Returns whether the card should be visible. */
    boolean refresh() {
        String st = r.state();
        boolean show = !r.uri().isEmpty() && !Renderer.NO_MEDIA.equals(st);
        String url = r.meta().art;
        if (!url.equals(bitmapUrl) && !url.equals(loadingUrl)) loadArt(url);
        removeCallbacks(ticker);
        if (Renderer.PLAYING.equals(st)) post(ticker);
        invalidate();
        return show;
    }

    private void loadArt(final String url) {
        if (url.isEmpty()) {
            bitmap = null;
            bitmapUrl = "";
            artPaint.setShader(null);
            return;
        }
        loadingUrl = url;
        final int target = Math.max(Ui.dp(120), (int) art.width());
        new Thread(() -> {
            Bitmap b = null;
            try {
                byte[] data = fetch(url);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int s = 1;
                while (Math.min(o.outWidth, o.outHeight) / (s * 2) >= target) s *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = s;
                o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory, fine for artwork
                b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } catch (Exception ignored) {
            }
            final Bitmap fb = b;
            post(() -> {
                if (!url.equals(loadingUrl)) return;
                loadingUrl = "";
                bitmapUrl = url;
                bitmap = fb;
                artPaint.setShader(null);
                invalidate();
            });
        }, "art").start();
    }

    private static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 4 << 20) break; // 4 MB cap
            }
            return b.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        card.setShader(new LinearGradient(0, 0, 0, h, 0xFF26262B, 0xFF19191D, Shader.TileMode.CLAMP));
        artPaint.setShader(null);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(ticker);
    }

    @Override protected void onDraw(Canvas c) {
        final int w = getWidth(), h = getHeight();
        final float pad = Ui.dpf(16), R = Ui.dpf(30);
        rf.set(0, 0, w, h);
        c.drawRoundRect(rf, R, R, card);

        // label
        float y = pad + Ui.dpf(10);
        int ci = Ui.dp(14);
        cast.setBounds((int) pad, (int) (y - ci + Ui.dpf(2)), (int) pad + ci, (int) y + Ui.dp(2));
        cast.setTint(Ui.ORANGE);
        cast.draw(c);
        label.setColor(Ui.ORANGE);
        c.drawText(Ui.t("NOW PLAYING", "REPRODUCIENDO"), pad + ci + Ui.dpf(6), y, label);

        // artwork
        float bottomBlock = Ui.dpf(172); // title, artist, progress, controls
        float side = Math.min(w - 2 * pad, h - (y + Ui.dpf(12)) - bottomBlock - pad);
        side = Math.max(Ui.dpf(60), side);
        art.set((w - side) / 2f, y + Ui.dpf(12), (w + side) / 2f, y + Ui.dpf(12) + side);
        float ar = Ui.dpf(18);
        if (bitmap != null) {
            if (artPaint.getShader() == null) {
                BitmapShader sh = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                float sc = Math.max(art.width() / bitmap.getWidth(), art.height() / bitmap.getHeight());
                m.setScale(sc, sc);
                m.postTranslate(art.left + (art.width() - bitmap.getWidth() * sc) / 2f, art.top + (art.height() - bitmap.getHeight() * sc) / 2f);
                sh.setLocalMatrix(m);
                artPaint.setShader(sh);
            }
            c.drawRoundRect(art, ar, ar, artPaint);
        } else {
            Ui.fill.setColor(Ui.CARD2);
            c.drawRoundRect(art, ar, ar, Ui.fill);
            int ni = (int) (side * 0.4f);
            note.setBounds((int) (art.centerX() - ni / 2f), (int) (art.centerY() - ni / 2f), (int) (art.centerX() + ni / 2f), (int) (art.centerY() + ni / 2f));
            note.setTint(Ui.TXT2);
            note.draw(c);
        }

        // texts
        Renderer.Meta m = r.meta();
        String t = m.title.isEmpty() ? fileName(r.uri()) : m.title;
        String a = m.artist.isEmpty() ? (m.album.isEmpty() ? "" : m.album) : m.artist;
        y = art.bottom + Ui.dpf(26);
        CharSequence ts = TextUtils.ellipsize(t, title, w - 2 * pad, TextUtils.TruncateAt.END);
        c.drawText(ts, 0, ts.length(), pad, y, title);
        y += Ui.dpf(19);
        CharSequence as = TextUtils.ellipsize(a, artist, w - 2 * pad, TextUtils.TruncateAt.END);
        c.drawText(as, 0, as.length(), pad, y, artist);

        // progress
        long pos = r.positionMs(), dur = r.durationMs();
        y += Ui.dpf(16);
        bar.set(pad, y, w - pad, y + Ui.dpf(4));
        Ui.fill.setColor(Ui.CARD3);
        c.drawRoundRect(bar, bar.height() / 2, bar.height() / 2, Ui.fill);
        if (dur > 0) {
            float f = Math.min(1f, pos / (float) dur);
            rf.set(bar.left, bar.top, bar.left + Math.max(bar.height(), f * bar.width()), bar.bottom);
            Ui.fill.setColor(Ui.TXT);
            c.drawRoundRect(rf, bar.height() / 2, bar.height() / 2, Ui.fill);
        }
        y += Ui.dpf(18);
        c.drawText(mmss(pos), pad, y, small);
        String ds = dur > 0 ? mmss(dur) : (Renderer.TRANSITIONING.equals(r.state()) ? "Cargando…" : "");
        c.drawText(ds, w - pad - small.measureText(ds), y, small);

        // transport
        float bs = Ui.dpf(66), cx = w / 2f, cy = y + Ui.dpf(12) + bs / 2f, sp = Ui.dpf(68);
        bPlay.set(cx - bs / 2, cy - bs / 2, cx + bs / 2, cy + bs / 2);
        float ss = Ui.dpf(52);
        bStop.set(cx - sp - ss / 2, cy - ss / 2, cx - sp + ss / 2, cy + ss / 2);
        bNext.set(cx + sp - ss / 2, cy - ss / 2, cx + sp + ss / 2, cy + ss / 2);
        boolean playing = Renderer.PLAYING.equals(r.state()) || Renderer.TRANSITIONING.equals(r.state());
        circle(c, bPlay, playing ? pause : play, true, 34);
        circle(c, bStop, stop, false, 28);
        boolean hasNext = !r.nextUri().isEmpty();
        if (hasNext) circle(c, bNext, next, false, 28);
        else bNext.setEmpty();
    }

    private void circle(Canvas c, RectF b, Drawable d, boolean primary, int iconDp) {
        boolean p = b == pressed;
        Ui.fill.setColor(primary ? (p ? Ui.TILE_ON_PRESSED : Ui.TILE_ON) : (p ? Ui.CARD3 : Ui.CARD2));
        c.drawCircle(b.centerX(), b.centerY(), b.width() / 2, Ui.fill);
        int s = Ui.dp(iconDp);
        d.setBounds((int) (b.centerX() - s / 2f), (int) (b.centerY() - s / 2f), (int) (b.centerX() + s / 2f), (int) (b.centerY() + s / 2f));
        d.setTint(primary ? Ui.TXT_ON : Ui.TXT);
        d.draw(c);
    }

    private static String mmss(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static String fileName(String u) {
        int q = u.indexOf('?');
        String p = q > 0 ? u.substring(0, q) : u;
        int sl = p.lastIndexOf('/');
        String n = sl >= 0 ? p.substring(sl + 1) : p;
        try { n = java.net.URLDecoder.decode(n, "UTF-8"); } catch (Exception ignored) { }
        return n.isEmpty() ? Ui.t("Music", "Música") : n;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(x, y);
                if (pressed == null && bar.width() > 0 && y > bar.top - Ui.dpf(14) && y < bar.bottom + Ui.dpf(14)
                        && x >= bar.left && x <= bar.right && r.durationMs() > 0) {
                    r.seek((long) ((x - bar.left) / bar.width() * r.durationMs()));
                    invalidate();
                    return true;
                }
                invalidate();
                return true; // swallow every touch: nothing must reach the clock card behind us
            case MotionEvent.ACTION_UP: {
                RectF p = pressed;
                pressed = null;
                if (p != null && p.contains(x, y)) {
                    playSoundEffect(SoundEffectConstants.CLICK);
                    if (p == bPlay) {
                        r.togglePlay();
                    } else if (p == bStop) {
                        r.setUri("", null); // stop and clear: the card hides
                    } else if (p == bNext) {
                        r.next();
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private RectF hit(float x, float y) {
        for (RectF b : new RectF[]{bPlay, bStop, bNext}) if (!b.isEmpty() && b.contains(x, y)) return b;
        return null;
    }
}
