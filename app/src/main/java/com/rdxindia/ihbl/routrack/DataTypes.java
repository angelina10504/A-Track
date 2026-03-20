package com.rdxindia.ihbl.routrack;

public final class DataTypes {

    private DataTypes() {}

    public static final int INSTALL      = 0;   // Fresh install or reinstall detected
    public static final int REBOOT       = 1;   // Device rebooted after install
    public static final int NORMAL       = 2;   // Regular location tracking every 1 min
    public static final int MOCK         = 8;   // Fake GPS detected
    public static final int PHOTO        = 50;  // Photo taken and saved
    public static final int VIDEO        = 60;  // Video recorded and saved
    public static final int ALARM_ACK    = 70;  // Alarm acknowledged (All OK pressed)
    public static final int ALARM_MISSED = 71;  // Alarm dismissed or timed out
    public static final int APP_KILL     = 99;  // App was killed/crashed — reason logged in textMsg
    public static final int HEALTH_ALERT = 100; // One or more health parameters disturbed at alarm time
}
