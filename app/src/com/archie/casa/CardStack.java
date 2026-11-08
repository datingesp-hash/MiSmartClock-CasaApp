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
 * tucks in behind, while the back card grows into place. The drag scrubs the animation.
 *
 * Animations are driven by our own frame loop (postOnAnimation + uptime) instead of
 * ValueAnimator, because the clock runs with the system animator scale set to 0.
 */
final class CardStack extends FrameLayout {
    private static final float PEEK_SCALE = 0.92f, PEEK_ALPHA = 0.55f, TILT = 7f;
    private static final long DURATION = 420;

    private View front, back;
    private boolean backEnabled;

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
            if (front instanceof NowPlayingView) swapRoles();
            animating = false;
            t = 0;
        }
        back.setVisibility(on ? VISIBLE : GONE);
        apply(0);
    }

    boolean musicInFront() { return front instanceof NowPlayingView; }

    /** New music arrived: slide the music card to the front (same animation as the swipe). */
    void showMusic() {
        if (!backEnabled || musicInFront() || animating || getWidth() == 0) return;
        animateTo(1f);
    }

    private void swapRoles() {
        View f = front;
        front = back;
        back = f;
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

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (!canSwipe()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                track(e, true);
                return animating && t < 0.5f; // catch the card while it is still on its way out
            case MotionEvent.ACTION_MOVE: {
                track(e, false);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (dx > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    dragging = true;
                    downX = e.getX(); // start scrubbing from here, no jump
                    getParent().requestDisallowInterceptTouchEvent(true);
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
                if (animating && t < 0.5f) { // catch it mid-flight and continue from here
                    animating = false;
                    dragging = true;
                    downX = e.getX() - t * 1.24f * getWidth();
                    return true;
                }
                return !animating;
            case MotionEvent.ACTION_MOVE: {
                track(e, false);
                float dx = e.getX() - downX;
                if (!dragging && dx > slop) dragging = true;
                if (dragging) {
                    // the drag scrubs the first half: front card going out to the right
                    apply(Math.max(0f, Math.min(0.5f, dx / (1.24f * getWidth()))));
                }
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
                if (dragging || t > 0) {
                    boolean go = t > 0.14f || vx > Ui.dpf(500);
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL && t < 0.14f) go = false;
                    animateTo(go ? 1f : 0f);
                }
                dragging = false;
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
