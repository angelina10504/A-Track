package com.example.a_track.utils;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmsService {

    // Replace with your Fast2SMS API key
    private static final String API_KEY = "qeTUy2apvc7bWtKNQ89AHhkuSigMsV5dwD4LJGCjZoBzR6f0nreXO6BkaqWm89wYFEZcMdPzoT1hHIUA";
    private static final String API_URL = "https://www.fast2sms.com/dev/bulkV2";

    public interface SmsCallback {
        void onSuccess(String message);
        void onFailure(String error);
    }

    public static void sendOtp(String phoneNumber, String otp, SmsCallback callback) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            try {
                String message = "Your A-Track OTP is: " + otp + ". Valid for 5 minutes.";

                // Prepare POST data
                String postData = "authorization=" + API_KEY +
                        "&variables_values=" + URLEncoder.encode(otp, "UTF-8") +
                        "&route=otp" +
                        "&numbers=" + phoneNumber;

                // Create connection
                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setDoOutput(true);

                // Send request
                OutputStream os = conn.getOutputStream();
                os.write(postData.getBytes());
                os.flush();
                os.close();

                // Get response
                int responseCode = conn.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();

                final String result = response.toString();

                handler.post(() -> {
                    if (responseCode == 200) {
                        callback.onSuccess("OTP sent successfully");
                    } else {
                        callback.onFailure("Failed to send OTP: " + result);
                    }
                });

            } catch (Exception e) {
                handler.post(() -> callback.onFailure("Error: " + e.getMessage()));
            }
        });
    }
}