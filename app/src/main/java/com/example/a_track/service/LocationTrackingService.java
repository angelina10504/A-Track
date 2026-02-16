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
import android.app.AlarmManager;
import android.app.PendingIntent;
import com.example.a_track.AlarmDialogActivity;
import com.example.a_track.AlarmReceiver;
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

import java.io.File;
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
    private static final long SYNC_INTERVAL = 120000; // 2 minutes

    // ✅ Datatype constants
    private static final int DATATYPE_INSTALL = 0;
    private static final int DATATYPE_REBOOT = 1;
    private static final int DATATYPE_NORMAL = 2;
    private static final int DATATYPE_MOCK = 8;
    private static final int DATATYPE_PHOTO = 50;  // ✅ NEW
    private static final int DATATYPE_VIDEO = 60;  // ✅ NEW

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private AppDatabase db;
    private SessionManager sessionManager;
    private ExecutorService executorService;
    private Location lastLocation;
    private Handler handler;
    private Handler alarmHandler;
    private Runnable locationRunnable;
    private Runnable syncRunnable;
    private Runnable alarmRunnable;
    private Random random;
    private PowerManager.WakeLock wakeLock;
    private Location lastSavedLocation;
    private Location stationaryBaseLocation;
    private int stationaryCount = 0;
    private static final float STATIONARY_THRESHOLD = 5.0f;
    private static final float MIN_SPEED = 0.5f;
    private static final int MIN_ALARM_INTERVAL = 15 * 60 * 1000; // 15 minutes
    private static final int MAX_ALARM_INTERVAL = 25 * 60 * 1000; // 25 minutes

    // ✅ Track if we've already logged install/reboot for this session
    private boolean hasLoggedInstallReboot = false;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public LocationTrackingService getService() {
            return LocationTrackingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d(TAG, "Service onCreate - Initializing location tracking");

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        db = AppDatabase.getInstance(this);
        sessionManager = new SessionManager(this);
        executorService = Executors.newSingleThreadExecutor();
        handler = new Handler(Looper.getMainLooper());

        // Acquire wake lock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ROU Track::LocationWakeLock");
        wakeLock.acquire();

        Log.d(TAG, "WakeLock acquired - Service will run in sleep mode");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        setupLocationCallback();
        startLocationUpdates();
        startPeriodicLocationFetch();
        startPeriodicSync();

        // Initialize alarm
        random = new Random();
        scheduleNextAlarm();

        Log.d(TAG, "Alarm system initialized");

        Log.d(TAG, "Service started successfully");
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
                handler.postDelayed(this, SYNC_INTERVAL);
            }
        };
        handler.postDelayed(syncRunnable, SYNC_INTERVAL);
        Log.d(TAG, "Periodic sync started (every 2 minutes)");
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
            if (location != null) {
                Log.d(TAG, "Fetched location for time-based save: Lat=" + location.getLatitude() +
                        ", Lng=" + location.getLongitude());
                saveLocationToDatabase(location);
                lastLocation = location;
            } else {
                Log.w(TAG, "Location is null, using last known location");
                if (lastLocation != null) {
                    saveLocationToDatabase(lastLocation);
                } else {
                    Log.e(TAG, "No location available to save");
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

        // Get data that's safe on main thread
        long currentTime = location.getTime();
        int battery = getBatteryLevel();
        float speedToSave = isStationary ? 0 : (location.hasSpeed() ? (location.getSpeed() * 3.6f) : 0);

        DeviceInfoHelper deviceInfo = new DeviceInfoHelper(this);
        long mobileTime = deviceInfo.getMobileTime();
        int nss = deviceInfo.getNetworkSignalStrength();

        // Move database operations to background thread
        executorService.execute(() -> {
            try {
                int lastRecNo = db.locationTrackDao().getLastRecNo(mobileNumber);
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
                        null,  // textMsg
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

            // Check for device reboot
            if (sessionManager.hasDeviceRebooted()) {
                Log.i(TAG, "🔑 NEW LOGIN DETECTED - datatype = 1");
                sessionManager.markLoginLogged();
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
            case DATATYPE_INSTALL: return "Install";
            case DATATYPE_REBOOT: return "Reboot";
            case DATATYPE_NORMAL: return "Normal";
            case DATATYPE_MOCK: return "Mock Location";
            case DATATYPE_PHOTO: return "Photo";      // ✅ NEW
            case DATATYPE_VIDEO: return "Video";      // ✅ NEW
            default: return "Unknown";
        }
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
            public void onSuccess(int syncedCount, List<Integer> syncedIds) {
                Log.d(TAG, "✓ Batch synced: " + syncedCount + " records");

                executorService.execute(() -> {
                    if (!syncedIds.isEmpty()) {
                        db.locationTrackDao().markAsSynced(syncedIds);
                        Log.d(TAG, "✓ Marked " + syncedIds.size() + " records as synced");
                    }
                    success[0] = true;
                    latch.countDown();
                });
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

    private void cleanupOldRecords() {
        executorService.execute(() -> {
            java.util.Calendar calendar = java.util.Calendar.getInstance();
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
        Log.d(TAG, "Service onStartCommand");

        if (intent != null && "RESCHEDULE_ALARM".equals(intent.getAction())) {
            Log.d(TAG, "⏰ Rescheduling next alarm after user response");
            scheduleNextAlarm();
        }

        return START_STICKY;
    }

    private void scheduleNextAlarm() {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                Log.d(TAG, "✓ Can schedule exact alarms");
            } else {
                Log.e(TAG, "✗ CANNOT schedule exact alarms!");
            }
        }

        alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent
        );

        Log.d(TAG, "✓ Alarm scheduled via BroadcastReceiver");
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

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.d(TAG, "Service onDestroy - Stopping location tracking");

        hasLoggedInstallReboot = false;

        if (handler != null && locationRunnable != null) {
            handler.removeCallbacks(locationRunnable);
        }

        if (handler != null && syncRunnable != null) {
            handler.removeCallbacks(syncRunnable);
        }

        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }

        // DON'T cancel alarm - let it persist
        // Alarm will restart the service when it fires

        Log.d(TAG, "Service stopped - alarm will continue");

        // ✅ Cancel scheduled alarm on logout
        Intent intent = new Intent(this, AlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                123,
                intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );

        if (pendingIntent != null) {
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            alarmManager.cancel(pendingIntent);
            Log.d(TAG, "Alarm cancelled on service destroy");
        }

        Log.d(TAG, "Service stopped and cleaned up");
    }
}