package com.example.a_track.workers;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.a_track.service.LocationTrackingService;
import com.example.a_track.utils.SessionManager;

/**
 * One-time worker enqueued by BootReceiver after device reboot.
 *
 * WorkManager workers are allowed to call startForegroundService() because
 * they run in a proper background execution context, unlike a BroadcastReceiver
 * which is blocked from doing so on Android 12+.
 */
public class ServiceStartWorker extends Worker {

    private static final String TAG = "ServiceStartWorker";

    public ServiceStartWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "─── doWork() entered ───");
        Log.d(TAG, "Run attempt: " + getRunAttemptCount());

        Context context = getApplicationContext();
        SessionManager sessionManager = new SessionManager(context);

        boolean loggedIn = sessionManager.isLoggedIn();
        String mobile = sessionManager.getMobileNumber();
        Log.d(TAG, "isLoggedIn() = " + loggedIn);
        Log.d(TAG, "mobileNumber = " + (mobile != null ? mobile : "null"));

        if (!loggedIn) {
            Log.d(TAG, "No user logged in - skipping service start");
            return Result.success();
        }

        try {
            Log.d(TAG, "Calling startForegroundService() - API level: " + Build.VERSION.SDK_INT);
            Intent serviceIntent = new Intent(context, LocationTrackingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
                Log.d(TAG, "startForegroundService() called");
            } else {
                context.startService(serviceIntent);
                Log.d(TAG, "startService() called (pre-O)");
            }
            Log.d(TAG, "✓ LocationTrackingService start command issued successfully");
        } catch (Exception e) {
            Log.e(TAG, "✗ Exception starting LocationTrackingService: " + e.getMessage(), e);
            return Result.failure();
        }

        Log.d(TAG, "─── doWork() complete ───");
        return Result.success();
    }
}
