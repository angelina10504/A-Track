package com.example.a_track.database;


import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface LocationTrackDao {

    @Insert
    void insert(LocationTrack locationTrack);

    @Query("SELECT * FROM location_tracks WHERE mobileNumber = :mobileNumber ORDER BY dateTime DESC LIMIT 5")
    LiveData<List<LocationTrack>> getTop5Tracks(String mobileNumber);

    @Query("SELECT * FROM location_tracks WHERE mobileNumber = :mobileNumber ORDER BY dateTime DESC")
    LiveData<List<LocationTrack>> getAllTracks(String mobileNumber);

    @Query("SELECT * FROM location_tracks WHERE mobileNumber = :mobileNumber AND dateTime BETWEEN :startTime AND :endTime ORDER BY dateTime DESC")
    LiveData<List<LocationTrack>> getTracksByDateRange(String mobileNumber, long startTime, long endTime);

    @Query("SELECT * FROM location_tracks WHERE sessionId = :sessionId ORDER BY dateTime DESC")
    LiveData<List<LocationTrack>> getTracksBySession(String sessionId);

    @Query("SELECT * FROM location_tracks WHERE sessionId = :sessionId ORDER BY dateTime ASC")
    List<LocationTrack> getTracksBySessionSync(String sessionId);

    @Query("SELECT * FROM location_tracks WHERE mobileNumber = :mobileNumber AND dateTime BETWEEN :startTime AND :endTime ORDER BY dateTime ASC")
    List<LocationTrack> getTracksByDateRangeSync(String mobileNumber, long startTime, long endTime);
}