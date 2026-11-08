package com.archie.casa;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Shared palette (Apple-style dark), paints and state helpers. */
final class Ui {
    static float D;

    static final int CARD = 0xFF1C1C1E, CARD2 = 0xFF2C2C2E, CARD3 = 0xFF3A3A3C,
            TILE_ON = 0xFFF2F2F7, TILE_ON_PRESSED = 0xFFD8D8DE,
            TXT = 0xFFF5F5F7, TXT2 = 0xFF8E8E93, TXT_ON = 0xFF1C1C1E, TXT2_ON = 0xFF6E6E73,
            AMBER = 0xFFFFB340, ORANGE = 0xFFFF9F0A, BLUE = 0xFF0A84FF, INDIGO = 0xFF5E5CE6,
            RED = 0xFFFF453A, GREEN = 0xFF30D158, CYAN = 0xFF64D2FF, HEAT = 0xFFFF6B3D;

    /** UI language: the system locale, or "language" in config.json. Spanish or English (default). */
    static Locale LOC = Locale.getDefault();
    static boolean es() { return "es".equals(LOC.getLanguage()); }
    static String t(String en, String es) { return es() ? es : en; }
    static Typeface LIGHT, MED, REG;
    static final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    static final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    static final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    static void init(Context c) {
        D = c.getResources().getDisplayMetrics().density;
        LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
        MED = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        REG = Typeface.create("sans-serif", Typeface.NORMAL);
        name.setTypeface(MED);
        name.setTextSize(dpf(13.5f));
        sub.setTypeface(REG);
        sub.setTextSize(dpf(12));
        big.setTypeface(LIGHT);
        big.setTextSize(dpf(26));
    }

    static int dp(float v) { return Math.round(v * D); }
    static float dpf(float v) { return v * D; }

    static Drawable icon(Context c, String n) {
        int id = 0;
        if (n != null && !n.isEmpty())
            id = c.getResources().getIdentifier("ic_" + n.replace('-', '_'), "drawable", c.getPackageName());
        if (id == 0) id = R.drawable.ic_lightbulb;
        return c.getDrawable(id).mutate();
    }

    static String defaultIcon(String domain) {
        switch (domain) {
            case "media_player": return "television-play";
            case "fan": return "fan";
            case "switch": return "power";
            case "input_boolean": return "power";
            case "sensor": return "thermometer";
            default: return "lightbulb";
        }
    }

    static boolean avail(Ha.Entity e) {
        return e != null && !"unavailable".equals(e.state) && !"unknown".equals(e.state);
    }

    static boolean isOn(Config.Item it, Ha.Entity e) {
        if (e == null) return false;
        switch (it.domain) {
            case "sensor": return false;
            case "media_player":
                switch (e.state) {
                    case "playing": case "paused": case "on": case "idle": case "buffering": return true;
                    default: return false;
                }
            default: return "on".equals(e.state);
        }
    }

    static boolean dimmable(Ha.Entity e) {
        JSONArray m = e == null ? null : e.attrs.optJSONArray("supported_color_modes");
        if (m == null) return false;
        for (int i = 0; i < m.length(); i++) if (!"onoff".equals(m.optString(i))) return true;
        return false;
    }

    static int accent(Config.Item it, Ha.Entity e) {
        if (it.color != 0) return it.color;
        switch (it.domain) {
            case "light": {
                if (e != null) {
                    String cm = e.attrs.optString("color_mode", "");
                    JSONArray rgb = e.attrs.optJSONArray("rgb_color");
                    if (rgb != null && rgb.length() == 3 && (cm.equals("xy") || cm.equals("hs") || cm.startsWith("rgb"))) {
                        int r = rgb.optInt(0), g = rgb.optInt(1), b = rgb.optInt(2);
                        float[] hsv = new float[3];
                        Color.RGBToHSV(r, g, b, hsv);
                        if (hsv[1] > 0.25f) { // real colour; keep it vivid enough to read on white
                            hsv[1] = Math.max(hsv[1], 0.6f);
                            hsv[2] = Math.max(hsv[2], 0.85f);
                            return Color.HSVToColor(hsv);
                        }
                    }
                }
                return AMBER;
            }
            case "media_player": return BLUE;
            case "switch": return INDIGO;
            case "fan": return HEAT;
            case "input_boolean": return GREEN;
            case "sensor": {
                String u = e == null ? "" : e.attrs.optString("unit_of_measurement", "");
                return "%".equals(u) ? CYAN : ORANGE;
            }
            default: return AMBER;
        }
    }

    static String state(Config.Item it, Ha.Entity e) {
        if (!avail(e)) return t("Unavailable", "No disponible");
        JSONObject a = e.attrs;
        switch (it.domain) {
            case "sensor": return sensor(e);
            case "light":
                if (!"on".equals(e.state)) return t("Off", "Apagado");
                if (a.has("brightness") && !a.isNull("brightness"))
                    return Math.round(a.optInt("brightness") * 100 / 255f) + "%";
                return t("On", "Encendido");
            case "media_player":
                switch (e.state) {
                    case "playing": return t("Playing", "Reproduciendo");
                    case "paused": return t("Paused", "En pausa");
                    case "idle": return t("Idle", "Inactivo");
                    case "standby": return t("Standby", "En reposo");
                    case "buffering": return t("Buffering", "Cargando");
                    case "on": return t("On", "Encendido");
                    default: return t("Off", "Apagado");
                }
            default:
                return "on".equals(e.state) ? t("On", "Encendido") : t("Off", "Apagado");
        }
    }

    static String sensor(Ha.Entity e) {
        String u = e.attrs.optString("unit_of_measurement", "");
        try {
            double v = Double.parseDouble(e.state);
            if (u.startsWith("°")) return String.format(LOC, "%.1f°", v);
            if (u.equals("%")) return String.format(LOC, "%.0f%%", v);
            return String.format(LOC, "%.1f", v) + (u.isEmpty() ? "" : " " + u);
        } catch (NumberFormatException ex) {
            return e.state + (u.isEmpty() ? "" : " " + u);
        }
    }

    static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
