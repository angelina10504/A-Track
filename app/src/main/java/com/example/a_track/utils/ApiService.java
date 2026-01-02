package com.example.a_track.utils;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import com.example.a_track.database.LocationTrack;

public class ApiService {

    private static final String TAG = "ApiService";

    // ✅ Your domain is correct
    private static final String BASE_URL = "https://droneaeromatix.com/api/";

    private static final int TIMEOUT = 15000; // 15 seconds

    public interface SyncCallback {
        void onSuccess(int syncedCount);
        void onFailure(String error);
    }

    public static void syncLocations(List<LocationTrack> tracks, SyncCallback callback) {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "sync_locations.php");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                // ✅ Connection settings
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");

                // 📦 Build JSON payload
                JSONObject payload = new JSONObject();
                JSONArray locationsArray = new JSONArray();

                for (LocationTrack track : tracks) {
                    JSONObject loc = new JSONObject();
                    loc.put("mobileNumber", track.getMobileNumber());
                    loc.put("latitude", track.getLatitude());
                    loc.put("longitude", track.getLongitude());
                    loc.put("speed", track.getSpeed());
                    loc.put("angle", track.getAngle());
                    loc.put("battery", track.getBattery());
                    loc.put("dateTime", track.getDateTime());
                    locationsArray.put(loc);
                }

                payload.put("locations", locationsArray);

                Log.d(TAG, "Syncing " + tracks.size() + " locations to server");
                Log.d(TAG, "Payload: " + payload.toString());

                // 📤 Send request
                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                // 📥 Read response
                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Response code: " + responseCode);

                BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                responseCode == 200
                                        ? conn.getInputStream()
                                        : conn.getErrorStream()
                        )
                );

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();

                String responseBody = response.toString();
                Log.d(TAG, "Response body: " + responseBody);

                // ✅ Parse JSON response properly
                if (responseCode == 200) {
                    try {
                        JSONObject result = new JSONObject(responseBody);
                        boolean success = result.optBoolean("success", false);

                        if (success) {
                            int synced = result.optInt("synced", 0);
                            int duplicates = result.optInt("duplicates", 0);
                            int errors = result.optInt("errors", 0);

                            Log.d(TAG, "✓ Sync successful: " + synced + " synced, " +
                                    duplicates + " duplicates, " + errors + " errors");

                            callback.onSuccess(synced);
                        } else {
                            String message = result.optString("message", "Unknown error");
                            Log.e(TAG, "✗ Sync failed: " + message);
                            callback.onFailure(message);
                        }
                    } catch (Exception parseError) {
                        Log.e(TAG, "✗ JSON parse error: " + parseError.getMessage());
                        callback.onFailure("Invalid server response");
                    }
                } else {
                    Log.e(TAG, "✗ HTTP error: " + responseCode);
                    callback.onFailure("HTTP " + responseCode + ": " + responseBody);
                }

            } catch (Exception e) {
                Log.e(TAG, "✗ Sync exception: " + e.getMessage());
                e.printStackTrace();
                callback.onFailure(e.getMessage());
            }
        }).start();
    }
}