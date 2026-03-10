package com.example.a_track;

import android.app.AlarmManager;
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
import com.example.a_track.utils.SessionManager;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";
    private static final String CHANNEL_ID = "SafetyAlarmChannel_v2"; // v2: IMPORTANCE_MAX
    static final String ALARM_TIMEOUT_ACTION = "com.example.a_track.ALARM_TIMEOUT";
    private static final int TIMEOUT_REQUEST_CODE = 2;
    private static final long ALARM_TIMEOUT_MS = 30000;

    @Override
    public void onReceive(Context context, Intent intent) {

        String action = intent.getAction();

        // ✅ Alarm timed out without acknowledgment (30s elapsed, activity never launched)
        if (ALARM_TIMEOUT_ACTION.equals(action)) {
            Log.d(TAG, "⏰ Alarm timeout fired - stopping alarm and marking as missed");

            // Cancel notification (stops looping alarm sound)
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(999);

            // Stop vibration
            Vibrator vib = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null) vib.cancel();

            // Mark as missed via service (only if dialog didn't already handle it)
            Intent serviceIntent = new Intent(context, com.example.a_track.service.LocationTrackingService.class);
            serviceIntent.setAction("ALARM_DISMISSED");
            context.startForegroundService(serviceIntent);

            Log.d(TAG, "Alarm auto-stopped after 30s, marked as missed");
            return;
        }

        // ✅ Guard: do nothing if no user is logged in
        SessionManager sessionManager = new SessionManager(context);
        if (!sessionManager.isLoggedIn() && !"DISMISS_ALARM".equals(action)) {
            Log.d(TAG, "🔕 No user logged in - ignoring alarm");
            return;
        }

        // ✅ Handle notification dismissed by swipe
        if ("DISMISS_ALARM".equals(action)) {
            Log.d(TAG, "🔕 Notification dismissed by user - stopping vibration");

            // Cancel the scheduled timeout so it doesn't double-fire as missed
            cancelAlarmTimeout(context);

            // Stop any lingering vibration
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) vibrator.cancel();

            // Save as MISSED since user dismissed without pressing ALL OK
            Intent serviceIntent = new Intent(context, com.example.a_track.service.LocationTrackingService.class);
            serviceIntent.setAction("ALARM_DISMISSED");
            context.startForegroundService(serviceIntent);

            Log.d(TAG, "Vibration stopped, alarm marked as missed");
            return;
        }

        // ✅ Check if service is running, restart if needed
        if (!com.example.a_track.service.LocationTrackingService.isRunning) {
            Log.w(TAG, "⚠️ Service not running! Restarting...");
            Intent serviceIntent = new Intent(context, com.example.a_track.service.LocationTrackingService.class);
            context.startForegroundService(serviceIntent);
        }

        // ✅ Log alarm timing
        long now = System.currentTimeMillis();
        android.content.SharedPreferences prefs = context.getSharedPreferences("AlarmLogs", Context.MODE_PRIVATE);
        long lastAlarm = prefs.getLong("lastAlarmTime", 0);

        if (lastAlarm > 0) {
            long gapMinutes = (now - lastAlarm) / (60 * 1000);
            Log.d(TAG, "⏱️ Time since last alarm: " + gapMinutes + " minutes");

            if (gapMinutes > 30) {
                Log.e(TAG, "⚠️ ALARM GAP TOO LONG! Expected 15-25 min, got " + gapMinutes + " min");
            }
        }

        prefs.edit().putLong("lastAlarmTime", now).apply();

        Log.d(TAG, "🚨 Alarm received at: " + new java.util.Date());

        // Acquire wake lock
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ATrack::AlarmWakeLock"
        );
        wakeLock.acquire(30000);

        Log.d(TAG, "WakeLock acquired");

        long[] pattern = {0, 1000, 500};

        // Create notification channel
        createNotificationChannel(context);

        // Launch activity with full screen intent
        Intent dialogIntent = new Intent(context, AlarmDialogActivity.class);
        dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_NO_USER_ACTION);

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

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // On Android 15 (API 35+), canUseFullScreenIntent() tells us if the FSI will actually work.
        // If not approved by the system (app not categorized as alarm/calling), FSI is silently dropped.
        // We still set it so it works when approved; on non-approved devices it degrades gracefully
        // to a heads-up notification (sound + vibration still fire).
        boolean fullScreenAllowed = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) { // API 35
            fullScreenAllowed = notificationManager.canUseFullScreenIntent();
            if (!fullScreenAllowed) {
                Log.w(TAG, "⚠️ Full-screen intent not allowed on this device (Android 15+ restriction)");
            }
        }

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
                .setFullScreenIntent(pendingIntent, true) // works if allowed; degrades to heads-up if not
                .setDeleteIntent(dismissPendingIntent)
                .setOngoing(false);

        notificationManager.notify(999, builder.build());

        Log.d(TAG, "Notification shown, fullScreenAllowed=" + fullScreenAllowed);

        // Reset the flag-71 guard for this fresh alarm cycle
        context.getSharedPreferences("AlarmGuard", Context.MODE_PRIVATE)
                .edit().putBoolean("alarm_missed_saved", false).apply();

        // Schedule guaranteed 30s timeout — fires even if AlarmDialogActivity never launches
        // (Android 15 background restriction). Cancels notification & marks missed.
        scheduleAlarmTimeout(context);

        // Release wake lock after delay
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock released");
            }
        }, 30000);
    }

    /** Schedule a broadcast 30s from now that stops the alarm if not yet acknowledged. */
    static void scheduleAlarmTimeout(Context context) {
        Intent timeoutIntent = new Intent(context, AlarmReceiver.class);
        timeoutIntent.setAction(ALARM_TIMEOUT_ACTION);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, TIMEOUT_REQUEST_CODE, timeoutIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        long triggerAt = System.currentTimeMillis() + ALARM_TIMEOUT_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
        Log.d(TAG, "⏰ Alarm timeout scheduled in 30s");
    }

    /** Cancel the pending 30s timeout — called when the alarm is handled by the dialog activity. */
    static void cancelAlarmTimeout(Context context) {
        Intent timeoutIntent = new Intent(context, AlarmReceiver.class);
        timeoutIntent.setAction(ALARM_TIMEOUT_ACTION);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, TIMEOUT_REQUEST_CODE, timeoutIntent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (pi != null) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarmManager.cancel(pi);
            pi.cancel();
            Log.d(TAG, "⏰ Alarm timeout cancelled");
        }
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Safety Alarm",
                    NotificationManager.IMPORTANCE_MAX // bypasses DND, acts like system alarm
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