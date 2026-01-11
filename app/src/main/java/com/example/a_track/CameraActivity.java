package com.example.a_track;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.a_track.database.AppDatabase;
import com.example.a_track.database.LocationTrack;
import com.example.a_track.utils.ApiService;
import com.example.a_track.utils.DeviceInfoHelper;
import com.example.a_track.utils.SessionManager;
import com.google.android.material.textfield.TextInputEditText;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity";
    private static final int CAMERA_PERMISSION_CODE = 100;

    private PreviewView previewView;
    private TextInputEditText etRemarks;
    private Button btnCapture, btnSend;
    private TextView tvLocationInfo;

    private ImageCapture imageCapture;
    private File capturedImageFile;
    private ExecutorService cameraExecutor;

    // Location data from intent
    private double latitude;
    private double longitude;
    private float speed;
    private float angle;
    private long dateTime;

    private AppDatabase db;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        initViews();
        getLocationDataFromIntent();

        db = AppDatabase.getInstance(this);
        sessionManager = new SessionManager(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (checkCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }

        setupClickListeners();
    }

    private void initViews() {
        previewView = findViewById(R.id.previewView);
        etRemarks = findViewById(R.id.etRemarks);
        btnCapture = findViewById(R.id.btnCapture);
        btnSend = findViewById(R.id.btnSend);
        tvLocationInfo = findViewById(R.id.tvLocationInfo);
    }

    private void getLocationDataFromIntent() {
        latitude = getIntent().getDoubleExtra("latitude", 0.0);
        longitude = getIntent().getDoubleExtra("longitude", 0.0);
        speed = getIntent().getFloatExtra("speed", 0.0f);
        angle = getIntent().getFloatExtra("angle", 0.0f);
        dateTime = getIntent().getLongExtra("dateTime", System.currentTimeMillis());

        tvLocationInfo.setText(String.format(Locale.getDefault(),
                "Lat: %.6f, Lng: %.6f", latitude, longitude));
    }

    private void setupClickListeners() {
        btnCapture.setOnClickListener(v -> capturePhoto());

        btnSend.setOnClickListener(v -> {
            String remarks = etRemarks.getText() != null ?
                    etRemarks.getText().toString().trim() : "";
            savePhotoRecord(remarks);
        });
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required!", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera: " + e.getMessage());
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        // Preview
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // ImageCapture
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        // Select back camera
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            cameraProvider.unbindAll();
            Camera camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture);

            Log.d(TAG, "Camera started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Camera binding failed: " + e.getMessage());
            Toast.makeText(this, "Failed to bind camera", Toast.LENGTH_SHORT).show();
        }
    }

    private void capturePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create file name using UTC timestamp
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                .format(new Date(dateTime));
        String fileName = "IMG_" + timestamp + ".jpg";

        // Create file in app's private directory
        File photoDir = new File(getFilesDir(), "Photos");
        if (!photoDir.exists()) {
            photoDir.mkdirs();
        }
        File photoFile = new File(photoDir, fileName);

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        runOnUiThread(() -> {
                            capturedImageFile = photoFile;
                            Log.d(TAG, "Photo saved: " + photoFile.getAbsolutePath());
                            Toast.makeText(CameraActivity.this,
                                    "Photo captured!", Toast.LENGTH_SHORT).show();

                            // Show Send button, hide Capture button
                            btnCapture.setVisibility(View.GONE);
                            btnSend.setVisibility(View.VISIBLE);
                        });
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        runOnUiThread(() -> {
                            Log.e(TAG, "Photo capture failed: " + exception.getMessage());
                            Toast.makeText(CameraActivity.this,
                                    "Failed to capture photo", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private void savePhotoRecord(String remarks) {
        if (capturedImageFile == null) {
            Toast.makeText(this, "No photo captured", Toast.LENGTH_SHORT).show();
            return;
        }

        String mobileNumber = sessionManager.getMobileNumber();
        String sessionId = sessionManager.getSessionId();

        if (mobileNumber == null || sessionId == null) {
            Toast.makeText(this, "Session error. Please login again.", Toast.LENGTH_SHORT).show();
            return;
        }

        int battery = getBatteryLevel();

        DeviceInfoHelper deviceInfo = new DeviceInfoHelper(this);

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date(dateTime));
        String photoFileName = mobileNumber + "_" + timestamp + ".jpg";



        LocationTrack track = new LocationTrack(
                mobileNumber,
                latitude,
                longitude,
                speed,
                angle,
                dateTime,
                sessionId,
                battery,
                photoFileName,
                remarks.isEmpty() ? null : remarks,
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
                deviceInfo.getImsiNo()
        );

        // Show progress
        Toast.makeText(this, "Saving and uploading photo...", Toast.LENGTH_SHORT).show();

        // Save to local database first
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.execute(() -> {
            try {
                long recordId = db.locationTrackDao().insert(track);
                track.setId((int) recordId);

                Log.d(TAG, "Photo record saved locally with ID: " + recordId);
                Log.d(TAG, "Photo filename: " + photoFileName);

                // Upload to server
                ApiService.uploadPhoto(track, capturedImageFile, new ApiService.PhotoUploadCallback() {
                    @Override
                    public void onSuccess(String photoPath) {
                        runOnUiThread(() -> {
                            Toast.makeText(CameraActivity.this,
                                    "✓ Photo uploaded successfully!", Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        runOnUiThread(() -> {
                            Toast.makeText(CameraActivity.this,
                                    "✓ Saved locally. Will sync later: " + error,
                                    Toast.LENGTH_LONG).show();
                            finish();
                        });
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error saving photo record: " + e.getMessage());
                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this,
                            "Failed to save record", Toast.LENGTH_SHORT).show();
                });
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
            Log.e(TAG, "Error getting battery level: " + e.getMessage());
        }
        return 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}