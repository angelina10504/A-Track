package com.example.a_track.database;


import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface SessionDao {

    @Insert
    long insert(Session session);

    @Query("UPDATE sessions SET logoutTime = :logoutTime WHERE id = :sessionId")
    void updateLogoutTime(int sessionId, long logoutTime);

    @Query("SELECT * FROM sessions WHERE mobileNumber = :mobileNumber ORDER BY loginTime DESC")
    LiveData<List<Session>> getSessionsByUser(String mobileNumber);

    @Query("SELECT * FROM sessions WHERE mobileNumber = :mobileNumber AND loginTime BETWEEN :startTime AND :endTime ORDER BY loginTime DESC")
    LiveData<List<Session>> getSessionsByDateRange(String mobileNumber, long startTime, long endTime);

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    Session getSessionById(int id);
}