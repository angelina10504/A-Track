package com.example.a_track;

import android.content.Intent;
import android.os.Bundle;
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

        // IMPORTANT: Always check session validity on app start
        // This ensures logout even if broadcast receiver didn't trigger
        checkAndValidateSession();

        // Check if already logged in (after validation)
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

    private void checkAndValidateSession() {
        // Check if session exists and if last boot time has changed
        if (sessionManager.isLoggedIn()) {
            long lastBootTime = getLastBootTime();
            long savedBootTime = sessionManager.getLastBootTime();

            // If boot time has changed, it means phone was rebooted
            if (lastBootTime != savedBootTime) {
                Log.d("LoginActivity", "Boot time changed - Phone was rebooted, logging out");

                // Update logout time for the session
                int sessionDbId = sessionManager.getSessionDbId();
                if (sessionDbId != -1) {
                    executorService.execute(() -> {
                        db.sessionDao().updateLogoutTime(sessionDbId, System.currentTimeMillis());
                    });
                }

                // Logout
                sessionManager.logout();
            }
        }
    }

    private long getLastBootTime() {
        // Get system boot time
        return System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
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