package com.archie.casa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Commands from the root button daemon (device/service.d/98-smartclock-buttons.sh):
 * PLAY_PAUSE = one press of the top button.
 * Protected by android.permission.DUMP in the manifest: only shell/root can send it.
 */
public final class Remote extends BroadcastReceiver {
    static final String PLAY_PAUSE = "com.archie.casa.PLAY_PAUSE";

    @Override public void onReceive(Context c, Intent i) {
        if (PLAY_PAUSE.equals(i.getAction())) Renderer.get(c).togglePlay();
    }
}
