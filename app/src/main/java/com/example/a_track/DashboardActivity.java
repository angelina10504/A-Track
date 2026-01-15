package com.example.a_track;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.a_track.adapter.TrackAdapter;
import com.example.a_track.database.AppDatabase;
import com.example.a_track.database.LocationTrack;
import com.example.a_track.service.LocationTrackingService;
import com.example.a_track.utils.SessionManager;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardActivity extends AppCompatActivity implements OnMapReadyCallback {

    private TextView tvUsername, tvLatitude, tvLongitude, tvSpeed, tvAngle, tvDateTime;
    private RecyclerView rvRecentTracks;
    private Button btnFilterLocation, btnFilterSession, btnLogout, btnTakePhoto;

    private GoogleMap mMap;
    private Marker currentLocationMarker;
    private Polyline routePolyline;
    private ArrayList<LatLng> routePoints = new ArrayList<>();

    private SessionManager sessionManager;
    private AppDatabase db;
    private TrackAdapter trackAdapter;
    private ExecutorService executorService;

    private LocationTrackingService locationService;
    private boolean serviceBound = false;
    private Handler handler;
    private Runnable updateRunnable;

    private SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault());

    private ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        Boolean fineLocation = result.get(Manifest.permission.ACCESS_FINE_LOCATION);
                        Boolean coarseLocation = result.get(Manifest.permission.ACCESS_COARSE_LOCATION);

                        if (fineLocation != null && fineLocation && coarseLocation != null && coarseLocation) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                requestBackgroundLocation();
                            } else {
                                startLocationService();
                            }
                        } else {
                            Toast.makeText(this, "Location permissions are required!", Toast.LENGTH_LONG).show();
                        }
                    }
            );

    private ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (isGranted) {
                            startLocationService();
                        } else {
                            Toast.makeText(this, "Background location is recommended", Toast.LENGTH_LONG).show();
                            startLocationService();
                        }
                    }
            );

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            LocationTrackingService.LocalBinder binder = (LocationTrackingService.LocalBinder) service;
            locationService = binder.getService();
            serviceBound = true;
            startLocationUpdates();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        initViews();
        sessionManager = new SessionManager(this);
        db = AppDatabase.getInstance(this);
        executorService = Executors.newSingleThreadExecutor();
        handler = new Handler();

        setupRecyclerView();
        setupMap();
        checkAndRequestPermissions();

        loadUserInfo();
        observeRecentTracks();

        btnFilterLocation.setOnClickListener(v -> {
            startActivity(new Intent(this, FilterLocationActivity.class));
        });

        btnFilterSession.setOnClickListener(v -> {
            startActivity(new Intent(this, FilterSessionActivity.class));
        });

        btnLogout.setOnClickListener(v -> showLogoutDialog());

        btnTakePhoto.setOnClickListener(v -> {
            if (serviceBound && locationService != null) {
                Location location = locationService.getLastLocation();
                if (location != null) {
                    openCameraActivity(location);
                } else {
                    Toast.makeText(this, "Waiting for location...", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Location service not ready", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void initViews() {
        tvUsername = findViewById(R.id.tvUsername);
        tvLatitude = findViewById(R.id.tvLatitude);
        tvLongitude = findViewById(R.id.tvLongitude);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvAngle = findViewById(R.id.tvAngle);
        tvDateTime = findViewById(R.id.tvDateTime);
        rvRecentTracks = findViewById(R.id.rvRecentTracks);
        btnFilterLocation = findViewById(R.id.btnFilterLocation);
        btnFilterSession = findViewById(R.id.btnFilterSession);
        btnLogout = findViewById(R.id.btnLogout);
        btnTakePhoto = findViewById(R.id.btnTakePhoto);
    }

    private void setupRecyclerView() {
        trackAdapter = new TrackAdapter();
        if (rvRecentTracks != null) {
            rvRecentTracks.setLayoutManager(new LinearLayoutManager(this));
            rvRecentTracks.setAdapter(trackAdapter);
        }
    }

    private void setupMap() {
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.mapFragment);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        } else{
            Log.w("DashboardActivity", "Map fragment not found");
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        try    {
            // Enable location layer if permission granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                mMap.setMyLocationEnabled(true);
            }

            // Set map UI settings
            mMap.getUiSettings().setZoomControlsEnabled(true);
            mMap.getUiSettings().setMyLocationButtonEnabled(true);
            mMap.getUiSettings().setCompassEnabled(true);

            // Set default location (will be updated with actual location)
            LatLng defaultLocation = new LatLng(21.1702, 72.8311); // Surat coordinates
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 15));

            Log.d("DashboardActivity", "Map initialized successfully");

            // Load existing route for current session
            loadCurrentSessionRoute();
        } catch (Exception e) {
            Log.e("DashboardActivity", "Error initializing map: " + e.getMessage());
        }
    }

    private void loadCurrentSessionRoute() {
        String mobile = sessionManager.getMobileNumber();
        String sessionId = sessionManager.getSessionId();

        if (mobile != null && sessionId != null) {
            executorService.execute(() -> {
                List<LocationTrack> tracks = db.locationTrackDao().getTracksBySessionSync(sessionId);

                runOnUiThread(() -> {
                    if (tracks != null && !tracks.isEmpty()) {
                        routePoints.clear();
                        for (LocationTrack track : tracks) {
                            routePoints.add(new LatLng(track.getLatitude(), track.getLongitude()));
                        }
                        drawRouteOnMap();
                    }
                });
            });
        }
    }

    private void drawRouteOnMap() {
        if (mMap == null || routePoints.isEmpty()) return;

        // Remove old polyline
        if (routePolyline != null) {
            routePolyline.remove();
        }

        // Draw route line
        PolylineOptions polylineOptions = new PolylineOptions()
                .addAll(routePoints)
                .width(8)
                .color(0xFF0000FF) // Blue color
                .geodesic(true);

        routePolyline = mMap.addPolyline(polylineOptions);
    }

    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            requestBackgroundLocation();
        } else {
            startLocationService();
        }
    }

    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            new AlertDialog.Builder(this)
                    .setTitle("Background Location Permission")
                    .setMessage("A-Track needs background location permission to track your location when the app is closed.")
                    .setPositiveButton("Allow", (dialog, which) -> {
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                    })
                    .setNegativeButton("Skip", (dialog, which) -> startLocationService())
                    .show();
        }
    }

    private void startLocationService() {
        Intent serviceIntent = new Intent(this, LocationTrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void startLocationUpdates() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (serviceBound && locationService != null) {
                    Location location = locationService.getLastLocation();
                    if (location != null) {
                        updateLocationUI(location);
                    }
                }
                handler.postDelayed(this, 5000); // Update UI every 5 seconds
            }
        };
        handler.post(updateRunnable);
    }

    private void updateLocationUI(Location location) {
        tvLatitude.setText(String.format(Locale.getDefault(), "Lat: %.4f", location.getLatitude()));
        tvLongitude.setText(String.format(Locale.getDefault(), "Lng: %.4f", location.getLongitude()));
        float speedKmh = location.getSpeed() * 3.6f;
        tvDateTime.setText( dateFormat.format(new Date()));
        tvSpeed.setText(String.format(Locale.getDefault(), "%.2f km/h", speedKmh));
        tvAngle.setText(String.format(Locale.getDefault(), "Angle: %.0f°", location.getBearing()));

        // Update map
        updateMapLocation(location);
    }

    private void updateMapLocation(Location location) {
        if (mMap == null) return;

        LatLng currentLatLng = new LatLng(location.getLatitude(), location.getLongitude());

        // Update or add current location marker
        if (currentLocationMarker == null) {
            currentLocationMarker = mMap.addMarker(new MarkerOptions()
                    .position(currentLatLng)
                    .title("Current Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 16));
        } else {
            currentLocationMarker.setPosition(currentLatLng);
        }

        // Add point to route
        routePoints.add(currentLatLng);
        drawRouteOnMap();
    }

    private void loadUserInfo() {
        String mobile = sessionManager.getMobileNumber();
        if (mobile != null) {
            tvUsername.setText("User: +91 " + mobile);
        }
    }

    private void observeRecentTracks() {
        String mobile = sessionManager.getMobileNumber();
        if (mobile != null) {
            db.locationTrackDao().getTop5Tracks(mobile).observe(this, new Observer<List<LocationTrack>>() {
                @Override
                public void onChanged(List<LocationTrack> locationTracks) {
                    if (locationTracks != null) {
                        trackAdapter.setTracks(locationTracks);
                    }
                }
            });
        }
    }

    private void showLogoutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout? Location tracking will be stopped.")
                .setPositiveButton("Logout", (dialog, which) -> performLogout())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performLogout() {
        // Stop location service
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        Intent serviceIntent = new Intent(this, LocationTrackingService.class);
        stopService(serviceIntent);

        // Stop UI updates
        if (handler != null && updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }

        // Clear displayed data (but keep in database)
        clearDisplayedData();

        // Update session logout time in database
        int sessionDbId = sessionManager.getSessionDbId();
        executorService.execute(() -> {
            db.sessionDao().updateLogoutTime(sessionDbId, System.currentTimeMillis());

            runOnUiThread(() -> {
                // Clear session from SharedPreferences
                sessionManager.logout();

                // Navigate to login screen
                Intent intent = new Intent(DashboardActivity.this, LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        });
    }

    private void clearDisplayedData() {
        // Clear current location display
        tvLatitude.setText("Latitude: --");
        tvLongitude.setText("Longitude: --");
        tvSpeed.setText("Speed: --");
        tvAngle.setText("Angle: --");
        tvDateTime.setText("Date Time: --");

        // Clear RecyclerView
        if (trackAdapter != null) {
            trackAdapter.setTracks(new ArrayList<>());
        }

        Log.d("DashboardActivity", "Display data cleared on logout");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Stop UI updates
        if (handler != null && updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }

        // Unbind service if still bound
        if (serviceBound) {
            try {
                unbindService(serviceConnection);
                serviceBound = false;
            } catch (Exception e) {
                // Service already unbound
            }
        }

        // Shutdown executor
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
    private void openCameraActivity(Location location) {
        Intent intent = new Intent(this, CameraActivity.class);
        intent.putExtra("latitude", location.getLatitude());
        intent.putExtra("longitude", location.getLongitude());
        intent.putExtra("speed", location.getSpeed());
        intent.putExtra("angle", location.getBearing());
        intent.putExtra("dateTime", location.getTime());
        startActivity(intent);
    }
}