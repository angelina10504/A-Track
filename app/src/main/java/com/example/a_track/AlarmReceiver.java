package com.example.a_track;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";
    private static final String CHANNEL_ID = "SafetyAlarmChannel";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "🚨 Alarm received at: " + new java.util.Date());
        // ✅ Handle notification dismissed by swipe
        if ("DISMISS_ALARM".equals(intent.getAction())) {
            Log.d(TAG, "🔕 Notification dismissed by user - stopping vibration");

            // Stop vibration
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            vibrator.cancel();

            // Save as MISSED since user dismissed without pressing ALL OK
            // Start a service to save the record
            Intent serviceIntent = new Intent(context, com.example.a_track.service.LocationTrackingService.class);
            serviceIntent.setAction("ALARM_DISMISSED");
            context.startService(serviceIntent);

            Log.d(TAG, "Vibration stopped, alarm marked as missed");
            return;
        }


        // Acquire wake lock
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ATrack::AlarmWakeLock"
        );
        wakeLock.acquire(30000);

        Log.d(TAG, "WakeLock acquired");

        // Start vibration
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        long[] pattern = {0, 1000, 500};
        vibrator.vibrate(pattern, 0);

        Log.d(TAG, "Vibration started");

        // Create notification channel
        createNotificationChannel(context);

        // Launch activity with full screen intent
        Intent dialogIntent = new Intent(context, AlarmDialogActivity.class);
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_NO_USER_ACTION);  // ✅ Added for Android 15

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                dialogIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        // Intent to stop vibration when notification is swiped away
        Intent dismissIntent = new Intent(context, AlarmReceiver.class);
        dismissIntent.setAction("DISMISS_ALARM");

        PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                1,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Get alarm sound
        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmSound == null) {
            alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }

        // Create high-priority notification with full screen intent
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("⚠️ Safety Check")
                .setContentText("All OK? Press this button")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setSound(alarmSound)
                .setVibrate(pattern)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)  // This ensures it works on Android 15
                .setDeleteIntent(dismissPendingIntent)
                .setOngoing(false);  // ✅ Not ongoing so it can be dismissed

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(999, builder.build());

        Log.d(TAG, "Notification shown with sound");

        // ✅ For Android 15: Check if we can start activity from background
        if (Build.VERSION.SDK_INT >= 35) {  // Android 15 (VANILLA_ICE_CREAM)
            // Android 15+ - rely on full screen intent notification
            Log.d(TAG, "Android 15+ detected - using full screen intent");
        } else {
            // Android 14 and below - directly start activity
            try {
                context.startActivity(dialogIntent);
                Log.d(TAG, "Activity started directly");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start activity: " + e.getMessage());
            }
        }

        // Release wake lock after delay
        new android.os.Handler().postDelayed(() -> {
            if (wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock released");
            }
        }, 30000);
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Safety Alarm",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Safety check alarms");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 1000, 500});

            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build();
            channel.setSound(alarmSound, attributes);

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
}