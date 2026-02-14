package com.example.a_track.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface LocationTrackDao {

    // ---------------- INSERT ----------------

    @Insert
    long insert(LocationTrack locationTrack);

    // ---------------- UI QUERIES (LiveData) ----------------

    @Query("SELECT * FROM location_tracks " +
            "WHERE mobileNumber = :mobileNumber " +
            "ORDER BY dateTime DESC LIMIT 5")
    LiveData<List<LocationTrack>> getTop5Tracks(String mobileNumber);

    @Query("SELECT * FROM location_tracks " +
            "WHERE mobileNumber = :mobileNumber " +
            "ORDER BY dateTime DESC")
    LiveData<List<LocationTrack>> getAllTracks(String mobileNumber);

    @Query("SELECT * FROM location_tracks " +
            "WHERE mobileNumber = :mobileNumber " +
            "AND dateTime BETWEEN :startTime AND :endTime " +
            "ORDER BY dateTime DESC")
    LiveData<List<LocationTrack>> getTracksByDateRange(
            String mobileNumber,
            long startTime,
            long endTime
    );

    @Query("SELECT * FROM location_tracks " +
            "WHERE sessionId = :sessionId " +
            "ORDER BY dateTime DESC")
    LiveData<List<LocationTrack>> getTracksBySession(String sessionId);

    // ---------------- BACKGROUND / SYNC QUERIES ----------------

    // Get ALL tracks for sync (synchronous) - ADDED mobile parameter
    @Query("SELECT * FROM location_tracks " +
            "WHERE mobileNumber = :mobile " +
            "ORDER BY dateTime ASC")
    List<LocationTrack> getAllTracksSync(String mobile);

    // Get UNSYNCED records (for upload) - FIXED: added WHERE mobileNumber
    @Query("SELECT * FROM location_tracks " +
            "WHERE mobileNumber = :mobile AND synced = 0 " +
            "ORDER BY dateTime ASC")
    List<LocationTrack> getUnsyncedTracks(String mobile);

    // Mark uploaded records as synced
    @Query("UPDATE location_tracks SET synced = 1 WHERE id IN (:ids)")
    void markAsSynced(List<Integer> ids);

    // Get records with unsynced photos
    @Query("SELECT * FROM location_tracks " +
            "WHERE mobileNumber = :mobile " +
            "AND photoPath IS NOT NULL " +
            "AND photoPath != '' " +
            "AND photoSynced = 0 " +
            "ORDER BY dateTime ASC")
    List<LocationTrack> getUnsyncedPhotos(String mobile);

    @Query("UPDATE location_tracks SET photoSynced = 1 WHERE id = :id")
    void markPhotoAsSynced(int id);

    // Cleanup only ALREADY SYNCED old records
    @Query("DELETE FROM location_tracks " +
            "WHERE synced = 1 " +
            "AND (photoPath IS NULL OR photoPath = '' OR photoSynced = 1) " +
            "AND (videoPath IS NULL OR videoPath = '' OR videoSynced = 1) " +
            "AND dateTime < :todayStartMillis")
    int deleteOldTracks(long todayStartMillis);

    // ---------------- OPTIONAL EXPORT / DEBUG ----------------

    // For CSV export or debugging
    @Query("SELECT * FROM location_tracks " +
            "WHERE sessionId = :sessionId " +
            "ORDER BY dateTime ASC")
    List<LocationTrack> getTracksBySessionSync(String sessionId);

    @Query("SELECT * FROM location_tracks " +
            "WHERE mobileNumber = :mobileNumber " +
            "AND dateTime BETWEEN :startTime AND :endTime " +
            "ORDER BY dateTime ASC")
    List<LocationTrack> getTracksByDateRangeSync(
            String mobileNumber,
            long startTime,
            long endTime
    );

    @Query("SELECT MAX(RecNo) FROM location_tracks WHERE mobileNumber = :mobile")
    int getLastRecNo(String mobile);

    // Get records with unsynced videos
    @Query("SELECT * FROM location_tracks " +
            "WHERE mobileNumber = :mobile " +
            "AND videoPath IS NOT NULL " +
            "AND videoPath != '' " +
            "AND videoSynced = 0 " +
            "ORDER BY dateTime ASC")
    List<LocationTrack> getUnsyncedVideos(String mobile);

    @Query("UPDATE location_tracks SET videoSynced = 1 WHERE id = :id")
    void markVideoAsSynced(int id);

}