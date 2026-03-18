package com.example.a_track.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import java.util.Random;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.AlarmManager;
import android.app.PendingIntent;
import androidx.annotation.RequiresApi;
import com.example.a_track.AlarmDialogActivity;
import com.example.a_track.AlarmReceiver;
import com.example.a_track.DataTypes;
import com.example.a_track.DashboardActivity;
import com.example.a_track.R;
import com.example.a_track.database.AppDatabase;
import com.example.a_track.database.LocationTrack;
import com.example.a_track.utils.ApiService;
import com.example.a_track.utils.SessionManager;
import com.example.a_track.utils.DeviceInfoHelper;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.example.a_track.workers.ServiceWatchdogWorker;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LocationTrackingService extends Service {

    private static final String TAG = "LocationTrackingService";
    private static final String CHANNEL_ID = "LocationTrackingChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final long UPDATE_INTERVAL = 60000; // 1 minute
    public static boolean isRunning = false;
    private static final long SYNC_INTERVAL = 120000; // 2 minutes

    // Datatype constants — single source of truth in DataTypes.java
    private static final int DATATYPE_INSTALL = DataTypes.INSTALL;
    private static final int DATATYPE_REBOOT  = DataTypes.REBOOT;
    private static final int DATATYPE_NORMAL  = DataTypes.NORMAL;
    private static final int DATATYPE_MOCK    = DataTypes.MOCK;
    private static final int DATATYPE_PHOTO   = DataTypes.PHOTO;
    private static final int DATATYPE_VIDEO   = DataTypes.VIDEO;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private AppDatabase db;
    private SessionManager sessionManager;
    private ExecutorService executorService;
    private Location lastLocation;
    private Handler handler;
    private Handler alarmHandler;
    private Handler healthCheckHandler;
    private Handler permissionCheckHandler;
    private Runnable locationRunnable;
    private Runnable syncRunnable;
    private Runnable alarmRunnable;
    private Random random;
    private PowerManager.WakeLock wakeLock;
    private Location lastSavedLocation;
    private Location stationaryBaseLocation;
    private LocationListener gpsTimeListener;
    private int stationaryCount = 0;
    private static final float STATIONARY_THRESHOLD = 5.0f;
    private static final float MIN_SPEED = 0.5f;
    private static final int MIN_ALARM_INTERVAL = 3 * 60 * 1000; // 15 minutes
    private static final int MAX_ALARM_INTERVAL = 5 * 60 * 1000; // 25 minutes

    // ✅ Track if we've already logged install/reboot for this session

    private boolean hasLoggedInstallReboot = false;

    // Pending kill-reason record — set by logPreviousExitReason(), flushed on first real GPS fix
    private volatile String pendingKillReason = null;
    private volatile long   pendingKillTime   = 0;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public LocationTrackingService getService() {
            return LocationTrackingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        isRunning = true;
        Log.d(TAG, "─── onCreate() entered ───");
        Log.d(TAG, "Package: " + getPackageName());
        Log.d(TAG, "Android API level: " + Build.VERSION.SDK_INT);
        Log.d(TAG, "Process ID: " + android.os.Process.myPid());

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        db = AppDatabase.getInstance(this);
        sessionManager = new SessionManager(this);
        executorService = Executors.newSingleThreadExecutor();
        handler = new Handler(Looper.getMainLooper());
        random = new Random(); // must be initialized before startPeriodicSync()

        // ─── Log previous app crash reason (API 30+) ──────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            logPreviousExitReason();
        }

        // Acquire wake lock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ROU Track::LocationWakeLock");
        wakeLock.acquire(10 * 60 * 1000L); // 10-minute timeout

        Log.d(TAG, "WakeLock acquired - Service will run in sleep mode");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        acquireGpsTimeOffset();      // accurate GPS satellite time (~10-30s), overwrites server time

        // Gate location recording behind server-time calibration so the very first record
        // carries a corrected timestamp (not raw device clock).  Max delay ≈ 10 s (HTTP timeout).
        // Even on failure the callback fires and location updates begin with device-clock fallback.
        startPeriodicSync();         // safe to start immediately — only syncs, never saves records
        calibrateTimeFromServer(() -> {
            // Runs on main thread after server responds (or times out)
            setupLocationCallback();
            startLocationUpdates();
            startPeriodicLocationFetch();
        });

        scheduleNextAlarm();
        scheduleWatchdog();

        checkAlarmPermission();
        checkBatteryOptimization();

        // ✅ NEW: Alarm health check every 10 minutes
        healthCheckHandler = new Handler(Looper.getMainLooper());
        Runnable healthCheckRunnable = new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(LocationTrackingService.this, AlarmReceiver.class);
                boolean alarmUp = (PendingIntent.getBroadcast(
                        LocationTrackingService.this, 123, intent,
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
                ) != null);

                if (!alarmUp) {
                    Log.w(TAG, "⚠️ ALARM LOST! Rescheduling now...");
                    scheduleNextAlarm();
                } else {
                    Log.d(TAG, "✓ Alarm health check: OK");
                }

                healthCheckHandler.postDelayed(this, 10 * 60 * 1000);
            }
        };
        healthCheckHandler.postDelayed(healthCheckRunnable, 10 * 60 * 1000);

        // ✅ NEW: Permission check every 30 minutes
        permissionCheckHandler = new Handler(Looper.getMainLooper());
        Runnable permissionCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkAlarmPermission();
                checkBatteryOptimization();
                permissionCheckHandler.postDelayed(this, 30 * 60 * 1000);
            }
        };
        permissionCheckHandler.postDelayed(permissionCheckRunnable, 30 * 60 * 1000);

        Log.d(TAG, "Alarm system initialized");
        Log.d(TAG, "─── onCreate() complete — service is running ───");
    }

    // ─── App crash reason logging ──────────────────────────────────────────────

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void logPreviousExitReason() {
        executorService.execute(() -> {
            try {
                android.content.SharedPreferences prefs =
                        getSharedPreferences("KillLog", MODE_PRIVATE);

                // ── Phase 1: Restore a crash reason detected but not yet saved ──
                String restored = prefs.getString("pending_kill_reason", null);
                if (restored != null) {
                    pendingKillReason = restored;
                    pendingKillTime   = prefs.getLong("pending_kill_time", 0);
                    Log.d(TAG, "✓ Restored unsaved crash reason from previous start: " + pendingKillReason);
                    return;
                }

                // ── Phase 2: Detect a new crash from the system's exit history ──
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am == null) return;

                List<ApplicationExitInfo> reasons =
                        am.getHistoricalProcessExitReasons(null, 0, 1);
                if (reasons.isEmpty()) return;

                ApplicationExitInfo info = reasons.get(0);

                // Only log reasons that actually crash/stop the service
                int reason = info.getReason();
                if (reason != ApplicationExitInfo.REASON_CRASH
                        && reason != ApplicationExitInfo.REASON_CRASH_NATIVE
                        && reason != ApplicationExitInfo.REASON_ANR
                        && reason != ApplicationExitInfo.REASON_INITIALIZATION_FAILURE) {
                    return;
                }

                // Guard: skip if already saved to DB
                long lastLoggedKillTime = prefs.getLong("last_logged_kill_time", 0);
                if (info.getTimestamp() <= lastLoggedKillTime) {
                    return;
                }

                String prefix;
                switch (reason) {
                    case ApplicationExitInfo.REASON_CRASH:               prefix = "CRASH"; break;
                    case ApplicationExitInfo.REASON_CRASH_NATIVE:        prefix = "CRASH_NATIVE"; break;
                    case ApplicationExitInfo.REASON_ANR:                 prefix = "ANR"; break;
                    case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: prefix = "INIT_FAILURE"; break;
                    default:                                             prefix = "REASON_" + reason; break;
                }
                String crashInfo = extractCrashInfo(info);
                String reasonText = prefix + " | " + crashInfo;
                if (reasonText.length() > 500) reasonText = reasonText.substring(0, 500);

                Log.w(TAG, "Crash detected: " + reasonText);
                pendingKillReason = reasonText;
                pendingKillTime   = info.getTimestamp();
                prefs.edit()
                        .putString("pending_kill_reason", reasonText)
                        .putLong("pending_kill_time", info.getTimestamp())
                        .apply();

            } catch (Exception e) {
                Log.e(TAG, "Error logging previous exit reason: " + e.getMessage());
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private String extractCrashInfo(ApplicationExitInfo info) {
        InputStream is = null;
        try {
            is = info.getTraceInputStream();
            if (is == null) return "(no trace available)";

            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;

            if (info.getReason() == ApplicationExitInfo.REASON_CRASH) {
                String exceptionLine = null;
                String ourFrame = null;
                while ((line = reader.readLine()) != null) {
                    if (exceptionLine == null
                            && !line.startsWith("FATAL EXCEPTION")
                            && !line.startsWith("Process:")
                            && !line.startsWith("PID:")
                            && !line.startsWith("\t")
                            && (line.contains("Exception") || line.contains("Error"))) {
                        exceptionLine = line.trim();
                    }
                    if (ourFrame == null && line.contains("com.example.a_track")) {
                        ourFrame = line.trim();
                    }
                    if (exceptionLine != null && ourFrame != null) break;
                }
                StringBuilder sb = new StringBuilder();
                if (exceptionLine != null) sb.append(exceptionLine);
                if (ourFrame != null) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(ourFrame);
                }
                return sb.length() > 0 ? sb.toString() : "(exception info not found in trace)";
            } else {
                // ANR / CRASH_NATIVE / INIT_FAILURE — first 8 non-empty lines
                StringBuilder sb = new StringBuilder();
                int count = 0;
                while ((line = reader.readLine()) != null && count < 8) {
                    if (!line.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(line.trim());
                        count++;
                    }
                }
                return sb.length() > 0 ? sb.toString() : "(no trace available)";
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read crash trace: " + e.getMessage());
            return "(trace read error)";
        } finally {
            if (is != null) {
                try { is.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Location Tracking",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Tracking your location in background");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, DashboardActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ROU Track Active")
                .setContentText("Securing your Safety")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void setupLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    Log.w(TAG, "Location result is null");
                    return;
                }

                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        Log.d(TAG, "Location received: Lat=" + location.getLatitude() +
                                ", Lng=" + location.getLongitude());
                        lastLocation = location;
                    }
                }
            }
        };
    }

    private void startLocationUpdates() {
        Log.d(TAG, "Starting continuous location updates");

        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(5000)
                .build();

        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted");
            return;
        }

        fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
        );

        Log.d(TAG, "Location updates started");
    }

    private void startPeriodicLocationFetch() {
        locationRunnable = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "1 minute elapsed - fetching and saving location");
                fetchAndSaveLocation();
                handler.postDelayed(this, UPDATE_INTERVAL);
            }
        };
        handler.post(locationRunnable);
        Log.d(TAG, "Periodic location fetch started (every 1 minute)");
    }

    private void startPeriodicSync() {
        syncRunnable = new Runnable() {
            @Override
            public void run() {
                if (isNetworkAvailable()) {
                    syncDataToServer();
                } else {
                    Log.d(TAG, "No internet - skipping sync");
                }
                // Jitter: ±30s so all phones don't hit the server at the same time
                long jitter = random.nextInt(60001) - 30000;
                long nextInterval = SYNC_INTERVAL + jitter;
                Log.d(TAG, "Next sync in " + (nextInterval / 1000) + "s");
                handler.postDelayed(this, nextInterval);
            }
        };
        // Jitter on first sync too
        long initialJitter = random.nextInt(60001) - 30000;
        handler.postDelayed(syncRunnable, SYNC_INTERVAL + initialJitter);
        Log.d(TAG, "Periodic sync started (every ~2 minutes with jitter)");
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = cm.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnected();
    }

    private void fetchAndSaveLocation() {
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted");
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            // Treat (0,0) the same as null — FusedLocationProvider can return a
            // "Null Island" location object with lat=0.0/lng=0.0 when its cache is
            // cold (e.g. first save right after login before GPS has a real fix).
            boolean locationValid = location != null
                    && (location.getLatitude() != 0.0 || location.getLongitude() != 0.0);

            if (locationValid) {
                Log.d(TAG, "Fetched location for time-based save: Lat=" + location.getLatitude() +
                        ", Lng=" + location.getLongitude());
                saveLocationToDatabase(location);
                lastLocation = location;
            } else {
                Log.w(TAG, "Location is null or (0,0) — using last known location");
                if (lastLocation != null) {
                    saveLocationToDatabase(lastLocation);
                } else {
                    Log.e(TAG, "No location available to save — skipping this cycle");
                }
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to get location: " + e.getMessage());
        });
    }

    private void saveLocationToDatabase(Location location) {
        String mobileNumber = sessionManager.getMobileNumber();
        String sessionId = sessionManager.getSessionId();

        if (mobileNumber == null || sessionId == null) {
            Log.e(TAG, "Cannot save: Mobile or SessionId is null");
            return;
        }

        // ✅ STEP 1: Determine datatype
        int datatype = determineDatatype(location);

        Location locationToSave;

        // ✅ Determine if stationary
        boolean isStationary = false;
        if (lastSavedLocation != null) {
            float distance = location.distanceTo(lastSavedLocation);
            float speed = location.hasSpeed() ? (location.getSpeed()) : 0;

            if (distance < STATIONARY_THRESHOLD && speed < MIN_SPEED) {
                isStationary = true;
                stationaryCount++;
            } else {
                isStationary = false;
                stationaryCount = 0;
                stationaryBaseLocation = null;
            }
        }

        // ✅ Handle stationary vs moving
        if (isStationary) {
            if (stationaryBaseLocation == null) {
                stationaryBaseLocation = new Location(location);
                Log.d(TAG, "🛑 Now stationary - base location set");
            }

            locationToSave = new Location(stationaryBaseLocation);
            locationToSave.setTime(location.getTime());
            locationToSave.setSpeed(0);
            locationToSave.setBearing(lastSavedLocation != null ? lastSavedLocation.getBearing() : 0);

            Log.d(TAG, "📍 Stationary (" + stationaryCount + "x) - using stable coordinates");

        } else {
            locationToSave = location;
            Log.d(TAG, "🚗 Moving - using GPS coordinates");
        }

        // Use server-calibrated true time (offset set from HTTP Date header in onCreate).
        // This is independent of the device clock — changing the device time has no effect.
        long currentTime = sessionManager.getTrueTimeMs();

        // ── TIME SPOOF DIAGNOSTICS ────────────────────────────────────────────
        long _storedOffset = sessionManager.getTimeOffset();
        long _deviceClock  = System.currentTimeMillis();
        long _diffDays     = (_deviceClock - currentTime) / 86_400_000L;
        Log.d(TAG, "TIME_DIAG | gps_offset=" + _storedOffset
                + " | device_clock=" + _deviceClock
                + " | trueTimeMs=" + currentTime
                + " | diff_days=" + _diffDays);
        if (_storedOffset == 0) {
            Log.w(TAG, "TIME_DIAG ⚠️ offset=0 — server calibration failed or not yet run."
                    + " Timestamp will use spoofed device clock!");
        }
        // ─────────────────────────────────────────────────────────────────────
        int battery = getBatteryLevel();
        float speedToSave = isStationary ? 0 : (location.hasSpeed() ? (location.getSpeed() * 3.6f) : 0);

        DeviceInfoHelper deviceInfo = new DeviceInfoHelper(this);
        long mobileTime = deviceInfo.getMobileTime();
        int nss = deviceInfo.getNetworkSignalStrength();

        // Move database operations to background thread
        executorService.execute(() -> {
            try {
                // ── Flush pending crash record with real GPS coordinates ──────────
                if (pendingKillReason != null) {
                    try {
                        int killLastRecNo = sessionManager.getLastRecNo();
                        if (killLastRecNo == 0) {
                            killLastRecNo = db.locationTrackDao().getLastRecNo(mobileNumber);
                        }
                        int killRecNo = killLastRecNo + 1;
                        LocationTrack killRecord = new LocationTrack(
                                mobileNumber,
                                locationToSave.getLatitude(),
                                locationToSave.getLongitude(),
                                0f,
                                locationToSave.getBearing(),
                                pendingKillTime,
                                sessionId,
                                battery,
                                null, null,
                                pendingKillReason + " | " + getDeviceHealthString(mobileNumber),
                                deviceInfo.getGpsState(),
                                deviceInfo.getInternetState(),
                                deviceInfo.getFlightState(),
                                deviceInfo.getRoamingState(),
                                deviceInfo.getIsNetThere(),
                                deviceInfo.getIsNwThere(),
                                deviceInfo.getIsMoving(0f),
                                deviceInfo.getModelNo(),
                                deviceInfo.getModelOS(),
                                deviceInfo.getApkName(),
                                deviceInfo.getImsiNo(),
                                mobileTime,
                                nss,
                                killRecNo,
                                DataTypes.APP_KILL
                        );
                        db.locationTrackDao().insert(killRecord);
                        sessionManager.saveLastRecNo(killRecNo);
                        getSharedPreferences("KillLog", MODE_PRIVATE)
                                .edit()
                                .putLong("last_logged_kill_time", pendingKillTime)
                                .remove("pending_kill_reason")
                                .remove("pending_kill_time")
                                .apply();
                        Log.d(TAG, "✓ Crash record saved: " + pendingKillReason);
                    } catch (Exception e) {
                        Log.e(TAG, "Error saving crash record: " + e.getMessage());
                    } finally {
                        pendingKillReason = null;
                        pendingKillTime   = 0;
                    }
                }
                // ─────────────────────────────────────────────────────────────────

                int lastRecNo = sessionManager.getLastRecNo();
                if (lastRecNo == 0) {
                    // Fallback to DB on first run or after data clear
                    lastRecNo = db.locationTrackDao().getLastRecNo(mobileNumber);
                }
                int nextRecNo = lastRecNo + 1;

                LocationTrack track = new LocationTrack(
                        mobileNumber,
                        location.getLatitude(),
                        location.getLongitude(),
                        speedToSave,
                        location.getBearing(),
                        currentTime,
                        sessionId,
                        battery,
                        null,  // photoPath
                        null,  // videoPath
                        getDeviceHealthString(mobileNumber),  // textMsg
                        deviceInfo.getGpsState(),
                        deviceInfo.getInternetState(),
                        deviceInfo.getFlightState(),
                        deviceInfo.getRoamingState(),
                        deviceInfo.getIsNetThere(),
                        deviceInfo.getIsNwThere(),
                        deviceInfo.getIsMoving(location.getSpeed()),
                        deviceInfo.getModelNo(),
                        deviceInfo.getModelOS(),
                        deviceInfo.getApkName(),
                        deviceInfo.getImsiNo(),
                        mobileTime,
                        nss,
                        nextRecNo,
                        datatype  // ✅ Pass datatype here
                );

                db.locationTrackDao().insert(track);
                sessionManager.saveLastRecNo(nextRecNo);
                lastSavedLocation = new Location(locationToSave);


                // ✅ Log datatype info
                String datatypeStr = getDatatypeString(datatype);
                Log.d(TAG, "✓ Location saved: RecNo=" + nextRecNo +
                        ", Datatype=" + datatype + " (" + datatypeStr + ")" +
                        ", Lat=" + location.getLatitude() +
                        ", Lng=" + location.getLongitude() +
                        ", Speed=" + speedToSave + " km/h" +
                        ", Battery=" + battery + "%");
            } catch (Exception e) {
                Log.e(TAG, "Error saving location: " + e.getMessage());
            }
        });
    }

    /**
     * ✅ Determine the datatype for this location entry
     * Uses SessionManager for install/reboot tracking
     * 0 = Install, 1 = login, 2 = Normal, 8 = Mock Location
     */
    private int determineDatatype(Location location) {
        // Priority 1: Check for mock location
        DeviceInfoHelper deviceInfo = new DeviceInfoHelper(this);
        if (deviceInfo.isMockLocation(location)) {
            Log.w(TAG, "🚨 MOCK LOCATION DETECTED - datatype = 8");
            return DATATYPE_MOCK;
        }

        // Priority 2: Check for install/reboot (only once per session)
        if (!hasLoggedInstallReboot) {
            // Check for first install
            if (sessionManager.isFirstRun()) {
                Log.i(TAG, "📱 FIRST INSTALL DETECTED - datatype = 0");
                sessionManager.markFirstRunComplete();
                hasLoggedInstallReboot = true;
                return DATATYPE_INSTALL;
            }

            // Check for device reboot — hasDeviceRebooted() self-resets on first true
            if (sessionManager.hasDeviceRebooted()) {
                Log.i(TAG, "🔑 REBOOT DETECTED - datatype = 1");
                hasLoggedInstallReboot = true;
                return DATATYPE_REBOOT;
            }

            // After first check, mark as logged
            hasLoggedInstallReboot = true;
        }

        // Default: Normal tracking
        return DATATYPE_NORMAL;
    }

    /**
     * Helper method to get human-readable datatype string
     */
    private String getDatatypeString(int datatype) {
        switch (datatype) {
            case DataTypes.INSTALL:      return "Install";
            case DataTypes.REBOOT:       return "Reboot";
            case DataTypes.NORMAL:       return "Normal";
            case DataTypes.MOCK:         return "Mock Location";
            case DataTypes.PHOTO:        return "Photo";
            case DataTypes.VIDEO:        return "Video";
            case DataTypes.ALARM_ACK:    return "Alarm Acknowledged";
            case DataTypes.ALARM_MISSED: return "Alarm Missed";
            default:                     return "Unknown";
        }
    }

    /**
     * Builds the standard health-status string appended to every record's TextMsg.
     * Format (fixed order):
     *   Loc:[Ok|NA], Net:[Ok|NA], Q:[n], BatOpt:[Yes|No], PlayPro:[On|Off], BkUsg:[Allowed|NA], Notif:[On|Off]
     *
     * MUST be called on a background thread — performs a synchronous DB query for Q.
     */
    private String getDeviceHealthString(String mobileNumber) {
        DeviceInfoHelper di = new DeviceInfoHelper(this);

        // Loc — GPS hardware on/off
        String loc = "1".equals(di.getGpsState()) ? "Ok" : "NA";

        // BgLoc — background "All the time" location permission
        String bgLoc = DeviceInfoHelper.hasAllTheTimeLocationPermission(this) ? "Ok" : "NA";

        // Net
        String net = "1".equals(di.getInternetState()) ? "Ok" : "NA";

        // Q — unsynced record count
        int q = 0;
        try {
            if (mobileNumber != null) {
                q = db.locationTrackDao().getUnsyncedCount(mobileNumber);
            }
        } catch (Exception e) {
            Log.w(TAG, "getDeviceHealthString: Q count error: " + e.getMessage());
        }

        // BatOpt — "Yes" = optimised (bad), "No" = exempted (good)
        String batOpt = "No";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                batOpt = "Yes";
            }
        }

        // PlayPro
        String playPro = "Off";
        try {
            int ve = Settings.Global.getInt(getContentResolver(), "package_verifier_enable", 1);
            int uc = Settings.Global.getInt(getContentResolver(), "package_verifier_user_consent", 1);
            playPro = (ve == 1 && uc != -1) ? "On" : "Off";
        } catch (Exception e) {
            Log.w(TAG, "getDeviceHealthString: PlayPro check error: " + e.getMessage());
        }

        // BkUsg — "NA" = background data blocked
        String bkUsg = "Allowed";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && cm.getRestrictBackgroundStatus()
                    == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                bkUsg = "NA";
            }
        }

        // Notif
        String notif = "On";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notif = (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) ? "On" : "Off";
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            notif = (nm != null && nm.areNotificationsEnabled()) ? "On" : "Off";
        }

        return "Loc:" + loc + ", BgLoc:" + bgLoc + ", Net:" + net + ", Q:" + q
                + ", BatOpt:" + batOpt + ", PlayPro:" + playPro
                + ", BkUsg:" + bkUsg + ", Notif:" + notif;
    }

    private void syncDataToServer() {
        String mobile = sessionManager.getMobileNumber();
        if (mobile == null) return;

        executorService.execute(() -> {
            List<LocationTrack> unsyncedTracks = db.locationTrackDao().getUnsyncedTracks(mobile);

            if (!unsyncedTracks.isEmpty()) {
                int totalRecords = unsyncedTracks.size();

                if (totalRecords <= 20) {
                    Log.d(TAG, "📍 Syncing " + totalRecords + " records (single batch)");
                    syncBatchBlocking(unsyncedTracks);
                } else {
                    int BATCH_SIZE = 20;
                    int totalBatches = (totalRecords + BATCH_SIZE - 1) / BATCH_SIZE;

                    Log.d(TAG, "📍 Starting batch sync: " + totalRecords + " records in " +
                            totalBatches + " batches");

                    for (int batchNum = 0; batchNum < totalBatches; batchNum++) {
                        int start = batchNum * BATCH_SIZE;
                        int end = Math.min(start + BATCH_SIZE, totalRecords);
                        List<LocationTrack> batch = unsyncedTracks.subList(start, end);

                        Log.d(TAG, "📤 Syncing batch " + (batchNum + 1) + "/" + totalBatches +
                                ": " + batch.size() + " records");

                        boolean success = syncBatchBlocking(batch);

                        if (!success) {
                            Log.w(TAG, "⚠ Batch " + (batchNum + 1) + " failed, continuing to next batch");
                        }

                        if (batchNum < totalBatches - 1) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Log.e(TAG, "Batch delay interrupted: " + e.getMessage());
                            }
                        }
                    }

                    Log.d(TAG, "✓ Batch sync completed for all location records");
                }

            } else {
                Log.d(TAG, "No location data to sync, checking for media...");
            }

            // ✅ Sync both photos and videos
            syncPendingPhotos();
            syncPendingVideos();  // ✅ NEW
            cleanupOldRecords();
        });
    }

    private boolean syncBatchBlocking(List<LocationTrack> batch) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};

        ApiService.syncLocations(batch, new ApiService.SyncCallback() {
            @Override
            public void onSuccess(int syncedCount, List<Integer> confirmedIds) {
                // DB work runs on the API thread (not executorService) to avoid deadlock:
                // executorService is a single thread already blocked on latch.await()
                if (!confirmedIds.isEmpty()) {
                    db.locationTrackDao().markAsSynced(confirmedIds);
                    Log.d(TAG, "✓ Marked " + confirmedIds.size() + " records as synced");
                }
                success[0] = true;
                latch.countDown();
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "✗ Batch sync failed: " + error);
                success[0] = false;
                latch.countDown();
            }
        });

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Batch sync interrupted: " + e.getMessage());
            return false;
        }

        return success[0];
    }

    private void syncPendingPhotos() {
        String mobile = sessionManager.getMobileNumber();
        if (mobile == null) return;

        executorService.execute(() -> {
            List<LocationTrack> unsyncedPhotos = db.locationTrackDao().getUnsyncedPhotos(mobile);

            if (unsyncedPhotos.isEmpty()) {
                Log.d(TAG, "✓ No pending photos to sync");
                return;
            }

            Log.d(TAG, "📸 Found " + unsyncedPhotos.size() + " photos to sync");

            for (LocationTrack track : unsyncedPhotos) {
                File photoFile = new File(track.getPhotoPath());

                if (!photoFile.exists()) {
                    Log.w(TAG, "⚠ Photo file missing, marking as synced: " + track.getPhotoPath());
                    db.locationTrackDao().markPhotoAsSynced(track.getId());
                    continue;
                }

                Log.d(TAG, "📤 Uploading photo: " + photoFile.getName() + " (" + photoFile.length() + " bytes)");

                ApiService.uploadPhoto(track, photoFile, new ApiService.PhotoUploadCallback() {
                    @Override
                    public void onSuccess(String photoPath) {
                        executorService.execute(() -> {
                            db.locationTrackDao().markPhotoAsSynced(track.getId());
                            Log.d(TAG, "✓ Photo synced successfully: " + photoFile.getName());

                            if (photoFile.delete()) {
                                Log.d(TAG, "✓ Local photo deleted: " + photoFile.getName());
                            } else {
                                Log.w(TAG, "⚠ Could not delete local photo: " + photoFile.getName());
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        Log.e(TAG, "✗ Photo upload failed (will retry): " + photoFile.getName() + " - " + error);
                    }
                });

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Sleep interrupted: " + e.getMessage());
                }
            }
        });
    }

    // ✅ NEW: Sync pending videos
    private void syncPendingVideos() {
        String mobile = sessionManager.getMobileNumber();
        if (mobile == null) return;

        executorService.execute(() -> {
            List<LocationTrack> unsyncedVideos = db.locationTrackDao().getUnsyncedVideos(mobile);

            if (unsyncedVideos.isEmpty()) {
                Log.d(TAG, "✓ No pending videos to sync");
                return;
            }

            Log.d(TAG, "🎥 Found " + unsyncedVideos.size() + " videos to sync");

            for (LocationTrack track : unsyncedVideos) {
                File videoFile = new File(track.getVideoPath());

                if (!videoFile.exists()) {
                    Log.w(TAG, "⚠ Video file missing, marking as synced: " + track.getVideoPath());
                    db.locationTrackDao().markVideoAsSynced(track.getId());
                    continue;
                }

                Log.d(TAG, "📤 Uploading video: " + videoFile.getName() + " (" + videoFile.length() + " bytes)");

                ApiService.uploadVideo(track, videoFile, new ApiService.VideoUploadCallback() {
                    @Override
                    public void onSuccess(String videoPath) {
                        executorService.execute(() -> {
                            db.locationTrackDao().markVideoAsSynced(track.getId());
                            Log.d(TAG, "✓ Video synced successfully: " + videoFile.getName());

                            if (videoFile.delete()) {
                                Log.d(TAG, "✓ Local video deleted: " + videoFile.getName());
                            } else {
                                Log.w(TAG, "⚠ Could not delete local video: " + videoFile.getName());
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        Log.e(TAG, "✗ Video upload failed (will retry): " + videoFile.getName() + " - " + error);
                    }
                });

                try {
                    Thread.sleep(2000);  // Longer delay for videos (larger files)
                } catch (InterruptedException e) {
                    Log.e(TAG, "Sleep interrupted: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Registers a one-shot listener on LocationManager.GPS_PROVIDER (direct GPS chip,
     * not FusedLocationProvider). The GPS chip gets its time from satellite atomic clocks,
     * so location.getTime() here is true UTC — unaffected by any device clock change.
     *
     * Offset formula:  offset = gps_fix_time − elapsed_at_fix
     * True time later: offset + SystemClock.elapsedRealtime()
     *
     * Triggered on every service start and reboot. Overwrites the server-time offset
     * with the more accurate GPS satellite value once the first fix arrives.
     */
    private void acquireGpsTimeOffset() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.d(TAG, "GPS provider unavailable — skipping GPS time sync");
            return;
        }

        gpsTimeListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location.getTime() > 0 && location.getElapsedRealtimeNanos() > 0) {
                    // Anchor GPS satellite time to the monotonic clock at the exact fix moment
                    long gpsFixElapsedMs = location.getElapsedRealtimeNanos() / 1_000_000L;
                    long gpsOffset = location.getTime() - gpsFixElapsedMs;
                    sessionManager.saveTimeOffset(gpsOffset);
                    Log.d(TAG, "✓ GPS time offset calibrated: " + gpsOffset
                            + " (fix UTC=" + location.getTime() + ")");
                    // One fix is enough — remove listener to save battery
                    lm.removeUpdates(this);
                    gpsTimeListener = null;
                }
            }

            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0, 0,
                    gpsTimeListener,
                    Looper.getMainLooper()
            );
            Log.d(TAG, "GPS time sync listener registered");
        } catch (SecurityException e) {
            Log.w(TAG, "GPS permission unavailable for time sync");
            gpsTimeListener = null;
        }
    }

    /**
     * Fetches the server's current UTC time via HTTP Date header (HTTPS, port 443).
     * Used as a fast fallback while waiting for the GPS fix (~2s vs ~10-30s).
     * Stores a calibrated offset so getTrueTimeMs() is correct from the very first record.
     *
     * fetchServerTimeOffsetMs() captures SystemClock.elapsedRealtime() at the exact instant
     * the response headers arrive, so the returned offset has minimal latency skew.
     *
     * onComplete is always posted to the main thread after the request finishes (success or
     * failure) so callers can gate any work that requires a valid time offset.
     */
    private void calibrateTimeFromServer(Runnable onComplete) {
        new Thread(() -> {
            long offset = com.example.a_track.utils.ApiService.fetchServerTimeOffsetMs();
            if (offset != Long.MIN_VALUE) {
                sessionManager.saveTimeOffset(offset);
                Log.d(TAG, "✓ Time calibrated from server: offset=" + offset + " ms");
            } else {
                Log.w(TAG, "⚠️ Server time unavailable - starting location with device time");
            }
            // Always unblock location recording, even on failure
            if (onComplete != null) {
                handler.post(onComplete);
            }
        }).start();
    }

    private void cleanupOldRecords() {
        executorService.execute(() -> {
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.setTimeInMillis(sessionManager.getTrueTimeMs());
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
            calendar.set(java.util.Calendar.MINUTE, 0);
            calendar.set(java.util.Calendar.SECOND, 0);
            calendar.set(java.util.Calendar.MILLISECOND, 0);
            long todayStartMillis = calendar.getTimeInMillis();

            Log.d(TAG, "🧹 Cleaning up old records before: " + new java.util.Date(todayStartMillis));

            int deleted = db.locationTrackDao().deleteOldTracks(todayStartMillis);

            if (deleted > 0) {
                Log.d(TAG, "✓ Deleted " + deleted + " old synced records");
            } else {
                Log.d(TAG, "✓ No old records to delete");
            }
        });
    }

    private int getBatteryLevel() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);

            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                return (int) ((level / (float) scale) * 100);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting battery level: " + e.getMessage());
        }
        return 0;
    }

    public Location getLastLocation() {
        return lastLocation;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "─── onStartCommand() entered ───");
        Log.d(TAG, "flags=" + flags + ", startId=" + startId);
        Log.d(TAG, "intent=" + (intent != null ? intent.toString() : "null"));

        if (intent != null) {
            String action = intent.getAction();
            Log.d(TAG, "action=" + (action != null ? action : "null (normal start)"));

            if ("RESCHEDULE_ALARM".equals(action)) {
                Log.d(TAG, "⏰ Rescheduling next alarm after user response");
                scheduleNextAlarm();

            } else if ("ALARM_DISMISSED".equals(action)) {
                Log.d(TAG, "🔕 Alarm dismissed - saving as MISSED and rescheduling");
                saveAlarmDismissed();
                scheduleNextAlarm();
            }
        } else {
            Log.d(TAG, "intent is null — service restarted by OS (START_STICKY)");
        }

        Log.d(TAG, "─── onStartCommand() returning START_STICKY ───");
        return START_STICKY;
    }

    // ─── Layer 3: WorkManager Watchdog ──────────────────────────────────────────
    private void scheduleWatchdog() {
        PeriodicWorkRequest watchdogWork = new PeriodicWorkRequest.Builder(
                ServiceWatchdogWorker.class,
                15, TimeUnit.MINUTES
        ).build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                ServiceWatchdogWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                watchdogWork
        );

        Log.d(TAG, "✓ WorkManager watchdog scheduled (every 15 min)");
    }

    private void scheduleNextAlarm() {
        if (!sessionManager.isLoggedIn()) {
            Log.d(TAG, "⏰ Not scheduling alarm - no user logged in");
            return;
        }

        int randomInterval = MIN_ALARM_INTERVAL +
                random.nextInt(MAX_ALARM_INTERVAL - MIN_ALARM_INTERVAL);

        long triggerTime = System.currentTimeMillis() + randomInterval;
        int seconds = randomInterval / 1000;

        Log.d(TAG, "⏰ SCHEDULING ALARM:");
        Log.d(TAG, "   - Interval: " + seconds + " seconds");
        Log.d(TAG, "   - Trigger time: " + new java.util.Date(triggerTime));

        // ✅ CHANGED: Use BroadcastReceiver instead of Activity
        Intent intent = new Intent(this, AlarmReceiver.class);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this, 123, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            // setAlarmClock fires even in Doze and needs no SCHEDULE_EXACT_ALARM permission
            Log.w(TAG, "✗ SCHEDULE_EXACT_ALARM not granted - using setAlarmClock() fallback");
            AlarmManager.AlarmClockInfo clockInfo =
                    new AlarmManager.AlarmClockInfo(triggerTime, pendingIntent);
            alarmManager.setAlarmClock(clockInfo, pendingIntent);
            Log.d(TAG, "✓ Alarm clock scheduled (Doze-safe fallback)");
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
            );
            Log.d(TAG, "✓ Exact alarm scheduled");
        }

        Log.d(TAG, "✓ Alarm scheduled via BroadcastReceiver");
        logAlarmDiagnostics();
    }

    private void triggerAlarm() {
        Log.d(TAG, "🚨 ALARM TRIGGERED - Showing alert dialog");

        Intent intent = new Intent(this, AlarmDialogActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);

        // Schedule next alarm
        scheduleNextAlarm();
    }

    private void checkAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e(TAG, "⚠️ EXACT ALARM PERMISSION REVOKED!");

                NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle("⚠️ Critical: Alarms Disabled")
                        .setContentText("Tap to grant alarm permission again")
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setAutoCancel(false)
                        .setOngoing(true);

                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        this, 0, intent, PendingIntent.FLAG_IMMUTABLE
                );
                builder.setContentIntent(pendingIntent);

                NotificationManager notificationManager = getSystemService(NotificationManager.class);
                notificationManager.notify(997, builder.build());
            }
        }
    }

    private void checkBatteryOptimization() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        String packageName = getPackageName();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.w(TAG, "⚠️ Battery optimization NOT disabled!");

                NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_launcher_foreground)
                        .setContentTitle("Action Required")
                        .setContentText("Please disable battery optimization for alarms to work")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true);

                NotificationManager notificationManager = getSystemService(NotificationManager.class);
                notificationManager.notify(998, builder.build());
            }
        }
    }

    private void logAlarmDiagnostics() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

        Log.d(TAG, "=== ALARM DIAGNOSTICS ===");
        Log.d(TAG, "Android Version: " + Build.VERSION.SDK_INT);
        Log.d(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d(TAG, "Can schedule exact alarms: " + alarmManager.canScheduleExactAlarms());
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.d(TAG, "Battery optimization disabled: " +
                    pm.isIgnoringBatteryOptimizations(getPackageName()));
        }

        Intent intent = new Intent(this, AlarmReceiver.class);
        boolean alarmScheduled = (PendingIntent.getBroadcast(
                this, 123, intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        ) != null);
        Log.d(TAG, "Alarm currently scheduled: " + alarmScheduled);

        Log.d(TAG, "========================");
    }

    private void saveAlarmDismissed() {
        android.content.SharedPreferences prefs = getSharedPreferences("AlarmGuard", MODE_PRIVATE);
        if (prefs.getBoolean("health_alert_saved", false)) {
            // Flag 100 was already saved for this alarm cycle — do not also save flag 71
            Log.d(TAG, "Health alert already saved this cycle — skipping ALARM_MISSED (71)");
            return;
        }
        if (prefs.getBoolean("alarm_missed_saved", false)) {
            Log.d(TAG, "⚠️ Flag 71 already saved for this alarm cycle - skipping duplicate");
            return;
        }
        prefs.edit().putBoolean("alarm_missed_saved", true).apply();

        String mobileNumber = sessionManager.getMobileNumber();
        String sessionId = sessionManager.getSessionId();

        if (mobileNumber == null || sessionId == null) return;

        executorService.execute(() -> {
            try {
                LocationTrack lastTrack = db.locationTrackDao().getLastLocationSync(mobileNumber);

                double lat = lastTrack != null ? lastTrack.getLatitude() : 0.0;
                double lng = lastTrack != null ? lastTrack.getLongitude() : 0.0;
                float speed = lastTrack != null ? lastTrack.getSpeed() : 0.0f;
                float angle = lastTrack != null ? lastTrack.getAngle() : 0.0f;

                DeviceInfoHelper deviceInfo = new DeviceInfoHelper(this);
                int lastRecNo = db.locationTrackDao().getLastRecNo(mobileNumber);

                String textMsg = "ALARM_MISS:30 | " + getDeviceHealthString(mobileNumber);

                LocationTrack track = new LocationTrack(
                        mobileNumber, lat, lng, speed, angle,
                        sessionManager.getTrueTimeMs(), sessionId,
                        getBatteryLevel(), null, null,
                        textMsg,
                        deviceInfo.getGpsState(), deviceInfo.getInternetState(),
                        deviceInfo.getFlightState(), deviceInfo.getRoamingState(),
                        deviceInfo.getIsNetThere(), deviceInfo.getIsNwThere(),
                        deviceInfo.getIsMoving(speed), deviceInfo.getModelNo(),
                        deviceInfo.getModelOS(), deviceInfo.getApkName(),
                        deviceInfo.getImsiNo(), deviceInfo.getMobileTime(),
                        deviceInfo.getNetworkSignalStrength(),
                        lastRecNo + 1, DataTypes.ALARM_MISSED
                );

                db.locationTrackDao().insert(track);
                Log.d(TAG, "✓ Alarm dismissed record saved: datatype=" + DataTypes.ALARM_MISSED);

            } catch (Exception e) {
                Log.e(TAG, "Error saving dismissed alarm: " + e.getMessage());
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        isRunning = false;
        Log.d(TAG, "Service onDestroy - Stopping location tracking");

        hasLoggedInstallReboot = false;

        if (handler != null && locationRunnable != null) {
            handler.removeCallbacks(locationRunnable);
        }

        if (handler != null && syncRunnable != null) {
            handler.removeCallbacks(syncRunnable);
        }

        if (healthCheckHandler != null) {
            healthCheckHandler.removeCallbacksAndMessages(null);
        }

        if (permissionCheckHandler != null) {
            permissionCheckHandler.removeCallbacksAndMessages(null);
        }

        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        if (gpsTimeListener != null) {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) lm.removeUpdates(gpsTimeListener);
            gpsTimeListener = null;
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }

        // ✅ DON'T cancel alarm when service is killed - let it persist
        Log.d(TAG, "Service destroyed - alarms will continue independently");

        // ✅ Schedule service restart with higher priority
        if (sessionManager != null && sessionManager.isLoggedIn()) {
            Intent restartIntent = new Intent(getApplicationContext(), LocationTrackingService.class);
            PendingIntent restartPendingIntent = PendingIntent.getService(
                    getApplicationContext(),
                    999,
                    restartIntent,
                    PendingIntent.FLAG_IMMUTABLE
            );

            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

            // Use setExactAndAllowWhileIdle for more reliable restart
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 5000, // 5 seconds delay
                        restartPendingIntent
                );
            } else {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 5000,
                        restartPendingIntent
                );
            }

            Log.d(TAG, "Service restart scheduled in 5 seconds");
        }
    }
}