package com.example.a_track;


import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.a_track.database.AppDatabase;
import com.example.a_track.database.LocationTrack;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapViewActivity extends AppCompatActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    private TextView tvMapInfo;
    private AppDatabase db;
    private ExecutorService executorService;

    private long startTime;
    private long endTime;
    private String mobileNumber;
    private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map_view);

        tvMapInfo = findViewById(R.id.tvMapInfo);

        db = AppDatabase.getInstance(this);
        executorService = Executors.newSingleThreadExecutor();

        // Get data from intent
        startTime = getIntent().getLongExtra("startTime", 0);
        endTime = getIntent().getLongExtra("endTime", 0);
        mobileNumber = getIntent().getStringExtra("mobileNumber");

        String info = "Showing route from " + dateTimeFormat.format(new Date(startTime)) +
                " to " + dateTimeFormat.format(new Date(endTime));
        tvMapInfo.setText(info);

        // Setup map
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.mapFragmentView);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        mMap.getUiSettings().setZoomControlsEnabled(true);
        mMap.getUiSettings().setCompassEnabled(true);

        loadAndDisplayRoute();
    }

    private void loadAndDisplayRoute() {
        executorService.execute(() -> {
            List<LocationTrack> tracks = db.locationTrackDao().getTracksByDateRangeSync(mobileNumber, startTime, endTime);

            runOnUiThread(() -> {
                if (tracks != null && !tracks.isEmpty()) {
                    displayRouteWithMarkers(tracks);
                } else {
                    tvMapInfo.setText("No location data found for this time range");
                }
            });
        });
    }

    private void displayRouteWithMarkers(List<LocationTrack> tracks) {
        ArrayList<LatLng> routePoints = new ArrayList<>();
        LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();

        // Labels for markers: A, B, C, D...
        String[] labels = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"};

        for (int i = 0; i < tracks.size(); i++) {
            LocationTrack track = tracks.get(i);
            LatLng position = new LatLng(track.getLatitude(), track.getLongitude());
            routePoints.add(position);
            boundsBuilder.include(position);

            // Add marker for every location point
            String label = i < labels.length ? labels[i] : String.valueOf(i + 1);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(track.getDateTime()));

            float markerColor = BitmapDescriptorFactory.HUE_AZURE;
            if (i == 0) {
                markerColor = BitmapDescriptorFactory.HUE_GREEN; // Start point
            } else if (i == tracks.size() - 1) {
                markerColor = BitmapDescriptorFactory.HUE_RED; // End point
            }

            mMap.addMarker(new MarkerOptions()
                    .position(position)
                    .title("Point " + label)
                    .snippet("Time: " + time + "\nSpeed: " + String.format("%.2f m/s", track.getSpeed()))
                    .icon(BitmapDescriptorFactory.defaultMarker(markerColor)));
        }

        // Draw route line connecting all points
        if (routePoints.size() > 1) {
            PolylineOptions polylineOptions = new PolylineOptions()
                    .addAll(routePoints)
                    .width(8)
                    .color(0xFF0000FF) // Blue color
                    .geodesic(true);

            mMap.addPolyline(polylineOptions);
        }

        // Fit camera to show all markers
        try {
            LatLngBounds bounds = boundsBuilder.build();
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100));
        } catch (Exception e) {
            // If only one point, just center on it
            if (!routePoints.isEmpty()) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(routePoints.get(0), 15));
            }
        }

        tvMapInfo.setText("Showing " + tracks.size() + " location points from " +
                dateTimeFormat.format(new Date(startTime)) + " to " +
                dateTimeFormat.format(new Date(endTime)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}