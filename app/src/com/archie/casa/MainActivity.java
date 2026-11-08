package com.archie.casa;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
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
        stack.setCards(clock, nowPlaying);
        row.setClipChildren(false);
        root.setClipChildren(false);
        stack.setTranslationZ(2); // draw above the rooms: the card slides out over them
        row.addView(stack, new LinearLayout.LayoutParams(Ui.dp(212), ViewGroup.LayoutParams.MATCH_PARENT));

        HorizontalScrollView hs = new HorizontalScrollView(this);
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
        LinearLayout col = null;
        for (int i = 0; i < cfg.rooms.size(); i++) {
            Config.Room room = cfg.rooms.get(i);
            if (i % 2 == 0) {
                col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                if (i > 0) lp.leftMargin = Ui.dp(22);
                cols.addView(col, lp);
            }
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f);
            if (i % 2 == 1) lp.topMargin = Ui.dp(8);
            col.addView(rv, lp);
            if (i % 2 == 0 && i == cfg.rooms.size() - 1) {
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 0, 1f);
                sp.topMargin = Ui.dp(8);
                col.addView(new View(this), sp); // keep a lone room at half height
            }
        }
        row.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        detail = new DetailView(this, this);
        detail.setVisibility(View.GONE);
        root.addView(detail, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

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
        // We are the home screen: back only closes the sheet, never exits.
        if (detail != null && detail.getVisibility() == View.VISIBLE) closeDetail();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i); // HOME pressed while already on top: just close any open sheet
        if (detail != null && detail.getVisibility() == View.VISIBLE) closeDetail();
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
        if (cfg.roomLights.contains(id)) updateRoomLit();
        Config.Item di = detail.item();
        if (di != null && di.id.equals(id)) detail.refresh();
    }

    private void updateRoomLit() {
        boolean lit = false;
        for (String l : cfg.roomLights) {
            Ha.Entity e = ha.states.get(l);
            if (e != null && "on".equals(e.state)) lit = true;
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
