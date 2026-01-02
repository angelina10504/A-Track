package com.example.a_track.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
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
import com.example.a_track.utils.SessionManager;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.IntentFilter;
import android.os.BatteryManager;
import com.example.a_track.utils.ApiService;


public class LocationTrackingService extends Service {

    private static final String TAG = "LocationTrackingService";
    private static final String CHANNEL_ID = "LocationTrackingChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final long UPDATE_INTERVAL = 30000; // 30 seconds
    //private static final float MIN_DISTANCE_METERS = 25.0f; // 25 meters

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private AppDatabase db;
    private SessionManager sessionManager;
    private ExecutorService executorService;
    private Location lastLocation;
    //private Location lastSavedLocation; // Track last saved location for distance calculation
    private long lastSaveTime; // Track last save time
    private Handler handler;
    private Runnable locationRunnable;
    private PowerManager.WakeLock wakeLock;

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
        lastSaveTime = System.currentTimeMillis();

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

        // Sync to server every 2 minutes
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                syncDataToServer();
                handler.postDelayed(this, 120000); // 2 minutes
            }
        }, 120000);


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
                        // Remove distance check - only time-based saving now
                    }
                }
            }
        };
    }

    private void startLocationUpdates() {
        Log.d(TAG, "Starting continuous location updates");

        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 5000) // Get updates every 5 seconds
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
                // Fetch and save location every 30 seconds regardless
                Log.d(TAG, "30 seconds elapsed - fetching and saving location");
                fetchAndSaveLocation();

                handler.postDelayed(this, UPDATE_INTERVAL); // Run every 30 seconds
            }
        };
        handler.post(locationRunnable);
        Log.d(TAG, "Periodic location fetch started (every 30 seconds)");
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

        long currentTime = System.currentTimeMillis();
        int battery = getBatteryLevel(); // Get battery level

        LocationTrack track = new LocationTrack(
                mobileNumber,
                location.getLatitude(),
                location.getLongitude(),
                location.getSpeed(),
                location.getBearing(),
                currentTime,
                sessionId,
                battery // Add battery level
        );

        executorService.execute(() -> {
            try {
                db.locationTrackDao().insert(track);

                Log.d(TAG, "✓ Location saved: Lat=" + location.getLatitude() +
                        ", Lng=" + location.getLongitude() +
                        ", Battery=" + battery + "%");
            } catch (Exception e) {
                Log.e(TAG, "Error saving location: " + e.getMessage());
            }
        });
    }

    private void syncDataToServer() {

        executorService.execute(() -> {

            try {
                // Get unsynced records
                java.util.List<LocationTrack> tracks =
                        db.locationTrackDao().getUnsyncedTracks(20);

                if (tracks == null || tracks.isEmpty()) {
                    Log.d("SYNC", "No data to sync");
                    return;
                }

                Log.d("SYNC", "Syncing " + tracks.size() + " records");

                ApiService.syncLocations(
                        tracks,
                        new ApiService.SyncCallback() {
                            @Override
                            public void onSuccess(int syncedCount) {
                                Log.d("SYNC", "Synced " + syncedCount + " records");
                            }

                            @Override
                            public void onFailure(String error) {
                                Log.e("SYNC", "Sync failed: " + error);
                            }
                        }
                );

            } catch (Exception e) {
                Log.e("SYNC", "Sync exception: " + e.getMessage());
            }
        });
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
        return START_STICKY; // Service will restart if killed by system
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.d(TAG, "Service onDestroy - Stopping location tracking");

        // Stop periodic location fetching
        if (handler != null && locationRunnable != null) {
            handler.removeCallbacks(locationRunnable);
        }

        // Stop location updates
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        // Shutdown executor
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }

        Log.d(TAG, "Service stopped");
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
}