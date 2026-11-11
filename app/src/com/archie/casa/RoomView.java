package com.archie.casa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Map;

/**
 * Room title (left) with inline sensor readouts (small grey icon + value),
 * then a 2-row grid of tiles; width grows with the number of tiles.
 */
final class RoomView extends ViewGroup {
    private final String title;
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Config.Item> sensors = new ArrayList<>();
    private final ArrayList<Drawable> sensorIcons = new ArrayList<>();
    private Map<String, Ha.Entity> states;
    private int cols = 1, tileW, tileH;
    private int gap = Ui.dp(10), titleH = Ui.dp(34);

    RoomView(Context c, String title) {
        super(c);
        this.title = title;
        tp.setTypeface(Ui.MED);
        tp.setTextSize(Ui.dpf(18));
        tp.setColor(Ui.TXT);
        tp.setLetterSpacing(-0.01f);
        sp.setTypeface(Ui.REG);
        sp.setTextSize(Ui.dpf(13.5f));
        sp.setColor(Ui.TXT2);
        setWillNotDraw(false);
        setClipChildren(false);
    }

    /** Zoomed mode: the room gets a whole column, so a slightly bigger title and spacing. */
    void setZoomed(boolean z) {
        tp.setTextSize(Ui.dpf(z ? 21 : 18));
        sp.setTextSize(Ui.dpf(z ? 15 : 13.5f));
        titleH = Ui.dp(z ? 40 : 34);
        gap = Ui.dp(z ? 12 : 10);
        requestLayout();
        invalidate();
    }

    void addSensor(Config.Item it) {
        sensors.add(it);
        sensorIcons.add(Ui.icon(getContext(), it.icon.isEmpty() ? Ui.defaultIcon(it.domain) : it.icon));
    }

    void bind(Map<String, Ha.Entity> st) { states = st; }

    boolean hasSensor(String id) {
        for (Config.Item it : sensors) if (it.id.equals(id)) return true;
        return false;
    }

    private String value(Config.Item it) {
        Ha.Entity e = states == null ? null : states.get(it.id);
        return Ui.avail(e) ? Ui.sensor(e) : "—";
    }

    @Override protected void onMeasure(int ws, int hs) {
        int h = MeasureSpec.getSize(hs);
        int n = getChildCount();
        tileH = Math.max(Ui.dp(60), (h - titleH - gap) / 2);
        tileW = Math.round(tileH * 1.32f);
        cols = Math.max(1, (n + 1) / 2);
        int head = (int) tp.measureText(title) + Ui.dp(8) + sensors.size() * Math.round(sp.getTextSize() * 5.6f);
        int w = Math.max(cols * tileW + (cols - 1) * gap, head);
        int cw = MeasureSpec.makeMeasureSpec(tileW, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY);
        for (int i = 0; i < n; i++) getChildAt(i).measure(cw, ch);
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0, n = getChildCount(); i < n; i++) {
            View v = getChildAt(i);
            int row = i / cols, col = i % cols;
            int x = col * (tileW + gap), y = titleH + row * (tileH + gap);
            v.layout(x, y, x + tileW, y + tileH);
        }
    }

    @Override protected void onDraw(Canvas c) {
        float base = titleH - Ui.dpf(11);
        float x = Ui.dpf(4);
        c.drawText(title, x, base, tp);
        x += tp.measureText(title) + Ui.dpf(14);
        int is = Ui.dp(15);
        float midY = base - Ui.dpf(5.5f); // optical centre of the title's x-height
        for (int i = 0; i < sensors.size(); i++) {
            Drawable d = sensorIcons.get(i);
            d.setBounds((int) x, (int) (midY - is / 2f), (int) x + is, (int) (midY + is / 2f));
            d.setTint(Ui.TXT2);
            d.draw(c);
            x += is + Ui.dpf(3);
            String v = value(sensors.get(i));
            c.drawText(v, x, base, sp);
            x += sp.measureText(v) + Ui.dpf(12);
        }
    }
}
