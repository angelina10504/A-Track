package com.rdxindia.ihbl.routrack.utils;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.rdxindia.ihbl.routrack.BuildConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Captures recent app logs to a shareable text file for remote debugging.
 * Purely a debugging utility — does not touch sync, tracking, or session logic.
 */
public class LogCapture {

    private static final String TAG = "LogCapture";

    private static final String PACKAGE = "com.rdxindia.ihbl.routrack";

    /** Lines mentioning the package OR any of these tags are kept. */
    private static final String[] RELEVANT_TAGS = {
            "LocationTrackingService",
            "ApiService",
            "SessionManager",
            "BootReceiver",
            "NetworkMonitor",
            "CameraActivity",
            "AlarmReceiver"
    };

    /**
     * Dumps the last 5000 logcat lines (filtered to this app) plus a debug header
     * into a timestamped file in the app's external cache dir.
     *
     * @return the written file, or {@code null} on failure.
     */
    public static File captureLogsToFile(Context context, SessionManager sessionManager) {
        BufferedReader reader = null;
        BufferedWriter writer = null;
        try {
            String dateTimeStamp =
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String filename = "routrack_logs_" + dateTimeStamp + ".txt";

            // External cache needs no runtime permission (Android 10+).
            File cacheDir = context.getExternalCacheDir();
            if (cacheDir == null) {
                Log.e(TAG, "External cache dir unavailable — cannot capture logs");
                return null;
            }
            File logFile = new File(cacheDir, filename);

            writer = new BufferedWriter(new FileWriter(logFile));
            writer.write(buildHeader(context, sessionManager));

            // -d = dump and exit, -t 5000 = last 5000 lines
            Process process = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-d", "-t", "5000"});
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            int kept = 0;
            while ((line = reader.readLine()) != null) {
                if (isRelevant(line)) {
                    writer.write(line);
                    writer.write("\n");
                    kept++;
                }
            }
            writer.flush();

            Log.d(TAG, "Captured " + kept + " relevant log lines -> " + logFile.getAbsolutePath());
            return logFile;

        } catch (IOException e) {
            Log.e(TAG, "Failed to capture logs: " + e.getMessage(), e);
            return null;
        } finally {
            closeQuietly(reader);
            closeQuietly(writer);
        }
    }

    private static String buildHeader(Context context, SessionManager sessionManager) {
        String now = formatTime(System.currentTimeMillis());

        String androidId;
        try {
            androidId = new DeviceInfoHelper(context).getImsiNo();
        } catch (Exception e) {
            androidId = "";
        }

        return "====== ROU TRACK LOG EXPORT ======\n"
                + "Date: " + now + "\n"
                + "App Version: " + BuildConfig.VERSION_NAME + "\n"
                + "Android Version: " + Build.VERSION.RELEASE + "\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Mobile: " + sessionManager.getMobileNumber() + "\n"
                + "Android ID: " + androidId + "\n"
                + "Verification: " + sessionManager.getVerificationStatus() + "\n"
                + "Logged In: " + sessionManager.isLoggedIn() + "\n"
                + "Last RecNo: " + sessionManager.getLastRecNo() + "\n"
                + "Last Tracked Lat: " + sessionManager.getLastTrackedLat() + "\n"
                + "Last Tracked Lng: " + sessionManager.getLastTrackedLng() + "\n"
                + "Last Tracked Time: " + formatTime(sessionManager.getLastTrackedTime()) + "\n"
                + "==================================\n\n";
    }

    private static boolean isRelevant(String line) {
        if (line.contains(PACKAGE)) {
            return true;
        }
        for (String tag : RELEVANT_TAGS) {
            if (line.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private static String formatTime(long timeMs) {
        if (timeMs <= 0) {
            return "N/A";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timeMs));
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
