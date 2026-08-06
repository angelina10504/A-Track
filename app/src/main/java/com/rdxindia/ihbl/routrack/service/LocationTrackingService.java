package com.rdxindia.ihbl.routrack.service;

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
import java.util.Locale;
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
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.AlarmManager;

import androidx.annotation.RequiresApi;
import com.rdxindia.ihbl.routrack.AlarmReceiver;
import com.rdxindia.ihbl.routrack.DataTypes;
import com.rdxindia.ihbl.routrack.DashboardActivity;
import com.rdxindia.ihbl.routrack.R;
import com.rdxindia.ihbl.routrack.database.AppDatabase;
import com.rdxindia.ihbl.routrack.database.LocationTrack;
import com.rdxindia.ihbl.routrack.utils.ApiService;
import com.rdxindia.ihbl.routrack.utils.SessionManager;
import com.rdxindia.ihbl.routrack.utils.DeviceInfoHelper;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.rdxindia.ihbl.routrack.workers.ServiceWatchdogWorker;
import com.rdxindia.ihbl.routrack.utils.NetworkMonitor;

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
    private Handler healthCheckHandler;
    private Runnable syncRunnable;
    private Runnable alarmRunnable;
    private Random random;
    private PowerManager.WakeLock wakeLock;
    private Location lastSavedLocation;
    private long lastSavedTime = 0;
    private Location stationaryBaseLocation;
    private LocationListener gpsTimeListener;
    private int stationaryCount = 0;
    private static final float STATIONARY_THRESHOLD = 10.0f;
    private static final float MIN_SPEED = 0.5f;
    // Silent health logging — replaces the old pop-up safety alarm. Every 20 min we run
    // the health checks with NO notification/sound/vibration/dialog and write a record
    // to the server ONLY when something is wrong.
    private static final long HEALTH_LOG_INTERVAL_MS = 20 * 60 * 1000; // 20 minutes

    // Distance-based tracking thresholds
    private static final float DISTANCE_THRESHOLD_METERS = 50.0f;
    private static final long HEARTBEAT_INTERVAL_MS = 10 * 60 * 1000; // 10 min stationary heartbeat
    private static final long SAFETY_NET_INTERVAL_MS = 15 * 60 * 1000; // 15 min force save
    private static final float MOVING_SPEED_THRESHOLD = 0.5f; // m/s — below this = stationary

    // Live notification status — refreshed via NotificationManager.notify(), never
    // by restarting the service. 60s cadence is cheap (one COUNT query + one notify).
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 60 * 1000;
    private volatile long lastFixTimeMs = 0;   // stamped on every usable GPS fix
    private volatile long lastSyncTimeMs = 0;  // stamped on server-confirmed sync
    private Runnable notificationUpdateRunnable;

    // GPS noise filtering — a stationary phone can drift 100m+ (multipath/weak signal)
    // and fake the 50m movement trigger. Filters apply ONLY to the movement case so
    // heartbeat/safety-net still fire indoors where accuracy is permanently poor.
    private static final float MAX_MOVEMENT_ACCURACY_METERS = 35.0f; // worse fix can't claim a move
    private static final long MOVE_CONFIRM_WINDOW_MS = 2 * 60 * 1000; // pending move expires after this
    private Location pendingMoveLocation; // first >50m fix, awaiting a confirming second fix
    private long pendingMoveTime = 0;

    // Teleport guard — a "move" implying a speed no field worker can achieve is a bad fix
    // (Wi-Fi/cell positioning can report a confident-looking accuracy while being km off).
    // Only applied to short gaps; after a long blackout a big genuine jump is expected.
    private static final float MAX_PLAUSIBLE_SPEED_KMH = 150.0f;
    private static final long TELEPORT_CHECK_MAX_GAP_MS = 5 * 60 * 1000;

    // Timestamp of the last fix good enough to trust (accuracy within the movement gate).
    // If this goes stale the phone has lost satellite lock and is running on coarse
    // cell-tower positions — the office needs to know, so it feeds the health check.
    private static final long NO_GNSS_ALERT_MS = 15 * 60 * 1000;
    private volatile long lastGoodFixTimeMs = 0;

    // ✅ Track if we've already logged install/reboot for this session

    private boolean hasLoggedInstallReboot = false;

    // Pending kill-reason record — set by logPreviousExitReason(), flushed on first real GPS fix
    private volatile String pendingKillReason = null;
    private volatile long   pendingKillTime   = 0;

    private NetworkMonitor networkMonitor;

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

        // CRITICAL (Android 12+): startForeground() MUST be the very first real work.
        // startForegroundService() (used by AlarmReceiver to cold-start us) gives ~5s to
        // reach foreground; on a slow device, doing GPS/DB/SharedPreferences/crash-log/
        // wakelock work first blows that deadline and the OS kills us with
        // ForegroundServiceDidNotStartInTimeException (the crash blamed on AlarmReceiver.java:47).
        // The initial notification needs no DB/SessionManager — counts render as "—".
        // Everything heavy is deferred to AFTER we are safely in the foreground.
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        // Baseline for the no-GNSS check — without this the first health run would
        // instantly report "no satellite lock" before any fix has had a chance to arrive.
        lastGoodFixTimeMs = System.currentTimeMillis();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        db = AppDatabase.getInstance(this);
        sessionManager = new SessionManager(this);
        executorService = Executors.newSingleThreadExecutor();

        // Restore last tracked location from SharedPreferences (survives service kills)
        long savedTime = sessionManager.getLastTrackedTime();
        if (savedTime > 0) {
            lastSavedLocation = new Location("");
            lastSavedLocation.setLatitude(sessionManager.getLastTrackedLat());
            lastSavedLocation.setLongitude(sessionManager.getLastTrackedLng());
            lastSavedTime = savedTime;
            Log.d(TAG, "Restored last tracked state: lat=" + lastSavedLocation.getLatitude()
                    + ", lng=" + lastSavedLocation.getLongitude()
                    + ", age=" + ((System.currentTimeMillis() - lastSavedTime) / 1000) + "s ago");
        }

        networkMonitor = new NetworkMonitor(this, sessionManager, this::stopSelf);
        networkMonitor.register();
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

        // Notification channel + startForeground() already done at the very top of onCreate().
        startNotificationUpdates();  // live foreground status notification (refreshes every 60s)

        acquireGpsTimeOffset();      // accurate GPS satellite time (~10-30s), overwrites server time

        // Gate location recording behind server-time calibration so the very first record
        // carries a corrected timestamp (not raw device clock).  Max delay ≈ 10 s (HTTP timeout).
        // Even on failure the callback fires and location updates begin with device-clock fallback.
        startPeriodicSync();         // safe to start immediately — only syncs, never saves records
        calibrateTimeFromServer(() -> {
            // Runs on main thread after server responds (or times out)
            setupLocationCallback();
            startLocationUpdates();
            // NOTE: No startPeriodicLocationFetch() — location saves are now
            // triggered by the callback using distance/heartbeat/safety-net logic.
        });

        scheduleWatchdog();          // invisible WorkManager restart safety net (every 15 min)

        // Silent health logging (replaces the old pop-up safety alarm).
        // Every 20 min we run the SAME checks the alarm used to, but with NO notification,
        // sound, vibration, or dialog. A record is written to the server ONLY when a
        // tracking-critical parameter is wrong (GPS off, BG-location off, no internet,
        // unsynced queue > 30, battery-optimised, background data blocked, notifications off).
        healthCheckHandler = new Handler(Looper.getMainLooper());
        Runnable healthLogRunnable = new Runnable() {
            @Override
            public void run() {
                logHealthIfProblem();
                healthCheckHandler.postDelayed(this, HEALTH_LOG_INTERVAL_MS);
            }
        };
        healthCheckHandler.postDelayed(healthLogRunnable, HEALTH_LOG_INTERVAL_MS);

        Log.d(TAG, "─── onCreate() complete — service running (silent health logging, no alerts) ───");
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
                    if (ourFrame == null && line.contains("com.rdxindia.ihbl.routrack")) {
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
        // Initial notification for startForeground() — counts not known yet
        return buildStatusNotification(-1, -1);
    }

    /**
     * Builds the foreground notification showing live tracking health.
     * Pass -1 for counts that aren't known (renders as "—").
     */
    private Notification buildStatusNotification(int pendingCount, int totalCount) {
        Intent notificationIntent = new Intent(this, DashboardActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        );

        boolean gpsOn = false;
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            gpsOn = lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {
        }

        String fixAge = lastFixTimeMs > 0 ? formatAge(lastFixTimeMs) : "waiting…";
        String syncAge = lastSyncTimeMs > 0 ? formatAge(lastSyncTimeMs) : "not yet";
        String pending = pendingCount >= 0 ? String.valueOf(pendingCount) : "—";
        String total = totalCount >= 0 ? String.valueOf(totalCount) : "—";

        // One-line summary (collapsed view)
        String summary = "GPS " + (gpsOn ? "on" : "OFF")
                + " · fix " + fixAge + " · " + pending + " pending";

        // Full status (expanded view)
        String details = "Tracking: Active"
                + "\nGPS: " + (gpsOn ? "Available" : "UNAVAILABLE")
                + "\nLast fix: " + fixAge
                + "\nPending upload: " + pending
                + "\nLast sync: " + syncAge
                + "\nStored records: " + total;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ROU Track Active")
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(details))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)   // updates must never re-alert/flash
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /** "45s ago" / "12 min ago" / "1h 05m ago" */
    private static String formatAge(long timeMs) {
        long sec = Math.max(0, (System.currentTimeMillis() - timeMs) / 1000);
        if (sec < 60) return sec + "s ago";
        long min = sec / 60;
        if (min < 60) return min + " min ago";
        return String.format(Locale.US, "%dh %02dm ago", min / 60, min % 60);
    }

    /**
     * Refreshes the foreground notification every 60s with live status via
     * NotificationManager.notify() — the service itself is never restarted.
     * DB counts run on their own short-lived thread so they can't queue behind
     * executorService (which sync deliberately blocks for up to 30s per batch).
     */
    private void startNotificationUpdates() {
        notificationUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                refreshNotification();
                handler.postDelayed(this, NOTIFICATION_UPDATE_INTERVAL_MS);
            }
        };
        handler.postDelayed(notificationUpdateRunnable, NOTIFICATION_UPDATE_INTERVAL_MS);
    }

    private void refreshNotification() {
        new Thread(() -> {
            int pending = -1;
            int total = -1;
            try {
                String mobile = sessionManager.getMobileNumber();
                if (mobile != null) {
                    pending = db.locationTrackDao().countUnsynced(mobile);
                    total = db.locationTrackDao().countAll(mobile);
                }
            } catch (Exception e) {
                Log.w(TAG, "Notification count query failed: " + e.getMessage());
            }

            final int p = pending;
            final int t = total;
            handler.post(() -> {
                try {
                    NotificationManager nm =
                            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.notify(NOTIFICATION_ID, buildStatusNotification(p, t));
                    }
                } catch (Exception e) {
                    // POST_NOTIFICATIONS revoked etc. — never let a status update crash tracking
                    Log.w(TAG, "Notification update failed: " + e.getMessage());
                }
            });
        }).start();
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
                    if (location == null) continue;

                    lastLocation = location;

                    // Skip (0,0) — FusedLocationProvider can return "Null Island" when cache is cold
                    if (location.getLatitude() == 0.0 && location.getLongitude() == 0.0) {
                        Log.w(TAG, "Skipping (0,0) location");
                        continue;
                    }

                    lastFixTimeMs = System.currentTimeMillis();

                    // Sync in-memory state if an external save occurred (photo/video/alarm)
                    long externalTime = sessionManager.getLastTrackedTime();
                    if (externalTime > lastSavedTime) {
                        lastSavedLocation = new Location("");
                        lastSavedLocation.setLatitude(sessionManager.getLastTrackedLat());
                        lastSavedLocation.setLongitude(sessionManager.getLastTrackedLng());
                        lastSavedTime = externalTime;
                        pendingMoveLocation = null; // anchor changed — stale move candidate is meaningless
                        Log.d(TAG, "Synced last tracked state from external save (photo/video/alarm)");
                    }

                    // ── Distance & time calculations ─────────────────────────────
                    float distanceMoved = lastSavedLocation != null
                            ? location.distanceTo(lastSavedLocation)
                            : Float.MAX_VALUE;
                    long timeSinceLastSave = System.currentTimeMillis() - lastSavedTime;

                    boolean shouldSave = false;
                    String saveReason = "";

                    // Is this fix trustworthy enough to move the anchor? Real GNSS reports
                    // 3-30m; coarse cell-tower fallback reports 500-1500m.
                    boolean accuracyOk = !location.hasAccuracy()
                            || location.getAccuracy() <= MAX_MOVEMENT_ACCURACY_METERS;
                    if (accuracyOk) {
                        lastGoodFixTimeMs = System.currentTimeMillis();
                    }

                    // Mock location → always save (datatype=8 handled by determineDatatype)
                    DeviceInfoHelper tempDeviceInfo = new DeviceInfoHelper(LocationTrackingService.this);
                    if (tempDeviceInfo.isMockLocation(location)) {
                        shouldSave = true;
                        saveReason = "MOCK_LOCATION";
                    }
                    // Case A: First record ever
                    else if (lastSavedLocation == null) {
                        shouldSave = true;
                        saveReason = "FIRST_RECORD";
                    }
                    else {
                        // Case B: Moved more than 50m (distance trigger).
                        // GPS drift on a stationary phone can fake this, so a move only
                        // counts when (a) the fix's accuracy is good enough to be trusted,
                        // (b) TWO consecutive fixes agree we're >50m away — a single
                        // drift spike snaps back on the next fix and gets discarded — and
                        // (c) the implied speed is physically plausible.
                        if (distanceMoved >= DISTANCE_THRESHOLD_METERS) {
                            boolean pendingValid = pendingMoveLocation != null
                                    && (System.currentTimeMillis() - pendingMoveTime) <= MOVE_CONFIRM_WINDOW_MS;

                            // Implied speed since the last saved anchor. Only meaningful for
                            // short gaps — after a long blackout a large jump is legitimate.
                            boolean teleport = false;
                            if (timeSinceLastSave > 0 && timeSinceLastSave <= TELEPORT_CHECK_MAX_GAP_MS) {
                                float impliedKmh = (distanceMoved / 1000f)
                                        / (timeSinceLastSave / 3600000f);
                                teleport = impliedKmh > MAX_PLAUSIBLE_SPEED_KMH;
                                if (teleport) {
                                    Log.d(TAG, "Move rejected: implausible speed "
                                            + Math.round(impliedKmh) + " km/h (distance="
                                            + distanceMoved + "m in " + (timeSinceLastSave / 1000) + "s)");
                                }
                            }

                            if (!accuracyOk) {
                                pendingMoveLocation = null;
                                Log.d(TAG, "Move rejected: accuracy " + location.getAccuracy()
                                        + "m too poor (distance=" + distanceMoved + "m)");
                            } else if (teleport) {
                                pendingMoveLocation = null;
                            } else if (pendingValid) {
                                shouldSave = true;
                                saveReason = "DISTANCE_MOVED";
                            } else {
                                pendingMoveLocation = location;
                                pendingMoveTime = System.currentTimeMillis();
                                Log.d(TAG, "Move pending confirmation: distance=" + distanceMoved
                                        + "m, accuracy=" + (location.hasAccuracy() ? location.getAccuracy() : -1) + "m");
                            }
                        } else {
                            // Fix snapped back under 50m — the earlier far fix was drift, forget it
                            pendingMoveLocation = null;
                        }

                        // Time-based fallbacks.
                        // CRITICAL: these are evaluated even when the distance trigger above
                        // REJECTED the fix. They used to sit in the same else-if chain, so a
                        // phone stuck on far-away low-accuracy cell-tower fixes never reached
                        // them and tracking went completely silent — the office saw a blank
                        // trace instead of an approximate position. A tagged low-quality point
                        // (see textMsg "Acc:") is far more useful than no point at all.
                        if (!shouldSave) {
                            if (timeSinceLastSave >= HEARTBEAT_INTERVAL_MS
                                    && location.getSpeed() < MOVING_SPEED_THRESHOLD) {
                                shouldSave = true;
                                saveReason = "STATIONARY_HEARTBEAT";
                            } else if (timeSinceLastSave >= SAFETY_NET_INTERVAL_MS) {
                                shouldSave = true;
                                saveReason = "SAFETY_NET";
                            }
                        }
                    }

                    if (shouldSave) {
                        pendingMoveLocation = null; // saved — next move measures from the new anchor
                        Log.d(TAG, "Saving record: " + saveReason
                                + " | distance=" + distanceMoved
                                + "m | timeSince=" + (timeSinceLastSave / 1000) + "s"
                                + " | " + describeFixQuality(location));
                        saveLocationToDatabase(location);
                    }
                }
            }
        };
    }

    private void startLocationUpdates() {
        Log.d(TAG, "Starting continuous location updates");

        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 10000)  // check every 10s, we filter ourselves
                .setMinUpdateDistanceMeters(0)            // no OS-level distance filter
                .setMinUpdateIntervalMillis(5000)          // min 5s between updates
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
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } else {
            NetworkInfo networkInfo = cm.getActiveNetworkInfo();
            return networkInfo != null && networkInfo.isConnected();
        }
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

                // locationToSave (NOT raw location): when stationary this is the anchored
                // base position, so a parked phone records one stable coordinate instead
                // of GPS jitter. Previously only lastSavedLocation used the anchor while
                // the record itself saved the raw fix — the smoothing never reached the CSV.
                LocationTrack track = new LocationTrack(
                        mobileNumber,
                        locationToSave.getLatitude(),
                        locationToSave.getLongitude(),
                        speedToSave,
                        locationToSave.getBearing(),
                        currentTime,
                        sessionId,
                        battery,
                        null,  // photoPath
                        null,  // videoPath
                        // textMsg — fix quality first so bad points are obvious in the CSV
                        describeFixQuality(location) + " | " + getDeviceHealthString(mobileNumber),
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
                lastSavedTime = System.currentTimeMillis();
                sessionManager.saveLastTrackedLocation(
                        locationToSave.getLatitude(), locationToSave.getLongitude(), lastSavedTime);

                // ✅ Log datatype info
                String datatypeStr = getDatatypeString(datatype);
                Log.d(TAG, "✓ Location saved: RecNo=" + nextRecNo +
                        ", Datatype=" + datatype + " (" + datatypeStr + ")" +
                        ", Lat=" + locationToSave.getLatitude() +
                        ", Lng=" + locationToSave.getLongitude() +
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
     * Compact fix-quality tag, e.g. "Acc:12m Src:gps" or "Acc:1000m Src:network".
     *
     * This rides along in textMsg (already synced to the server's TextMsg column) rather
     * than a new DB column, because the Room config uses fallbackToDestructiveMigration() —
     * bumping the schema version would WIPE every field device's database, including
     * unsynced records. Accuracy is the single most important diagnostic: without it a
     * garbage cell-tower point is indistinguishable from a real GPS point on the map.
     */
    private String describeFixQuality(Location location) {
        if (location == null) return "Acc:NA Src:none";
        String acc = location.hasAccuracy()
                ? Math.round(location.getAccuracy()) + "m"
                : "NA";
        String src = location.getProvider() != null ? location.getProvider() : "unknown";
        return "Acc:" + acc + " Src:" + src;
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

        // Gnss — "NA" means no fix accurate enough to trust for 15+ min, i.e. the phone
        // has lost satellite lock and is running on coarse cell-tower positions.
        String gnss = isGnssStale() ? "NA" : "Ok";

        return "Loc:" + loc + ", BgLoc:" + bgLoc + ", Net:" + net + ", Q:" + q
                + ", BatOpt:" + batOpt + ", PlayPro:" + playPro
                + ", BkUsg:" + bkUsg + ", Notif:" + notif
                + ", Gnss:" + gnss;
    }

    private void syncDataToServer() {
        String verificationStatus = sessionManager.getVerificationStatus();
        if (SessionManager.STATUS_PENDING.equals(verificationStatus)) {
            Log.d(TAG, "Sync skipped - awaiting verification");
            return;
        }
        if (SessionManager.STATUS_REJECTED.equals(verificationStatus)) {
            stopSelf();
            return;
        }

        String mobile = sessionManager.getMobileNumber();
        if (mobile == null) return;

        executorService.execute(() -> {
            List<LocationTrack> unsyncedTracks = db.locationTrackDao().getUnsyncedTracks(mobile);

            if (!unsyncedTracks.isEmpty()) {
                int totalRecords = unsyncedTracks.size();

                final int BATCH_SIZE = 10;
                if (totalRecords <= BATCH_SIZE) {
                    Log.d(TAG, "📍 Syncing " + totalRecords + " records (single batch)");
                    syncBatchBlocking(unsyncedTracks);
                } else {
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

                // Poison-row self-heal. The server responded, so any batch row it did NOT
                // confirm was actively rejected (inserted + duplicate rows are all echoed
                // in syncedRecNos). The usual permanent cause is an over-long textMsg
                // ("Data too long"), so trim it in the DB and let the next cycle retry.
                // The DAO query is a no-op for rows whose textMsg is already short, and
                // transient failures never reach here (they go to onFailure).
                if (confirmedIds.size() < batch.size()) {
                    List<Integer> unconfirmedIds = new ArrayList<>();
                    for (LocationTrack t : batch) {
                        if (!confirmedIds.contains(t.getId())) {
                            unconfirmedIds.add(t.getId());
                        }
                    }
                    int healed = db.locationTrackDao()
                            .capTextMsg(unconfirmedIds, LocationTrack.TEXT_MSG_MAX_LEN);
                    if (healed > 0) {
                        Log.w(TAG, "🩹 Self-heal: trimmed over-long textMsg on " + healed
                                + " stuck record(s); will retry next sync");
                    }
                }

                lastSyncTimeMs = System.currentTimeMillis();
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
            long offset = com.rdxindia.ihbl.routrack.utils.ApiService.fetchServerTimeOffsetMs();
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

        // CRITICAL (Android 12+): every Context.startForegroundService() call makes a
        // promise that this service will call startForeground() within ~5s. onCreate()
        // only runs on the FIRST start — so when AlarmReceiver re-starts us via
        // startForegroundService() (ALARM_DISMISSED / timeout / restart) while we are
        // ALREADY running, onCreate() does NOT run, the promise is never fulfilled, and
        // the OS kills us with ForegroundServiceDidNotStartInTimeException
        // (seen at AlarmReceiver.java:47). Calling startForeground() here satisfies the
        // promise on every start; it is idempotent/harmless when already foreground.
        try {
            startForeground(NOTIFICATION_ID, createNotification());
        } catch (Exception e) {
            Log.e(TAG, "startForeground() in onStartCommand failed: " + e.getMessage());
        }

        if (intent != null) {
            String action = intent.getAction();
            Log.d(TAG, "action=" + (action != null ? action : "null (normal start)"));

            // Alarm-related actions (RESCHEDULE_ALARM / ALARM_DISMISSED) removed —
            // the pop-up safety alarm no longer exists. Action kept only for logging.
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

    /**
     * Silent health logging — replaces the old pop-up safety alarm. Runs the same checks
     * the alarm used, but with NO notification/sound/vibration/dialog. Writes a
     * HEALTH_ALERT record (which syncs to the server) ONLY when a problem is found.
     * DB work runs on the service executor, so this is safe to call from the main thread.
     */
    private void logHealthIfProblem() {
        final String mobileNumber = sessionManager.getMobileNumber();
        final String sessionId = sessionManager.getSessionId();
        if (mobileNumber == null || sessionId == null) return;

        executorService.execute(() -> {
            try {
                if (!hasHealthProblem(mobileNumber)) {
                    Log.d(TAG, "✓ Silent health check: all OK — nothing logged");
                    return;
                }

                LocationTrack lastTrack = db.locationTrackDao().getLastLocationSync(mobileNumber);
                double lat = lastTrack != null ? lastTrack.getLatitude() : 0.0;
                double lng = lastTrack != null ? lastTrack.getLongitude() : 0.0;
                float speed = lastTrack != null ? lastTrack.getSpeed() : 0.0f;
                float angle = lastTrack != null ? lastTrack.getAngle() : 0.0f;

                DeviceInfoHelper deviceInfo = new DeviceInfoHelper(this);
                int lastRecNo = sessionManager.getLastRecNo();
                if (lastRecNo == 0) {
                    lastRecNo = db.locationTrackDao().getLastRecNo(mobileNumber);
                }
                int nextRecNo = lastRecNo + 1;
                sessionManager.saveLastRecNo(nextRecNo);

                String textMsg = "HEALTH_ALERT | " + getDeviceHealthString(mobileNumber);

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
                        nextRecNo, DataTypes.HEALTH_ALERT
                );

                db.locationTrackDao().insert(track);
                Log.d(TAG, "⚠️ Silent health alert logged (datatype=" + DataTypes.HEALTH_ALERT + "): " + textMsg);

            } catch (Exception e) {
                Log.e(TAG, "logHealthIfProblem error: " + e.getMessage());
            }
        });
    }

    /**
     * True when no fix good enough to trust has arrived for {@link #NO_GNSS_ALERT_MS}.
     * Means the phone has lost satellite lock and the fused provider is falling back to
     * coarse cell-tower positions (accuracy 500-1500m) — the cause of erratic traces.
     */
    private boolean isGnssStale() {
        return lastGoodFixTimeMs > 0
                && (System.currentTimeMillis() - lastGoodFixTimeMs) >= NO_GNSS_ALERT_MS;
    }

    /**
     * True if ANY tracking-critical health parameter is bad. Mirrors exactly the
     * "Report to Office" rule the old AlarmDialogActivity used (Play Protect is NOT
     * counted as a problem).
     */
    private boolean hasHealthProblem(String mobileNumber) {
        DeviceInfoHelper di = new DeviceInfoHelper(this);

        boolean gpsOff   = !"1".equals(di.getGpsState());
        boolean bgLocOff = !DeviceInfoHelper.hasAllTheTimeLocationPermission(this);
        boolean netOff   = !"1".equals(di.getInternetState());

        boolean batOptimised = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            batOptimised = pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        boolean bkgndBlocked = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            bkgndBlocked = cm != null && cm.getRestrictBackgroundStatus()
                    == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
        }

        boolean notifOff = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifOff = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            notifOff = nm != null && !nm.areNotificationsEnabled();
        }

        int q = 0;
        try {
            q = db.locationTrackDao().getUnsyncedCount(mobileNumber);
        } catch (Exception ignored) {
        }

        return gpsOff || bgLocOff || netOff || q > 30
                || batOptimised || bkgndBlocked || notifOff
                || isGnssStale();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        isRunning = false;

        if (networkMonitor != null) networkMonitor.unregister();
        Log.d(TAG, "Service onDestroy - Stopping location tracking");

        hasLoggedInstallReboot = false;

        if (handler != null && syncRunnable != null) {
            handler.removeCallbacks(syncRunnable);
        }

        if (handler != null && notificationUpdateRunnable != null) {
            handler.removeCallbacks(notificationUpdateRunnable);
        }

        if (healthCheckHandler != null) {
            healthCheckHandler.removeCallbacksAndMessages(null);
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

            // Inexact restart — does NOT require SCHEDULE_EXACT_ALARM (which we no longer
            // hold). A few seconds of Doze slack is fine; the WorkManager watchdog (15 min)
            // and START_STICKY are the other restart safety nets.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 5000, // ~5 seconds delay
                        restartPendingIntent
                );
            } else {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 5000,
                        restartPendingIntent
                );
            }

            Log.d(TAG, "Service restart scheduled in ~5 seconds (inexact)");
        }
    }
}