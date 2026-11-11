package com.archie.casa;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Two cards in the same slot (clock in front, music behind, peeking out on the right).
 * Swiping right always swaps them: the front card slides out to the right with a slight tilt and
 * tucks in behind, while the back card grows into place. The drag scrubs the whole animation and
 * the swap completes under the finger (no need to lift it); one swipe = at most one swap.
 *
 * Animations are driven by our own frame loop (postOnAnimation + uptime) instead of
 * ValueAnimator, because the clock runs with the system animator scale set to 0.
 */
final class CardStack extends FrameLayout {
    private static final float PEEK_SCALE = 0.92f, PEEK_ALPHA = 0.55f, TILT = 7f;
    private static final long DURATION = 420;

    private View front, back, music;
    private boolean backEnabled;

    /** Told whenever the card in front changes (true = the music card). */
    interface FrontListener { void onFront(boolean music); }
    private FrontListener frontListener;
    void setFrontListener(FrontListener l) { frontListener = l; }

    // gesture
    private final int slop;
    private float downX, downY;
    private boolean dragging;
    private VelocityTracker vt;

    // animation: t in [0,1]; 0 = rest, 0.5 = front fully out on the right, 1 = swapped
    private float t;
    private boolean animating;
    private float animFrom, animTo;
    private long animStart, animDur;

    CardStack(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClipChildren(false);
    }

    void setCards(View frontCard, View backCard) {
        front = frontCard;
        back = backCard;
        music = backCard;
        addView(back, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(front, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setBackEnabled(false);
    }

    /** The back card exists only while there is music. */
    void setBackEnabled(boolean on) {
        if (backEnabled == on && back.getVisibility() == (on ? VISIBLE : GONE)) return;
        backEnabled = on;
        if (!on) {
            // music gone: make sure the clock (whichever view it is) ends up in front, no animation
            if (front == music) swapRoles();
            animating = false;
            t = 0;
        }
        back.setVisibility(on ? VISIBLE : GONE);
        apply(0);
    }

    boolean musicInFront() { return music != null && front == music; }

    /** New music arrived: slide the music card to the front (same animation as the swipe). */
    void showMusic() {
        if (!backEnabled || musicInFront() || animating || getWidth() == 0) return;
        animateTo(1f);
    }

    private void swapRoles() {
        View f = front;
        front = back;
        back = f;
        if (frontListener != null) frontListener.onFront(musicInFront());
    }

    // ------------------------------------------------------------------ drawing state

    private static float lerp(float a, float b, float f) { return a + (b - a) * f; }

    private float peekTx() { return getWidth() * (1 - PEEK_SCALE) / 2f + Ui.dpf(9); }

    /** Lay both cards out for animation position t (0..1). */
    private void apply(float tt) {
        t = tt;
        if (front == null) return;
        float w = getWidth(), out = 0.62f * w, peek = peekTx();
        // front card: out to the right (first half), then tucked in behind (second half)
        if (tt < 0.5f) {
            float f = tt / 0.5f;
            set(front, lerp(0, out, f), lerp(0, TILT, f), lerp(1, 0.97f, f), 1f);
        } else {
            float f = (tt - 0.5f) / 0.5f;
            set(front, lerp(out, peek, f), lerp(TILT, 0, f), lerp(0.97f, PEEK_SCALE, f), lerp(1, PEEK_ALPHA, f));
        }
        // back card: from peek to centre over the whole animation
        float g = smooth(tt);
        set(back, lerp(peek, 0, g), 0, lerp(PEEK_SCALE, 1, g), lerp(PEEK_ALPHA, 1, g));
        // z-order flips half way, when the front card is fully out of the way
        boolean flipped = tt >= 0.5f;
        front.setTranslationZ(flipped ? 0 : 1);
        back.setTranslationZ(flipped ? 1 : 0);
    }

    private static void set(View v, float tx, float rot, float scale, float alpha) {
        v.setTranslationX(tx);
        v.setRotation(rot);
        v.setScaleX(scale);
        v.setScaleY(scale);
        v.setAlpha(alpha);
    }

    private static float smooth(float x) { return x * x * (3 - 2 * x); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        apply(t);
    }

    // ------------------------------------------------------------------ animation loop

    private void animateTo(float target) {
        animFrom = t;
        animTo = target;
        animDur = Math.max(140, (long) (DURATION * Math.abs(target - t)));
        animStart = SystemClock.uptimeMillis();
        if (!animating) {
            animating = true;
            postOnAnimation(frame);
        }
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!animating) return;
            float u = Math.min(1f, (SystemClock.uptimeMillis() - animStart) / (float) animDur);
            float e = 1 - (1 - u) * (1 - u) * (1 - u); // ease-out cubic
            apply(lerp(animFrom, animTo, e));
            if (u < 1f) {
                postOnAnimation(this);
                return;
            }
            animating = false;
            if (animTo >= 1f) { // swap finished: the old back card is now the front one
                swapRoles();
            }
            apply(0);
        }
    };

    // ------------------------------------------------------------------ gesture

    private boolean canSwipe() { return backEnabled && front != null && getWidth() > 0; }

    /** Finger travel for one full swap. */
    private float cycle() { return 1.24f * getWidth(); }

    /**
     * Drag progress 0..1 for one swap. It doesn't wait for the finger to lift: as soon as the
     * drag reaches the end the cards trade places, and the rest of that gesture is ignored, so
     * a long swipe never swaps back to the card you were already looking at.
     */
    private void scrub(float x) {
        if (swaps > 0) return;
        float p = Math.max(0f, (x - downX) / cycle());
        if (p >= 1f) {
            swapRoles();
            swaps = 1;
            apply(0);
            return;
        }
        apply(p);
    }

    private int swaps; // 1 once this drag has already swapped the cards

    private void startDrag(float x) {
        animating = false;
        dragging = true;
        swaps = 0;
        downX = x - t * cycle(); // continue from wherever the card is, no jump
        getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (!canSwipe()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                track(e, true);
                if (animating) { // catch the card mid-flight
                    startDrag(e.getX());
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE: {
                track(e, false);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (dx > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    t = 0;
                    startDrag(e.getX());
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!canSwipe()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                track(e, true);
                if (animating) {
                    startDrag(e.getX());
                    return true;
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                track(e, false);
                if (!dragging && e.getX() - downX > slop) {
                    t = 0;
                    startDrag(e.getX());
                }
                if (dragging) scrub(e.getX());
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vx = 0;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                    vt.recycle();
                    vt = null;
                }
                if (swaps == 0 && (dragging || t > 0)) {
                    float fling = Ui.dpf(500);
                    boolean go = vx > fling || (vx > -fling && t > 0.14f);
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL && t < 0.5f) go = false;
                    animateTo(go ? 1f : 0f);
                }
                dragging = false;
                swaps = 0;
                return true;
            }
            default:
                return true;
        }
    }

    private void track(MotionEvent e, boolean reset) {
        if (reset || vt == null) {
            if (vt != null) vt.recycle();
            vt = VelocityTracker.obtain();
        }
        vt.addMovement(e);
    }
}
