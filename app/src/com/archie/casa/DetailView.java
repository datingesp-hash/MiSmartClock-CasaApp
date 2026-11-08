package com.archie.casa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/** Modal sheet drawn on a single View: lights (brightness / white temp), media, TV inputs. */
final class DetailView extends View {
    interface Host {
        void call(String domain, String service, String entity, JSONObject data);
        void toggle(Config.Item it);
        void changed(String id);
        void closeDetail();
    }

    private static final class Btn {
        final RectF r = new RectF();
        Drawable icon;
        String label;
        Runnable act;
        boolean primary, round;
    }

    private final Host host;
    private Config.Item item;
    private Ha.Entity e;
    private Drawable head;
    private final RectF panel = new RectF(), bri = new RectF(), ct = new RectF(), tmp = new RectF();
    private final ArrayList<Btn> btns = new ArrayList<>();
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint btnTxt = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ctPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable bulb;
    private boolean hasBri, hasCt;
    private float briV, ctV = 0.5f;
    private int ctMin = 2200, ctMax = 6500, drag;
    private long lastSend;
    private Btn pressed;
    private boolean downOutside;

    DetailView(Context c, Host h) {
        super(c);
        host = h;
        bulb = Ui.icon(c, "lightbulb");
        title.setTypeface(Ui.MED);
        title.setTextSize(Ui.dpf(20));
        title.setColor(Ui.TXT);
        sub.setTypeface(Ui.REG);
        sub.setTextSize(Ui.dpf(14));
        sub.setColor(Ui.TXT2);
        btnTxt.setTypeface(Ui.MED);
        btnTxt.setTextSize(Ui.dpf(14));
        btnTxt.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dpf(1.5f));
        stroke.setColor(0x40000000);
    }

    Config.Item item() { return getVisibility() == VISIBLE ? item : null; }

    void show(Config.Item it, Ha.Entity en) {
        item = it;
        e = en;
        head = Ui.icon(getContext(), it.icon.isEmpty() ? Ui.defaultIcon(it.domain) : it.icon);
        drag = 0;
        pressed = null;
        ctV = 0.5f;
        setVisibility(VISIBLE);
        refresh();
    }

    void refresh() {
        if (item == null) return;
        if (drag == 0) readValues();
        build();
        invalidate();
    }

    private void readValues() {
        hasBri = hasCt = false;
        if (!"light".equals(item.domain) || e == null) return;
        JSONObject a = e.attrs;
        org.json.JSONArray modes = a.optJSONArray("supported_color_modes");
        if (modes != null) for (int i = 0; i < modes.length(); i++) {
            String m = modes.optString(i);
            if (!"onoff".equals(m)) hasBri = true;
            if ("color_temp".equals(m)) hasCt = true;
        }
        briV = "on".equals(e.state) ? a.optInt("brightness", 255) / 255f : 0f;
        ctMin = a.optInt("min_color_temp_kelvin", 2200);
        ctMax = a.optInt("max_color_temp_kelvin", 6500);
        if (ctMax <= ctMin) ctMax = ctMin + 1;
        int k = a.optInt("color_temp_kelvin", 0);
        if (k > 0) ctV = Ui.clamp((k - ctMin) / (float) (ctMax - ctMin));
    }

    private Btn btn(float l, float t, float r, float b, String icon, String label, boolean primary, boolean round, Runnable act) {
        Btn x = new Btn();
        x.r.set(l, t, r, b);
        x.icon = icon == null ? null : Ui.icon(getContext(), icon);
        x.label = label;
        x.primary = primary;
        x.round = round;
        x.act = act;
        btns.add(x);
        return x;
    }

    private void build() {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        btns.clear();
        final float pad = Ui.dpf(22), gap = Ui.dpf(12), headH = Ui.dpf(52);
        final float pw = Math.min(w - Ui.dpf(80), Ui.dpf(470));
        final boolean on = Ui.isOn(item, e);
        final String id = item.id;
        String d = item.domain;
        float content;
        if ("light".equals(d)) content = (hasBri ? Ui.dpf(64) + gap : 0) + (hasCt ? Ui.dpf(46) + gap : 0) + Ui.dpf(52);
        else if ("media_player".equals(d)) content = Ui.dpf(64) + gap + Ui.dpf(52);
        else if (!item.inputs.isEmpty()) content = Ui.dpf(86);
        else content = Ui.dpf(52);
        float ph = pad * 2 + headH + gap + Ui.dpf(4) + content;
        panel.set((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f);
        float L = panel.left + pad, Rr = panel.right - pad, y = panel.top + pad + headH + gap + Ui.dpf(4);

        if ("light".equals(d)) {
            if (hasBri) {
                bri.set(L, y, Rr, y + Ui.dpf(64));
                y += Ui.dpf(64) + gap;
            }
            if (hasCt) {
                ct.set(L, y, Rr, y + Ui.dpf(46));
                ctPaint.setShader(new LinearGradient(ct.left, 0, ct.right, 0,
                        new int[]{0xFFFFA347, 0xFFFFE2B8, 0xFFF4F6FF, 0xFFC9DEFF}, null, Shader.TileMode.CLAMP));
                y += Ui.dpf(46) + gap;
            }
            btn(L, y, Rr, y + Ui.dpf(52), "power", on ? Ui.t("Turn off", "Apagar") : Ui.t("Turn on", "Encender"), !on, false, () -> host.toggle(item));
        } else if ("media_player".equals(d)) {
            float s = Ui.dpf(64), cx = (L + Rr) / 2f, sp = Ui.dpf(90);
            boolean playing = e != null && "playing".equals(e.state);
            btn(cx - sp - s / 2, y, cx - sp + s / 2, y + s, "skip-previous", null, false, true,
                    () -> host.call("media_player", "media_previous_track", id, null));
            btn(cx - s / 2, y, cx + s / 2, y + s, playing ? "pause" : "play", null, true, true,
                    () -> host.call("media_player", "media_play_pause", id, null));
            btn(cx + sp - s / 2, y, cx + sp + s / 2, y + s, "skip-next", null, false, true,
                    () -> host.call("media_player", "media_next_track", id, null));
            y += s + gap;
            float bw = (Rr - L - 2 * gap) / 3f;
            btn(L, y, L + bw, y + Ui.dpf(52), "volume-minus", null, false, false,
                    () -> host.call("media_player", "volume_down", id, null));
            btn(L + bw + gap, y, L + 2 * bw + gap, y + Ui.dpf(52), "power", on ? Ui.t("Turn off", "Apagar") : Ui.t("Turn on", "Encender"), false, false,
                    () -> host.call("media_player", on ? "turn_off" : "turn_on", id, null));
            btn(L + 2 * (bw + gap), y, Rr, y + Ui.dpf(52), "volume-plus", null, false, false,
                    () -> host.call("media_player", "volume_up", id, null));
        } else if (!item.inputs.isEmpty()) {
            int n = item.inputs.size() + 1;
            float bw = (Rr - L - (n - 1) * gap) / n;
            btn(L, y, L + bw, y + Ui.dpf(86), "power", on ? Ui.t("Turn off", "Apagar") : Ui.t("Turn on", "Encender"), !on, false, () -> host.toggle(item));
            for (int i = 0; i < item.inputs.size(); i++) {
                final Config.Input in = item.inputs.get(i);
                float x = L + (i + 1) * (bw + gap);
                btn(x, y, x + bw, y + Ui.dpf(86), in.icon, in.name, false, false,
                        () -> host.call(null, in.service, null, null));
            }
        } else {
            btn(L, y, Rr, y + Ui.dpf(52), "power", on ? Ui.t("Turn off", "Apagar") : Ui.t("Turn on", "Encender"), !on, false, () -> host.toggle(item));
        }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (item != null) build();
    }

    @Override protected void onDraw(Canvas c) {
        if (item == null) return;
        c.drawColor(0xB8000000);
        final float R = Ui.dpf(32), pad = Ui.dpf(22);
        Ui.fill.setColor(Ui.CARD);
        c.drawRoundRect(panel, R, R, Ui.fill);

        // header
        boolean on = Ui.isOn(item, e);
        float cr = Ui.dpf(26), cx = panel.left + pad + cr, cy = panel.top + pad + cr;
        Ui.fill.setColor(on ? Ui.accent(item, e) : Ui.CARD2);
        c.drawCircle(cx, cy, cr, Ui.fill);
        int is = Ui.dp(26);
        head.setBounds((int) (cx - is / 2f), (int) (cy - is / 2f), (int) (cx + is / 2f), (int) (cy + is / 2f));
        head.setTint(on ? 0xFFFFFFFF : Ui.TXT2);
        head.draw(c);
        float tx = cx + cr + Ui.dpf(14), tw = panel.right - pad - tx;
        CharSequence t = TextUtils.ellipsize(item.name, title, tw, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), tx, cy - Ui.dpf(3), title);
        String s = Ui.state(item, e);
        if ("media_player".equals(item.domain) && e != null) {
            String mt = e.attrs.optString("media_title", ""), ma = e.attrs.optString("media_artist", "");
            if (!mt.isEmpty() && !"null".equals(mt)) s = mt + (ma.isEmpty() || "null".equals(ma) ? "" : " — " + ma);
            else {
                String app = e.attrs.optString("app_name", "");
                if (!app.isEmpty() && !"null".equals(app)) s = s + " · " + app;
            }
        }
        CharSequence st = TextUtils.ellipsize(s, sub, tw, TextUtils.TruncateAt.END);
        c.drawText(st, 0, st.length(), tx, cy + Ui.dpf(18), sub);

        if ("light".equals(item.domain)) {
            if (hasBri) drawBri(c);
            if (hasCt) drawCt(c);
        }
        for (Btn b : btns) drawBtn(c, b);
    }

    private void drawBri(Canvas c) {
        float r = bri.height() / 2f;
        Ui.fill.setColor(Ui.CARD2);
        c.drawRoundRect(bri, r, r, Ui.fill);
        if (briV > 0) {
            tmp.set(bri.left, bri.top, bri.left + Math.max(bri.height(), briV * bri.width()), bri.bottom);
            Ui.fill.setColor(Ui.AMBER);
            c.drawRoundRect(tmp, r, r, Ui.fill);
        }
        int is = Ui.dp(24);
        float cx = bri.left + r, cy = bri.centerY();
        bulb.setBounds((int) (cx - is / 2f), (int) (cy - is / 2f), (int) (cx + is / 2f), (int) (cy + is / 2f));
        bulb.setTint(briV > 0 ? Ui.TXT_ON : Ui.TXT2);
        bulb.draw(c);
        btnTxt.setTextAlign(Paint.Align.RIGHT);
        btnTxt.setColor(briV > 0.85f ? Ui.TXT_ON : Ui.TXT);
        btnTxt.setTextSize(Ui.dpf(16));
        c.drawText(Math.round(briV * 100) + "%", bri.right - Ui.dpf(22), cy + Ui.dpf(6), btnTxt);
        btnTxt.setTextSize(Ui.dpf(14));
        btnTxt.setTextAlign(Paint.Align.CENTER);
    }

    private void drawCt(Canvas c) {
        float r = ct.height() / 2f;
        c.drawRoundRect(ct, r, r, ctPaint);
        float kx = ct.left + r + ctV * (ct.width() - 2 * r), kr = r - Ui.dpf(5);
        Ui.fill.setColor(0xFFFFFFFF);
        c.drawCircle(kx, ct.centerY(), kr, Ui.fill);
        c.drawCircle(kx, ct.centerY(), kr, stroke);
    }

    private void drawBtn(Canvas c, Btn b) {
        boolean p = b == pressed;
        float r = b.round ? b.r.height() / 2f : Math.min(Ui.dpf(20), b.r.height() / 2f);
        Ui.fill.setColor(b.primary ? (p ? Ui.TILE_ON_PRESSED : Ui.TILE_ON) : (p ? Ui.CARD3 : Ui.CARD2));
        c.drawRoundRect(b.r, r, r, Ui.fill);
        int fg = b.primary ? Ui.TXT_ON : Ui.TXT;
        btnTxt.setColor(fg);
        int is = Ui.dp(b.round ? 30 : 22);
        float cx = b.r.centerX(), cy = b.r.centerY();
        if (b.label == null) {
            drawIcon(c, b.icon, cx, cy, is, fg);
        } else if (b.r.height() > Ui.dpf(70)) {
            drawIcon(c, b.icon, cx, cy - Ui.dpf(11), Ui.dp(26), fg);
            c.drawText(b.label, cx, cy + Ui.dpf(24), btnTxt);
        } else {
            float tw = btnTxt.measureText(b.label), total = is + Ui.dpf(8) + tw;
            float x = cx - total / 2f;
            drawIcon(c, b.icon, x + is / 2f, cy, is, fg);
            c.drawText(b.label, x + is + Ui.dpf(8) + tw / 2f, cy + Ui.dpf(5), btnTxt);
        }
    }

    private static void drawIcon(Canvas c, Drawable d, float cx, float cy, int s, int tint) {
        if (d == null) return;
        d.setBounds((int) (cx - s / 2f), (int) (cy - s / 2f), (int) (cx + s / 2f), (int) (cy + s / 2f));
        d.setTint(tint);
        d.draw(c);
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downOutside = !panel.contains(x, y);
                drag = 0;
                pressed = null;
                if (!downOutside) {
                    if (hasBri && bri.contains(x, y)) drag = 1;
                    else if (hasCt && ct.contains(x, y)) drag = 2;
                    else for (Btn b : btns) if (b.r.contains(x, y)) pressed = b;
                }
                if (drag != 0) updateDrag(x, false);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (drag != 0) updateDrag(x, false);
                else if (pressed != null && !pressed.r.contains(x, y)) {
                    pressed = null;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (drag != 0) {
                    updateDrag(x, true);
                    drag = 0;
                } else if (pressed != null) {
                    Btn b = pressed;
                    pressed = null;
                    playSoundEffect(SoundEffectConstants.CLICK);
                    b.act.run();
                    invalidate();
                } else if (downOutside && !panel.contains(x, y)) {
                    host.closeDetail();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                drag = 0;
                pressed = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void updateDrag(float x, boolean fin) {
        long now = SystemClock.uptimeMillis();
        boolean send = fin || now - lastSend > 350;
        try {
            if (drag == 1) {
                briV = Ui.clamp((x - bri.left) / bri.width());
                if (send) {
                    lastSend = now;
                    int pct = Math.round(briV * 100);
                    if (pct <= 0) {
                        host.call("light", "turn_off", item.id, null);
                        if (e != null) e.state = "off";
                    } else {
                        host.call("light", "turn_on", item.id, new JSONObject().put("brightness_pct", pct));
                        if (e != null) {
                            e.state = "on";
                            e.attrs.put("brightness", Math.round(pct * 2.55f));
                        }
                    }
                    if (fin) host.changed(item.id);
                }
            } else if (drag == 2) {
                ctV = Ui.clamp((x - ct.left - ct.height() / 2f) / (ct.width() - ct.height()));
                if (send) {
                    lastSend = now;
                    int k = Math.round(ctMin + ctV * (ctMax - ctMin));
                    host.call("light", "turn_on", item.id, new JSONObject().put("color_temp_kelvin", k));
                    if (e != null) {
                        e.state = "on";
                        e.attrs.put("color_temp_kelvin", k);
                    }
                    if (fin) host.changed(item.id);
                }
            }
        } catch (JSONException ignored) {
        }
        invalidate();
    }
}
