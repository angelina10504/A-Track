package com.example.a_track.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import androidx.core.app.ActivityCompat;

public class DeviceInfoHelper {

    private Context context;

    public DeviceInfoHelper(Context context) {
        this.context = context;
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
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            return isNetworkEnabled ? "1" : "0";
        }
        return "0";
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
    public String getApkName() {
        return context.getPackageName(); // e.g., "com.example.a_track"
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
}