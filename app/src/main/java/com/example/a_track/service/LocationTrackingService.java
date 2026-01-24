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
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
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
    private static final long UPDATE_INTERVAL = 30000; // 30 seconds
    private static final long SYNC_INTERVAL = 120000; // 2 minutes

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private AppDatabase db;
    private SessionManager sessionManager;
    private ExecutorService executorService;
    private Location lastLocation;
    private Handler handler;
    private Runnable locationRunnable;
    private Runnable syncRunnable;
    private PowerManager.WakeLock wakeLock;
    private Location lastSavedLocation;
    private Location stationaryBaseLocation; // Reference point when stationary
    private int stationaryCount = 0; // How many consecutive stationary readings
    private static final float STATIONARY_THRESHOLD = 5.0f; // Consider stationary if moved < 5m
    private static final float MIN_SPEED = 0.5f;

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

        // Acquire wake lock to keep service running even in sleep mode
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ATrack::LocationWakeLock");
        wakeLock.acquire();

        Log.d(TAG, "WakeLock acquired - Service will run in sleep mode");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        setupLocationCallback();
        startLocationUpdates();
        startPeriodicLocationFetch();
        startPeriodicSync(); // Start sync

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
                .setContentTitle("A-Track Active")
                .setContentText("Tracking your location every 30 seconds")
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
                Log.d(TAG, "30 seconds elapsed - fetching and saving location");
                fetchAndSaveLocation();
                handler.postDelayed(this, UPDATE_INTERVAL);
            }
        };
        handler.post(locationRunnable);
        Log.d(TAG, "Periodic location fetch started (every 30 seconds)");
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
        handler.postDelayed(syncRunnable, SYNC_INTERVAL); // First sync after 2 minutes
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

        Location locationToSave;

        // ✅ Determine if stationary
        boolean isStationary = false;
        if (lastSavedLocation != null) {
            float distance = location.distanceTo(lastSavedLocation);
            float speed = location.hasSpeed() ? (location.getSpeed()) : 0;

            // Check if stationary (small movement + low speed)
            if (distance < STATIONARY_THRESHOLD && speed < MIN_SPEED) {
                isStationary = true;
                stationaryCount++;
            } else {
                isStationary = false;
                stationaryCount = 0;
                stationaryBaseLocation = null; // Reset when moving
            }
        }

        // ✅ Handle stationary vs moving
        if (isStationary) {
            // Set base location on first stationary detection
            if (stationaryBaseLocation == null) {
                stationaryBaseLocation = new Location(location);
                Log.d(TAG, "🛑 Now stationary - base location set");
            }

            // Use the base location (no zigzag)
            locationToSave = new Location(stationaryBaseLocation);
            locationToSave.setTime(location.getTime());
            locationToSave.setSpeed(0); // Force speed to 0 when stationary
            locationToSave.setBearing(lastSavedLocation != null ? lastSavedLocation.getBearing() : 0);

            Log.d(TAG, "📍 Stationary (" + stationaryCount + "x) - using stable coordinates");

        } else {
            // Moving - use actual location
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
                // Get next RecNo on background thread
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
                        null,
                        null,
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
                        nextRecNo
                );

                db.locationTrackDao().insert(track);
                lastSavedLocation = new Location(locationToSave);

                Log.d(TAG, "✓ Location saved: RecNo=" + nextRecNo +
                        ", Lat=" + location.getLatitude() +
                        ", Lng=" + location.getLongitude() +
                        ", Speed=" + speedToSave + " km/h" +
                        ", Battery=" + battery + "%");
            } catch (Exception e) {
                Log.e(TAG, "Error saving location: " + e.getMessage());
            }
        });
    }

    private void syncDataToServer() {
        String mobile = sessionManager.getMobileNumber();
        if (mobile == null) return;

        executorService.execute(() -> {
            // ✅ Step 1: Get all unsynced location records
            List<LocationTrack> unsyncedTracks = db.locationTrackDao().getUnsyncedTracks(mobile);

            if (!unsyncedTracks.isEmpty()) {
                int totalRecords = unsyncedTracks.size();

                // ✅ If 20 or fewer records, sync all at once (no batching)
                if (totalRecords <= 20) {
                    Log.d(TAG, "📍 Syncing " + totalRecords + " records (single batch)");
                    syncBatchBlocking(unsyncedTracks);
                }
                // ✅ If more than 20 records, use batch syncing
                else {
                    int BATCH_SIZE = 20; // Sync 20 records at a time
                    int totalBatches = (totalRecords + BATCH_SIZE - 1) / BATCH_SIZE;

                    Log.d(TAG, "📍 Starting batch sync: " + totalRecords + " records in " +
                            totalBatches + " batches");

                    // Sync in batches
                    for (int batchNum = 0; batchNum < totalBatches; batchNum++) {
                        int start = batchNum * BATCH_SIZE;
                        int end = Math.min(start + BATCH_SIZE, totalRecords);
                        List<LocationTrack> batch = unsyncedTracks.subList(start, end);

                        Log.d(TAG, "📤 Syncing batch " + (batchNum + 1) + "/" + totalBatches +
                                ": " + batch.size() + " records");

                        // Sync this batch (blocking)
                        boolean success = syncBatchBlocking(batch);

                        if (!success) {
                            Log.w(TAG, "⚠ Batch " + (batchNum + 1) + " failed, continuing to next batch");
                        }

                        // Small delay between batches to reduce server load
                        if (batchNum < totalBatches - 1) { // Don't delay after last batch
                            try {
                                Thread.sleep(1000); // 1 second between batches
                            } catch (InterruptedException e) {
                                Log.e(TAG, "Batch delay interrupted: " + e.getMessage());
                            }
                        }
                    }

                    Log.d(TAG, "✓ Batch sync completed for all location records");
                }

            } else {
                Log.d(TAG, "No location data to sync, checking for photos...");
            }

            // ✅ Step 2: Sync photos (after all location batches)
            syncPendingPhotos();

            // ✅ Step 3: Clean up old records
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
                    // Mark this batch as synced
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

        // Wait for this batch to complete before returning
        try {
            latch.await(30, TimeUnit.SECONDS); // Max 30 seconds per batch
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
            // ✅ Get all records with unsynced photos using new query
            List<LocationTrack> unsyncedPhotos = db.locationTrackDao().getUnsyncedPhotos(mobile);

            if (unsyncedPhotos.isEmpty()) {
                Log.d(TAG, "✓ No pending photos to sync");
                return;
            }

            Log.d(TAG, "📸 Found " + unsyncedPhotos.size() + " photos to sync");

            for (LocationTrack track : unsyncedPhotos) {
                File photoFile = new File(track.getPhotoPath());

                // Check if file exists
                if (!photoFile.exists()) {
                    Log.w(TAG, "⚠ Photo file missing, marking as synced: " + track.getPhotoPath());
                    // Mark as synced anyway to prevent repeated attempts
                    db.locationTrackDao().markPhotoAsSynced(track.getId());
                    continue;
                }

                Log.d(TAG, "📤 Uploading photo: " + photoFile.getName() + " (" + photoFile.length() + " bytes)");

                // Upload photo
                ApiService.uploadPhoto(track, photoFile, new ApiService.PhotoUploadCallback() {
                    @Override
                    public void onSuccess(String photoPath) {
                        executorService.execute(() -> {
                            // ✅ Mark photo as synced
                            db.locationTrackDao().markPhotoAsSynced(track.getId());
                            Log.d(TAG, "✓ Photo synced successfully: " + photoFile.getName());

                            // ✅ Delete local photo after successful upload
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
                        // Don't mark as synced - will retry on next sync
                    }
                });

                // Add small delay between uploads to avoid overwhelming server
                try {
                    Thread.sleep(1000); // 1 second delay between photos
                } catch (InterruptedException e) {
                    Log.e(TAG, "Sleep interrupted: " + e.getMessage());
                }
            }
        });
    }

    private void cleanupOldRecords() {
        executorService.execute(() -> {
            // Get today's start time (00:00:00)
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
            calendar.set(java.util.Calendar.MINUTE, 0);
            calendar.set(java.util.Calendar.SECOND, 0);
            calendar.set(java.util.Calendar.MILLISECOND, 0);
            long todayStartMillis = calendar.getTimeInMillis();

            Log.d(TAG, "🧹 Cleaning up old records before: " + new java.util.Date(todayStartMillis));

            // ✅ This now only deletes records where BOTH location AND photo are synced
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
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.d(TAG, "Service onDestroy - Stopping location tracking");

        // Stop periodic location fetching
        if (handler != null && locationRunnable != null) {
            handler.removeCallbacks(locationRunnable);
        }

        // Stop sync
        if (handler != null && syncRunnable != null) {
            handler.removeCallbacks(syncRunnable);
        }

        // Stop location updates
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        // Release WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released");
        }

        // Shutdown executor
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }

        Log.d(TAG, "Service stopped and cleaned up");
    }
}