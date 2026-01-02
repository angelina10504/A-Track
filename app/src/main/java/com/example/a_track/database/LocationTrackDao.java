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
    void insert(LocationTrack locationTrack);

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

    // Get UNSYNCED records (for upload)
    @Query("SELECT * FROM location_tracks " +
            "WHERE synced = 0 " +
            "ORDER BY dateTime ASC " +
            "LIMIT :limit")
    List<LocationTrack> getUnsyncedTracks(int limit);

    // Mark uploaded records as synced
    @Query("UPDATE location_tracks SET synced = 1 WHERE id IN (:ids)")
    void markAsSynced(List<Integer> ids);

    // Cleanup only ALREADY SYNCED old records
    @Query("DELETE FROM location_tracks " +
            "WHERE synced = 1 AND dateTime < :beforeTime")
    int deleteSyncedOlderThan(long beforeTime);

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
}
