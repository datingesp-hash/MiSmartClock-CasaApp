package com.archie.casa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;

/** One entity = one flat View drawn on canvas (no nested layouts). */
final class TileView extends View {
    final Config.Item item;
    Ha.Entity entity;
    private final Drawable icon;
    private final RectF rf = new RectF();
    private CharSequence nameFit = "";

    TileView(Context c, Config.Item it) {
        super(c);
        item = it;
        icon = Ui.icon(c, it.icon.isEmpty() ? Ui.defaultIcon(it.domain) : it.icon);
        setClickable(true);
        setLongClickable(true);
        setFocusable(false);
    }

    void refresh() {
        setAlpha(Ui.avail(entity) ? 1f : 0.45f);
        invalidate();
    }

    @Override public boolean hasOverlappingRendering() { return false; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        nameFit = TextUtils.ellipsize(item.name, Ui.name, w - Ui.dpf(24), TextUtils.TruncateAt.END);
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        final int w = getWidth(), h = getHeight();
        final boolean sensor = "sensor".equals(item.domain);
        final boolean on = Ui.isOn(item, entity);
        final boolean pressed = isPressed() && !sensor;
        final int acc = Ui.accent(item, entity);
        final float r = Ui.dpf(22), pad = Ui.dpf(12), cr = Ui.dpf(17);

        int save = c.save();
        if (pressed) c.scale(0.96f, 0.96f, w / 2f, h / 2f);

        Ui.fill.setColor(on ? (pressed ? Ui.TILE_ON_PRESSED : Ui.TILE_ON) : (pressed ? Ui.CARD2 : Ui.CARD));
        rf.set(0, 0, w, h);
        c.drawRoundRect(rf, r, r, Ui.fill);

        // icon bubble
        float cx = pad + cr, cy = pad + cr;
        Ui.fill.setColor(on ? acc : sensor ? (acc & 0x00FFFFFF) | 0x2E000000 : Ui.CARD2);
        c.drawCircle(cx, cy, cr, Ui.fill);
        int is = Ui.dp(20);
        icon.setBounds((int) (cx - is / 2f), (int) (cy - is / 2f), (int) (cx + is / 2f), (int) (cy + is / 2f));
        icon.setTint(on ? 0xFFFFFFFF : sensor ? acc : Ui.TXT2);
        icon.draw(c);

        String st = Ui.state(item, entity);
        float bottom = h - pad;
        if (sensor && Ui.avail(entity)) {
            // big value top-right, name bottom
            Ui.big.setColor(Ui.TXT);
            Ui.big.setTextAlign(android.graphics.Paint.Align.RIGHT);
            c.drawText(st, w - pad, cy - (Ui.big.ascent() + Ui.big.descent()) / 2f, Ui.big);
            Ui.big.setTextAlign(android.graphics.Paint.Align.LEFT);
            Ui.name.setColor(Ui.TXT);
            c.drawText(nameFit, 0, nameFit.length(), pad, bottom - Ui.dpf(17), Ui.name);
            Ui.sub.setColor(Ui.TXT2);
            c.drawText("Sensor", pad, bottom - Ui.dpf(1), Ui.sub);
        } else {
            Ui.name.setColor(on ? Ui.TXT_ON : Ui.TXT);
            c.drawText(nameFit, 0, nameFit.length(), pad, bottom - Ui.dpf(17), Ui.name);
            Ui.sub.setColor(on ? Ui.TXT2_ON : Ui.TXT2);
            CharSequence s = TextUtils.ellipsize(st, Ui.sub, w - 2 * pad, TextUtils.TruncateAt.END);
            c.drawText(s, 0, s.length(), pad, bottom - Ui.dpf(1), Ui.sub);
        }
        c.restoreToCount(save);
    }
}
