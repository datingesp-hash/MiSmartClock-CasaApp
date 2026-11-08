package com.archie.casa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;

import java.io.File;

/**
 * Keeps the DLNA renderer alive while other apps (SmartTube) are in front.
 * Foreground with a silent, minimum-importance notification (the status bar is hidden anyway).
 */
public final class DlnaService extends Service implements Renderer.Listener {
    private Upnp upnp;
    private Presence presence;
    private WifiManager.MulticastLock mlock;
    private WifiManager.WifiLock wlock;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, DlnaService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        String name = receiverName(); // also applies "language" from config.json
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("receiver", Ui.t("Music receiver", "Receptor de música"), NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "receiver")
                .setSmallIcon(R.drawable.ic_cast_audio)
                .setContentTitle(name)
                .setContentText(Ui.t("Ready to receive music", "Listo para recibir música"))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(1, n);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mlock = wm.createMulticastLock("casa-ssdp");
        mlock.setReferenceCounted(false);
        mlock.acquire();
        wlock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "casa-stream");
        wlock.setReferenceCounted(false); // held only while music plays (WiFi power save off = heat)
        Renderer.get(this).addListener(this);

        upnp = new Upnp(this, Renderer.get(this), receiverName());
        upnp.start();

        presence = new Presence(this); // wake the screen when someone comes close
        presence.start();
    }

    @Override public void onRendererChanged() {
        String st = Renderer.get(this).state();
        boolean playing = Renderer.PLAYING.equals(st) || Renderer.TRANSITIONING.equals(st);
        if (playing && !wlock.isHeld()) wlock.acquire();
        else if (!playing && wlock.isHeld()) wlock.release();
    }

    private String receiverName() {
        try {
            return Config.load(new File(getFilesDir(), "config.json")).receiverName;
        } catch (Exception e) {
            return "Mi Smart Clock";
        }
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        if (upnp != null) upnp.stop();
        if (presence != null) presence.stop();
        Renderer.get(this).removeListener(this);
        Renderer.get(this).stop();
        if (mlock != null && mlock.isHeld()) mlock.release();
        if (wlock != null && wlock.isHeld()) wlock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
