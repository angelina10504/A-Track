package com.example.a_track.database;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "location_tracks")
public class LocationTrack {

    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "mobileNumber")
    private String mobileNumber;

    @ColumnInfo(name = "latitude")
    private double latitude;

    @ColumnInfo(name = "longitude")
    private double longitude;

    @ColumnInfo(name = "speed")
    private float speed;

    @ColumnInfo(name = "angle")
    private float angle;

    @ColumnInfo(name = "dateTime")
    private long dateTime;

    @ColumnInfo(name = "sessionId")
    private String sessionId;

    @ColumnInfo(name = "battery")
    private int battery;

    @ColumnInfo(name = "synced")
    private int synced;

    // 🔑 VERY IMPORTANT FOR V2
    @ColumnInfo(name = "photoPath")
    private String photoPath;

    @ColumnInfo(name = "textMsg")
    private String textMsg;

    // ✅ Constructor for NEW rows
    public LocationTrack(
            String mobileNumber,
            double latitude,
            double longitude,
            float speed,
            float angle,
            long dateTime,
            String sessionId,
            int battery,
            String photoPath,
            String textMsg
    ) {
        this.mobileNumber = mobileNumber;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speed = speed;
        this.angle = angle;
        this.dateTime = dateTime;
        this.sessionId = sessionId;
        this.battery = battery;
        this.synced = 0;        // always unsynced when created
        this.photoPath = photoPath;
        this.textMsg = textMsg;
    }

    /* =====================
       GETTERS & SETTERS
       ===================== */

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public void setMobileNumber(String mobileNumber) {
        this.mobileNumber = mobileNumber;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public float getAngle() {
        return angle;
    }

    public void setAngle(float angle) {
        this.angle = angle;
    }

    public long getDateTime() {
        return dateTime;
    }

    public void setDateTime(long dateTime) {
        this.dateTime = dateTime;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getBattery() {
        return battery;
    }

    public void setBattery(int battery) {
        this.battery = battery;
    }

    public int getSynced() {
        return synced;
    }

    public void setSynced(int synced) {
        this.synced = synced;
    }

    public String getPhotoPath() {
        return photoPath;
    }

    public void setPhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }

    public String getTextMsg() {
        return textMsg;
    }

    public void setTextMsg(String textMsg) {
        this.textMsg = textMsg;
    }
}
