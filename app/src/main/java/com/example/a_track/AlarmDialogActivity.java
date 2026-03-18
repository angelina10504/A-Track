package com.example.a_track;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import androidx.core.app.NotificationManagerCompat;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Vibrator;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.a_track.database.AppDatabase;
import com.example.a_track.database.LocationTrack;
import com.example.a_track.utils.DeviceInfoHelper;
import com.example.a_track.utils.SessionManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.app.KeyguardManager;
import android.graphics.Color;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.LinearLayout;
public class AlarmDialogActivity extends AppCompatActivity {

    private static final String TAG = "AlarmDialogActivity";
    private static final long ALARM_DURATION = 30000; // 30 seconds

    private TextView tvMessage;
    private TextView tvTimer;
    private Button btnAllOk;

    // Health check views
    private LinearLayout layoutDialogCard;
    private TextView tvLocationStatus;
    private TextView tvBgLocStatus;
    private TextView tvInternetStatus;
    private TextView tvQCount;
    private TextView tvBatteryOptimized;
    private TextView tvPlayProtect;
    private TextView tvBkgndUsage;
    private TextView tvNotifications;

    // Health status flags — set in loadSystemHealth(), read by applyConditionalUi()
    // and getHealthStatusString()
    private boolean healthLocationOk      = true;
    private boolean healthBgLocOk         = true;
    private boolean healthInternetOk      = true;
    private boolean healthBatIssue        = false;
    private boolean healthPlayProtectOn   = false;
    private boolean healthBkgndRestricted = false;
    private boolean healthNotificationsOff = false;
    private int     healthQCount          = 0;

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private CountDownTimer countDownTimer;
    private long startTime;
    private boolean responded = false;
    private boolean healthAlertSaved = false; // guard: save flag 100 only once per alarm

    private AppDatabase db;
    private SessionManager sessionManager;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // No-op: removed the erroneous finish() on recreation.
        // Activity recreated on rotation should continue normally.

        // ✅ Modern way to handle lock screen for Android 8.0+ (API 27+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);

            // For Android 15+, also request to dismiss keyguard
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                KeyguardManager keyguardManager =
                        (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                keyguardManager.requestDismissKeyguard(this, null);
            }
        } else {
            // Fallback for older Android versions
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }

        setContentView(R.layout.activity_alarm_dialog);

        db = AppDatabase.getInstance(this);
        sessionManager = new SessionManager(this);

        initViews();
        loadSystemHealth();
        startAlarm();
        startCountdown();
    }

    private void initViews() {
        tvMessage = findViewById(R.id.tvAlarmMessage);
        tvTimer = findViewById(R.id.tvAlarmTimer);
        btnAllOk = findViewById(R.id.btnAllOk);

        layoutDialogCard = findViewById(R.id.layoutDialogCard);
        tvLocationStatus = findViewById(R.id.tvLocationStatus);
        tvBgLocStatus    = findViewById(R.id.tvBgLocStatus);
        tvInternetStatus = findViewById(R.id.tvInternetStatus);
        tvQCount = findViewById(R.id.tvQCount);
        tvBatteryOptimized = findViewById(R.id.tvBatteryOptimized);
        tvPlayProtect = findViewById(R.id.tvPlayProtect);
        tvBkgndUsage = findViewById(R.id.tvBkgndUsage);
        tvNotifications = findViewById(R.id.tvNotifications);

        btnAllOk.setOnClickListener(v -> handleResponse());
    }

    private void loadSystemHealth() {
        DeviceInfoHelper deviceInfo = new DeviceInfoHelper(this);

        // --- Location (GPS hardware on/off) ---
        healthLocationOk = "1".equals(deviceInfo.getGpsState());
        tvLocationStatus.setText(healthLocationOk ? "Available" : "NA");

        // --- BG Location ("All the time" background permission) ---
        healthBgLocOk = DeviceInfoHelper.hasAllTheTimeLocationPermission(this);
        tvBgLocStatus.setText(healthBgLocOk ? "Allowed" : "NA");

        // --- Internet ---
        healthInternetOk = "1".equals(deviceInfo.getInternetState());
        tvInternetStatus.setText(healthInternetOk ? "Available" : "NA");

        // --- Battery Optimization (API 23+) ---
        // isIgnoringBatteryOptimizations() == true  → app is EXEMPTED (good)
        // isIgnoringBatteryOptimizations() == false → app IS optimised (problem)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                healthBatIssue = !pm.isIgnoringBatteryOptimizations(getPackageName());
                tvBatteryOptimized.setText(healthBatIssue ? "Yes" : "No");
            } else {
                tvBatteryOptimized.setText("N/A");
            }
        } else {
            tvBatteryOptimized.setText("N/A");
        }

        // --- Play Protect (package verifier) ---
        // package_verifier_user_consent: 1 = enabled, -1 = user disabled
        try {
            int verifyEnabled = Settings.Global.getInt(
                    getContentResolver(), "package_verifier_enable", 1);
            int userConsent = Settings.Global.getInt(
                    getContentResolver(), "package_verifier_user_consent", 1);
            healthPlayProtectOn = (verifyEnabled == 1) && (userConsent != -1);
            tvPlayProtect.setText(healthPlayProtectOn ? "On" : "Off");
        } catch (Exception e) {
            tvPlayProtect.setText("N/A");
        }

        // --- Background Data Usage (API 24+) ---
        // RESTRICT_BACKGROUND_STATUS_ENABLED → app blocked from background data
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                healthBkgndRestricted = (cm.getRestrictBackgroundStatus()
                        == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED);
            }
        }
        tvBkgndUsage.setText(healthBkgndRestricted ? "Not Allowed" : "Allowed");

        // --- Notifications ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            healthNotificationsOff = (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED);
        } else {
            healthNotificationsOff = !NotificationManagerCompat.from(this).areNotificationsEnabled();
        }
        tvNotifications.setText(healthNotificationsOff ? "Off" : "On");

        // --- Q Count: must run off the main thread ---
        // applyConditionalUi() is called once the count is known so all 7 checks are evaluated
        String mobileNumber = sessionManager.getMobileNumber();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            int count = 0;
            try {
                if (mobileNumber != null) {
                    count = db.locationTrackDao().getUnsyncedCount(mobileNumber);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching unsynced count: " + e.getMessage());
            }
            final int finalCount = count;
            healthQCount = finalCount;
            runOnUiThread(() -> {
                tvQCount.setText(String.valueOf(finalCount));
                applyConditionalUi();
            });
        });
    }

    /**
     * Applies background colour and button text based on all 7 health conditions.
     * Must be called on the UI thread after Q count is known.
     */
    private void applyConditionalUi() {
        boolean anyIssue = !healthLocationOk
                || !healthBgLocOk
                || !healthInternetOk
                || healthQCount > 30
                || healthBatIssue
                || healthPlayProtectOn
                || healthBkgndRestricted
                || healthNotificationsOff;

        if (anyIssue) {
            layoutDialogCard.setBackgroundColor(Color.parseColor("#FFCDD2")); // Material Red 100
            btnAllOk.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.RED));
            btnAllOk.setText("Report to Office");

            // Fire flag 100 automatically — irrespective of whether user presses RTO or misses it
            if (!healthAlertSaved) {
                healthAlertSaved = true;
                saveHealthAlertRecord();
            }
        }
        // All-good state: default white background and "All OK" text are set in XML — no change needed
    }

    /**
     * Saves a HEALTH_ALERT (flag 100) record immediately when any health parameter is disturbed.
     * Fires automatically — does not wait for user to press RTO or miss the alarm.
     * textMsg includes all parameters so the server sees exactly what triggered it.
     */
    private void saveHealthAlertRecord() {
        String mobileNumber = sessionManager.getMobileNumber();
        String sessionId = sessionManager.getSessionId();

        if (mobileNumber == null || sessionId == null) {
            Log.e(TAG, "Cannot save health alert: Session not found");
            return;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                DeviceInfoHelper deviceInfo = new DeviceInfoHelper(this);
                long currentTime = System.currentTimeMillis();

                LocationTrack lastTrack = db.locationTrackDao().getLastLocationSync(mobileNumber);
                double lat = 0.0, lng = 0.0;
                float speed = 0.0f, angle = 0.0f;
                if (lastTrack != null) {
                    lat   = lastTrack.getLatitude();
                    lng   = lastTrack.getLongitude();
                    speed = lastTrack.getSpeed();
                    angle = lastTrack.getAngle();
                }

                int battery = getBatteryLevel();
                int lastRecNo = db.locationTrackDao().getLastRecNo(mobileNumber);
                int nextRecNo = lastRecNo + 1;

                String textMsg = "HEALTH_ALERT | " + getHealthStatusString();

                LocationTrack track = new LocationTrack(
                        mobileNumber,
                        lat,
                        lng,
                        speed,
                        angle,
                        currentTime,
                        sessionId,
                        battery,
                        null,
                        null,
                        textMsg,
                        deviceInfo.getGpsState(),
                        deviceInfo.getInternetState(),
                        deviceInfo.getFlightState(),
                        deviceInfo.getRoamingState(),
                        deviceInfo.getIsNetThere(),
                        deviceInfo.getIsNwThere(),
                        deviceInfo.getIsMoving(speed),
                        deviceInfo.getModelNo(),
                        deviceInfo.getModelOS(),
                        deviceInfo.getApkName(),
                        deviceInfo.getImsiNo(),
                        deviceInfo.getMobileTime(),
                        deviceInfo.getNetworkSignalStrength(),
                        nextRecNo,
                        DataTypes.HEALTH_ALERT
                );

                db.locationTrackDao().insert(track);

                // Persist so handleResponse, handleTimeout, and the service's
                // saveAlarmDismissed all see that flag 100 was already saved this cycle
                getSharedPreferences("AlarmGuard", MODE_PRIVATE)
                        .edit().putBoolean("health_alert_saved", true).apply();

                Log.d(TAG, "✓ Health alert record saved: " + textMsg);

            } catch (Exception e) {
                Log.e(TAG, "Error saving health alert record: " + e.getMessage());
            }
        });
    }

    /**
     * Returns the standard health-status string using flags already computed in loadSystemHealth().
     * Format (fixed order, matches getDeviceHealthString() in the service):
     *   Loc:[Ok|NA], Net:[Ok|NA], Q:[n], BatOpt:[Yes|No], PlayPro:[On|Off], BkUsg:[Allowed|NA], Notif:[On|Off]
     */
    private String getHealthStatusString() {
        return "Loc:" + (healthLocationOk ? "Ok" : "NA")
                + ", BgLoc:" + (healthBgLocOk ? "Ok" : "NA")
                + ", Net:" + (healthInternetOk ? "Ok" : "NA")
                + ", Q:" + healthQCount
                + ", BatOpt:" + (healthBatIssue ? "Yes" : "No")
                + ", PlayPro:" + (healthPlayProtectOn ? "On" : "Off")
                + ", BkUsg:" + (healthBkgndRestricted ? "NA" : "Allowed")
                + ", Notif:" + (healthNotificationsOff ? "Off" : "On");
    }

    private void startAlarm() {
        startTime = System.currentTimeMillis();

        // Start sound
        try {
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            mediaPlayer = MediaPlayer.create(this, alarmUri);
            mediaPlayer.setLooping(true);
            mediaPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start alarm sound: " + e.getMessage());
        }

        // Start vibration
        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            long[] pattern = {0, 1000, 500}; // 0ms delay, 1000ms vibrate, 500ms pause
            vibrator.vibrate(pattern, 0); // Repeat from index 0
        } catch (Exception e) {
            Log.e(TAG, "Failed to start vibration: " + e.getMessage());
        }
    }

    private void startCountdown() {
        countDownTimer = new CountDownTimer(ALARM_DURATION, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) (millisUntilFinished / 1000);
                tvTimer.setText(secondsLeft + " seconds");
            }

            @Override
            public void onFinish() {
                if (!responded) {
                    handleTimeout();
                }
            }
        }.start();
    }

    private void handleResponse() {
        if (responded) return;  // ✅ Guard against double execution
        responded = true;

        long responseTime = (System.currentTimeMillis() - startTime) / 1000;

        // Cancel the 30s safety-net timeout so it doesn't double-fire as missed
        AlarmReceiver.cancelAlarmTimeout(this);

        // Block service from saving 71 if ALARM_DISMISSED races in after this point
        getSharedPreferences("AlarmGuard", MODE_PRIVATE)
                .edit().putBoolean("alarm_missed_saved", true).apply();

        stopAlarm();

        // Dismiss notification
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancel(999);

        // If flag 100 was already saved this cycle, do not also save flag 70
        if (getSharedPreferences("AlarmGuard", MODE_PRIVATE)
                .getBoolean("health_alert_saved", false)) {
            Log.d(TAG, "Health alert already saved this cycle — skipping ALARM_ACK (70)");
        } else {
            saveAlarmRecord(DataTypes.ALARM_ACK, (int) responseTime);
            Toast.makeText(this, "Response recorded: " + responseTime + "s", Toast.LENGTH_SHORT).show();
        }

        rescheduleNextAlarm();
        finish();
    }

    private void handleTimeout() {
        // Cancel the 30s safety-net timeout so it doesn't double-fire as missed
        AlarmReceiver.cancelAlarmTimeout(this);

        stopAlarm();

        android.content.SharedPreferences prefs = getSharedPreferences("AlarmGuard", MODE_PRIVATE);
        if (prefs.getBoolean("health_alert_saved", false)) {
            // Flag 100 already saved this cycle — do not also save flag 71
            Log.d(TAG, "Health alert already saved this cycle — skipping ALARM_MISSED (71)");
        } else if (!prefs.getBoolean("alarm_missed_saved", false)) {
            prefs.edit().putBoolean("alarm_missed_saved", true).apply();
            saveAlarmRecord(DataTypes.ALARM_MISSED, 30);
        } else {
            Log.d(TAG, "⚠️ Flag 71 already saved for this alarm cycle - skipping duplicate");
        }

        rescheduleNextAlarm();
        // ✅ Dismiss notification
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancel(999);
        finish();
    }

    private void stopAlarm() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }

        if (vibrator != null) {
            vibrator.cancel();
        }

        // ✅ Dismiss the notification
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancel(999); // Same ID used in AlarmReceiver
    }

    private void saveAlarmRecord(int datatype, int responseTime) {
        String mobileNumber = sessionManager.getMobileNumber();
        String sessionId = sessionManager.getSessionId();

        if (mobileNumber == null || sessionId == null) {
            Log.e(TAG, "Cannot save alarm: Session not found");
            return;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                DeviceInfoHelper deviceInfo = new DeviceInfoHelper(this);
                long currentTime = System.currentTimeMillis();

                // ✅ Get last location from database instead of 0,0
                LocationTrack lastTrack = db.locationTrackDao().getLastLocationSync(mobileNumber);

                double lat = 0.0;
                double lng = 0.0;
                float speed = 0.0f;
                float angle = 0.0f;

                if (lastTrack != null) {
                    lat = lastTrack.getLatitude();
                    lng = lastTrack.getLongitude();
                    speed = lastTrack.getSpeed();
                    angle = lastTrack.getAngle();
                    Log.d(TAG, "Using last known location: " + lat + ", " + lng);
                } else {
                    Log.w(TAG, "No previous location found, using 0,0");
                }

                int battery = getBatteryLevel();
                int lastRecNo = db.locationTrackDao().getLastRecNo(mobileNumber);
                int nextRecNo = lastRecNo + 1;

                String textMsg = (datatype == DataTypes.ALARM_ACK) ?
                        "ALARM_ACK:" + responseTime :
                        "ALARM_MISS:" + responseTime;
                textMsg += " | " + getHealthStatusString();

                LocationTrack track = new LocationTrack(
                        mobileNumber,
                        lat,
                        lng,
                        speed,
                        angle,
                        currentTime,
                        sessionId,
                        battery,
                        null,
                        null,
                        textMsg,
                        deviceInfo.getGpsState(),
                        deviceInfo.getInternetState(),
                        deviceInfo.getFlightState(),
                        deviceInfo.getRoamingState(),
                        deviceInfo.getIsNetThere(),
                        deviceInfo.getIsNwThere(),
                        deviceInfo.getIsMoving(speed),
                        deviceInfo.getModelNo(),
                        deviceInfo.getModelOS(),
                        deviceInfo.getApkName(),
                        deviceInfo.getImsiNo(),
                        deviceInfo.getMobileTime(),
                        deviceInfo.getNetworkSignalStrength(),
                        nextRecNo,
                        datatype
                );

                db.locationTrackDao().insert(track);

                Log.d(TAG, "✓ Alarm record saved: datatype=" + datatype +
                        ", responseTime=" + responseTime + "s, location=" + lat + "," + lng);

            } catch (Exception e) {
                Log.e(TAG, "Error saving alarm record: " + e.getMessage());
            }
        });
    }

    private int getBatteryLevel() {
        try {
            android.content.IntentFilter ifilter =
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent batteryStatus = registerReceiver(null, ifilter);

            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(
                        android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(
                        android.os.BatteryManager.EXTRA_SCALE, -1);
                return (int) ((level / (float) scale) * 100);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting battery: " + e.getMessage());
        }
        return 0;
    }
    private void rescheduleNextAlarm() {
        // Notify the service to schedule next alarm
        Intent serviceIntent = new Intent(this, com.example.a_track.service.LocationTrackingService.class);
        serviceIntent.setAction("RESCHEDULE_ALARM");
        startService(serviceIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAlarm();
    }
}