package com.example.a_track.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

public class SessionManager {
    private static final String TAG = "SessionManager";
    private static final String PREF_NAME = "ATrackSession";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";
    private static final String KEY_MOBILE_NUMBER = "mobileNumber";
    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_SESSION_DB_ID = "sessionDbId";
    private static final String KEY_LAST_BOOT_TIME = "lastBootTime";

    // ✅ NEW: Track app install time to detect reinstalls
    private static final String KEY_APP_INSTALL_TIME = "appInstallTime";
    private static final String KEY_INSTALL_LOGGED = "installLogged";

    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;
    private Context context;

    public SessionManager(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = prefs.edit();
    }

    public void createLoginSession(String mobileNumber, String sessionId, int sessionDbId) {
        // Get current boot time
        long currentBootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();

        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_MOBILE_NUMBER, mobileNumber);
        editor.putString(KEY_SESSION_ID, sessionId);
        editor.putInt(KEY_SESSION_DB_ID, sessionDbId);
        editor.putLong(KEY_LAST_BOOT_TIME, currentBootTime);
        editor.commit();

        Log.d(TAG, "Login session created for: " + mobileNumber);
    }

    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    public String getMobileNumber() {
        return prefs.getString(KEY_MOBILE_NUMBER, null);
    }

    public String getSessionId() {
        return prefs.getString(KEY_SESSION_ID, null);
    }

    public int getSessionDbId() {
        return prefs.getInt(KEY_SESSION_DB_ID, -1);
    }

    public long getLastBootTime() {
        return prefs.getLong(KEY_LAST_BOOT_TIME, 0);
    }

    // ✅ FIXED: Check if this is first run (install/reinstall)
    public boolean isFirstRun() {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);

            long actualInstallTime = packageInfo.firstInstallTime;
            long savedInstallTime = prefs.getLong(KEY_APP_INSTALL_TIME, 0);

            // Check if install has been logged for this install time
            boolean installLogged = prefs.getBoolean(KEY_INSTALL_LOGGED, false);

            if (savedInstallTime == 0) {
                // First time ever - save install time
                Log.d(TAG, "📱 FIRST INSTALL detected");
                editor.putLong(KEY_APP_INSTALL_TIME, actualInstallTime);
                editor.putBoolean(KEY_INSTALL_LOGGED, false);
                editor.commit();
                return true;
            }

            // Check if app was reinstalled (install time changed)
            if (actualInstallTime != savedInstallTime) {
                Log.d(TAG, "📱 REINSTALL detected (install time changed)");
                editor.putLong(KEY_APP_INSTALL_TIME, actualInstallTime);
                editor.putBoolean(KEY_INSTALL_LOGGED, false);
                editor.commit();
                return true;
            }

            // Check if install event was already logged for current install
            if (!installLogged) {
                Log.d(TAG, "📱 Install event not yet logged for this session");
                return true;
            }

            Log.d(TAG, "Not first run - install already logged");
            return false;

        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Error checking install time: " + e.getMessage());
            return false;
        }
    }

    // ✅ FIXED: Mark first run as complete
    public void markFirstRunComplete() {
        Log.d(TAG, "✓ Marking install as logged");
        editor.putBoolean(KEY_INSTALL_LOGGED, true);
        editor.commit();
    }

    // ✅ Check if device has rebooted since last check
    public boolean hasDeviceRebooted() {
        long currentBootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        long lastBootTime = prefs.getLong(KEY_LAST_BOOT_TIME, 0);

        // If boot time changed significantly (more than 10 seconds difference), device rebooted
        if (lastBootTime == 0) {
            // First time checking, save current boot time
            Log.d(TAG, "First boot time check - saving current boot time");
            editor.putLong(KEY_LAST_BOOT_TIME, currentBootTime);
            editor.commit();
            return false;
        }

        // Allow 10 second tolerance for time drift
        long timeDifference = Math.abs(currentBootTime - lastBootTime);

        if (timeDifference > 10000) { // 10 seconds threshold
            Log.d(TAG, "🔄 DEVICE REBOOT detected (boot time changed by " +
                    (timeDifference / 1000) + " seconds)");
            // Device rebooted, update boot time
            editor.putLong(KEY_LAST_BOOT_TIME, currentBootTime);
            editor.commit();
            return true;
        }

        return false;
    }

    public void logout() {
        Log.d(TAG, "Logging out - clearing session data");
        // ✅ IMPORTANT: Keep app install time when logging out
        long installTime = prefs.getLong(KEY_APP_INSTALL_TIME, 0);
        boolean installLogged = prefs.getBoolean(KEY_INSTALL_LOGGED, false);

        editor.clear();

        // Restore install tracking data
        editor.putLong(KEY_APP_INSTALL_TIME, installTime);
        editor.putBoolean(KEY_INSTALL_LOGGED, installLogged);
        editor.commit();
    }
}