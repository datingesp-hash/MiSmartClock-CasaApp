package com.archie.casa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * Round lyrics toggle in the top-right corner of the Now Playing card. Card grey (like the card's own
 * buttons) when off; solid white with a dark glyph when on. Pops in/out (scale + fade) with the music.
 */
final class LyricsButton extends View {
    interface Listener { void onLyrics(); }

    private final Listener listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bubble = new Path();
    private final RectF rf = new RectF();
    private boolean on, pressed;
    private float onF;          // 0..1 animated "on" fill
    private float shownF;       // 0..1 animated presence
    private boolean shown;
    private long last;
    private boolean looping;

    LyricsButton(Context c, Listener l) {
        super(c);
        listener = l;
        setVisibility(GONE);
        setContentDescription(Ui.t("Lyrics", "Letra"));
    }

    @Override public boolean hasOverlappingRendering() { return false; }

    void setOn(boolean v) {
        if (on == v) return;
        on = v;
        loop();
    }

    void setShown(boolean v) {
        if (shown == v) return;
        shown = v;
        if (v) setVisibility(VISIBLE);
        loop();
    }

    private void loop() {
        if (looping) return;
        looping = true;
        last = SystemClock.uptimeMillis();
        postOnAnimation(frame);
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            long t = SystemClock.uptimeMillis();
            float dt = Math.min(64, t - last);
            last = t;
            float k = 1f - (float) Math.exp(-dt / 70f);
            onF += ((on ? 1 : 0) - onF) * k;
            shownF += ((shown ? 1 : 0) - shownF) * k;
            boolean settled = Math.abs((on ? 1 : 0) - onF) < 0.01f && Math.abs((shown ? 1 : 0) - shownF) < 0.01f;
            if (settled) {
                onF = on ? 1 : 0;
                shownF = shown ? 1 : 0;
            }
            // pop: a little overshoot on the way in
            float s = shown ? 0.6f + 0.4f * shownF + 0.07f * (float) Math.sin(Math.PI * shownF) : 0.6f + 0.4f * shownF;
            setScaleX(s * (pressed ? 0.92f : 1f));
            setScaleY(s * (pressed ? 0.92f : 1f));
            setAlpha(shownF);
            invalidate();
            if (settled) {
                looping = false;
                if (!shown) setVisibility(GONE);
            } else postOnAnimation(this);
        }
    };

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f, cx = w / 2f, cy = h / 2f;
        // material
        p.setStyle(Paint.Style.FILL);
        p.setColor(blend(Ui.CARD2, 0xFFF2F2F7, onF));
        c.drawCircle(cx, cy, r, p);
        // the glyph is drawn for a 36 dp button: scale it to this one
        int save = c.save();
        float k = r * 2 / Ui.dpf(36);
        c.scale(k, k, cx, cy);
        // glyph: speech bubble with two lines of "text"
        int ink = blend(Ui.TXT, 0xFF1C1C1E, onF);
        float bw = Ui.dpf(15), bh = Ui.dpf(11.5f), rad = Ui.dpf(3.5f);
        float l = cx - bw / 2, t = cy - bh / 2 - Ui.dpf(1.2f);
        bubble.reset();
        rf.set(l, t, l + bw, t + bh);
        bubble.addRoundRect(rf, rad, rad, Path.Direction.CW);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dpf(1.6f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(ink);
        c.drawPath(bubble, p);
        // tail
        Path tail = new Path();
        tail.moveTo(l + Ui.dpf(3.5f), t + bh - 0.5f);
        tail.lineTo(l + Ui.dpf(2.2f), t + bh + Ui.dpf(3.6f));
        tail.lineTo(l + Ui.dpf(7.5f), t + bh - 0.5f);
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeWidth(Ui.dpf(1.2f));
        c.drawPath(tail, p);
        // lines
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dpf(1.7f));
        float y1 = t + bh * 0.38f, y2 = t + bh * 0.66f;
        c.drawLine(l + Ui.dpf(3.6f), y1, l + bw - Ui.dpf(3.6f), y1, p);
        c.drawLine(l + Ui.dpf(3.6f), y2, l + bw - Ui.dpf(6.5f), y2, p);
        c.restoreToCount(save);
    }

    private static int blend(int a, int b, float f) {
        f = Ui.clamp(f);
        int r = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int x = (a >>> sh) & 255, y = (b >>> sh) & 255;
            r |= ((int) (x + (y - x) * f) & 255) << sh;
        }
        return r;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!shown) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                pressed = true;
                loopPress();
                return true;
            case MotionEvent.ACTION_UP:
                pressed = false;
                loopPress();
                if (e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight()) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    listener.onLyrics();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                loopPress();
                return true;
            default:
                return true;
        }
    }

    private void loopPress() {
        float s = (pressed ? 0.92f : 1f);
        if (!looping) {
            setScaleX(s);
            setScaleY(s);
        }
    }
}
