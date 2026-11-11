package com.archie.casa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.FileInputStream;

/**
 * Presence with the clock's IR proximity sensor (EM3071X). Android only reports "near" at the driver's high
 * threshold (96, a few cm), so we also poll the raw value (about 1 with nothing in front) every POLL ms
 * and wake the screen as soon as it rises: a hand in front of the clock turns it on in ~0.1 s.
 * The Android listener stays registered: it keeps the chip powered so the raw value keeps updating.
 *
 * After the screen is turned off by hand while a hand is still in front, presence is ignored until
 * nothing has been seen for CLEAR ms (otherwise it would turn straight back on).
 */
final class Presence implements SensorEventListener {
    private static final String TAG = "CasaPresence";
    private static final String RAW = "/sys/bus/platform/drivers/als_ps/ps";
    private static final int TH = 5;
    private static final long POLL = 200, POKE = 10000, CLEAR = 20000;

    private final Context c;
    private final SensorManager sm;
    private final Sensor prox;
    private final PowerManager pm;
    private HandlerThread thread;
    private Handler h;
    private long lastSeen, lastPoke;
    private boolean muted;

    Presence(Context c) {
        this.c = c;
        sm = c.getSystemService(SensorManager.class);
        pm = c.getSystemService(PowerManager.class);
        prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY, true);
    }

    void start() {
        if (prox != null) sm.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL);
        c.registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        thread = new HandlerThread("presence");
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(poll);
    }

    void stop() {
        sm.unregisterListener(this);
        try { c.unregisterReceiver(screenOff); } catch (Exception ignored) { }
        if (thread != null) thread.quitSafely();
    }

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            // the timeout never fires while someone is there (we keep poking), so this was by hand
            if (SystemClock.uptimeMillis() - lastSeen < 2 * POLL + 300) muted = true;
        }
    };

    private final Runnable poll = new Runnable() {
        private final byte[] buf = new byte[16];

        @Override public void run() {
            int v = read();
            if (v < 0) return; // not readable (SELinux): only the Android "near" event remains
            long now = SystemClock.uptimeMillis();
            if (v >= TH) {
                lastSeen = now;
                if (!muted && (!pm.isInteractive() || now - lastPoke >= POKE)) {
                    lastPoke = now;
                    wake(c);
                    Runnable p = onPresence;
                    if (p != null) p.run();
                }
            } else if (muted && now - lastSeen >= CLEAR) {
                muted = false;
            }
            h.postDelayed(this, POLL);
        }

        private int read() {
            try (FileInputStream in = new FileInputStream(RAW)) {
                int n = in.read(buf);
                String s = new String(buf, 0, Math.max(0, n)).trim(); // "0x0001"
                return Integer.parseInt(s.startsWith("0x") ? s.substring(2) : s, 16);
            } catch (Exception e) {
                Log.w(TAG, "raw proximity not readable: " + e);
                return -1;
            }
        }
    };

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.values[0] < prox.getMaximumRange() && !muted) wake(c);
    }

    /** Someone is in front of the clock (used by the lyrics' own dimming). Called off the main thread. */
    static volatile Runnable onPresence;

    /** Turn the screen on if it is off, and restart the screen-off timeout if it is on. */
    @SuppressWarnings("deprecation") // ACQUIRE_CAUSES_WAKEUP still works for apps targeting 33
    static void wake(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "casa:presence");
        wl.acquire(500); // ON_AFTER_RELEASE: the release counts as user activity
    }

    private static PowerManager.WakeLock dim;

    /**
     * Room light on (from Home Assistant): keep the screen on, dimmed after the timeout, so the clock can be
     * read at a glance; turn it on if it was off. Light off: let it go off after the normal timeout.
     * (The ambient light sensor is useless here: it reads 1-2 lux with the light on.)
     */
    @SuppressWarnings("deprecation")
    static void setRoomLit(Context c, boolean lit) {
        if (dim == null) {
            dim = c.getSystemService(PowerManager.class).newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "casa:roomlit");
            dim.setReferenceCounted(false);
        }
        if (lit && !dim.isHeld()) dim.acquire();
        else if (!lit && dim.isHeld()) dim.release();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }
}
