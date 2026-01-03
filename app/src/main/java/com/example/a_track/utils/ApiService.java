package com.example.a_track.utils;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.example.a_track.database.LocationTrack;

public class ApiService {

    private static final String TAG = "ApiService";

    // ✅ Your domain is correct
    private static final String BASE_URL = "https://droneaeromatix.com/api/";

    private static final int TIMEOUT = 15000; // 15 seconds

    public interface SyncCallback {
        void onSuccess(int syncedCount, List<Integer> trackIds);
        void onFailure(String error);
    }

    public static void syncLocations(List<LocationTrack> tracks, SyncCallback callback) {
        new Thread(() -> {
            try {
                // FILTER: Only sync records where synced = 0
                List<LocationTrack> unsyncedTracks = new ArrayList<>();
                List<Integer> trackIds = new ArrayList<>();

                for (LocationTrack track : tracks) {
                    if (track.getSynced() == 0) { // Only unsynced records
                        unsyncedTracks.add(track);
                        trackIds.add(track.getId());
                    }
                }

                if (unsyncedTracks.isEmpty()) {
                    Log.d(TAG, "✓ No new data to sync");
                    callback.onSuccess(0, new ArrayList<>()); // FIXED: Pass empty list
                    return;
                }

                Log.d(TAG, "📤 Syncing " + unsyncedTracks.size() + " unsynced locations");

                URL url = new URL(BASE_URL + "sync_locations.php");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");

                // Build JSON payload
                JSONObject payload = new JSONObject();
                JSONArray locationsArray = new JSONArray();

                for (LocationTrack track : unsyncedTracks) {
                    JSONObject loc = new JSONObject();
                    loc.put("mobileNumber", track.getMobileNumber());
                    loc.put("latitude", track.getLatitude());
                    loc.put("longitude", track.getLongitude());
                    loc.put("speed", track.getSpeed());
                    loc.put("angle", track.getAngle());
                    loc.put("battery", track.getBattery());
                    loc.put("dateTime", track.getDateTime());
                    loc.put("sessionId", track.getSessionId());
                    locationsArray.put(loc);
                }

                payload.put("locations", locationsArray);

                // Send request
                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                // Read response
                int responseCode = conn.getResponseCode();
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
                Log.d(TAG, "📥 Response: " + responseBody);

                if (responseCode == 200) {
                    try {
                        JSONObject result = new JSONObject(responseBody);
                        boolean success = result.optBoolean("success", false);

                        if (success) {
                            int synced = result.optInt("synced", 0);
                            int duplicates = result.optInt("duplicates", 0);

                            Log.d(TAG, "✓ Sync complete: " + synced + " new, " + duplicates + " duplicates");

                            // FIXED: Pass track IDs back to mark as synced
                            callback.onSuccess(synced, trackIds);
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
                    callback.onFailure("HTTP " + responseCode);
                }

            } catch (Exception e) {
                Log.e(TAG, "✗ Sync exception: " + e.getMessage());
                e.printStackTrace();
                callback.onFailure(e.getMessage());
            }
        }).start();
    }
}