package com.example.a_track.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {
    private static final String PREF_NAME = "ATrackSession";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";
    private static final String KEY_MOBILE_NUMBER = "mobileNumber";
    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_SESSION_DB_ID = "sessionDbId";
    private static final String KEY_LAST_BOOT_TIME = "lastBootTime";

    // ✅ NEW: Track first run for install detection
    private static final String KEY_FIRST_RUN = "isFirstRun";

    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;

    public SessionManager(Context context) {
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

    // ✅ NEW: Check if this is the first run after install/reinstall
    public boolean isFirstRun() {
        return prefs.getBoolean(KEY_FIRST_RUN, true);
    }

    // ✅ NEW: Mark first run as complete
    public void markFirstRunComplete() {
        editor.putBoolean(KEY_FIRST_RUN, false);
        editor.commit();
    }

    // ✅ NEW: Check if device has rebooted since last check
    public boolean hasDeviceRebooted() {
        long currentBootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        long lastBootTime = prefs.getLong(KEY_LAST_BOOT_TIME, 0);

        // If boot time changed significantly (more than 10 seconds difference), device rebooted
        if (lastBootTime == 0) {
            // First time checking, save current boot time
            editor.putLong(KEY_LAST_BOOT_TIME, currentBootTime);
            editor.commit();
            return false;
        }

        if (Math.abs(currentBootTime - lastBootTime) > 10000) { // 10 seconds threshold
            // Device rebooted, update boot time
            editor.putLong(KEY_LAST_BOOT_TIME, currentBootTime);
            editor.commit();
            return true;
        }

        return false;
    }

    public void logout() {
        editor.clear();
        editor.commit();
    }
}