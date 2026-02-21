package com.example.a_track;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class AlarmJobService extends JobService {
    private static final String TAG = "AlarmJobService";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "Job started - checking service and alarm");

        // Check if service is running
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        boolean serviceRunning = false;

        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (com.example.a_track.service.LocationTrackingService.class.getName().equals(service.service.getClassName())) {
                serviceRunning = true;
                break;
            }
        }

        if (!serviceRunning) {
            Log.w(TAG, "Service not running - restarting");
            Intent serviceIntent = new Intent(this, com.example.a_track.service.LocationTrackingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }

        return false; // Job finished
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // Reschedule if stopped
    }
}