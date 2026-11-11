package com.archie.casa;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;

public final class MainActivity extends Activity implements Ha.Listener, DetailView.Host, Renderer.Listener {
    private Ha ha;
    private ClockView clock;
    private DetailView detail;
    private NowPlayingView nowPlaying;
    private CardStack stack;
    private boolean hadMusic;
    private Config cfg;
    private Boolean roomLit; // null = not known yet
    private boolean bound; // receivers, renderer listener and HA socket active
    private final HashMap<String, ArrayList<TileView>> tiles = new HashMap<>();
    private final ArrayList<Config.Item> all = new ArrayList<>();
    private final ArrayList<RoomView> roomsWithSensors = new ArrayList<>();
    private final ArrayList<RoomView> roomViews = new ArrayList<>();
    private HorizontalScrollView hs;
    private LinearLayout cols;
    private boolean zoomed;
    private int zoomRoom; // room kept in view across a zoom change
    private ZoomSwitch zoomSwitch;
    private LyricsView lyricsView;
    private boolean lyricsOn;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { clock.setTime(Calendar.getInstance()); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        DlnaService.start(this); // music receiver, independent of the HA config
        try {
            cfg = Config.load(new File(getFilesDir(), "config.json"));
        } catch (Exception e) {
            TextView t = new TextView(this);
            t.setText(Ui.t("Missing config.json\n", "Falta config.json\n") + new File(getFilesDir(), "config.json") + "\n\n" + e);
            t.setTextColor(Ui.TXT2);
            t.setGravity(Gravity.CENTER);
            setContentView(t);
            return;
        }

        FrameLayout root = new FrameLayout(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Ui.dp(14);
        row.setPadding(pad, pad, 0, pad);

        clock = new ClockView(this, new ClockView.OnIcon() {
            @Override public void onIcon(Config.Item it) { toggle(it); }
            @Override public void onApp() { launch("org.smarttube.stable"); }
            @Override public void onSettings() {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
        // clock in front, music card behind it (peeking out); swipe right to swap them
        nowPlaying = new NowPlayingView(this, Renderer.get(this));
        stack = new CardStack(this);
        nowPlaying.setOnLyrics(() -> setLyrics(!lyricsOn)); // LYRICS pill at the bottom of the card
        stack.setCards(clock, nowPlaying);
        stack.setFrontListener(music -> { if (!music && lyricsOn) setLyrics(false); }); // clock in front: lyrics go
        row.setClipChildren(false);
        root.setClipChildren(false);
        stack.setTranslationZ(2); // draw above the rooms: the card slides out over them
        row.addView(stack, new LinearLayout.LayoutParams(Ui.dp(212), ViewGroup.LayoutParams.MATCH_PARENT));

        HorizontalScrollView hs = new HorizontalScrollView(this) {
            private boolean flung;
            @Override public void fling(int vx) {
                if (!zoomed) { super.fling(vx); return; }
                flung = true;
                smoothScrollTo(snap(getScrollX(), vx), 0);
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) flung = false;
                boolean r = super.onTouchEvent(e);
                if (zoomed && !flung && (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL))
                    smoothScrollTo(snap(getScrollX(), 0), 0);
                return r;
            }
        };
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipToPadding(false);
        hs.setPadding(pad, 0, pad, 0);
        hs.setFillViewport(true);
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        hs.addView(cols, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ArrayList<String> ids = new ArrayList<>();
        for (int i = 0; i < cfg.rooms.size(); i++) {
            Config.Room room = cfg.rooms.get(i);
            RoomView rv = new RoomView(this, room.name);
            for (final Config.Item it : room.items) {
                if (!ids.contains(it.id)) ids.add(it.id);
                if ("sensor".equals(it.domain)) { // readout next to the room name, not a tile
                    rv.addSensor(it);
                    if (!roomsWithSensors.contains(rv)) roomsWithSensors.add(rv);
                    continue;
                }
                TileView tv = new TileView(this, it);
                tv.setOnClickListener(v -> onTap(it));
                tv.setOnLongClickListener(v -> onLong(it));
                rv.addView(tv);
                ArrayList<TileView> l = tiles.get(it.id);
                if (l == null) tiles.put(it.id, l = new ArrayList<>());
                l.add(tv);
                all.add(it);
            }
            roomViews.add(rv);
        }
        this.hs = hs;
        this.cols = cols;
        zoomed = getPreferences(MODE_PRIVATE).getBoolean("zoomed", false);
        layoutRooms(zoomed);
        // right side: the rooms, or the lyrics of what is playing in the same space
        FrameLayout right = new FrameLayout(this);
        right.setClipChildren(false);
        right.addView(hs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lyricsView = new LyricsView(this, Renderer.get(this));
        lyricsView.setVisibility(View.GONE);
        FrameLayout.LayoutParams lvp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lvp.leftMargin = pad + Ui.dp(4);
        right.addView(lyricsView, lvp);
        row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // overview / zoomed rooms switch, floating in the bottom-right corner
        FrameLayout.LayoutParams zp = new FrameLayout.LayoutParams(Ui.dp(70), Ui.dp(30), Gravity.BOTTOM | Gravity.END);
        zp.rightMargin = zp.bottomMargin = Ui.dp(10);
        zoomSwitch = new ZoomSwitch(this, zoomed, this::setZoomed);
        root.addView(zoomSwitch, zp);

        detail = new DetailView(this, this);
        detail.setVisibility(View.GONE);
        root.addView(detail, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        for (String id : cfg.awake) if (!ids.contains(id)) ids.add(id); // watched even if not on screen
        ha = new Ha(cfg.url, cfg.token, ids, this);
        for (RoomView rv : roomsWithSensors) rv.bind(ha.states);
        for (String id : ids) onEntity(id);
    }

    @Override protected void onStart() {
        super.onStart();
        if (ha == null) return;
        clock.setTime(Calendar.getInstance());
        onRendererChanged();
        if (bound) return; // still bound from a screen-off stop
        bound = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, f);
        ha.start();
        Renderer.get(this).addListener(this);
    }

    @Override public void onRendererChanged() {
        boolean show = nowPlaying.refresh();
        stack.setBackEnabled(show);
        if (!show && lyricsOn) setLyrics(false);
        if (lyricsOn) lyricsView.update();
        updateLyricsAwake();
        // music just arrived (also noticed when coming back from SmartTube): bring its card to the front
        if (show && !hadMusic) stack.post(stack::showMusic);
        hadMusic = show;
    }

    @Override protected void onStop() {
        super.onStop();
        if (ha == null) return;
        // Screen off with Casa on top: stay connected to HA so a room light turning on wakes the screen.
        if (!bound || !getSystemService(android.os.PowerManager.class).isInteractive()) return;
        bound = false;
        unregisterReceiver(tick);
        Renderer.get(this).removeListener(this);
        ha.stop(); // no socket / no work while SmartTube or anything else is in front
        clock.setConnected(false);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (ha != null) ha.quit();
        Presence.setRoomLit(this, false);
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onBackPressed() {
        // We are the home screen: back only closes the sheet (or the lyrics), never exits.
        if (detail != null && detail.getVisibility() == View.VISIBLE) closeDetail();
        else if (lyricsOn) setLyrics(false);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i); // HOME pressed while already on top: just close any open sheet
        if (detail != null && detail.getVisibility() == View.VISIBLE) closeDetail();
    }

    // ---- rooms grid: overview (two rooms per column) or zoomed (one room per column, bigger tiles)

    private void layoutRooms(boolean zoom) {
        for (RoomView rv : roomViews) {
            ViewGroup p = (ViewGroup) rv.getParent();
            if (p != null) p.removeView(rv);
        }
        cols.removeAllViews();
        int per = zoom ? 1 : 2, n = roomViews.size();
        LinearLayout col = null;
        for (int i = 0; i < n; i++) {
            RoomView rv = roomViews.get(i);
            rv.setZoomed(zoom);
            if (i % per == 0) {
                col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setClipChildren(false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                if (i > 0) lp.leftMargin = Ui.dp(zoom ? 28 : 22);
                cols.addView(col, lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f);
            if (i % per == 1) lp.topMargin = Ui.dp(8);
            col.addView(rv, lp);
            if (per == 2 && i % 2 == 0 && i == n - 1) {
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 0, 1f);
                sp.topMargin = Ui.dp(8);
                col.addView(new View(this), sp); // keep a lone room at half height
            }
        }
    }

    /**
     * Zoomed mode resting points: each room's start, and for a room wider than the screen
     * also its end, so every tile can still be reached. vx picks the next point in that direction.
     */
    private int snap(int x, int vx) {
        int view = hs.getWidth() - hs.getPaddingLeft() - hs.getPaddingRight();
        int max = Math.max(0, cols.getWidth() - view);
        ArrayList<Integer> pts = new ArrayList<>();
        for (int i = 0; i < cols.getChildCount(); i++) {
            View c = cols.getChildAt(i);
            pts.add(Math.min(max, c.getLeft()));
            if (c.getWidth() > view) pts.add(Math.min(max, c.getRight() - view));
        }
        pts.add(max);
        int best = 0, bd = Integer.MAX_VALUE;
        for (int p : pts) {
            if (vx > 0 && p <= x) continue;
            if (vx < 0 && p >= x) continue;
            int d = Math.abs(p - x);
            if (d < bd) { bd = d; best = p; }
        }
        return bd == Integer.MAX_VALUE ? (vx > 0 ? max : 0) : best;
    }

    /** First room whose right edge is still on screen. */
    private int firstVisibleRoom() {
        int x = hs.getScrollX();
        for (int i = 0; i < roomViews.size(); i++) {
            View col = (View) roomViews.get(i).getParent();
            if (col != null && col.getRight() > x + Ui.dp(24)) return i;
        }
        return 0;
    }

    private long zoomStart;
    private boolean zoomSwapped;

    /** Fade + scale the grid out, re-flow it, then bring it back in around the same room. */
    private void setZoomed(boolean z) {
        if (z == zoomed) return;
        zoomed = z;
        getPreferences(MODE_PRIVATE).edit().putBoolean("zoomed", z).apply();
        zoomRoom = firstVisibleRoom();
        zoomStart = SystemClock.uptimeMillis();
        zoomSwapped = false;
        cols.removeCallbacks(zoomFrame);
        cols.postOnAnimation(zoomFrame);
    }

    private static final long ZOOM_OUT = 150, ZOOM_IN = 300;

    private final Runnable zoomFrame = new Runnable() {
        @Override public void run() {
            long el = SystemClock.uptimeMillis() - zoomStart;
            float grow = zoomed ? 1 : -1; // zooming in: everything seems to grow, and the reverse
            if (!zoomSwapped && el < ZOOM_OUT) {
                float u = el / (float) ZOOM_OUT, e = u * u;
                cols.setPivotX(hs.getScrollX());
                cols.setPivotY(cols.getHeight() / 2f);
                setGrid(1 - e, 1 + grow * 0.05f * e);
                cols.postOnAnimation(this);
                return;
            }
            if (!zoomSwapped) {
                zoomSwapped = true;
                setGrid(0, 1);
                layoutRooms(zoomed);
                cols.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        cols.getViewTreeObserver().removeOnPreDrawListener(this);
                        View col = (View) roomViews.get(zoomRoom).getParent();
                        hs.scrollTo(col == null ? 0 : col.getLeft(), 0);
                        zoomStart = SystemClock.uptimeMillis() - ZOOM_OUT;
                        cols.postOnAnimation(zoomFrame);
                        return true;
                    }
                });
                return;
            }
            float u = Math.min(1f, (el - ZOOM_OUT) / (float) ZOOM_IN);
            float e = 1 - (1 - u) * (1 - u) * (1 - u); // ease-out cubic
            cols.setPivotX(hs.getScrollX());
            cols.setPivotY(cols.getHeight() / 2f);
            setGrid(e, 1 - grow * 0.05f * (1 - e));
            if (u < 1f) cols.postOnAnimation(this);
        }
    };

    private void setGrid(float alpha, float scale) {
        cols.setAlpha(alpha);
        cols.setScaleX(scale);
        cols.setScaleY(scale);
    }

    // ---- lyrics: music card on the left, lyrics instead of the rooms on the right

    private float lyricsF; // 0 = rooms, 1 = lyrics
    private long lyricsLast;
    private boolean lyricsLoop;

    private void setLyrics(boolean on) {
        if (on == lyricsOn) return;
        lyricsOn = on;
        nowPlaying.setLyricsOn(on);
        updateLyricsAwake();
        if (on) {
            if (!stack.musicInFront()) stack.showMusic();
            lyricsView.setVisibility(View.VISIBLE);
            lyricsView.update();
        } else {
            hs.setVisibility(View.VISIBLE);
            zoomSwitch.setVisibility(View.VISIBLE);
        }
        if (!lyricsLoop) {
            lyricsLoop = true;
            lyricsLast = SystemClock.uptimeMillis();
            lyricsView.postOnAnimation(lyricsFrame);
        }
    }

    private final Runnable lyricsFrame = new Runnable() {
        @Override public void run() {
            long t = SystemClock.uptimeMillis();
            float dt = Math.min(64, t - lyricsLast);
            lyricsLast = t;
            float want = lyricsOn ? 1 : 0;
            lyricsF += (want - lyricsF) * (1f - (float) Math.exp(-dt / 85f));
            boolean done = Math.abs(want - lyricsF) < 0.01f;
            if (done) lyricsF = want;
            float e = lyricsF;
            hs.setAlpha(1 - e);
            hs.setTranslationY(-Ui.dpf(10) * e);
            zoomSwitch.setAlpha(1 - e);
            lyricsView.setAlpha(e);
            lyricsView.setTranslationY(Ui.dpf(16) * (1 - e));
            if (!done) {
                lyricsView.postOnAnimation(this);
                return;
            }
            lyricsLoop = false;
            if (lyricsOn) {
                hs.setVisibility(View.INVISIBLE);
                zoomSwitch.setVisibility(View.GONE);
            } else lyricsView.setVisibility(View.GONE);
        }
    };

    // ---- softer dimming while lyrics are on screen

    /**
     * With the lyrics showing (and music playing) Casa dims the screen itself instead of the system:
     * after the normal screen timeout it glides down to LYRICS_DIM (the system dim is 5 %, too dark
     * to read lyrics), and a touch or a hand in front of the clock brings it straight back.
     */
    private static final float LYRICS_DIM = 0.30f;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean lyricsAwake;
    private float lyricsBright = -1; // current override, -1 = none

    private void updateLyricsAwake() {
        boolean keep = lyricsOn && Renderer.PLAYING.equals(Renderer.get(this).state());
        if (keep == lyricsAwake) return;
        lyricsAwake = keep;
        if (keep) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Presence.onPresence = () -> ui.post(this::lyricsActivity);
            lyricsActivity();
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Presence.onPresence = null;
            ui.removeCallbacks(lyricsDim);
            setBrightness(-1);
            // back to the system's own timeout from now on
            Presence.wake(this);
        }
    }

    /** Touch or presence: full brightness again, and restart the countdown. */
    private void lyricsActivity() {
        if (!lyricsAwake) return;
        ui.removeCallbacks(lyricsDim);
        ui.removeCallbacks(rampDown);
        setBrightness(-1);
        int timeout = android.provider.Settings.System.getInt(getContentResolver(),
                android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 60000);
        ui.postDelayed(lyricsDim, Math.max(15000, timeout));
    }

    private long rampStart;
    private float rampFrom;

    private final Runnable lyricsDim = () -> {
        int user = android.provider.Settings.System.getInt(getContentResolver(),
                android.provider.Settings.System.SCREEN_BRIGHTNESS, 190);
        rampFrom = Math.max(LYRICS_DIM, user / 255f);
        rampStart = SystemClock.uptimeMillis();
        ui.post(this.rampDown);
    };

    private final Runnable rampDown = new Runnable() {
        @Override public void run() {
            float u = Math.min(1f, (SystemClock.uptimeMillis() - rampStart) / 900f);
            float e = u * u * (3 - 2 * u);
            setBrightness(rampFrom + (LYRICS_DIM - rampFrom) * e);
            if (u < 1f) ui.postDelayed(this, 30);
        }
    };

    private void setBrightness(float b) {
        if (b == lyricsBright) return;
        lyricsBright = b;
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b < 0 ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : b;
        getWindow().setAttributes(lp);
    }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        lyricsActivity();
    }

    private void launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }

    // ---- Ha.Listener
    @Override public void onEntity(String id) {
        Ha.Entity e = ha.states.get(id);
        ArrayList<TileView> l = tiles.get(id);
        if (l != null) for (TileView t : l) {
            t.entity = e;
            t.refresh();
        }
        for (RoomView rv : roomsWithSensors) if (rv.hasSensor(id)) rv.invalidate();
        clock.setActive(all, ha.states);
        if (cfg.awake.contains(id)) updateRoomLit();
        Config.Item di = detail.item();
        if (di != null && di.id.equals(id)) detail.refresh();
    }

    private void updateRoomLit() {
        boolean lit = false;
        for (String l : cfg.awake) {
            Ha.Entity e = ha.states.get(l);
            if (e != null && ("on".equals(e.state) || "playing".equals(e.state) || "home".equals(e.state))) lit = true;
        }
        if (roomLit == null || roomLit != lit) Presence.setRoomLit(this, lit);
        roomLit = lit;
    }

    @Override public void onConnection(boolean ok) { clock.setConnected(ok); }

    // ---- interactions
    private void onTap(Config.Item it) {
        switch (it.domain) {
            case "sensor": return;
            case "media_player": openDetail(it); return;
            default: toggle(it);
        }
    }

    private boolean onLong(Config.Item it) {
        Ha.Entity e = ha.states.get(it.id);
        if ("media_player".equals(it.domain) || !it.inputs.isEmpty()
                || ("light".equals(it.domain) && Ui.dimmable(e))) {
            openDetail(it);
            return true;
        }
        return false;
    }

    private void openDetail(Config.Item it) { detail.show(it, ha.states.get(it.id)); }

    @Override public void toggle(Config.Item it) {
        Ha.Entity e = ha.states.get(it.id);
        if (!Ui.avail(e) || "sensor".equals(it.domain)) return;
        boolean on = Ui.isOn(it, e);
        ha.call(it.domain, on ? "turn_off" : "turn_on", it.id, null);
        e.state = on ? ("media_player".equals(it.domain) ? "standby" : "off") : "on"; // optimistic
        onEntity(it.id);
    }

    @Override public void call(String domain, String service, String entity, JSONObject data) {
        ha.call(domain, service, entity, data);
    }

    @Override public void changed(String id) { onEntity(id); }

    @Override public void closeDetail() { detail.setVisibility(View.GONE); }
}
