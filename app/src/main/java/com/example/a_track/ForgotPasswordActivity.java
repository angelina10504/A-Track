package com.example.a_track;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.a_track.database.AppDatabase;
import com.example.a_track.database.User;
import com.example.a_track.utils.SmsService;
import com.google.android.material.textfield.TextInputEditText;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ForgotPasswordActivity extends AppCompatActivity {

    private LinearLayout layoutMobileInput, layoutOtpInput, layoutPasswordInput;
    private TextInputEditText etMobileNumber, etOtp, etNewPassword, etConfirmPassword;
    private Button btnSendOtp, btnVerifyOtp, btnResetPassword;
    private TextView tvResendOtp, tvTimer, tvBackToLogin;

    private AppDatabase db;
    private ExecutorService executorService;
    private String generatedOtp;
    private String mobileNumber;
    private CountDownTimer countDownTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        initViews();
        db = AppDatabase.getInstance(this);
        executorService = Executors.newSingleThreadExecutor();

        btnSendOtp.setOnClickListener(v -> sendOtp());
        btnVerifyOtp.setOnClickListener(v -> verifyOtp());
        btnResetPassword.setOnClickListener(v -> resetPassword());
        tvResendOtp.setOnClickListener(v -> resendOtp());
        tvBackToLogin.setOnClickListener(v -> finish());
    }

    private void initViews() {
        layoutMobileInput = findViewById(R.id.layoutMobileInput);
        layoutOtpInput = findViewById(R.id.layoutOtpInput);
        layoutPasswordInput = findViewById(R.id.layoutPasswordInput);

        etMobileNumber = findViewById(R.id.etMobileNumber);
        etOtp = findViewById(R.id.etOtp);
        etNewPassword = findViewById(R.id.etNewPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);

        btnSendOtp = findViewById(R.id.btnSendOtp);
        btnVerifyOtp = findViewById(R.id.btnVerifyOtp);
        btnResetPassword = findViewById(R.id.btnResetPassword);

        tvResendOtp = findViewById(R.id.tvResendOtp);
        tvTimer = findViewById(R.id.tvTimer);
        tvBackToLogin = findViewById(R.id.tvBackToLogin);

        // Initially show only mobile input
        layoutMobileInput.setVisibility(View.VISIBLE);
        layoutOtpInput.setVisibility(View.GONE);
        layoutPasswordInput.setVisibility(View.GONE);
    }

    private void sendOtp() {
        mobileNumber = etMobileNumber.getText().toString().trim();

        if (TextUtils.isEmpty(mobileNumber)) {
            Toast.makeText(this, "Enter mobile number", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mobileNumber.length() != 10) {
            Toast.makeText(this, "Enter valid 10-digit mobile number", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSendOtp.setEnabled(false);

        // Check if user exists
        executorService.execute(() -> {
            User user = db.userDao().getUserByMobile(mobileNumber);

            runOnUiThread(() -> {
                if (user == null) {
                    Toast.makeText(this, "Mobile number not registered!", Toast.LENGTH_SHORT).show();
                    btnSendOtp.setEnabled(true);
                } else {
                    // Generate OTP
                    generatedOtp = generateOtp();

                    // Show OTP in Toast for now (as backup)
                    Toast.makeText(this, "OTP: " + generatedOtp + " (Sending SMS...)", Toast.LENGTH_LONG).show();

                    // Send SMS using API
                    SmsService.sendOtp(mobileNumber, generatedOtp, new SmsService.SmsCallback() {
                        @Override
                        public void onSuccess(String message) {
                            Toast.makeText(ForgotPasswordActivity.this,
                                    "OTP sent to +91" + mobileNumber, Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onFailure(String error) {
                            Toast.makeText(ForgotPasswordActivity.this,
                                    "SMS failed. Use OTP shown above", Toast.LENGTH_LONG).show();
                        }
                    });

                    // Show OTP input screen
                    layoutMobileInput.setVisibility(View.GONE);
                    layoutOtpInput.setVisibility(View.VISIBLE);

                    startTimer();
                }
            });
        });
    }

    private String generateOtp() {
        Random random = new Random();
        int otp = 100000 + random.nextInt(900000); // 6-digit OTP
        return String.valueOf(otp);
    }

    private boolean sendSmsOtp(String phoneNumber, String otp) {
        try {
            // Check if SMS permission is granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "SMS permission not granted", Toast.LENGTH_SHORT).show();
                return false;
            }

            SmsManager smsManager = SmsManager.getDefault();
            String message = "Your A-Track password reset OTP is: " + otp +
                    ". Valid for 5 minutes. Do not share this OTP with anyone.";

            // Add +91 country code if not present
            String fullNumber = phoneNumber;
            if (!phoneNumber.startsWith("+")) {
                fullNumber = "+91" + phoneNumber;
            }

            // For testing: Also show OTP in Toast as backup
            Toast.makeText(this, "Sending OTP " + otp + " to " + fullNumber, Toast.LENGTH_LONG).show();

            // Send SMS
            smsManager.sendTextMessage(fullNumber, null, message, null, null);

            android.util.Log.d("SMS", "OTP SMS sent successfully to: " + fullNumber);
            return true;

        } catch (SecurityException se) {
            android.util.Log.e("SMS", "Security Exception: " + se.getMessage());
            Toast.makeText(this, "Permission denied. OTP: " + otp, Toast.LENGTH_LONG).show();
            return false;
        } catch (Exception e) {
            android.util.Log.e("SMS", "Failed to send SMS: " + e.getMessage());
            e.printStackTrace();
            Toast.makeText(this, "SMS failed. OTP: " + otp + " (Use this to verify)", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void startTimer() {
        tvResendOtp.setEnabled(false);
        tvResendOtp.setTextColor(getResources().getColor(android.R.color.darker_gray));

        countDownTimer = new CountDownTimer(60000, 1000) { // 60 seconds
            @Override
            public void onTick(long millisUntilFinished) {
                tvTimer.setText("Resend OTP in " + (millisUntilFinished / 1000) + "s");
            }

            @Override
            public void onFinish() {
                tvTimer.setText("");
                tvResendOtp.setEnabled(true);
                tvResendOtp.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
            }
        }.start();
    }

    private void verifyOtp() {
        String enteredOtp = etOtp.getText().toString().trim();

        if (TextUtils.isEmpty(enteredOtp)) {
            Toast.makeText(this, "Enter OTP", Toast.LENGTH_SHORT).show();
            return;
        }

        if (enteredOtp.equals(generatedOtp)) {
            Toast.makeText(this, "OTP Verified!", Toast.LENGTH_SHORT).show();

            // Stop timer
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }

            // Show password reset screen
            layoutOtpInput.setVisibility(View.GONE);
            layoutPasswordInput.setVisibility(View.VISIBLE);
        } else {
            Toast.makeText(this, "Invalid OTP! Please try again.", Toast.LENGTH_SHORT).show();
        }
    }

    private void resendOtp() {
        generatedOtp = generateOtp();

        Toast.makeText(this, "OTP: " + generatedOtp + " (Sending SMS...)", Toast.LENGTH_LONG).show();

        SmsService.sendOtp(mobileNumber, generatedOtp, new SmsService.SmsCallback() {
            @Override
            public void onSuccess(String message) {
                Toast.makeText(ForgotPasswordActivity.this,
                        "OTP resent to +91" + mobileNumber, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(String error) {
                Toast.makeText(ForgotPasswordActivity.this,
                        "SMS failed. Use OTP shown above", Toast.LENGTH_LONG).show();
            }
        });

        startTimer();
    }

    private void resetPassword() {
        String newPassword = etNewPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();

        if (TextUtils.isEmpty(newPassword)) {
            Toast.makeText(this, "Enter new password", Toast.LENGTH_SHORT).show();
            return;
        }

        if (newPassword.length() < 4) {
            Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!newPassword.equals(confirmPassword)) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
            return;
        }

        btnResetPassword.setEnabled(false);

        executorService.execute(() -> {
            User user = db.userDao().getUserByMobile(mobileNumber);
            if (user != null) {
                user.setPassword(newPassword);
                db.userDao().insert(user); // Updates existing user

                runOnUiThread(() -> {
                    Toast.makeText(this, "Password reset successful! Please login.", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}