package com.example.a_track.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface UserDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(User user);

    @Query("SELECT * FROM users WHERE mobileNumber = :mobileNumber LIMIT 1")
    User getUserByMobile(String mobileNumber);

    @Query("SELECT * FROM users WHERE mobileNumber = :mobileNumber AND password = :password LIMIT 1")
    User validateUser(String mobileNumber, String password);
}