package com.example.a_track;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.VideoView;
import androidx.core.content.PermissionChecker;

import com.example.a_track.database.AppDatabase;
import com.example.a_track.database.LocationTrack;
import com.example.a_track.utils.DeviceInfoHelper;
import com.example.a_track.utils.SessionManager;
import com.google.android.material.textfield.TextInputEditText;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.example.a_track.utils.ImageCompressor;

public class CameraActivity extends AppCompatActivity {

    private static final String TAG = "CameraActivity";
    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final int AUDIO_PERMISSION_CODE = 101;

    private CameraSelector currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
    private ImageButton btnSwitchCamera;

    private PreviewView previewView;
    private ImageView ivPhotoPreview;
    private TextInputEditText etRemarks;
    private Button btnCapture, btnSend, btnRetake;
    private TextView tvLocationInfo;

    private ImageCapture imageCapture;
    private File capturedImageFile;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;

    // Location data from intent
    private double latitude;
    private double longitude;
    private float speed;
    private float angle;
    private long dateTime;

    private AppDatabase db;
    private SessionManager sessionManager;
    private Button btnCaptureVideo;
    private VideoView videoPreview;
    private TextView tvRecordingTimer;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private File capturedVideoFile;
    private Handler recordingHandler;
    private Runnable recordingRunnable;
    private int recordingSeconds = 0;
    private static final int MAX_RECORDING_SECONDS = 5;
    private enum CaptureMode { PHOTO, VIDEO, NONE }
    private CaptureMode currentMode = CaptureMode.NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        initViews();
        getLocationDataFromIntent();

        db = AppDatabase.getInstance(this);
        sessionManager = new SessionManager(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (checkAllPermissions()) {
            startCamera();
        } else {
            requestCameraPermission();
        }

        setupClickListeners();
    }

    private void initViews() {
        previewView = findViewById(R.id.previewView);
        ivPhotoPreview = findViewById(R.id.ivPhotoPreview);
        videoPreview = findViewById(R.id.videoPreview);
        tvRecordingTimer = findViewById(R.id.tvRecordingTimer);
        etRemarks = findViewById(R.id.etRemarks);
        btnCapture = findViewById(R.id.btnCapture);
        btnCaptureVideo = findViewById(R.id.btnCaptureVideo);
        btnSend = findViewById(R.id.btnSend);
        btnRetake = findViewById(R.id.btnRetake);
        tvLocationInfo = findViewById(R.id.tvLocationInfo);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);

        recordingHandler = new Handler(Looper.getMainLooper());
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

        btnCaptureVideo.setOnClickListener(v -> {
            if (activeRecording == null) {
                startVideoRecording();
            } else {
                stopVideoRecording();
            }
        });

        btnRetake.setOnClickListener(v -> retakeCapture());

        btnSend.setOnClickListener(v -> {
            String remarks = etRemarks.getText() != null ?
                    etRemarks.getText().toString().trim() : "";

            if (currentMode == CaptureMode.PHOTO) {
                savePhotoRecord(remarks);
            } else if (currentMode == CaptureMode.VIDEO) {
                saveVideoRecord(remarks);
            }
        });

        btnSwitchCamera.setOnClickListener(v -> switchCamera());
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkAllPermissions() {
        return checkCameraPermission() && checkAudioPermission();
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                CAMERA_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == CAMERA_PERMISSION_CODE) {
            boolean allGranted = true;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                startCamera();
            } else {
                // Check which permission was denied
                boolean cameraGranted = ContextCompat.checkSelfPermission(this,
                        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                boolean audioGranted = ContextCompat.checkSelfPermission(this,
                        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

                if (!cameraGranted && !audioGranted) {
                    Toast.makeText(this, "Camera and Microphone permissions are required!",
                            Toast.LENGTH_LONG).show();
                } else if (!cameraGranted) {
                    Toast.makeText(this, "Camera permission is required!",
                            Toast.LENGTH_LONG).show();
                } else if (!audioGranted) {
                    Toast.makeText(this, "Microphone permission is required for video recording!",
                            Toast.LENGTH_LONG).show();
                }
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
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

        // VideoCapture (NEW)
        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build();
        videoCapture = VideoCapture.withOutput(recorder);

        try {
            cameraProvider.unbindAll();
            Camera camera = cameraProvider.bindToLifecycle(
                    this, currentCameraSelector, preview, imageCapture, videoCapture);

            Log.d(TAG, "Camera started successfully with video support");
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
        File tempPhotoFile = new File(photoDir, "temp_" + fileName);
        final File finalPhotoFile = new File(photoDir, fileName);

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(tempPhotoFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        // ✅ Compress the image
                        boolean compressed = ImageCompressor.compressImage(tempPhotoFile, finalPhotoFile);

                        // Delete temp file
                        if (tempPhotoFile.exists()) {
                            tempPhotoFile.delete();
                        }

                        if (compressed) {
                            runOnUiThread(() -> {
                                capturedImageFile = finalPhotoFile;

                                long sizeKB = finalPhotoFile.length() / 1024;
                                Log.d(TAG, "✓ Photo compressed: " + sizeKB + " KB");
                                Log.d(TAG, "✓ Photo saved: " + finalPhotoFile.getAbsolutePath());

                                showPhotoPreview(finalPhotoFile);
                                Toast.makeText(CameraActivity.this,
                                        "Photo captured!", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            runOnUiThread(() -> {
                                Log.e(TAG, "✗ Image compression failed");
                                Toast.makeText(CameraActivity.this,
                                        "Failed to compress photo", Toast.LENGTH_SHORT).show();
                            });
                        }
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

    private void startVideoRecording() {
        if (videoCapture == null) {
            Toast.makeText(this, "Video capture not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create file name
        String mobileNumber = sessionManager.getMobileNumber();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata")); // or whatever your server timezone is
        String timestamp = sdf.format(new Date(dateTime));
        String fileName = mobileNumber + "_"+timestamp + ".mp4";

        // Create Videos directory
        File videoDir = new File(getFilesDir(), "Videos");
        if (!videoDir.exists()) {
            videoDir.mkdirs();
        }
        capturedVideoFile = new File(videoDir, fileName);

        // Prepare output options
        MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions
                .Builder(getContentResolver(), android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .build();

        // Start recording
        if (!checkAudioPermission()) {
            Toast.makeText(this, "Please grant microphone permission to record video with audio",
                    Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    AUDIO_PERMISSION_CODE);
            return;
        }

        activeRecording = videoCapture.getOutput()
                .prepareRecording(this, new androidx.camera.video.FileOutputOptions.Builder(capturedVideoFile).build())
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), videoRecordEvent -> {
                    if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                        runOnUiThread(() -> {
                            btnCaptureVideo.setText("⏹ Stop");
                            btnCaptureVideo.setBackgroundTintList(
                                    ContextCompat.getColorStateList(this, android.R.color.holo_red_dark));
                            tvRecordingTimer.setVisibility(View.VISIBLE);
                            btnCapture.setEnabled(false);
                            btnSwitchCamera.setVisibility(View.GONE);
                            startRecordingTimer();
                        });

                    } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;

                        runOnUiThread(() -> {
                            stopRecordingTimer();

                            if (!finalizeEvent.hasError()) {
                                Log.d(TAG, "Video saved: " + capturedVideoFile.getAbsolutePath());
                                showVideoPreview(capturedVideoFile);
                                Toast.makeText(this, "Video recorded!", Toast.LENGTH_SHORT).show();
                            } else {
                                Log.e(TAG, "Video recording error: " + finalizeEvent.getError());
                                Toast.makeText(this, "Failed to record video", Toast.LENGTH_SHORT).show();
                            }

                            btnCaptureVideo.setText("🎥 Video");
                            btnCaptureVideo.setBackgroundTintList(
                                    ContextCompat.getColorStateList(this, android.R.color.holo_orange_dark));
                            tvRecordingTimer.setVisibility(View.GONE);
                            btnCapture.setEnabled(true);
                        });

                        activeRecording = null;
                    }
                });
    }

    private void stopVideoRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
    }

    private void startRecordingTimer() {
        recordingSeconds = 0;
        recordingRunnable = new Runnable() {
            @Override
            public void run() {
                recordingSeconds++;
                tvRecordingTimer.setText("⏺ Recording: " + recordingSeconds + "s");

                if (recordingSeconds >= MAX_RECORDING_SECONDS) {
                    stopVideoRecording();
                } else {
                    recordingHandler.postDelayed(this, 1000);
                }
            }
        };
        recordingHandler.post(recordingRunnable);
    }

    private void stopRecordingTimer() {
        if (recordingHandler != null && recordingRunnable != null) {
            recordingHandler.removeCallbacks(recordingRunnable);
        }
        recordingSeconds = 0;
    }

    private void showVideoPreview(File videoFile) {
        try {
            currentMode = CaptureMode.VIDEO;

            // Hide camera preview
            previewView.setVisibility(View.GONE);
            ivPhotoPreview.setVisibility(View.GONE);
            videoPreview.setVisibility(View.VISIBLE);

            // Set video
            videoPreview.setVideoPath(videoFile.getAbsolutePath());
            videoPreview.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                mp.start();
            });
            videoPreview.start();

            // Update buttons
            btnCapture.setVisibility(View.GONE);
            btnCaptureVideo.setVisibility(View.GONE);
            btnRetake.setVisibility(View.VISIBLE);
            btnSend.setVisibility(View.VISIBLE);
            btnSwitchCamera.setVisibility(View.GONE);

        } catch (Exception e) {
            Log.e(TAG, "Error showing video preview: " + e.getMessage());
            Toast.makeText(this, "Error showing video preview", Toast.LENGTH_SHORT).show();
        }
    }

    private void showPhotoPreview(File photoFile) {
        try {
            currentMode = CaptureMode.PHOTO;
            // Load and display the captured photo
            Bitmap bitmap = loadAndRotateBitmap(photoFile.getAbsolutePath());
            ivPhotoPreview.setImageBitmap(bitmap);

            // Hide camera preview, show photo preview
            previewView.setVisibility(View.GONE);
            ivPhotoPreview.setVisibility(View.VISIBLE);

            // Update buttons
            btnCapture.setVisibility(View.GONE);
            btnRetake.setVisibility(View.VISIBLE);
            btnSend.setVisibility(View.VISIBLE);
            btnSwitchCamera.setVisibility(View.GONE);

        } catch (Exception e) {
            Log.e(TAG, "Error showing preview: " + e.getMessage());
            Toast.makeText(this, "Error showing preview", Toast.LENGTH_SHORT).show();
        }
    }

    private void retakeCapture() {
        // Delete the captured file
        if (currentMode == CaptureMode.PHOTO && capturedImageFile != null && capturedImageFile.exists()) {
            capturedImageFile.delete();
            capturedImageFile = null;
        } else if (currentMode == CaptureMode.VIDEO && capturedVideoFile != null && capturedVideoFile.exists()) {
            videoPreview.stopPlayback();
            capturedVideoFile.delete();
            capturedVideoFile = null;
        }

        currentMode = CaptureMode.NONE;

        // Show camera preview again
        previewView.setVisibility(View.VISIBLE);
        ivPhotoPreview.setVisibility(View.GONE);
        videoPreview.setVisibility(View.GONE);

        // Update buttons
        btnCapture.setVisibility(View.VISIBLE);
        btnCaptureVideo.setVisibility(View.VISIBLE);
        btnRetake.setVisibility(View.GONE);
        btnSend.setVisibility(View.GONE);
        btnSwitchCamera.setVisibility(View.VISIBLE);

        // Clear remarks
        etRemarks.setText("");
    }

    private Bitmap loadAndRotateBitmap(String photoPath) {
        // Load bitmap with scaling to avoid memory issues
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2; // Scale down by 2x for preview
        return BitmapFactory.decodeFile(photoPath, options);
        // ✅ No rotation needed - ImageCompressor already handled it
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
        long mobileTime = deviceInfo.getMobileTime();
        int nss = deviceInfo.getNetworkSignalStrength();

        Toast.makeText(this, "Saving photo...", Toast.LENGTH_SHORT).show();

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.execute(() -> {
            try {
                // ✅ Photo is already compressed from capturePhoto()
                File finalPhotoFile = capturedImageFile;

                Log.d(TAG, "✓ Using compressed photo: " +
                        ImageCompressor.getReadableFileSize(finalPhotoFile.length()));

                // Get next RecNo
                int lastRecNo = db.locationTrackDao().getLastRecNo(mobileNumber);
                int nextRecNo = lastRecNo + 1;

                // Create record with compressed photo path
                LocationTrack track = new LocationTrack(
                        mobileNumber,
                        latitude,
                        longitude,
                        speed,
                        angle,
                        dateTime,
                        sessionId,
                        battery,
                        finalPhotoFile.getAbsolutePath(),
                        null,
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
                        deviceInfo.getImsiNo(),
                        mobileTime,
                        nss,
                        nextRecNo,
                        50
                );

                // Save to local database
                long recordId = db.locationTrackDao().insert(track);
                track.setId((int) recordId);

                Log.d(TAG, "✓ Photo record saved locally with ID: " + recordId + ", RecNo: " + nextRecNo);
                Log.d(TAG, "✓ Photo will be synced by background service");

                // ✅ REMOVED: Immediate upload
                // Photo will sync later with all other location data

                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this,
                            "✓ Photo saved.", Toast.LENGTH_LONG).show();
                    finish();
                });

            } catch (Exception e) {
                Log.e(TAG, "✗ Error saving photo record: " + e.getMessage());
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this,
                            "Failed to save photo", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void saveVideoRecord(String remarks) {
        if (capturedVideoFile == null) {
            Toast.makeText(this, "No video captured", Toast.LENGTH_SHORT).show();
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
        long mobileTime = deviceInfo.getMobileTime();
        int nss = deviceInfo.getNetworkSignalStrength();

        Toast.makeText(this, "Saving video...", Toast.LENGTH_SHORT).show();

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.execute(() -> {
            try {
                File finalVideoFile = capturedVideoFile;

                Log.d(TAG, "✓ Video file: " + finalVideoFile.getAbsolutePath());
                Log.d(TAG, "✓ Video size: " + (finalVideoFile.length() / 1024) + " KB");

                // Get next RecNo
                int lastRecNo = db.locationTrackDao().getLastRecNo(mobileNumber);
                int nextRecNo = lastRecNo + 1;

                // Create record with datatype = 60 for video
                LocationTrack track = new LocationTrack(
                        mobileNumber,
                        latitude,
                        longitude,
                        speed,
                        angle,
                        dateTime,
                        sessionId,
                        battery,
                        null,  // photoPath is null
                        finalVideoFile.getAbsolutePath(),  // videoPath
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
                        deviceInfo.getImsiNo(),
                        mobileTime,
                        nss,
                        nextRecNo,
                        60  // datatype = 60 for VIDEO
                );

                // Save to local database
                long recordId = db.locationTrackDao().insert(track);
                track.setId((int) recordId);

                Log.d(TAG, "✓ Video record saved locally with ID: " + recordId + ", RecNo: " + nextRecNo);
                Log.d(TAG, "✓ Video will be synced by background service");

                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this,
                            "✓ Video saved.", Toast.LENGTH_LONG).show();
                    finish();
                });

            } catch (Exception e) {
                Log.e(TAG, "✗ Error saving video record: " + e.getMessage());
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(CameraActivity.this,
                            "Failed to save video", Toast.LENGTH_SHORT).show();
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

    private void switchCamera() {
        // Toggle between front and back camera
        if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
            Log.d(TAG, "Switched to front camera");
            Toast.makeText(this, "Front Camera", Toast.LENGTH_SHORT).show();
        } else {
            currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
            Log.d(TAG, "Switched to back camera");
            Toast.makeText(this, "Back Camera", Toast.LENGTH_SHORT).show();
        }

        // Restart camera with new selector
        if (cameraProvider != null) {
            bindCameraUseCases(cameraProvider);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (activeRecording != null) {
            activeRecording.stop();
        }

        if (videoPreview != null) {
            videoPreview.stopPlayback();
        }

        if (recordingHandler != null && recordingRunnable != null) {
            recordingHandler.removeCallbacks(recordingRunnable);
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}