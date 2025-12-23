package com.example.a_track;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.example.a_track.database.AppDatabase;
import com.example.a_track.service.LocationTrackingService;
import com.example.a_track.utils.SessionManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (action == null) return;

        Log.d(TAG, "Received action: " + action);

        SessionManager sessionManager = new SessionManager(context);

        // Check if user was logged in
        if (sessionManager.isLoggedIn()) {
            Log.d(TAG, "Active session found - terminating session");

            int sessionDbId = sessionManager.getSessionDbId();

            // Stop location tracking service if running
            try {
                Intent serviceIntent = new Intent(context, LocationTrackingService.class);
                context.stopService(serviceIntent);
                Log.d(TAG, "Location tracking service stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping service: " + e.getMessage());
            }

            // Update logout time in database
            if (sessionDbId != -1) {
                AppDatabase db = AppDatabase.getInstance(context);
                ExecutorService executor = Executors.newSingleThreadExecutor();

                executor.execute(() -> {
                    try {
                        db.sessionDao().updateLogoutTime(sessionDbId, System.currentTimeMillis());
                        Log.d(TAG, "Session terminated - logout time updated for session ID: " + sessionDbId);
                    } catch (Exception e) {
                        Log.e(TAG, "Error updating session: " + e.getMessage());
                    } finally {
                        executor.shutdown();
                    }
                });
            }

            // Clear session - user must login again
            sessionManager.logout();
            Log.d(TAG, "User logged out - credentials required on next app launch");
        } else {
            Log.d(TAG, "No active session found");
        }
    }
}