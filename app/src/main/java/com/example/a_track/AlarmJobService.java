package com.example.a_track;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.util.Log;

public class AlarmJobService extends JobService {
    private static final String TAG = "AlarmJobService";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "Job started - checking service and alarm");

        if (!com.example.a_track.service.LocationTrackingService.isRunning) {
            Log.w(TAG, "Service not running - restarting");
            Intent serviceIntent = new Intent(this, com.example.a_track.service.LocationTrackingService.class);
            startForegroundService(serviceIntent);
        }

        return false; // Job finished
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // Reschedule if stopped
    }
}