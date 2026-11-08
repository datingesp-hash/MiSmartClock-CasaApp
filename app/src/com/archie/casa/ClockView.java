package com.archie.casa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Vertical clock card: stacked HH / MM, date, and bubbles for everything that is on. */
final class ClockView extends View {
    interface OnIcon {
        void onIcon(Config.Item it);
        void onApp();       // SmartTube button
        void onSettings();  // long-press on the clock (maintenance escape hatch)
    }

    private final OnIcon cb;
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint time = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint day = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint date = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final SimpleDateFormat fDay = new SimpleDateFormat("EEEE", Ui.LOC);
    private final SimpleDateFormat fDate = new SimpleDateFormat(Ui.es() ? "d 'de' MMMM" : "MMMM d", Ui.LOC);
    private final ArrayList<Config.Item> active = new ArrayList<>();
    private final ArrayList<Integer> colors = new ArrayList<>();
    private final ArrayList<RectF> hits = new ArrayList<>();
    private final HashMap<String, Drawable> icons = new HashMap<>();
    private final Drawable wifiOff, playIcon;
    private final RectF btn = new RectF();
    private final TextPaint btnTxt = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private boolean btnDown;
    private final Runnable longPress = this::fireLongPress;

    private void fireLongPress() {
        downIdx = -2; // consumed
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        cb.onSettings();
    }
    private String hh = "--", mm = "--", sDay = "", sDate = "";
    private boolean connected;
    private float capH;
    private int downIdx = -1;

    ClockView(Context c, OnIcon cb) {
        super(c);
        this.cb = cb;
        wifiOff = Ui.icon(c, "wifi-off");
        playIcon = Ui.icon(c, "play");
        btnTxt.setTypeface(Ui.MED);
        btnTxt.setTextSize(Ui.dpf(15));
        btnTxt.setColor(0xFFFFFFFF);
        time.setTypeface(Ui.LIGHT);
        time.setLetterSpacing(-0.03f);
        day.setTypeface(Ui.MED);
        day.setTextSize(Ui.dpf(17));
        day.setColor(Ui.TXT);
        date.setTypeface(Ui.REG);
        date.setTextSize(Ui.dpf(14));
        date.setColor(Ui.TXT2);
        label.setTypeface(Ui.MED);
        label.setTextSize(Ui.dpf(11));
        label.setLetterSpacing(0.08f);
    }

    void setTime(Calendar c) {
        hh = String.format(Locale.US, "%02d", c.get(Calendar.HOUR_OF_DAY));
        mm = String.format(Locale.US, "%02d", c.get(Calendar.MINUTE));
        String d = fDay.format(c.getTime());
        sDay = d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1);
        sDate = fDate.format(c.getTime());
        invalidate();
    }

    void setConnected(boolean ok) {
        if (ok != connected) {
            connected = ok;
            invalidate();
        }
    }

    void setActive(ArrayList<Config.Item> items, Map<String, Ha.Entity> st) {
        active.clear();
        colors.clear();
        for (Config.Item it : items) {
            Ha.Entity e = st.get(it.id);
            if (Ui.isOn(it, e)) {
                active.add(it);
                colors.add(Ui.accent(it, e));
            }
        }
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        card.setShader(new LinearGradient(0, 0, 0, h, 0xFF26262B, 0xFF19191D, Shader.TileMode.CLAMP));
        float pad = Ui.dpf(20);
        time.setTextSize(100);
        Rect b = new Rect();
        time.getTextBounds("0", 0, 1, b);
        float cap = b.height() / 100f;
        float byW = (w - 2 * pad) * 100f / time.measureText("00");
        float byH = (h * 0.46f) / (cap * 2 + 0.16f);
        float size = Math.min(byW, byH);
        time.setTextSize(size);
        capH = cap * size;
    }

    @Override protected void onDraw(Canvas c) {
        final int w = getWidth(), h = getHeight();
        final float pad = Ui.dpf(20), R = Ui.dpf(30);
        rf.set(0, 0, w, h);
        c.drawRoundRect(rf, R, R, card);

        float y = pad + capH + Ui.dpf(4);
        time.setColor(Ui.TXT);
        c.drawText(hh, pad - Ui.dpf(3), y, time);
        y += capH + time.getTextSize() * 0.16f;
        time.setColor(Ui.ORANGE);
        c.drawText(mm, pad - Ui.dpf(3), y, time);

        y += Ui.dpf(30);
        c.drawText(sDay, pad, y, day);
        y += Ui.dpf(20);
        c.drawText(sDate, pad, y, date);

        // SmartTube pill at the bottom of the card
        float bh = Ui.dpf(46);
        btn.set(pad - Ui.dpf(6), h - pad + Ui.dpf(6) - bh, w - pad + Ui.dpf(6), h - pad + Ui.dpf(6));
        Ui.fill.setColor(btnDown ? 0xFFD70015 : 0xFFFF3B30);
        c.drawRoundRect(btn, bh / 2f, bh / 2f, Ui.fill);
        String bt = "SmartTube";
        int pis = Ui.dp(22);
        float tw = btnTxt.measureText(bt), gx = btn.centerX() - (pis + Ui.dpf(6) + tw) / 2f;
        playIcon.setBounds((int) gx, (int) (btn.centerY() - pis / 2f), (int) gx + pis, (int) (btn.centerY() + pis / 2f));
        playIcon.setTint(0xFFFFFFFF);
        playIcon.draw(c);
        c.drawText(bt, gx + pis + Ui.dpf(6), btn.centerY() + Ui.dpf(5.5f), btnTxt);

        y += Ui.dpf(30);
        hits.clear();
        if (!connected) {
            int is = Ui.dp(16);
            wifiOff.setBounds((int) pad, (int) (y - is + Ui.dpf(3)), (int) pad + is, (int) y + Ui.dp(3));
            wifiOff.setTint(Ui.RED);
            wifiOff.draw(c);
            label.setColor(Ui.RED);
            c.drawText(Ui.t("OFFLINE", "SIN CONEXIÓN"), pad + is + Ui.dpf(6), y, label);
            return;
        }
        label.setColor(Ui.TXT2);
        c.drawText(active.isEmpty() ? Ui.t("ALL OFF", "TODO APAGADO") : Ui.t("ON", "ENCENDIDO") + " · " + active.size(), pad, y, label);

        float r = Ui.dpf(19), gap = Ui.dpf(8);
        int perRow = Math.max(1, (int) ((w - 2 * pad + gap) / (2 * r + gap)));
        float x0 = pad, top = y + Ui.dpf(12);
        int is = Ui.dp(20);
        for (int i = 0; i < active.size(); i++) {
            int row = i / perRow, col = i % perRow;
            float cx = x0 + r + col * (2 * r + gap), cy = top + r + row * (2 * r + gap);
            if (cy + r > btn.top - Ui.dpf(10)) break;
            Config.Item it = active.get(i);
            Ui.fill.setColor(colors.get(i));
            c.drawCircle(cx, cy, i == downIdx ? r * 0.9f : r, Ui.fill);
            Drawable d = icons.get(it.id);
            if (d == null) {
                d = Ui.icon(getContext(), it.icon.isEmpty() ? Ui.defaultIcon(it.domain) : it.icon);
                icons.put(it.id, d);
            }
            d.setBounds((int) (cx - is / 2f), (int) (cy - is / 2f), (int) (cx + is / 2f), (int) (cy + is / 2f));
            d.setTint(0xFFFFFFFF);
            d.draw(c);
            hits.add(new RectF(cx - r, cy - r, cx + r, cy + r));
        }
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                btnDown = btn.contains(x, y);
                downIdx = btnDown ? -1 : hit(x, y);
                if (!btnDown && downIdx < 0) postDelayed(longPress, 900);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (btnDown && !btn.contains(x, y)) {
                    btnDown = false;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                if (btnDown && btn.contains(x, y)) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    cb.onApp();
                } else {
                    int i = hit(x, y);
                    if (i >= 0 && i == downIdx && i < active.size()) {
                        playSoundEffect(android.view.SoundEffectConstants.CLICK);
                        cb.onIcon(active.get(i));
                    }
                }
                btnDown = false;
                downIdx = -1;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                btnDown = false;
                downIdx = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private int hit(float x, float y) {
        for (int i = 0; i < hits.size(); i++) if (hits.get(i).contains(x, y)) return i;
        return -1;
    }
}
