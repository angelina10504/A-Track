package com.example.a_track.utils;

import android.os.SystemClock;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Fetches accurate UTC time from an NTP server.
 * The result is independent of the device's system clock,
 * so manual clock changes on the device do not affect it.
 */
public class NtpTimeHelper {

    private static final String TAG = "NtpTimeHelper";
    private static final String NTP_HOST = "pool.ntp.org";
    private static final int NTP_PORT = 123;
    private static final int NTP_TIMEOUT_MS = 5000;

    // NTP epoch starts Jan 1 1900; Unix epoch starts Jan 1 1970.
    // Difference: 70 years = 2,208,988,800 seconds.
    private static final long NTP_TO_UNIX_OFFSET_SECS = 2208988800L;

    /**
     * Queries pool.ntp.org and returns the server's UTC time in milliseconds.
     * Compensates for network round-trip latency.
     *
     * Must be called from a background thread (does network I/O).
     *
     * @return server UTC time in milliseconds since Unix epoch, or -1 on failure.
     */
    public static long fetchNtpTimeMs() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(NTP_TIMEOUT_MS);

            // 48-byte NTP v3 client request packet
            byte[] buffer = new byte[48];
            buffer[0] = 0x1B; // LI=0, Version=3, Mode=3 (client)

            InetAddress address = InetAddress.getByName(NTP_HOST);
            DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, NTP_PORT);

            long sendElapsedMs = SystemClock.elapsedRealtime();
            socket.send(request);

            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            long receiveElapsedMs = SystemClock.elapsedRealtime();

            // Transmit Timestamp: bytes 40–47 (big-endian 64-bit NTP fixed-point)
            long seconds = ((buffer[40] & 0xFFL) << 24)
                         | ((buffer[41] & 0xFFL) << 16)
                         | ((buffer[42] & 0xFFL) << 8)
                         |  (buffer[43] & 0xFFL);

            long fractions = ((buffer[44] & 0xFFL) << 24)
                           | ((buffer[45] & 0xFFL) << 16)
                           | ((buffer[46] & 0xFFL) << 8)
                           |  (buffer[47] & 0xFFL);

            // Convert to Unix epoch milliseconds
            long ntpTimeMs = (seconds - NTP_TO_UNIX_OFFSET_SECS) * 1000L
                           + (fractions * 1000L >>> 32);

            // Add half of round-trip time to correct for latency
            long latencyMs = (receiveElapsedMs - sendElapsedMs) / 2;
            long calibratedTimeMs = ntpTimeMs + latencyMs;

            Log.d(TAG, "NTP success: serverTime=" + calibratedTimeMs
                    + " latency=" + (receiveElapsedMs - sendElapsedMs) + "ms");
            return calibratedTimeMs;

        } catch (Exception e) {
            Log.w(TAG, "NTP query failed: " + e.getMessage());
            return -1;
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }
}
