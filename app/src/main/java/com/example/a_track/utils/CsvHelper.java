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

    // Generate CSV for Location Tracks
    public static File generateLocationTrackCsv(Context context, List<LocationTrack> tracks,
                                                String username, long startTime, long endTime) {
        try {
            // Create filename: Username_FilteredDateTime.csv
            String startDate = FILE_DATE_FORMAT.format(new Date(startTime));
            String endDate = FILE_DATE_FORMAT.format(new Date(endTime));
            String filename = username + "_" + startDate + "_to_" + endDate + ".csv";

            // Create file in app's external files directory
            File csvFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), filename);
            FileWriter writer = new FileWriter(csvFile);

            // Write CSV header
            writer.append("User,Latitude,Longitude,GpsDateTime,Angle,Speed,Battery\n");

            // Write data rows
            for (LocationTrack track : tracks) {
                writer.append(username).append(",");
                writer.append(String.valueOf(track.getLatitude())).append(",");
                writer.append(String.valueOf(track.getLongitude())).append(",");
                writer.append(DATE_FORMAT.format(new Date(track.getDateTime()))).append(",");
                writer.append(String.valueOf(track.getAngle())).append(",");
                writer.append(String.valueOf(track.getSpeed())).append(",");
                writer.append(String.valueOf(track.getBattery())).append("%\n");
            }

            writer.flush();
            writer.close();

            return csvFile;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Generate CSV for Sessions
    public static File generateSessionCsv(Context context, List<Session> sessions,
                                          String username, long startTime, long endTime) {
        try {
            String startDate = FILE_DATE_FORMAT.format(new Date(startTime));
            String endDate = FILE_DATE_FORMAT.format(new Date(endTime));
            String filename = username + "_Sessions_" + startDate + "_to_" + endDate + ".csv";

            File csvFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), filename);
            FileWriter writer = new FileWriter(csvFile);

            // Write CSV header
            writer.append("User,SessionID,LoginTime,LogoutTime,Duration\n");

            // Write data rows
            for (Session session : sessions) {
                long duration = session.getLogoutTime() - session.getLoginTime();
                long minutes = duration / (1000 * 60);

                writer.append(username).append(",");
                writer.append(session.getSessionId()).append(",");
                writer.append(DATE_FORMAT.format(new Date(session.getLoginTime()))).append(",");
                writer.append(DATE_FORMAT.format(new Date(session.getLogoutTime()))).append(",");
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
}