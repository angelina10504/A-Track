package com.rdxindia.ihbl.routrack;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Safety-alarm receiver — NEUTRALISED.
 *
 * The periodic pop-up "safety check" alarm (sound + vibration + full-screen dialog every
 * 15–25 min) was removed to improve battery life and stop interrupting field users.
 * Device health is now logged SILENTLY to the server from
 * {@code LocationTrackingService.logHealthIfProblem()} — a record is sent only when
 * something is actually wrong, with no notification of any kind.
 *
 * This class is kept (rather than deleted) so the manifest entry stays valid and so any
 * STALE alarm left scheduled by an older installed build fires harmlessly here. The old
 * implementation called {@code startForegroundService()} from this background receiver,
 * which crashed on Android 12+ with ForegroundServiceDidNotStartInTimeException
 * (AlarmReceiver.java:47) — that whole path is now gone.
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // No-op: alarms/alerts have been removed. Do nothing (no notification, no service start).
        Log.d(TAG, "AlarmReceiver fired but alerts are disabled — ignoring.");
    }
}
