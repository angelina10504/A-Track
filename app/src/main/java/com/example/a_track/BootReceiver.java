// TEST: adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.example.a_track
package com.example.a_track;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.example.a_track.utils.SessionManager;
import com.example.a_track.workers.ServiceStartWorker;

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

        // Shutdown/reboot about to happen — session is kept alive for auto-resume
        if (Intent.ACTION_SHUTDOWN.equals(action) ||
                "android.intent.action.REBOOT".equals(action)) {
            Log.d(TAG, "Device shutting down - session preserved for auto-resume");
            return;
        }

        // BOOT_COMPLETED or QUICKBOOT_POWERON
        Log.d(TAG, "Boot action detected - checking session...");
        SessionManager sessionManager = new SessionManager(context);

        boolean loggedIn = sessionManager.isLoggedIn();
        String mobile = sessionManager.getMobileNumber();
        Log.d(TAG, "isLoggedIn() = " + loggedIn);
        Log.d(TAG, "mobileNumber = " + (mobile != null ? mobile : "null"));

        if (loggedIn) {
            Log.d(TAG, "Active session found - calling onDeviceReboot()");
            sessionManager.onDeviceReboot();
            Log.d(TAG, "onDeviceReboot() complete");

            // On Android 12+ startForegroundService() is blocked from BroadcastReceiver
            // in background. Delegate to WorkManager which runs in an allowed context.
            Log.d(TAG, "Enqueueing ServiceStartWorker via WorkManager...");
            try {
                OneTimeWorkRequest startWork =
                        new OneTimeWorkRequest.Builder(ServiceStartWorker.class).build();
                WorkManager.getInstance(context).enqueue(startWork);
                Log.d(TAG, "✓ ServiceStartWorker enqueued successfully - id: " + startWork.getId());
            } catch (Exception e) {
                Log.e(TAG, "✗ Failed to enqueue ServiceStartWorker: " + e.getMessage(), e);
            }
        } else {
            Log.d(TAG, "No active session - nothing to resume");
        }

        Log.d(TAG, "─── onReceive() complete ───");
    }
}
