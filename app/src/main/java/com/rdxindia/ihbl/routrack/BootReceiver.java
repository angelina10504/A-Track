// TEST: adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.rdxindia.ihbl.routrack
package com.rdxindia.ihbl.routrack;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.rdxindia.ihbl.routrack.database.AppDatabase;
import com.rdxindia.ihbl.routrack.utils.SessionManager;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "─── onReceive() entered ───");

        String action = intent.getAction();
        Log.d(TAG, "Action received: " + action);
        Log.d(TAG, "Android API level: " + Build.VERSION.SDK_INT);

        if (action == null) {
            Log.w(TAG, "Action is null - ignoring");
            return;
        }

        // Shutdown/reboot about to happen — nothing to do here
        if (Intent.ACTION_SHUTDOWN.equals(action) ||
                "android.intent.action.REBOOT".equals(action)) {
            Log.d(TAG, "Device shutting down - no action needed");
            return;
        }

        // BOOT_COMPLETED or QUICKBOOT_POWERON — clear session so user must login again
        Log.d(TAG, "Boot action detected - clearing session...");
        SessionManager sessionManager = new SessionManager(context);

        boolean loggedIn = sessionManager.isLoggedIn();
        Log.d(TAG, "isLoggedIn() = " + loggedIn);

        if (loggedIn) {
            if (!sessionManager.isSessionPreReboot()) {
                // Session was already refreshed post-reboot by LoginActivity — do not touch it
                Log.d(TAG, "Session is already post-reboot (user already logged in) - skipping");
            } else {
                int sessionDbId = sessionManager.getSessionDbId();
                long logoutTime = sessionManager.getTrueTimeMs();

                // Clear pre-reboot session — user must login again
                sessionManager.logout();
                // Mark that the next login follows a reboot (so first location gets datatype=REBOOT)
                sessionManager.setRebootedFlag();
                Log.d(TAG, "Pre-reboot session cleared - user must login again");

                // Record the logout time in DB on a background thread
                if (sessionDbId != -1) {
                    final PendingResult pendingResult = goAsync();
                    AppDatabase db = AppDatabase.getInstance(context);
                    new Thread(() -> {
                        try {
                            db.sessionDao().updateLogoutTime(sessionDbId, logoutTime);
                            Log.d(TAG, "Session logout time recorded in DB for id: " + sessionDbId);
                        } finally {
                            pendingResult.finish();
                        }
                    }).start();
                    return; // pendingResult.finish() signals completion from the thread
                }
            }
        } else {
            Log.d(TAG, "No active session - nothing to clear");
        }

        Log.d(TAG, "─── onReceive() complete ───");
    }
}
