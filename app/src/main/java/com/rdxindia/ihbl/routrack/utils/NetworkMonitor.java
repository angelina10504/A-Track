package com.rdxindia.ihbl.routrack.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.rdxindia.ihbl.routrack.LoginActivity;
import com.rdxindia.ihbl.routrack.R;

import java.util.concurrent.atomic.AtomicBoolean;

public class NetworkMonitor {

    private static final String TAG = "NetworkMonitor";
    private static final String CHANNEL_ID = "VerificationChannel";
    private static final int NOTIF_ID = 999;

    private final Context context;
    private final SessionManager sessionManager;
    private final Runnable onStopRequested;
    private final ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final AtomicBoolean verificationInProgress = new AtomicBoolean(false);

    public NetworkMonitor(Context context, SessionManager sessionManager, Runnable onStopRequested) {
        this.context = context;
        this.sessionManager = sessionManager;
        this.onStopRequested = onStopRequested;
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void register() {
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handleNetworkAvailable();
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback);
            Log.d(TAG, "NetworkMonitor registered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register NetworkCallback: " + e.getMessage());
        }
    }

    public void unregister() {
        if (networkCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                Log.d(TAG, "NetworkMonitor unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister NetworkCallback: " + e.getMessage());
            }
            networkCallback = null;
        }
    }

    private void handleNetworkAvailable() {
        String status = sessionManager.getVerificationStatus();

        if (SessionManager.STATUS_PENDING.equals(status)) {
            if (!verificationInProgress.compareAndSet(false, true)) {
                Log.d(TAG, "Verification already in progress, skipping");
                return;
            }

            String mobile = sessionManager.getMobileNumber();
            if (mobile == null) {
                verificationInProgress.set(false);
                return;
            }

            String androidId = new DeviceInfoHelper(context).getImsiNo();
            Log.d(TAG, "Network available - attempting deferred verification for: " + mobile);

            ApiService.verifyActivation(mobile, androidId, false, new ApiService.VerificationCallback() {
                @Override
                public void onVerified() {
                    verificationInProgress.set(false);
                    sessionManager.setVerificationStatus(SessionManager.STATUS_VERIFIED);
                    Log.d(TAG, "User verified successfully - sync enabled");
                }

                @Override
                public void onRejected(String reason) {
                    verificationInProgress.set(false);
                    sessionManager.setVerificationStatus(SessionManager.STATUS_REJECTED);
                    showAccessRevokedNotification();
                    sessionManager.logout();

                    Intent intent = new Intent(context, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    context.startActivity(intent);

                    if (onStopRequested != null) onStopRequested.run();
                }

                @Override
                public void onNetworkError() {
                    verificationInProgress.set(false);
                    Log.w(TAG, "Network error during deferred verification - will retry on next connection");
                }
            });

        } else if (SessionManager.STATUS_REJECTED.equals(status)) {
            if (onStopRequested != null) onStopRequested.run();
        }
        // STATUS_VERIFIED: already verified, do nothing
    }

    private void showAccessRevokedNotification() {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Verification Alerts", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }

        Intent intent = new Intent(context, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Access Revoked")
                .setContentText("Please contact your administrator")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Access Revoked - Please contact your administrator"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        nm.notify(NOTIF_ID, builder.build());
    }
}
