package com.rdxindia.ihbl.routrack.workers;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.rdxindia.ihbl.routrack.service.LocationTrackingService;
import com.rdxindia.ihbl.routrack.utils.SessionManager;

/**
 * Periodic watchdog worker — runs every 15 minutes.
 *
 * Uses ActivityManager to check if the service is actually alive in the OS
 * process list, rather than relying on a static in-memory flag that resets
 * on process death. On API 26+ getRunningServices() only returns services
 * belonging to the caller's own app, which is exactly what we need here.
 */
public class ServiceWatchdogWorker extends Worker {

    private static final String TAG = "ServiceWatchdogWorker";
    public static final String WORK_NAME = "ServiceWatchdog";

    public ServiceWatchdogWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        SessionManager sessionManager = new SessionManager(context);

        if (!sessionManager.isLoggedIn()) {
            Log.d(TAG, "No user logged in - watchdog idle");
            return Result.success();
        }

        if (!isServiceRunning(context)) {
            Log.w(TAG, "⚠️ Service not running - restarting via watchdog");
            Intent serviceIntent = new Intent(context, LocationTrackingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d(TAG, "✓ Service restart triggered by watchdog");
        } else {
            Log.d(TAG, "✓ Watchdog check: service is alive");
        }

        return Result.success();
    }

    /**
     * Checks the OS process list for our ForegroundService.
     * getRunningServices() is deprecated for third-party inspection but still
     * works correctly when checking your own app's services (API 26+ restriction
     * only blocks seeing other apps' services).
     */
    private boolean isServiceRunning(Context context) {
        ActivityManager manager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;

        for (ActivityManager.RunningServiceInfo info :
                manager.getRunningServices(Integer.MAX_VALUE)) {
            if (LocationTrackingService.class.getName().equals(info.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
