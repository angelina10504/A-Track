package com.rdxindia.ihbl.routrack.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.rdxindia.ihbl.routrack.utils.ApiService;
import com.rdxindia.ihbl.routrack.utils.DeviceInfoHelper;
import com.rdxindia.ihbl.routrack.utils.SessionManager;

import java.util.Map;

/**
 * Receives Firebase Cloud Messaging DATA messages from the server.
 *
 * Purpose: revive {@link LocationTrackingService} when the OS has killed it. The server
 * notices a device has gone quiet and pushes {"action":"restart_tracking"}; a
 * HIGH-PRIORITY data message gives the app a short exemption to start a foreground
 * service from the background (Android 12+), which a normal-priority one does not.
 *
 * Shows no notification of its own — the tracking service already has one.
 */
public class RouTrackMessagingService extends FirebaseMessagingService {

    private static final String TAG = "RouTrackMessaging";

    private static final String ACTION_RESTART_TRACKING = "restart_tracking";
    private static final String ACTION_PING = "ping";

    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "onNewToken: new FCM token issued");
        SessionManager sessionManager = new SessionManager(this);
        sessionManager.saveFcmToken(token);
        // A new token has never been seen by the server. Mark it unsynced BEFORE trying
        // to upload, so a failed upload is retried on the next service start.
        sessionManager.setFcmTokenSynced(false);
        syncTokenIfNeeded(getApplicationContext());
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        // DATA payload only — notification payloads are ignored on purpose.
        Map<String, String> data = message.getData();
        String action = data.get("action");
        Log.d(TAG, "onMessageReceived: action=" + action
                + " priority=" + message.getPriority()
                + " (original=" + message.getOriginalPriority() + ")");

        if (ACTION_PING.equals(action)) {
            Log.d(TAG, "Ping received — no action taken");
            return;
        }

        if (!ACTION_RESTART_TRACKING.equals(action)) {
            Log.d(TAG, "Unknown or missing action — ignored");
            return;
        }

        SessionManager sessionManager = new SessionManager(this);
        if (!sessionManager.isLoggedIn()) {
            Log.d(TAG, "restart_tracking ignored — no user logged in");
            return;
        }
        if (SessionManager.STATUS_REJECTED.equals(sessionManager.getVerificationStatus())) {
            Log.d(TAG, "restart_tracking ignored — verification status is REJECTED");
            return;
        }

        if (!LocationTrackingService.canStartFromBackground(this)) {
            Log.w(TAG, "restart_tracking ignored — location is not 'Allow all the time',"
                    + " so Android would refuse a background start");
            return;
        }

        try {
            boolean wasRunning = LocationTrackingService.isRunning;
            // Read BEFORE starting: the restarted service will overwrite it on its first save.
            long lastRecordTime = sessionManager.getLastTrackedTime();

            Intent serviceIntent = new Intent(this, LocationTrackingService.class);
            startForegroundService(serviceIntent);
            Log.d(TAG, "✓ restart_tracking: startForegroundService() issued (service was "
                    + (wasRunning ? "already running" : "not running") + ")");

            if (!wasRunning) {
                // A real revive. Leave a note for the service, which writes it to the
                // server as an FCM_REVIVE (101) record on its first GPS fix.
                getSharedPreferences(LocationTrackingService.PREFS_FCM_REVIVE, MODE_PRIVATE).edit()
                        .putLong("last_record_time", lastRecordTime)
                        .putLong("revived_at", System.currentTimeMillis())
                        .apply();
            }
        } catch (Exception e) {
            // e.g. ForegroundServiceStartNotAllowedException when the message was
            // delivered at normal priority and the app is in the background.
            Log.e(TAG, "✗ restart_tracking: could not start service: " + e, e);
        }
    }

    /**
     * Uploads this install's FCM token to the server if a user is logged in and the
     * current token has not been accepted yet. Safe to call repeatedly and from any
     * thread: it does nothing once the token is synced. On failure the synced flag
     * stays false, so the next call (next login or next service start) retries.
     */
    public static void syncTokenIfNeeded(Context context) {
        final Context appContext = context.getApplicationContext();
        final SessionManager sessionManager = new SessionManager(appContext);

        if (!sessionManager.isLoggedIn()) {
            Log.d(TAG, "FCM token sync skipped — no user logged in");
            return;
        }
        if (sessionManager.isFcmTokenSynced()) {
            return;
        }

        String savedToken = sessionManager.getFcmToken();
        if (savedToken != null && !savedToken.isEmpty()) {
            uploadToken(appContext, savedToken);
            return;
        }

        // No token stored yet (e.g. first run after updating to a Firebase build).
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) {
                    Log.w(TAG, "FCM getToken() failed: " + task.getException());
                    return;
                }
                sessionManager.saveFcmToken(task.getResult());
                uploadToken(appContext, task.getResult());
            });
        } catch (Exception e) {
            Log.e(TAG, "FCM getToken() threw: " + e.getMessage());
        }
    }

    private static void uploadToken(Context appContext, String token) {
        final SessionManager sessionManager = new SessionManager(appContext);
        final String mobile = sessionManager.getMobileNumber();
        if (mobile == null) return;

        String androidId = new DeviceInfoHelper(appContext).getImsiNo();
        ApiService.registerFcmToken(mobile, androidId, token, new ApiService.FcmTokenCallback() {
            @Override
            public void onSuccess() {
                // Only mark synced if this is still the current token and the same user.
                if (token.equals(sessionManager.getFcmToken())
                        && mobile.equals(sessionManager.getMobileNumber())) {
                    sessionManager.setFcmTokenSynced(true);
                    Log.d(TAG, "✓ FCM token registered with server for " + mobile);
                }
            }

            @Override
            public void onFailure(String error) {
                Log.w(TAG, "FCM token registration failed (will retry on next service start): " + error);
            }
        });
    }
}
