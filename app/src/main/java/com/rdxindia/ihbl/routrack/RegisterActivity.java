package com.rdxindia.ihbl.routrack;


import android.app.AlertDialog;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.rdxindia.ihbl.routrack.database.AppDatabase;
import com.rdxindia.ihbl.routrack.database.User;
import com.rdxindia.ihbl.routrack.utils.ApiService;
import com.google.android.material.textfield.TextInputEditText;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegisterActivity extends AppCompatActivity {

    private TextInputEditText etMobileNumber, etPassword, etConfirmPassword;
    private Button btnRegister;
    private TextView tvBackToLogin;
    private AppDatabase db;
    private ExecutorService executorService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        initViews();
        db = AppDatabase.getInstance(this);
        executorService = Executors.newSingleThreadExecutor();

        btnRegister.setOnClickListener(v -> registerUser());
        tvBackToLogin.setOnClickListener(v -> finish());
    }

    private void initViews() {
        etMobileNumber = findViewById(R.id.etMobileNumber);
        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        btnRegister = findViewById(R.id.btnRegister);
        tvBackToLogin = findViewById(R.id.tvBackToLogin);
    }

    private void registerUser() {
        String mobile = etMobileNumber.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();

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

        if (password.length() < 4) {
            Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!password.equals(confirmPassword)) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
            return;
        }

        btnRegister.setEnabled(false);

        executorService.execute(() -> {
            User existingUser = db.userDao().getUserByMobile(mobile);

            runOnUiThread(() -> {
                if (existingUser != null) {
                    btnRegister.setEnabled(true);
                    Toast.makeText(this, "Mobile number already registered!", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Server-side mobile check (if internet available)
                if (isInternetAvailable()) {
                    ApiService.checkMobileRegistered(mobile, new ApiService.MobileCheckCallback() {
                        @Override
                        public void onRegistered() {
                            runOnUiThread(() -> saveUserLocally(mobile, password));
                        }

                        @Override
                        public void onNotRegistered(String reason) {
                            runOnUiThread(() -> {
                                btnRegister.setEnabled(true);
                                new AlertDialog.Builder(RegisterActivity.this)
                                        .setTitle("Registration Not Allowed")
                                        .setMessage(reason)
                                        .setPositiveButton("OK", null)
                                        .show();
                            });
                        }

                        @Override
                        public void onNetworkError() {
                            runOnUiThread(() -> saveUserLocally(mobile, password));
                        }
                    });
                } else {
                    saveUserLocally(mobile, password);
                }
            });
        });
    }

    private void saveUserLocally(String mobile, String password) {
        User newUser = new User(mobile, password, System.currentTimeMillis());
        executorService.execute(() -> {
            db.userDao().insert(newUser);
            runOnUiThread(() -> {
                Toast.makeText(this, "Registration successful! Please login.", Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    private boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}