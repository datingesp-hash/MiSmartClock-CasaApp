package com.archie.casa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Small two-position capsule (bottom-right corner): left = overview (two rooms per column),
 * right = zoomed (one room per column, bigger tiles). Tap toggles, the thumb can also be dragged.
 * The thumb glides with a slight stretch, like an iOS segmented control.
 * Own frame loop: the system animator scale is 0 on the clock.
 */
final class ZoomSwitch extends View {
    interface Listener { void onZoom(boolean zoomed); }

    private static final long DURATION = 320;
    private final Listener listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final int slop;
    private final float inset = Ui.dpf(3);

    private boolean zoomed;
    private float pos;          // thumb position 0 (left) .. 1 (right)
    private float stretch;      // 0..1, extra thumb width while it moves
    private boolean pressed, dragged;
    private float downX, downPos;

    private boolean animating;
    private float animFrom, animTo;
    private long animStart;

    ZoomSwitch(Context c, boolean zoomed, Listener l) {
        super(c);
        listener = l;
        this.zoomed = zoomed;
        pos = zoomed ? 1 : 0;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setContentDescription(Ui.t("Zoom", "Zoom"));
    }

    @Override public boolean hasOverlappingRendering() { return false; }

    private float thumbW() { return (getWidth() - 2 * inset) / 2f; }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        // track: translucent, hairline edge, floats over the tiles
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xE6161618);
        rf.set(0, 0, w, h);
        c.drawRoundRect(rf, r, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, Ui.dpf(0.6f)));
        p.setColor(0x24FFFFFF);
        rf.inset(0.5f, 0.5f);
        c.drawRoundRect(rf, r, r, p);
        p.setStyle(Paint.Style.FILL);

        // thumb
        float tw = thumbW(), th = h - 2 * inset, ext = stretch * tw * 0.22f;
        float tx = inset + pos * tw;
        float l = tx - ext * pos, rr = tx + tw + ext * (1 - pos);
        float sh = pressed ? 0.94f : 1f;
        float cy = h / 2f, hh = th / 2f * sh;
        p.setColor(0xFF636366);
        rf.set(l + (1 - sh) * tw / 2, cy - hh, rr - (1 - sh) * tw / 2, cy + hh);
        c.drawRoundRect(rf, hh, hh, p);

        // glyphs: 2x2 grid (overview) and one big square (zoomed)
        float lc = inset + tw / 2f, rc = inset + tw * 1.5f;
        p.setColor(blend(Ui.TXT, Ui.TXT2, pos));
        float s = Ui.dpf(4.6f), g = Ui.dpf(2.2f), rad = Ui.dpf(1.3f);
        for (int i = 0; i < 4; i++) {
            float x = lc - s - g / 2 + (i % 2) * (s + g), y = cy - s - g / 2 + (i / 2) * (s + g);
            rf.set(x, y, x + s, y + s);
            c.drawRoundRect(rf, rad, rad, p);
        }
        p.setColor(blend(Ui.TXT2, Ui.TXT, pos));
        float b = Ui.dpf(5.8f);
        rf.set(rc - b, cy - b, rc + b, cy + b);
        c.drawRoundRect(rf, Ui.dpf(3), Ui.dpf(3), p);
    }

    private static int blend(int a, int b, float f) {
        f = Ui.clamp(f);
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
        int gg = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
        int bb = (int) ((a & 255) + ((b & 255) - (a & 255)) * f);
        return 0xFF000000 | (r << 16) | (gg << 8) | bb;
    }

    // ---- touch

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                animating = false;
                pressed = true;
                dragged = false;
                downX = e.getX();
                downPos = pos;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                if (!dragged && Math.abs(dx) > slop) dragged = true;
                if (dragged) {
                    pos = Ui.clamp(downPos + dx / thumbW());
                    stretch = 0.5f;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                pressed = false;
                select(dragged ? pos > 0.5f : !zoomed);
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                select(zoomed);
                return true;
            default:
                return true;
        }
    }

    private void select(boolean z) {
        boolean changed = z != zoomed;
        zoomed = z;
        animFrom = pos;
        animTo = z ? 1 : 0;
        animStart = SystemClock.uptimeMillis();
        if (!animating) {
            animating = true;
            postOnAnimation(frame);
        }
        if (changed) {
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            listener.onZoom(z);
        }
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!animating) return;
            float span = Math.abs(animTo - animFrom);
            long dur = Math.max(160, (long) (DURATION * span));
            float u = Math.min(1f, (SystemClock.uptimeMillis() - animStart) / (float) dur);
            float ee = u < 0.5f ? 4 * u * u * u : 1 - (float) Math.pow(-2 * u + 2, 3) / 2; // ease-in-out cubic
            pos = animFrom + (animTo - animFrom) * ee;
            stretch = (float) Math.sin(Math.PI * u) * span; // widens mid-way, settles at the end
            invalidate();
            if (u < 1f) postOnAnimation(this);
            else {
                animating = false;
                stretch = 0;
            }
        }
    };
}
