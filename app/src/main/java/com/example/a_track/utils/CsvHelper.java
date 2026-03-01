package com.example.a_track.utils;

import android.content.Context;
import android.os.Environment;

import com.example.a_track.database.LocationTrack;
import com.example.a_track.database.Session;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CsvHelper {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault());

    private static final SimpleDateFormat FILE_DATE_FORMAT =
            new SimpleDateFormat("ddMMyyyy_HHmmss", Locale.getDefault());

    // ── Location Tracks ─────────────────────────────────────────────────────────

    public static File generateLocationTrackCsv(Context context, List<LocationTrack> tracks,
                                                String username, long startTime, long endTime) {
        try {
            String startDate = FILE_DATE_FORMAT.format(new Date(startTime));
            String endDate   = FILE_DATE_FORMAT.format(new Date(endTime));
            String filename  = username + "_" + startDate + "_to_" + endDate + ".csv";

            File csvFile = new File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), filename);
            FileWriter writer = new FileWriter(csvFile);

            // Header — matches server DB column order
            writer.append("RecNo,MobileNumber,SessionId,")
                  .append("Latitude,Longitude,Speed,Angle,")
                  .append("GpsDateTime,MobileTime,")
                  .append("Battery,GpsState,InternetState,FlightState,RoamingState,")
                  .append("IsNetThere,IsNwThere,IsMoving,")
                  .append("ModelNo,ModelOS,ApkName,ImsiNo,Nss,")
                  .append("DataType,TextMsg,PhotoPath,VideoPath\n");

            // Rows
            for (LocationTrack t : tracks) {
                writer.append(csv(t.getRecNo())).append(",");
                writer.append(csv(t.getMobileNumber())).append(",");
                writer.append(csv(t.getSessionId())).append(",");

                writer.append(csv(t.getLatitude())).append(",");
                writer.append(csv(t.getLongitude())).append(",");
                writer.append(csv(t.getSpeed())).append(",");
                writer.append(csv(t.getAngle())).append(",");

                writer.append(csvDate(t.getDateTime())).append(",");
                writer.append(csvDate(t.getMobileTime())).append(",");

                writer.append(csv(t.getBattery())).append(",");
                writer.append(csv(t.getGpsState())).append(",");
                writer.append(csv(t.getInternetState())).append(",");
                writer.append(csv(t.getFlightState())).append(",");
                writer.append(csv(t.getRoamingState())).append(",");

                writer.append(csv(t.getIsNetThere())).append(",");
                writer.append(csv(t.getIsNwThere())).append(",");
                writer.append(csv(t.getIsMoving())).append(",");

                writer.append(csv(t.getModelNo())).append(",");
                writer.append(csv(t.getModelOS())).append(",");
                writer.append(csv(t.getApkName())).append(",");
                writer.append(csv(t.getImsiNo())).append(",");
                writer.append(csv(t.getNss())).append(",");

                writer.append(csv(t.getDatatype())).append(",");
                writer.append(csv(t.getTextMsg())).append(",");
                writer.append(csvFilename(t.getPhotoPath())).append(",");
                writer.append(csvFilename(t.getVideoPath())).append("\n");
            }

            writer.flush();
            writer.close();
            return csvFile;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ── Sessions ────────────────────────────────────────────────────────────────

    public static File generateSessionCsv(Context context, List<Session> sessions,
                                          String username, long startTime, long endTime) {
        try {
            String startDate = FILE_DATE_FORMAT.format(new Date(startTime));
            String endDate   = FILE_DATE_FORMAT.format(new Date(endTime));
            String filename  = username + "_Sessions_" + startDate + "_to_" + endDate + ".csv";

            File csvFile = new File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), filename);
            FileWriter writer = new FileWriter(csvFile);

            writer.append("User,SessionID,LoginTime,LogoutTime,Duration\n");

            for (Session session : sessions) {
                long duration = session.getLogoutTime() - session.getLoginTime();
                long minutes  = duration / (1000 * 60);

                writer.append(csv(username)).append(",");
                writer.append(csv(session.getSessionId())).append(",");
                writer.append(csvDate(session.getLoginTime())).append(",");
                writer.append(csvDate(session.getLogoutTime())).append(",");
                writer.append(minutes + " minutes\n");
            }

            writer.flush();
            writer.close();
            return csvFile;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ── CSV helpers ─────────────────────────────────────────────────────────────

    /** Wrap a string value safely — quotes it if it contains a comma, quote, or newline. */
    private static String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String csv(int value)    { return String.valueOf(value); }
    private static String csv(long value)   { return String.valueOf(value); }
    private static String csv(float value)  { return String.valueOf(value); }
    private static String csv(double value) { return String.valueOf(value); }

    /** Format epoch millis as human-readable date; returns empty string for 0/null. */
    private static String csvDate(long epochMs) {
        if (epochMs <= 0) return "";
        return csv(DATE_FORMAT.format(new Date(epochMs)));
    }

    /** Store only the filename, not the full local device path. */
    private static String csvFilename(String fullPath) {
        if (fullPath == null || fullPath.isEmpty()) return "";
        return csv(new java.io.File(fullPath).getName());
    }
}
