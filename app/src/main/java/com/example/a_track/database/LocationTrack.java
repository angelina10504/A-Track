package com.example.a_track.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "location_tracks")
public class LocationTrack {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String mobileNumber;
    private double latitude;
    private double longitude;
    private float speed;
    private float angle;
    private long dateTime;
    private String sessionId;
    private int battery;

    private int synced;

    public LocationTrack(String mobileNumber, double latitude, double longitude,
                         float speed, float angle, long dateTime, String sessionId, int battery) {
        this.mobileNumber = mobileNumber;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speed = speed;
        this.angle = angle;
        this.dateTime = dateTime;
        this.sessionId = sessionId;
        this.battery = battery;
        this.synced = 0;

    }

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

    public int getSynced() { return synced; }
    public void setSynced(int synced) { this.synced = synced; }
}