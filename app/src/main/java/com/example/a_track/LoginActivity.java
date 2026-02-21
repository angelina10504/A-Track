package com.example.a_track;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.a_track.database.AppDatabase;
import com.example.a_track.database.Session;
import com.example.a_track.database.User;
import com.example.a_track.utils.SessionManager;
import com.google.android.material.textfield.TextInputEditText;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.app.AlarmManager;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etMobileNumber, etPassword;
    private Button btnLogin;
    private TextView tvCreateAccount, tvForgotPassword, tvViewUsers;
    private SessionManager sessionManager;
    private AppDatabase db;
    private ExecutorService executorService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize SessionManager first
        sessionManager = new SessionManager(this);

        // Initialize database and executor BEFORE checking session
        db = AppDatabase.getInstance(this);
        executorService = Executors.newSingleThreadExecutor();

        // Check if already logged in
        if (sessionManager.isLoggedIn()) {
            navigateToDashboard();
            return;
        }

        setContentView(R.layout.activity_login);

        initViews();

        btnLogin.setOnClickListener(v -> loginUser());
        tvCreateAccount.setOnClickListener(v -> {
            startActivity(new Intent(LoginActivity.this, RegisterActivity.class));
        });
        tvForgotPassword.setOnClickListener(v -> {
            startActivity(new Intent(LoginActivity.this, ForgotPasswordActivity.class));
        });
    }

    private void initViews() {
        etMobileNumber = findViewById(R.id.etMobileNumber);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvCreateAccount = findViewById(R.id.tvCreateAccount);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);
    }

    private void loginUser() {
        String mobile = etMobileNumber.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (TextUtils.isEmpty(mobile)) {
            Toast.makeText(this, "Enter mobile number", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mobile.length() != 10) {
            Toast.makeText(this, "Enter valid 10-digit mobile number", Toast.LENGTH_SHORT).show();
            return;
        }

        if (TextUtils.isEmpty(password)) {
            Toast.makeText(this, "Enter password", Toast.LENGTH_SHORT).show();
            return;
        }

        btnLogin.setEnabled(false);

        executorService.execute(() -> {
            User user = db.userDao().validateUser(mobile, password);

            runOnUiThread(() -> {
                btnLogin.setEnabled(true);

                if (user != null) {
                    // Create new session
                    String sessionId = UUID.randomUUID().toString();
                    Session session = new Session(mobile, sessionId, System.currentTimeMillis(), null);

                    executorService.execute(() -> {
                        long sessionDbId = db.sessionDao().insert(session);

                        runOnUiThread(() -> {
                            sessionManager.createLoginSession(mobile, sessionId, (int) sessionDbId);

                            // Request battery optimization exemption for alarms to work
                            requestBatteryOptimization();

                            Toast.makeText(LoginActivity.this, "Login successful!", Toast.LENGTH_SHORT).show();
                            navigateToDashboard();
                        });
                    });
                } else {
                    Toast.makeText(this, "Invalid credentials!", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void requestBatteryOptimization() {
        // Battery optimization for Android 6+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            String packageName = getPackageName();

            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e("LoginActivity", "Failed to request battery optimization: " + e.getMessage());
                }
            }
        }

        // Exact alarm permission for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (!alarmManager.canScheduleExactAlarms()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    startActivity(intent);
                    Toast.makeText(this, "Please allow alarms and reminders for safety checks", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e("LoginActivity", "Failed to request alarm permission: " + e.getMessage());
                }
            }
        }

        // ✅ NEW: Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        101);
            }
        }

        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        101);
            }
        }

        // ✅ NEW: Full-screen intent permission for Android 14+ (CRITICAL for lock screen alarms)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {  // Android 14+
            android.app.NotificationManager notificationManager =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            if (notificationManager != null && !notificationManager.canUseFullScreenIntent()) {
                try {
                    // Guide user to enable full-screen intent permission
                    Toast.makeText(this,
                            "Please enable 'Alarms & reminders' permission for lock screen alerts",
                            Toast.LENGTH_LONG).show();

                    // On Android 14+, this opens the "Alarms & reminders" settings
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e("LoginActivity", "Failed to request full-screen intent permission: " + e.getMessage());

                    // Fallback: Open app settings page
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception ex) {
                        Log.e("LoginActivity", "Failed to open app settings: " + ex.getMessage());
                    }
                }
            }
        }
    }

    private void navigateToDashboard() {
        Intent intent = new Intent(LoginActivity.this, DashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}