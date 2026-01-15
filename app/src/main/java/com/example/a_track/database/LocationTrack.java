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

    @ColumnInfo(name = "photoSynced")
    private int photoSynced = 0;

    private String gpsState;
    private String internetState;
    private String flightState;
    private String roamingState;
    private String isNetThere;
    private String isNwThere;
    private String isMoving;
    private String modelNo;
    private String modelOS;
    private String apkName;
    private String imsiNo;
    private long mobileTime;

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
            String textMsg,String gpsState, String internetState, String flightState,
            String roamingState, String isNetThere, String isNwThere,
            String isMoving, String modelNo, String modelOS,
            String apkName, String imsiNo, long mobileTime
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
        this.gpsState = gpsState;
        this.internetState = internetState;
        this.flightState = flightState;
        this.roamingState = roamingState;
        this.isNetThere = isNetThere;
        this.isNwThere = isNwThere;
        this.isMoving = isMoving;
        this.modelNo = modelNo;
        this.modelOS = modelOS;
        this.apkName = apkName;
        this.imsiNo = imsiNo;
        this.mobileTime = mobileTime;
    }

    /* =====================
       GETTERS & SETTERS
       ===================== */

    public int getId() {return id;}

    public void setId(int id) {this.id = id;}

    public String getMobileNumber() {return mobileNumber;}

    public void setMobileNumber(String mobileNumber) {this.mobileNumber = mobileNumber;}

    public double getLatitude() {return latitude;}

    public void setLatitude(double latitude) {this.latitude = latitude;}

    public double getLongitude() {return longitude;}

    public void setLongitude(double longitude) {this.longitude = longitude;}

    public float getSpeed() {return speed;}

    public void setSpeed(float speed) {this.speed = speed;}

    public float getAngle() {return angle;}

    public void setAngle(float angle) {this.angle = angle;}

    public long getDateTime() {return dateTime;}

    public void setDateTime(long dateTime) {this.dateTime = dateTime;}

    public String getSessionId() {return sessionId;}

    public void setSessionId(String sessionId) {this.sessionId = sessionId;}

    public int getBattery() {return battery;}

    public void setBattery(int battery) {this.battery = battery;}

    public int getSynced() {return synced;}

    public void setSynced(int synced) {this.synced = synced;}

    public String getPhotoPath() {return photoPath;}

    public void setPhotoPath(String photoPath) {this.photoPath = photoPath;}

    public String getTextMsg() {return textMsg;}

    public void setTextMsg(String textMsg) {this.textMsg = textMsg;}

    public String getGpsState() { return gpsState; }
    public void setGpsState(String gpsState) { this.gpsState = gpsState; }

    public String getInternetState() { return internetState; }
    public void setInternetState(String internetState) { this.internetState = internetState; }

    public String getFlightState() { return flightState; }
    public void setFlightState(String flightState) { this.flightState = flightState; }

    public String getRoamingState() { return roamingState; }
    public void setRoamingState(String roamingState) { this.roamingState = roamingState; }

    public String getIsNetThere() { return isNetThere; }
    public void setIsNetThere(String isNetThere) { this.isNetThere = isNetThere; }

    public String getIsNwThere() { return isNwThere; }
    public void setIsNwThere(String isNwThere) { this.isNwThere = isNwThere; }

    public String getIsMoving() { return isMoving; }
    public void setIsMoving(String isMoving) { this.isMoving = isMoving; }

    public String getModelNo() { return modelNo; }
    public void setModelNo(String modelNo) { this.modelNo = modelNo; }

    public String getModelOS() { return modelOS; }
    public void setModelOS(String modelOS) { this.modelOS = modelOS; }

    public String getApkName() { return apkName; }
    public void setApkName(String apkName) { this.apkName = apkName; }

    public String getImsiNo() { return imsiNo; }
    public void setImsiNo(String imsiNo) { this.imsiNo = imsiNo; }

    public int getPhotoSynced() {
        return photoSynced;
    }

    public void setPhotoSynced(int photoSynced) {
        this.photoSynced = photoSynced;
    }

    public long getMobileTime() {return mobileTime;}

    public void setMobileTime(long mobileTime) {this.mobileTime = mobileTime;}
}
