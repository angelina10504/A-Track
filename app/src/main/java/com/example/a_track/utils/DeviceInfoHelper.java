package com.example.a_track.utils;

import static androidx.camera.core.CameraXThreads.TAG;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import androidx.core.app.ActivityCompat;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.SignalStrength;
import android.telephony.ServiceState;
import android.util.Log;
import java.util.List;

// For Android 10+ (5G)
import android.os.Build;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthNr;

import com.example.a_track.R;
import com.example.a_track.database.AppDatabase;

public class DeviceInfoHelper {

    // ── Static helpers (no instance required) ────────────────────────────────

    /**
     * Returns true if the app holds background ("All the time") location access.
     *   Android 10+ (API 29+) — ACCESS_BACKGROUND_LOCATION must be granted.
     *   Android 9  and below — ACCESS_FINE_LOCATION is sufficient; background
     *                          access is implicit when FINE is granted.
     */
    public static boolean hasAllTheTimeLocationPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return context.checkSelfPermission(
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return context.checkSelfPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * Builds the standard device-health string written to the textMsg DB column.
     *
     * Format (fixed order):
     *   Loc:[Ok|NA], Net:[Ok|NA], Q:[n], BatOpt:[Yes|No],
     *   PlayPro:[On|Off], BkUsg:[Allowed|NA], Notif:[On|Off]
     *
     * IMPORTANT: contains a synchronous Room query (Q count).
     * Always call from a background thread.
     */
    public static String getDeviceHealthString(Context context) {
        // Loc — GPS hardware on/off
        DeviceInfoHelper di = new DeviceInfoHelper(context);
        String loc = "1".equals(di.getGpsState()) ? "Ok" : "NA";

        // BgLoc — background "All the time" location permission
        String bgLoc = hasAllTheTimeLocationPermission(context) ? "Ok" : "NA";

        // Net
        String net = "1".equals(di.getInternetState()) ? "Ok" : "NA";

        // Q — unsynced record count (synchronous DB read, background thread only)
        int q = 0;
        try {
            SessionManager sm = new SessionManager(context);
            String mobile = sm.getMobileNumber();
            if (mobile != null) {
                q = AppDatabase.getInstance(context)
                        .locationTrackDao().getUnsyncedCount(mobile);
            }
        } catch (Exception e) {
            Log.w("DeviceInfoHelper", "getDeviceHealthString: Q count error: " + e.getMessage());
        }

        // BatOpt — "Yes" = optimised (bad), "No" = exempted (good)
        String batOpt = "No";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm =
                    (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
                batOpt = "Yes";
            }
        }

        // PlayPro
        String playPro = "Off";
        try {
            int ve = Settings.Global.getInt(
                    context.getContentResolver(), "package_verifier_enable", 1);
            int uc = Settings.Global.getInt(
                    context.getContentResolver(), "package_verifier_user_consent", 1);
            playPro = (ve == 1 && uc != -1) ? "On" : "Off";
        } catch (Exception e) {
            Log.w("DeviceInfoHelper", "getDeviceHealthString: PlayPro check error: " + e.getMessage());
        }

        // BkUsg — "NA" = background data blocked
        String bkUsg = "Allowed";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && cm.getRestrictBackgroundStatus()
                    == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
                bkUsg = "NA";
            }
        }

        // Notif
        String notif = "On";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notif = (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) ? "On" : "Off";
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) context.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            notif = (nm != null && nm.areNotificationsEnabled()) ? "On" : "Off";
        }

        return "Loc:" + loc + ", BgLoc:" + bgLoc + ", Net:" + net + ", Q:" + q
                + ", BatOpt:" + batOpt + ", PlayPro:" + playPro
                + ", BkUsg:" + bkUsg + ", Notif:" + notif;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Context context;

    public DeviceInfoHelper(Context context) {
        this.context = context;
    }

    // ✅ NEW: Check if location is from mock provider
    // ✅ CORRECTED: Mock location detection
    public boolean isMockLocation(Location location) {
        if (location == null) {
            return false;
        }

        try {
            // Android 12+ (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (location.isMock()) {
                    Log.w("DeviceInfoHelper", "⚠️ Mock location detected via isMock()");
                    return true;
                }
            }
            // Android 4.3+ (API 18+)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                if (location.isFromMockProvider()) {
                    Log.w("DeviceInfoHelper", "⚠️ Mock location detected via isFromMockProvider()");
                    return true;
                }
            }

        } catch (Exception e) {
            Log.e("DeviceInfoHelper", "Error checking mock location: " + e.getMessage());
        }

        return false;
    }

    // Check if GPS is enabled
    public String getGpsState() {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            return isGpsEnabled ? "1" : "0";
        }
        return "0";
    }

    // Check if mobile data is enabled
    public String getInternetState() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
                return isConnected ? "1" : "0";
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "0";
    }

    // Check if flight mode is enabled
    public String getFlightState() {
        int airplaneModeOn = Settings.Global.getInt(
                context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                0
        );
        return airplaneModeOn != 0 ? "1" : "0";
    }

    // Check if roaming is enabled
    public String getRoamingState() {
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                boolean isRoaming = telephonyManager.isNetworkRoaming();
                return isRoaming ? "1" : "0";
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "0";
    }

    // Check if network is available (WiFi or Mobile Data)
    public String getIsNetThere() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo networkInfo = cm.getActiveNetworkInfo();
            return (networkInfo != null && networkInfo.isConnected()) ? "1" : "0";
        }
        return "0";
    }

    // Check if network location provider is available
    public String getIsNwThere() {
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

            if (telephonyManager != null) {
                // ✅ FIXED: Check permission first before accessing ServiceState
                boolean hasPermission = true;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    hasPermission = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED;
                }

                // For Android 10+ (API 29+), ServiceState requires READ_PHONE_STATE permission
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && hasPermission) {
                    try {
                        ServiceState serviceState = telephonyManager.getServiceState();

                        if (serviceState != null) {
                            int state = serviceState.getState();

                            Log.d("DeviceInfoHelper", "ServiceState: " + state +
                                    " (0=IN_SERVICE, 1=OUT_OF_SERVICE, 2=EMERGENCY_ONLY, 3=POWER_OFF)");

                            return (state == ServiceState.STATE_IN_SERVICE) ? "1" : "0";
                        }

                    } catch (Exception e) {
                        Log.w("DeviceInfoHelper", "ServiceState failed: " + e.getMessage());
                    }
                }

                // ✅ FALLBACK: Use simpler method that works without permission
                // Check if SIM card is present and ready
                int simState = telephonyManager.getSimState();

                // SIM_STATE_READY means SIM is present and working
                if (simState == TelephonyManager.SIM_STATE_READY) {
                    // Check network type
                    int networkType = telephonyManager.getNetworkType();

                    Log.d("DeviceInfoHelper", "SIM Ready, Network Type: " + networkType +
                            " (0=UNKNOWN, 1=GPRS, 2=EDGE, 3=UMTS, 13=LTE, 20=NR/5G)");

                    // If network type is not UNKNOWN, there's a cellular connection
                    return (networkType != TelephonyManager.NETWORK_TYPE_UNKNOWN) ? "1" : "0";
                }

                Log.d("DeviceInfoHelper", "SIM State: " + simState + " (Not ready)");
                return "0";
            }

            return "0";

        } catch (Exception e) {
            Log.e("DeviceInfoHelper", "Error checking cellular network: " + e.getMessage());
            return "0";
        }
    }

    // Get Android OS version
    public String getModelOS() {
        return Build.VERSION.RELEASE; // e.g., "13", "14"
    }

    // Get device model number
    public String getModelNo() {
        return Build.MODEL; // e.g., "Pixel 4a", "SM-G991B"
    }

    // Get APK name (package name)
    @SuppressLint("RestrictedApi")
    public String getApkName() {
        try {
            String appName = context.getString(R.string.app_name);
            String version = getAppVersion();
            return appName + " " + version;
        } catch (Exception e) {
            Log.e(TAG, "Error getting app name: " + e.getMessage());
            return "Unknown";
        }
    }

    // Get IMSI Number (requires READ_PHONE_STATE permission)
    public String getImsiNo() {
        try {
            String ImsiNo = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
            return ImsiNo != null ? ImsiNo : "";
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    // Get app version
    public String getAppVersion() {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return "1.0";
    }

    // Check if device is currently moving (based on speed)
    public String getIsMoving(float speed) {
        // Consider moving if speed > 0.5 m/s (~ 1.8 km/h)
        return speed > 0.5f ? "1" : "0";
    }

    // Get network type name
    public String getNetworkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (activeNetwork != null) {
                    return activeNetwork.getTypeName(); // "WIFI", "MOBILE", etc.
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }
    public long getMobileTime() {
        return System.currentTimeMillis();
    }

    // Get Network Signal Strength (0-11 based on SINR)
    public int getNetworkSignalStrength() {
        try {
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Android 9+ (API 28+)
                    SignalStrength signalStrength = telephonyManager.getSignalStrength();
                    if (signalStrength != null) {
                        int level = signalStrength.getLevel(); // 0-4
                        // Map 0-4 to 0-11 scale
                        // 0 = 0-2, 1 = 3-5, 2 = 6-8, 3 = 9-10, 4 = 11
                        if (level == 0) return 1;
                        if (level == 1) return 4;
                        if (level == 2) return 7;
                        if (level == 3) return 9;
                        if (level == 4) return 11;
                    }
                } else {
                    // Below Android 9 - use cell info
                    List<CellInfo> cellInfoList = telephonyManager.getAllCellInfo();
                    if (cellInfoList != null && !cellInfoList.isEmpty()) {
                        for (CellInfo cellInfo : cellInfoList) {
                            if (cellInfo.isRegistered()) {
                                int dbm = getCellSignalStrength(cellInfo);
                                // Convert dBm to 0-11 scale (SINR approximation)
                                return mapDbmToScale(dbm);
                            }
                        }
                    }
                }
            }
        } catch (SecurityException e) {
            Log.e("DeviceInfoHelper", "Permission denied for signal strength");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0; // No signal or error
    }

    // Helper: Get signal strength from CellInfo
    private int getCellSignalStrength(CellInfo cellInfo) {
        if (cellInfo instanceof CellInfoLte) {
            CellSignalStrengthLte lte = ((CellInfoLte) cellInfo).getCellSignalStrength();
            return lte.getDbm();
        } else if (cellInfo instanceof CellInfoGsm) {
            CellSignalStrengthGsm gsm = ((CellInfoGsm) cellInfo).getCellSignalStrength();
            return gsm.getDbm();
        } else if (cellInfo instanceof CellInfoWcdma) {
            CellSignalStrengthWcdma wcdma = ((CellInfoWcdma) cellInfo).getCellSignalStrength();
            return wcdma.getDbm();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo instanceof CellInfoNr) {
            CellSignalStrengthNr nr = (CellSignalStrengthNr) ((CellInfoNr) cellInfo).getCellSignalStrength();
            return nr.getDbm();
        }
        return -120; // Very weak signal
    }

    // Helper: Map dBm to 0-11 scale (approximating SINR)
    private int mapDbmToScale(int dbm) {
        if (dbm >= -70) return 11;  // Excellent
        if (dbm >= -80) return 9;   // Good
        if (dbm >= -90) return 7;   // Mid
        if (dbm >= -100) return 4;  // Acceptable
        if (dbm >= -110) return 2;  // Weak
        return 0;                    // Very weak/No signal
    }
}