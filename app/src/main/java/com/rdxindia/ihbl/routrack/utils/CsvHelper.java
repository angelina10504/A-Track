package com.rdxindia.ihbl.routrack.utils;

import android.content.Context;
import android.os.Environment;

import com.rdxindia.ihbl.routrack.database.LocationTrack;
import com.rdxindia.ihbl.routrack.database.Session;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CsvHelper {

    // MySQL-compatible datetime format required for server DB import
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

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

            // Header — exact server DB column order (41 fields)
            writer.append("id,imei,level,ch_km,observations,lat,lng,gpsdatetime,server_date_time,")
                  .append("angle,speed,extbat,intbat,dooropen,altitude,ignition,datatype,datasource,")
                  .append("nss,RecNo,ImsiNo,GpsState,InternetState,FlightState,RoamingState,")
                  .append("IsNetThere,IsNwThere,NwLat,NwLong,NwDateTime,mobile_time,IsMoving,")
                  .append("ModelNo,ModelOS,ApkName,TrackState,TextMsg,AudioPath,PhotoPath,VdoPath,usage_data\n");

            // Rows — columns in exact server order
            for (LocationTrack t : tracks) {
                writer.append("").append(",");                             // 1.  id (AUTO_INCREMENT)
                writer.append(csv(t.getMobileNumber())).append(",");      // 2.  imei
                writer.append("").append(",");                             // 3.  level
                writer.append("").append(",");                             // 4.  ch_km
                writer.append("").append(",");                             // 5.  observations
                writer.append(csv(t.getLatitude())).append(",");          // 6.  lat
                writer.append(csv(t.getLongitude())).append(",");         // 7.  lng
                writer.append(csvDate(t.getDateTime())).append(",");      // 8.  gpsdatetime
                writer.append("").append(",");                             // 9.  server_date_time
                writer.append(csv(t.getAngle())).append(",");             // 10. angle
                writer.append(csv(t.getSpeed())).append(",");             // 11. speed
                writer.append("0").append(",");                            // 12. extbat
                writer.append(csv(t.getBattery())).append(",");           // 13. intbat
                writer.append("0").append(",");                            // 14. dooropen
                writer.append("").append(",");                             // 15. altitude
                writer.append("").append(",");                             // 16. ignition
                writer.append(csv(t.getDatatype())).append(",");          // 17. datatype
                writer.append("").append(",");                             // 18. datasource
                writer.append(csv(t.getNss())).append(",");               // 19. nss
                writer.append(csv(t.getRecNo())).append(",");             // 20. RecNo
                writer.append(csv(t.getImsiNo())).append(",");            // 21. ImsiNo
                writer.append(csv(t.getGpsState())).append(",");          // 22. GpsState
                writer.append(csv(t.getInternetState())).append(",");     // 23. InternetState
                writer.append(csv(t.getFlightState())).append(",");       // 24. FlightState
                writer.append(csv(t.getRoamingState())).append(",");      // 25. RoamingState
                writer.append(csv(t.getIsNetThere())).append(",");        // 26. IsNetThere
                writer.append(csv(t.getIsNwThere())).append(",");         // 27. IsNwThere
                writer.append("").append(",");                             // 28. NwLat
                writer.append("").append(",");                             // 29. NwLong
                writer.append("").append(",");                             // 30. NwDateTime
                writer.append(csvDate(t.getMobileTime())).append(",");    // 31. mobile_time
                writer.append(csv(t.getIsMoving())).append(",");          // 32. IsMoving
                writer.append(csv(t.getModelNo())).append(",");           // 33. ModelNo
                writer.append(csv(t.getModelOS())).append(",");           // 34. ModelOS
                writer.append(csv(t.getApkName())).append(",");           // 35. ApkName
                writer.append("").append(",");                             // 36. TrackState
                writer.append(csv(t.getTextMsg())).append(",");           // 37. TextMsg
                writer.append("").append(",");                             // 38. AudioPath
                writer.append(csvFilename(t.getPhotoPath())).append(","); // 39. PhotoPath
                writer.append(csvFilename(t.getVideoPath())).append(","); // 40. VdoPath
                writer.append("").append("\n");                            // 41. usage_data
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
