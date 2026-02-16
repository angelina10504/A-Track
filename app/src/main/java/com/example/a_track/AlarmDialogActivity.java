package com.example.a_track;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
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
public class AlarmDialogActivity extends AppCompatActivity {

    private static final String TAG = "AlarmDialogActivity";
    private static final long ALARM_DURATION = 30000; // 30 seconds

    private TextView tvMessage;
    private TextView tvTimer;
    private Button btnAllOk;

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private CountDownTimer countDownTimer;
    private long startTime;
    private boolean responded = false;

    private AppDatabase db;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            finish();
            return;
        }

        // Show on lock screen and turn screen on
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_alarm_dialog);

        db = AppDatabase.getInstance(this);
        sessionManager = new SessionManager(this);

        initViews();
        startAlarm();
        startCountdown();
    }

    private void initViews() {
        tvMessage = findViewById(R.id.tvAlarmMessage);
        tvTimer = findViewById(R.id.tvAlarmTimer);
        btnAllOk = findViewById(R.id.btnAllOk);

        btnAllOk.setOnClickListener(v -> handleResponse());
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
        responded = true;
        long responseTime = (System.currentTimeMillis() - startTime) / 1000; // in seconds

        stopAlarm();
        Toast.makeText(this, "Response recorded: " + responseTime + "s", Toast.LENGTH_SHORT).show();

        saveAlarmRecord(70, (int) responseTime); // datatype 70 = acknowledged
        rescheduleNextAlarm();

        // ✅ Dismiss notification
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.cancel(999);

        Toast.makeText(this, "Response recorded: " + responseTime + "s", Toast.LENGTH_SHORT).show();

        saveAlarmRecord(70, (int) responseTime);
        rescheduleNextAlarm();
        finish();
    }

    private void handleTimeout() {
        stopAlarm();
        saveAlarmRecord(71, 30); // datatype 71 = missed
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

                String textMsg = (datatype == 70) ?
                        "ALARM_ACK:" + responseTime :
                        "ALARM_MISS:" + responseTime;

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