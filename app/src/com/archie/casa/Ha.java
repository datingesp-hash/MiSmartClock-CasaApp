package com.archie.casa;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Home Assistant WebSocket session. Uses "subscribe_entities" so HA only pushes compact
 * diffs for the entities on screen. State lives on the main thread.
 */
final class Ha {
    interface Listener {
        void onEntity(String id);
        void onConnection(boolean ok);
    }

    static final class Entity {
        final String id;
        String state = "unavailable";
        JSONObject attrs = new JSONObject();
        Entity(String id) { this.id = id; }
    }

    final HashMap<String, Entity> states = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("ha-io");
    private final Handler io;
    private final URI uri;
    private final String token;
    private final JSONArray ids;
    private final Listener listener;
    private final AtomicInteger seq = new AtomicInteger(10);
    private volatile boolean running;
    private volatile Ws ws;
    private volatile long backoff = 1000;

    Ha(String url, String token, List<String> ids, Listener l) {
        this.uri = URI.create(url);
        this.token = token;
        this.ids = new JSONArray(ids);
        this.listener = l;
        for (String id : ids) states.put(id, new Entity(id));
        thread.start();
        io = new Handler(thread.getLooper());
    }

    void start() {
        running = true;
        backoff = 1000;
        io.removeCallbacks(connect);
        io.post(connect);
    }

    void stop() {
        running = false;
        io.removeCallbacks(connect);
        io.removeCallbacks(ping);
        Ws w = ws;
        ws = null;
        if (w != null) w.close();
    }

    void quit() {
        stop();
        thread.quitSafely();
    }

    private final Runnable connect = new Runnable() {
        @Override public void run() {
            if (!running || ws != null) return;
            final Ws w = new Ws();
            try {
                w.connect(uri, 5000);
            } catch (Exception e) {
                w.close();
                retry();
                return;
            }
            ws = w;
            Thread t = new Thread(() -> w.readLoop(new Ws.Listener() {
                @Override public void onText(String s) { handle(w, s); }
                @Override public void onClosed(Exception e) {
                    io.post(() -> {
                        if (ws == w) ws = null;
                        io.removeCallbacks(ping);
                        retry();
                    });
                    main.post(() -> listener.onConnection(false));
                }
            }), "ha-read");
            t.start();
        }
    };

    private void retry() {
        if (!running) return;
        io.removeCallbacks(connect);
        io.postDelayed(connect, backoff);
        backoff = Math.min(backoff * 2, 30_000);
    }

    private final Runnable ping = new Runnable() {
        @Override public void run() {
            send("{\"id\":" + seq.incrementAndGet() + ",\"type\":\"ping\"}");
            io.postDelayed(this, 30_000);
        }
    };

    private void send(String s) {
        Ws w = ws;
        if (w == null) return;
        try {
            w.sendText(s);
        } catch (Exception e) {
            w.close();
        }
    }

    /** Runs on the reader thread. */
    private void handle(Ws w, String s) {
        try {
            JSONObject m = new JSONObject(s);
            switch (m.optString("type")) {
                case "auth_required":
                    w.sendText(new JSONObject().put("type", "auth").put("access_token", token).toString());
                    break;
                case "auth_ok":
                    w.sendText(new JSONObject().put("id", 1).put("type", "subscribe_entities")
                            .put("entity_ids", ids).toString());
                    io.post(() -> {
                        backoff = 1000;
                        io.removeCallbacks(ping);
                        io.postDelayed(ping, 30_000);
                    });
                    main.post(() -> listener.onConnection(true));
                    break;
                case "auth_invalid":
                    backoff = 60_000;
                    w.close();
                    break;
                case "event":
                    if (m.optInt("id") == 1) {
                        final JSONObject ev = m.getJSONObject("event");
                        main.post(() -> apply(ev));
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private Entity ent(String id) {
        Entity e = states.get(id);
        if (e == null) {
            e = new Entity(id);
            states.put(id, e);
        }
        return e;
    }

    private void apply(JSONObject ev) {
        try {
            JSONObject a = ev.optJSONObject("a");
            if (a != null) {
                for (Iterator<String> it = a.keys(); it.hasNext(); ) {
                    String id = it.next();
                    JSONObject o = a.getJSONObject(id);
                    Entity e = ent(id);
                    e.state = o.optString("s", "unknown");
                    JSONObject at = o.optJSONObject("a");
                    e.attrs = at != null ? at : new JSONObject();
                    listener.onEntity(id);
                }
            }
            JSONObject c = ev.optJSONObject("c");
            if (c != null) {
                for (Iterator<String> it = c.keys(); it.hasNext(); ) {
                    String id = it.next();
                    JSONObject d = c.getJSONObject(id);
                    Entity e = ent(id);
                    JSONObject p = d.optJSONObject("+");
                    if (p != null) {
                        if (p.has("s")) e.state = p.optString("s");
                        JSONObject pa = p.optJSONObject("a");
                        if (pa != null) {
                            for (Iterator<String> k = pa.keys(); k.hasNext(); ) {
                                String key = k.next();
                                e.attrs.put(key, pa.opt(key));
                            }
                        }
                    }
                    JSONObject mi = d.optJSONObject("-");
                    if (mi != null) {
                        JSONArray ra = mi.optJSONArray("a");
                        if (ra != null) for (int i = 0; i < ra.length(); i++) e.attrs.remove(ra.optString(i));
                    }
                    listener.onEntity(id);
                }
            }
            JSONArray r = ev.optJSONArray("r");
            if (r != null) {
                for (int i = 0; i < r.length(); i++) {
                    String id = r.optString(i);
                    ent(id).state = "unavailable";
                    listener.onEntity(id);
                }
            }
        } catch (JSONException ignored) {
        }
    }

    /** service may be "domain.service" with domain == null. entity may be null. */
    void call(String domain, String service, String entity, JSONObject data) {
        try {
            if (domain == null) {
                int dot = service.indexOf('.');
                domain = service.substring(0, dot);
                service = service.substring(dot + 1);
            }
            JSONObject m = new JSONObject().put("id", seq.incrementAndGet()).put("type", "call_service")
                    .put("domain", domain).put("service", service);
            if (data != null) m.put("service_data", data);
            if (entity != null) m.put("target", new JSONObject().put("entity_id", entity));
            final String s = m.toString();
            io.post(() -> send(s));
        } catch (JSONException ignored) {
        }
    }
}
