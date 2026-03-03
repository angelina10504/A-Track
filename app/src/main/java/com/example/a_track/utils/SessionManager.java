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
    private static final String KEY_TIME_OFFSET = "timeOffset";

    // ✅ NEW: Track app install time to detect reinstalls
    private static final String KEY_APP_INSTALL_TIME = "appInstallTime";
    private static final String KEY_INSTALL_LOGGED = "installLogged";
    private static final String KEY_LOGIN_LOGGED = "loginLogged";

    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;
    private Context context;

    public SessionManager(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = prefs.edit();
    }

    public int getLastRecNo() {
        return prefs.getInt("last_rec_no", 0);
    }

    public void saveLastRecNo(int recNo) {
        prefs.edit().putInt("last_rec_no", recNo).apply();
    }


    public void createLoginSession(String mobileNumber, String sessionId, int sessionDbId) {
        // Initial offset: best-effort from system clock; will be refined by NTP on first REBOOT/INSTALL save.
        long initialOffset = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();

        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_MOBILE_NUMBER, mobileNumber);
        editor.putString(KEY_SESSION_ID, sessionId);
        editor.putInt(KEY_SESSION_DB_ID, sessionDbId);
        editor.putLong(KEY_TIME_OFFSET, initialOffset);
        editor.putBoolean(KEY_LOGIN_LOGGED, false);
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

    /** Returns the stored time offset: (accurate UTC time) - SystemClock.elapsedRealtime(). */
    public long getTimeOffset() {
        return prefs.getLong(KEY_TIME_OFFSET, 0);
    }

    /** Saves the NTP-calibrated time offset. */
    public void saveTimeOffset(long offset) {
        prefs.edit().putLong(KEY_TIME_OFFSET, offset).apply();
    }

    /**
     * Returns the current true time in milliseconds.
     * Uses the NTP-calibrated offset when available, so the result is
     * not affected by manual changes to the device clock.
     */
    public long getTrueTimeMs() {
        long offset = getTimeOffset();
        return offset != 0
                ? offset + android.os.SystemClock.elapsedRealtime()
                : System.currentTimeMillis();
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

    // Returns true exactly once after a reboot or new login, then resets itself.
    // Self-resetting: consumes the flag on first true so no subsequent call ever
    // returns true again — even if the service restarts before the next location save.
    public boolean hasDeviceRebooted() {
        boolean loginLogged = prefs.getBoolean(KEY_LOGIN_LOGGED, false);

        if (!loginLogged) {
            editor.putBoolean(KEY_LOGIN_LOGGED, true);
            editor.commit();
            Log.d(TAG, "🔑 Reboot/login detected - datatype=1 will be logged (flag consumed)");
            return true;
        }

        return false;
    }

    // Called by BootReceiver after reboot — keeps session alive but marks it
    // so the service will log a datatype=1 (reboot) entry on first location save.
    public void onDeviceReboot() {
        // Save initial offset from system clock; NTP will correct it on the first REBOOT save.
        long initialOffset = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        editor.putBoolean(KEY_LOGIN_LOGGED, false);
        editor.putLong(KEY_TIME_OFFSET, initialOffset);
        editor.commit();
        Log.d(TAG, "Device reboot detected - login flag reset, time offset initialised");
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