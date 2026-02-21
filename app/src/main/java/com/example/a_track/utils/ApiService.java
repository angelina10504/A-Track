package com.example.a_track.utils;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

import com.example.a_track.database.LocationTrack;

public class ApiService {

    private static final String TAG = "ApiService";

    // ✅ V2 API base
    private static final String BASE_URL = "https://droneaeromatix.com/api2/";

    private static final int TIMEOUT = 15000;
    private static final int PHOTO_TIMEOUT = 30000;
    private static final int VIDEO_TIMEOUT = 60000;  // ✅ NEW: Longer timeout for videos

    /* ================================
       SYNC LOCATIONS (JSON)
       ================================ */

    public interface SyncCallback {
        void onSuccess(int syncedCount, List<Integer> syncedRecNos); // ✅ Now RecNos not IDs
        void onFailure(String error);
    }

    public static void syncLocations(List<LocationTrack> tracks, SyncCallback callback) {
        new Thread(() -> {
            try {
                List<LocationTrack> unsynced = new ArrayList<>();
                for (LocationTrack t : tracks) {
                    if (t.getSynced() == 0) {
                        unsynced.add(t);
                    }
                }

                if (unsynced.isEmpty()) {
                    Log.d(TAG, "✓ No new data to sync");
                    callback.onSuccess(0, new ArrayList<>());
                    return;
                }

                Log.d(TAG, "📤 Syncing " + unsynced.size() + " records");

                URL url = new URL(BASE_URL + "sync_locations.php");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(TIMEOUT);
                conn.setReadTimeout(TIMEOUT);
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");

                JSONArray arr = new JSONArray();
                for (LocationTrack t : unsynced) {
                    JSONObject o = new JSONObject();
                    o.put("mobileNumber", t.getMobileNumber());
                    o.put("sessionId", t.getSessionId());
                    o.put("RecNo", t.getRecNo());  // ✅ Critical for dedup
                    o.put("nss", t.getNss());
                    o.put("latitude", t.getLatitude());
                    o.put("longitude", t.getLongitude());
                    o.put("speed", t.getSpeed());
                    o.put("angle", t.getAngle());
                    o.put("battery", t.getBattery());
                    o.put("dateTime", t.getDateTime());
                    o.put("mobileTime", t.getMobileTime());
                    o.put("datatype", t.getDatatype());
                    if (t.getPhotoPath() != null && !t.getPhotoPath().isEmpty()) {
                        o.put("photoPath", new File(t.getPhotoPath()).getName());
                    }
                    if (t.getVideoPath() != null && !t.getVideoPath().isEmpty()) {
                        o.put("videoPath", new File(t.getVideoPath()).getName());
                    }
                    if (t.getTextMsg() != null && !t.getTextMsg().isEmpty()) {
                        o.put("textMsg", t.getTextMsg());
                    }
                    o.put("gpsState", t.getGpsState());
                    o.put("internetState", t.getInternetState());
                    o.put("flightState", t.getFlightState());
                    o.put("roamingState", t.getRoamingState());
                    o.put("isNetThere", t.getIsNetThere());
                    o.put("isNwThere", t.getIsNwThere());
                    o.put("isMoving", t.getIsMoving());
                    o.put("modelNo", t.getModelNo());
                    o.put("modelOS", t.getModelOS());
                    o.put("apkName", t.getApkName());
                    o.put("imsiNo", t.getImsiNo());
                    arr.put(o);
                }

                JSONObject payload = new JSONObject();
                payload.put("locations", arr);

                conn.getOutputStream().write(payload.toString().getBytes("UTF-8"));
                conn.getOutputStream().flush();
                conn.getOutputStream().close();

                int code = conn.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code == 200 ? conn.getInputStream() : conn.getErrorStream()
                ));

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                String responseBody = sb.toString();
                Log.d(TAG, "📥 Response: " + responseBody);

                if (code == 200) {
                    JSONObject res = new JSONObject(responseBody);
                    if (res.optBoolean("success")) {
                        // ✅ Use server-confirmed RecNos to find matching DB ids
                        JSONArray recNosArray = res.optJSONArray("syncedRecNos");
                        List<Integer> confirmedRecNos = new ArrayList<>();
                        if (recNosArray != null) {
                            for (int i = 0; i < recNosArray.length(); i++) {
                                confirmedRecNos.add(recNosArray.getInt(i));
                            }
                        }

                        // ✅ Map RecNos back to DB ids
                        List<Integer> confirmedIds = new ArrayList<>();
                        for (LocationTrack t : unsynced) {
                            if (confirmedRecNos.contains(t.getRecNo())) {
                                confirmedIds.add(t.getId());
                            }
                        }

                        int synced = res.optInt("synced", 0);
                        int duplicates = res.optInt("duplicates", 0);
                        Log.d(TAG, "✓ Sync: " + synced + " new, " + duplicates + " duplicates, "
                                + confirmedIds.size() + " marked synced");
                        callback.onSuccess(synced, confirmedIds);  // ✅ Only confirmed ids
                    } else {
                        callback.onFailure(res.optString("message", "Unknown error"));
                    }
                } else {
                    callback.onFailure("HTTP " + code);
                }

            } catch (Exception e) {
                Log.e(TAG, "✗ Sync exception: " + e.getMessage());
                callback.onFailure(e.getMessage());
            }
        }).start();
    }

    /* ================================
       UPLOAD PHOTO (MULTIPART)
       ================================ */

    public interface PhotoUploadCallback {
        void onSuccess(String photoPath);
        void onFailure(String error);
    }

    public static void uploadPhoto(LocationTrack track, File photo, PhotoUploadCallback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                if (photo == null || !photo.exists()) {
                    Log.e(TAG, "Photo file missing");
                    callback.onFailure("Photo file missing");
                    return;
                }

                Log.d(TAG, "📤 Uploading photo: " + photo.getName() + " (" + photo.length() + " bytes)");

                String boundary = "----ROUTrackBoundary" + System.currentTimeMillis();
                String lineEnd = "\r\n";
                String twoHyphens = "--";

                URL url = new URL(BASE_URL + "upload_photo.php");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(PHOTO_TIMEOUT);
                conn.setReadTimeout(PHOTO_TIMEOUT);
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream out = new DataOutputStream(conn.getOutputStream());

                // Add required form fields
                writeFormField(out, boundary, "mobileNumber", track.getMobileNumber());
                writeFormField(out, boundary, "nss", String.valueOf(track.getNss()));
                writeFormField(out, boundary, "recNo", String.valueOf(track.getRecNo()));
                writeFormField(out, boundary, "dateTime", String.valueOf(track.getDateTime()));
                writeFormField(out, boundary, "mobileTime", String.valueOf(track.getMobileTime()));
                writeFormField(out, boundary, "latitude", String.valueOf(track.getLatitude()));
                writeFormField(out, boundary, "longitude", String.valueOf(track.getLongitude()));
                writeFormField(out, boundary, "speed", String.valueOf(track.getSpeed()));
                writeFormField(out, boundary, "angle", String.valueOf(track.getAngle()));
                writeFormField(out, boundary, "battery", String.valueOf(track.getBattery()));
                writeFormField(out, boundary, "datatype", String.valueOf(track.getDatatype()));  // ✅ FIXED: Use actual datatype
                writeFormField(out, boundary, "gpsState", track.getGpsState());
                writeFormField(out, boundary, "internetState", track.getInternetState());
                writeFormField(out, boundary, "flightState", track.getFlightState());
                writeFormField(out, boundary, "roamingState", track.getRoamingState());
                writeFormField(out, boundary, "isNetThere", track.getIsNetThere());
                writeFormField(out, boundary, "isNwThere", track.getIsNwThere());
                writeFormField(out, boundary, "isMoving", track.getIsMoving());
                writeFormField(out, boundary, "modelNo", track.getModelNo());
                writeFormField(out, boundary, "modelOS", track.getModelOS());
                writeFormField(out, boundary, "apkName", track.getApkName());
                writeFormField(out, boundary, "imsiNo", track.getImsiNo());

                // Add text message if exists
                if (track.getTextMsg() != null && !track.getTextMsg().isEmpty()) {
                    writeFormField(out, boundary, "textMsg", track.getTextMsg());
                }

                // Add photo file
                String mime = URLConnection.guessContentTypeFromName(photo.getName());
                if (mime == null) mime = "image/jpeg";

                out.writeBytes(twoHyphens + boundary + lineEnd);
                out.writeBytes("Content-Disposition: form-data; name=\"photo\"; filename=\"" +
                        photo.getName() + "\"" + lineEnd);
                out.writeBytes("Content-Type: " + mime + lineEnd);
                out.writeBytes("Content-Transfer-Encoding: binary" + lineEnd);
                out.writeBytes(lineEnd);

                FileInputStream fis = new FileInputStream(photo);
                byte[] buffer = new byte[4096];
                int bytes;
                long totalBytes = 0;
                while ((bytes = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytes);
                    totalBytes += bytes;
                }
                fis.close();

                Log.d(TAG, "Photo uploaded: " + totalBytes + " bytes sent");

                out.writeBytes(lineEnd);
                out.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
                out.flush();
                out.close();

                int code = conn.getResponseCode();
                Log.d(TAG, "Upload response code: " + code);

                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code == 200 ? conn.getInputStream() : conn.getErrorStream()
                ));

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                String responseBody = sb.toString();
                Log.d(TAG, "📥 Upload response: " + responseBody);

                if (code == 200) {
                    JSONObject res = new JSONObject(responseBody);
                    if (res.optBoolean("success")) {
                        String photoPath = res.optString("photoPath", "");
                        Log.d(TAG, "✓ Photo uploaded successfully: " + photoPath);
                        callback.onSuccess(photoPath);
                    } else {
                        String msg = res.optString("message", "Upload failed");
                        Log.e(TAG, "✗ Upload failed: " + msg);
                        callback.onFailure(msg);
                    }
                } else {
                    Log.e(TAG, "✗ HTTP error: " + code);
                    callback.onFailure("HTTP " + code + ": " + responseBody);
                }

            } catch (Exception e) {
                Log.e(TAG, "✗ Upload exception: " + e.getMessage());
                e.printStackTrace();
                callback.onFailure(e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /* ================================
       ✅ NEW: UPLOAD VIDEO (MULTIPART)
       ================================ */

    public interface VideoUploadCallback {
        void onSuccess(String videoPath);
        void onFailure(String error);
    }

    public static void uploadVideo(LocationTrack track, File video, VideoUploadCallback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                if (video == null || !video.exists()) {
                    Log.e(TAG, "Video file missing");
                    callback.onFailure("Video file missing");
                    return;
                }

                Log.d(TAG, "📤 Uploading video: " + video.getName() + " (" + video.length() + " bytes)");

                String boundary = "----ROUTrackBoundary" + System.currentTimeMillis();
                String lineEnd = "\r\n";
                String twoHyphens = "--";

                URL url = new URL(BASE_URL + "upload_video.php");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(VIDEO_TIMEOUT);
                conn.setReadTimeout(VIDEO_TIMEOUT);
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream out = new DataOutputStream(conn.getOutputStream());

                // Add required form fields
                writeFormField(out, boundary, "mobileNumber", track.getMobileNumber());
                writeFormField(out, boundary, "nss", String.valueOf(track.getNss()));
                writeFormField(out, boundary, "recNo", String.valueOf(track.getRecNo()));
                writeFormField(out, boundary, "dateTime", String.valueOf(track.getDateTime()));
                writeFormField(out, boundary, "mobileTime", String.valueOf(track.getMobileTime()));
                writeFormField(out, boundary, "latitude", String.valueOf(track.getLatitude()));
                writeFormField(out, boundary, "longitude", String.valueOf(track.getLongitude()));
                writeFormField(out, boundary, "speed", String.valueOf(track.getSpeed()));
                writeFormField(out, boundary, "angle", String.valueOf(track.getAngle()));
                writeFormField(out, boundary, "battery", String.valueOf(track.getBattery()));
                writeFormField(out, boundary, "datatype", String.valueOf(track.getDatatype()));  // Should be 60
                writeFormField(out, boundary, "gpsState", track.getGpsState());
                writeFormField(out, boundary, "internetState", track.getInternetState());
                writeFormField(out, boundary, "flightState", track.getFlightState());
                writeFormField(out, boundary, "roamingState", track.getRoamingState());
                writeFormField(out, boundary, "isNetThere", track.getIsNetThere());
                writeFormField(out, boundary, "isNwThere", track.getIsNwThere());
                writeFormField(out, boundary, "isMoving", track.getIsMoving());
                writeFormField(out, boundary, "modelNo", track.getModelNo());
                writeFormField(out, boundary, "modelOS", track.getModelOS());
                writeFormField(out, boundary, "apkName", track.getApkName());
                writeFormField(out, boundary, "imsiNo", track.getImsiNo());

                // Add text message if exists
                if (track.getTextMsg() != null && !track.getTextMsg().isEmpty()) {
                    writeFormField(out, boundary, "textMsg", track.getTextMsg());
                }

                // Add video file
                String mime = URLConnection.guessContentTypeFromName(video.getName());
                if (mime == null) mime = "video/mp4";

                out.writeBytes(twoHyphens + boundary + lineEnd);
                out.writeBytes("Content-Disposition: form-data; name=\"video\"; filename=\"" +
                        video.getName() + "\"" + lineEnd);
                out.writeBytes("Content-Type: " + mime + lineEnd);
                out.writeBytes("Content-Transfer-Encoding: binary" + lineEnd);
                out.writeBytes(lineEnd);

                FileInputStream fis = new FileInputStream(video);
                byte[] buffer = new byte[4096];
                int bytes;
                long totalBytes = 0;
                while ((bytes = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytes);
                    totalBytes += bytes;
                }
                fis.close();

                Log.d(TAG, "Video uploaded: " + totalBytes + " bytes sent");

                out.writeBytes(lineEnd);
                out.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
                out.flush();
                out.close();

                int code = conn.getResponseCode();
                Log.d(TAG, "Upload response code: " + code);

                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code == 200 ? conn.getInputStream() : conn.getErrorStream()
                ));

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                String responseBody = sb.toString();
                Log.d(TAG, "📥 Upload response: " + responseBody);

                if (code == 200) {
                    JSONObject res = new JSONObject(responseBody);
                    if (res.optBoolean("success")) {
                        String videoPath = res.optString("videoPath", "");
                        Log.d(TAG, "✓ Video uploaded successfully: " + videoPath);
                        callback.onSuccess(videoPath);
                    } else {
                        String msg = res.optString("message", "Upload failed");
                        Log.e(TAG, "✗ Upload failed: " + msg);
                        callback.onFailure(msg);
                    }
                } else {
                    Log.e(TAG, "✗ HTTP error: " + code);
                    callback.onFailure("HTTP " + code + ": " + responseBody);
                }

            } catch (Exception e) {
                Log.e(TAG, "✗ Upload exception: " + e.getMessage());
                e.printStackTrace();
                callback.onFailure(e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /* ================================
       MULTIPART HELPER
       ================================ */

    private static void writeFormField(DataOutputStream out, String boundary,
                                       String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n");
        out.writeBytes("Content-Type: text/plain; charset=UTF-8\r\n");
        out.writeBytes("\r\n");
        out.writeBytes(value + "\r\n");
    }
}