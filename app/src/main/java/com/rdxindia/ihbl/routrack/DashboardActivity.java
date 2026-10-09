package com.rdxindia.ihbl.routrack;

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
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.rdxindia.ihbl.routrack.adapter.TrackAdapter;
import com.rdxindia.ihbl.routrack.database.AppDatabase;
import com.rdxindia.ihbl.routrack.workers.ServiceWatchdogWorker;
import com.rdxindia.ihbl.routrack.database.LocationTrack;
import com.rdxindia.ihbl.routrack.service.LocationTrackingService;
import com.rdxindia.ihbl.routrack.utils.SessionManager;
import com.rdxindia.ihbl.routrack.utils.LogCapture;
import com.rdxindia.ihbl.routrack.utils.ApiService;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.view.HapticFeedbackConstants;
import java.io.File;
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
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.net.Uri;
import android.provider.Settings;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

public class DashboardActivity extends AppCompatActivity implements OnMapReadyCallback {

    private TextView tvUsername, tvLatitude, tvLongitude, tvSpeed, tvAngle, tvDateTime;
    private TextView tvVersion;
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
    private AlertDialog bgLocationDialog;

    // ─── SOS button ───
    private static final String SOS_TAG = "SOS";
    private static final long SOS_HOLD_MS = 3000;
    private static final long SOS_TAP_MAX_MS = 400;       // shorter than this = a tap
    private static final long SOS_COOLDOWN_MS = 60_000;
    private static long sosCooldownUntil = 0;             // static: survives screen rotation
    private TextView btnSos;
    private android.widget.ProgressBar sosProgress;
    private android.animation.ObjectAnimator sosAnimator;
    private boolean sosHoldCompleted = false;
    private long sosDownTime = 0;
    private final Handler sosHandler = new Handler(android.os.Looper.getMainLooper());
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
                                // ✅ After location permission, request phone state
                                requestPhoneStatePermission();
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
                            // ✅ After background location, request phone state
                            requestPhoneStatePermission();
                        } else {
                            Toast.makeText(this, "Location must be set to \"Allow all the time\"", Toast.LENGTH_LONG).show();
                            // ✅ Still request phone state even if background denied
                            requestPhoneStatePermission();
                        }
                    }
            );

    // ✅ NEW: Phone state permission launcher
    private ActivityResultLauncher<String> phoneStatePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (isGranted) {
                            Log.d("DashboardActivity", "✓ Phone state permission granted");
                            Toast.makeText(this, "Cellular detection enabled", Toast.LENGTH_SHORT).show();
                        } else {
                            Log.d("DashboardActivity", "Phone state permission denied - using fallback");
                        }
                        // Start service regardless of permission (fallback will work)
                        startLocationService();
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
        applyWindowInsets();
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

    private void applyWindowInsets() {
        int basePx = (int) (16 * getResources().getDisplayMetrics().density);
        // Header: push content below the status bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.dashboardHeader), (view, insets) -> {
            int statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            view.setPadding(basePx, basePx + statusTop, basePx, basePx);
            return insets;
        });
        // The SOS button floats over the scroll view's bottom-right corner. The red circle
        // is 72dp inside an 88dp ring, so a 16dp margin puts the circle 24dp from the edges.
        float density = getResources().getDisplayMetrics().density;
        int sosMarginPx = (int) (16 * density);
        int sosClearancePx = (int) (112 * density);   // ring height + margins
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.dashboardScrollView), (view, insets) -> {
            int navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            // Extra bottom padding lets the last controls scroll up clear of the button
            view.setPadding(0, 0, 0, navBottom + sosClearancePx);
            android.view.View sos = findViewById(R.id.sosContainer);
            if (sos != null) {
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) sos.getLayoutParams();
                lp.bottomMargin = sosMarginPx + navBottom;
                sos.setLayoutParams(lp);
            }
            return insets;
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

        // Hidden debug-log export: long-press the version label
        tvVersion = findViewById(R.id.tvVersion);
        if (tvVersion != null) {
            tvVersion.setText("v" + BuildConfig.VERSION_NAME);
            tvVersion.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showLogExportDialog();
                return true;
            });
        }
        maybeShowLogHint();
        setupSosButton();
    }

    // ─── SOS: press and hold for 3 seconds ──────────────────────────────────────

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupSosButton() {
        btnSos = findViewById(R.id.btnSos);
        sosProgress = findViewById(R.id.sosProgress);
        if (btnSos == null || sosProgress == null) return;

        applySosCooldownState();

        btnSos.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (System.currentTimeMillis() < sosCooldownUntil) return true;
                    sosDownTime = android.os.SystemClock.elapsedRealtime();
                    sosHoldCompleted = false;
                    v.setPressed(true);
                    startSosHold(v);
                    return true;

                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (sosHoldCompleted || sosDownTime == 0) return true;
                    long held = android.os.SystemClock.elapsedRealtime() - sosDownTime;
                    sosDownTime = 0;
                    cancelSosHold();
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                            && held < SOS_TAP_MAX_MS) {
                        Toast.makeText(this, "Hold for 3 seconds to send SOS", Toast.LENGTH_SHORT).show();
                    } else {
                        Log.d(SOS_TAG, "Hold released early after " + held + " ms — cancelled");
                    }
                    return true;
            }
            return false;
        });
    }

    private void startSosHold(android.view.View v) {
        Log.d(SOS_TAG, "Hold started");
        sosAnimator = android.animation.ObjectAnimator.ofInt(sosProgress, "progress", 0, (int) SOS_HOLD_MS);
        sosAnimator.setDuration(SOS_HOLD_MS);
        sosAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        sosAnimator.start();

        // Light tick at 1s and 2s; the 3s mark gets the strong vibration instead.
        sosHandler.postDelayed(() -> v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP), 1000);
        sosHandler.postDelayed(() -> v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP), 2000);
        sosHandler.postDelayed(this::onSosHoldComplete, SOS_HOLD_MS);
    }

    private void cancelSosHold() {
        sosHandler.removeCallbacksAndMessages(null);
        if (sosAnimator != null) sosAnimator.cancel();
        if (sosProgress != null) sosProgress.setProgress(0);
    }

    private void onSosHoldComplete() {
        sosHoldCompleted = true;
        sosDownTime = 0;
        Log.d(SOS_TAG, "Hold completed (3s) — sending SOS");

        sosVibrateStrong();
        sosCooldownUntil = System.currentTimeMillis() + SOS_COOLDOWN_MS;
        applySosCooldownState();
        Toast.makeText(this, "Sending SOS…", Toast.LENGTH_SHORT).show();

        if (serviceBound && locationService != null) {
            locationService.triggerSos(this::showSosResult);
        } else {
            // Tracking service not connected: still save the SOS so it is never lost.
            Log.w(SOS_TAG, "Tracking service not bound — saving SOS directly");
            saveSosWithoutService();
        }
    }

    private void sosVibrateStrong() {
        try {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                        700, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception e) {
            Log.w(SOS_TAG, "Vibration failed: " + e.getMessage());
        }
    }

    /** Greys the button out until the 60-second cooldown ends, then re-arms it. */
    private void applySosCooldownState() {
        if (btnSos == null) return;
        long remaining = sosCooldownUntil - System.currentTimeMillis();
        if (remaining > 0) {
            btnSos.setEnabled(false);
            btnSos.setAlpha(0.6f);
            if (sosProgress != null) sosProgress.setProgress(0);
            sosHandler.postDelayed(this::applySosCooldownState, remaining + 50);
        } else {
            btnSos.setEnabled(true);
            btnSos.setAlpha(1f);
        }
    }

    private void showSosResult(boolean sent) {
        Log.d(SOS_TAG, sent ? "Result: sent" : "Result: saved, will send when online");
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(sent ? "SOS sent" : "SOS saved — will send when online")
                .setMessage(sent
                        ? "Your emergency alert has been delivered to the server."
                        : "Your emergency alert is saved on this phone and will be sent "
                                + "automatically as soon as there is a connection.")
                .setPositiveButton("OK", null)
                .show();
    }

    /**
     * Fallback used only when the tracking service is not connected. Saves the SOS with
     * the last tracked position; the service's normal sync sends it once it is running.
     */
    private void saveSosWithoutService() {
        final String mobile = sessionManager.getMobileNumber();
        final String sessionId = sessionManager.getSessionId();
        if (mobile == null || sessionId == null) {
            Log.e(SOS_TAG, "Cannot save SOS: no active session");
            showSosResult(false);
            return;
        }
        final long sosTime = sessionManager.getTrueTimeMs();
        executorService.execute(() -> {
            try {
                double lat = sessionManager.getLastTrackedLat();
                double lng = sessionManager.getLastTrackedLng();
                com.rdxindia.ihbl.routrack.utils.DeviceInfoHelper deviceInfo =
                        new com.rdxindia.ihbl.routrack.utils.DeviceInfoHelper(this);

                int lastRecNo = sessionManager.getLastRecNo();
                if (lastRecNo == 0) {
                    lastRecNo = db.locationTrackDao().getLastRecNo(mobile);
                }
                int nextRecNo = lastRecNo + 1;
                sessionManager.saveLastRecNo(nextRecNo);

                String textMsg = "SOS | " + dateFormat.format(new Date(sosTime))
                        + " | Acc:NA Src:last_tracked | "
                        + com.rdxindia.ihbl.routrack.utils.DeviceInfoHelper.getDeviceHealthString(this);

                android.content.Intent battery = registerReceiver(null,
                        new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int level = battery != null ? battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) : 0;
                int scale = battery != null ? battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) : 100;

                LocationTrack track = new LocationTrack(
                        mobile, lat, lng, 0f, 0f,
                        sosTime, sessionId,
                        scale > 0 ? (int) (level * 100f / scale) : 0, null, null,
                        textMsg,
                        deviceInfo.getGpsState(), deviceInfo.getInternetState(),
                        deviceInfo.getFlightState(), deviceInfo.getRoamingState(),
                        deviceInfo.getIsNetThere(), deviceInfo.getIsNwThere(),
                        deviceInfo.getIsMoving(0f), deviceInfo.getModelNo(),
                        deviceInfo.getModelOS(), deviceInfo.getApkName(),
                        deviceInfo.getImsiNo(), deviceInfo.getMobileTime(),
                        deviceInfo.getNetworkSignalStrength(),
                        nextRecNo, DataTypes.SOS
                );
                db.locationTrackDao().insert(track);
                sessionManager.saveLastTrackedLocation(lat, lng, System.currentTimeMillis());
                Log.d(SOS_TAG, "✓ SOS record saved without service: RecNo=" + nextRecNo + " | " + textMsg);
            } catch (Exception e) {
                Log.e(SOS_TAG, "Error saving SOS without service: " + e.getMessage(), e);
            }
            runOnUiThread(() -> showSosResult(false));
        });
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
            LatLng defaultLocation = new LatLng(18.5526051, 73.7697251);
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
            if (!getSharedPreferences("PermissionPrefs", MODE_PRIVATE)
                    .getBoolean("bg_location_requested", false)) {
                requestBackgroundLocation();      // first time: system request
            } else {
                // Already asked once. Start tracking now (it works while the app is open);
                // onResume() keeps showing the "Allow all the time" reminder until granted.
                requestPhoneStatePermission();
            }
        } else {
            // ✅ Check phone state permission after other permissions
            requestPhoneStatePermission();
        }
    }

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * First-time request for "Allow all the time". There is deliberately no Skip button:
     * without this permission Android 14+ blocks every background restart of the tracking
     * service, so tracking silently stops whenever the OS closes the app.
     */
    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (bgLocationDialog != null && bgLocationDialog.isShowing()) return;
            bgLocationDialog = new AlertDialog.Builder(this)
                    .setTitle("Allow location all the time")
                    .setMessage("ROU Track must record your location when the app is closed.\n\n"
                            + "On the next screen choose \"Allow all the time\".")
                    .setCancelable(false)
                    .setPositiveButton("Continue", (dialog, which) -> {
                        getSharedPreferences("PermissionPrefs", MODE_PRIVATE).edit()
                                .putBoolean("bg_location_requested", true).apply();
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                    })
                    .show();
        }
    }

    /**
     * Shown on every return to the dashboard while location is still "only while using
     * the app". Android stops offering its own prompt after the first refusals, so this
     * sends the user to the app's settings page instead.
     */
    private void showBackgroundLocationReminder() {
        if (bgLocationDialog != null && bgLocationDialog.isShowing()) return;
        bgLocationDialog = new AlertDialog.Builder(this)
                .setTitle("Location must be \"Allow all the time\"")
                .setMessage("Tracking stops when the app is closed because location is set to "
                        + "\"Only while using the app\".\n\n"
                        + "Tap Open Settings, then:\nPermissions → Location → Allow all the time")
                .setCancelable(false)
                // "Later" keeps the dashboard (and the SOS button) usable. The reminder
                // comes back every time the dashboard is opened until the permission is set.
                .setNegativeButton("Later", (dialog, which) ->
                        Log.d("DashboardActivity", "Background location reminder postponed"))
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e("DashboardActivity", "Could not open app settings: " + e.getMessage());
                    }
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasBackgroundLocation()) {
            if (bgLocationDialog != null && bgLocationDialog.isShowing()) {
                bgLocationDialog.dismiss();
            }
            return;
        }
        // Only once the first-time flow has run; before that onCreate() drives the prompts.
        if (hasFineLocation() && getSharedPreferences("PermissionPrefs", MODE_PRIVATE)
                .getBoolean("bg_location_requested", false)) {
            showBackgroundLocationReminder();
        }
    }

    // ✅ NEW: Request phone state permission
    private void requestPhoneStatePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            startLocationService();   // Android 5.x or lower — permission not needed
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            startLocationService();   // already granted
            return;
        }

        // Not granted. Ask AT MOST ONCE — cellular detection is optional, so if the user
        // has already been prompted (granted-then-revoked, or skipped) we must NOT nag them
        // on every Dashboard launch. Remember that we asked and move on.
        android.content.SharedPreferences prefs =
                getSharedPreferences("PermissionPrefs", MODE_PRIVATE);
        if (prefs.getBoolean("cellular_prompt_shown", false)) {
            Log.d("DashboardActivity", "Cellular permission already asked once — not re-prompting");
            startLocationService();
            return;
        }
        prefs.edit().putBoolean("cellular_prompt_shown", true).apply();

        new AlertDialog.Builder(this)
                .setTitle("Cellular Detection Permission")
                .setMessage("A-Track needs this permission to detect cellular network availability for better tracking accuracy.")
                .setPositiveButton("Allow", (dialog, which) ->
                        phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE))
                .setNegativeButton("Skip", (dialog, which) -> {
                    Log.d("DashboardActivity", "Phone state permission skipped — won't ask again");
                    startLocationService();
                })
                .show();
    }

    private void startLocationService() {
        Intent serviceIntent = new Intent(this, LocationTrackingService.class);
        startForegroundService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        scheduleWatchdog();
    }

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

        Log.d("DashboardActivity", "✓ ServiceWatchdogWorker scheduled (every 15 min)");
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
        tvLatitude.setText(String.format(Locale.getDefault(), "Lat: %.6f", location.getLatitude()));
        tvLongitude.setText(String.format(Locale.getDefault(), "Lng: %.6f", location.getLongitude()));
        tvDateTime.setText(dateFormat.format(new Date(sessionManager.getTrueTimeMs())));
        tvSpeed.setText(String.format(Locale.getDefault(), "%.2f km/h", location.getSpeed()*3.6f));
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
                .setPositiveButton("Logout", (dialog, which) -> startLogoutFlow())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean logoutInProgress = false;

    /**
     * Logout button: write the LOGOUT record (datatype 3) so the server knows the duty
     * ended, try once to send it (10 seconds at most), then log out whatever the result.
     */
    private void startLogoutFlow() {
        if (logoutInProgress) return;
        logoutInProgress = true;

        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Logging out...");
        progress.setCancelable(false);
        progress.show();

        final Runnable finish = () -> {
            try {
                if (progress.isShowing()) progress.dismiss();
            } catch (Exception ignored) {
            }
            performLogout();
        };

        if (serviceBound && locationService != null) {
            locationService.saveLogoutRecordAndSync(10_000,
                    synced -> {
                        Log.d("DashboardActivity", "Logout record "
                                + (synced ? "sent" : "saved, will sync after next login"));
                        finish.run();
                    });
        } else {
            // Tracking service not connected: still save the record; it syncs after next login.
            saveLogoutRecordWithoutService(finish);
        }
    }

    private void saveLogoutRecordWithoutService(Runnable then) {
        final String mobile = sessionManager.getMobileNumber();
        final String sessionId = sessionManager.getSessionId();
        if (mobile == null || sessionId == null) {
            then.run();
            return;
        }
        final long logoutTime = sessionManager.getTrueTimeMs();
        executorService.execute(() -> {
            try {
                double lat = sessionManager.getLastTrackedLat();
                double lng = sessionManager.getLastTrackedLng();
                com.rdxindia.ihbl.routrack.utils.DeviceInfoHelper deviceInfo =
                        new com.rdxindia.ihbl.routrack.utils.DeviceInfoHelper(this);

                int lastRecNo = sessionManager.getLastRecNo();
                if (lastRecNo == 0) {
                    lastRecNo = db.locationTrackDao().getLastRecNo(mobile);
                }
                int nextRecNo = lastRecNo + 1;
                sessionManager.saveLastRecNo(nextRecNo);

                String textMsg = "LOGOUT | " + dateFormat.format(new Date(logoutTime)) + " | "
                        + com.rdxindia.ihbl.routrack.utils.DeviceInfoHelper.getDeviceHealthString(this);

                android.content.Intent battery = registerReceiver(null,
                        new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int level = battery != null ? battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) : 0;
                int scale = battery != null ? battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) : 100;

                LocationTrack track = new LocationTrack(
                        mobile, lat, lng, 0f, 0f,
                        logoutTime, sessionId,
                        scale > 0 ? (int) (level * 100f / scale) : 0, null, null,
                        textMsg,
                        deviceInfo.getGpsState(), deviceInfo.getInternetState(),
                        deviceInfo.getFlightState(), deviceInfo.getRoamingState(),
                        deviceInfo.getIsNetThere(), deviceInfo.getIsNwThere(),
                        deviceInfo.getIsMoving(0f), deviceInfo.getModelNo(),
                        deviceInfo.getModelOS(), deviceInfo.getApkName(),
                        deviceInfo.getImsiNo(), deviceInfo.getMobileTime(),
                        deviceInfo.getNetworkSignalStrength(),
                        nextRecNo, DataTypes.LOGOUT
                );
                db.locationTrackDao().insert(track);
                sessionManager.saveLastTrackedLocation(lat, lng, System.currentTimeMillis());
                Log.d("DashboardActivity", "✓ LOGOUT record saved without service: RecNo=" + nextRecNo);
            } catch (Exception e) {
                Log.e("DashboardActivity", "Error saving LOGOUT record: " + e.getMessage(), e);
            }
            runOnUiThread(then);
        });
    }

    private void performLogout() {
        android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);

        // Cancel safety alarm (scheduled by LocationTrackingService, request code 123)
        Intent alarmIntent = new Intent(this, AlarmReceiver.class);
        PendingIntent alarmPendingIntent = PendingIntent.getBroadcast(
                this, 123, alarmIntent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (alarmPendingIntent != null) {
            alarmManager.cancel(alarmPendingIntent);
            Log.d("DashboardActivity", "Safety alarm cancelled on logout");
        }

        // Cancel service restart alarm (scheduled by onDestroy(), request code 999)
        Intent restartIntent = new Intent(this, LocationTrackingService.class);
        PendingIntent restartPendingIntent = PendingIntent.getService(
                this, 999, restartIntent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (restartPendingIntent != null) {
            alarmManager.cancel(restartPendingIntent);
            Log.d("DashboardActivity", "Service restart alarm cancelled on logout");
        }

        // Cancel JobScheduler
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.app.job.JobScheduler jobScheduler =
                    (android.app.job.JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
            jobScheduler.cancel(12345);
            Log.d("DashboardActivity", "JobScheduler cancelled on logout");
        }

        // ✅ FIX: Logout BEFORE stopping service so that onDestroy() sees isLoggedIn() == false
        // and does NOT schedule a service restart alarm.
        int sessionDbId = sessionManager.getSessionDbId();
        sessionManager.logout();

        // Stop location service
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        stopService(new Intent(this, LocationTrackingService.class));

        // Stop UI updates
        if (handler != null && updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }

        clearDisplayedData();

        // Update session logout time in database, then navigate to login
        executorService.execute(() -> {
            db.sessionDao().updateLogoutTime(sessionDbId, sessionManager.getTrueTimeMs());

            runOnUiThread(() -> {
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
        sosHandler.removeCallbacksAndMessages(null);
        if (sosAnimator != null) sosAnimator.cancel();

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
        intent.putExtra("speed", location.getSpeed()*3.6f);
        intent.putExtra("angle", location.getBearing());
        intent.putExtra("dateTime", location.getTime());
        startActivity(intent);
    }

    // ─── Debug log export (hidden: long-press version label) ────────────────────

    private void showLogExportDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Send Debug Logs")
                .setMessage("Send app logs to the office for debugging?")
                .setPositiveButton("Send", (d, w) -> exportAndSendLogs())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Captures the logs and uploads them to the server, which emails them to the
     * office — the same path the location report uses. No WhatsApp/email app needed
     * on the device.
     */
    private void exportAndSendLogs() {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Capturing & sending logs...");
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            File logFile = LogCapture.captureLogsToFile(this, sessionManager);
            if (logFile == null) {
                runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    Toast.makeText(this, "Failed to capture logs", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            String mobile = sessionManager.getMobileNumber();
            String subject = "ROU Track DEBUG LOGS - " + mobile;
            String body = "Debug logs attached.\n"
                    + "Mobile: " + mobile + "\n"
                    + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                    + "App: v" + BuildConfig.VERSION_NAME;

            ApiService.sendReportEmail(mobile, subject, body, logFile,
                    new ApiService.ReportEmailCallback() {
                        @Override
                        public void onSuccess() {
                            runOnUiThread(() -> {
                                if (progress.isShowing()) progress.dismiss();
                                Toast.makeText(DashboardActivity.this,
                                        "✓ Logs sent to office", Toast.LENGTH_LONG).show();
                            });
                        }

                        @Override
                        public void onFailure(String error) {
                            runOnUiThread(() -> {
                                if (progress.isShowing()) progress.dismiss();
                                Toast.makeText(DashboardActivity.this,
                                        "✗ Failed to send logs: " + error, Toast.LENGTH_LONG).show();
                            });
                        }
                    });
        }).start();
    }

    private void maybeShowLogHint() {
        SharedPreferences prefs = getSharedPreferences("debug_prefs", MODE_PRIVATE);
        if (!prefs.getBoolean("logHintShown", false)) {
            Toast.makeText(this, "Long press version to send logs to office", Toast.LENGTH_LONG).show();
            prefs.edit().putBoolean("logHintShown", true).apply();
        }
    }
}