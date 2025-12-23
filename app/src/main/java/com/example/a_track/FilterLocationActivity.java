package com.example.a_track;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import com.example.a_track.utils.CsvHelper;
import com.example.a_track.adapter.TrackAdapter;
import com.example.a_track.database.AppDatabase;
import com.example.a_track.database.LocationTrack;
import com.example.a_track.utils.SessionManager;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.io.File;

public class FilterLocationActivity extends AppCompatActivity {

    private Button btnStartDate, btnStartTime, btnEndDate, btnEndTime, btnFilter, btnViewOnMap, btnSendEmail;
    private List<LocationTrack> currentFilteredTracks;
    private RecyclerView rvFilteredTracks;

    private AppDatabase db;
    private SessionManager sessionManager;
    private TrackAdapter trackAdapter;

    private Calendar startCalendar, endCalendar;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault());
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_location);

        initViews();
        db = AppDatabase.getInstance(this);
        sessionManager = new SessionManager(this);

        startCalendar = Calendar.getInstance();
        endCalendar = Calendar.getInstance();

        // Set default time range (yesterday 14:00 to 15:00 as example)
        //startCalendar.add(Calendar.DAY_OF_MONTH, -1);
        startCalendar.set(Calendar.HOUR_OF_DAY,0);
        startCalendar.set(Calendar.MINUTE, 0);
        startCalendar.set(Calendar.SECOND, 0);

        //endCalendar.add(Calendar.DAY_OF_MONTH, -1);
        endCalendar.set(Calendar.HOUR_OF_DAY, 23);
        endCalendar.set(Calendar.MINUTE, 59);
        endCalendar.set(Calendar.SECOND, 59);

        updateButtonTexts();

        setupRecyclerView();

        btnStartDate.setOnClickListener(v -> showStartDatePicker());
        btnStartTime.setOnClickListener(v -> showStartTimePicker());
        btnEndDate.setOnClickListener(v -> showEndDatePicker());
        btnEndTime.setOnClickListener(v -> showEndTimePicker());
        btnFilter.setOnClickListener(v -> applyFilter());
        btnViewOnMap.setOnClickListener(v -> viewOnMap());
        btnSendEmail.setOnClickListener(v -> showEmailDialog());

        // Load all tracks initially
        loadAllTracks();
    }

    private void initViews() {
        btnStartDate = findViewById(R.id.btnStartDate);
        btnStartTime = findViewById(R.id.btnStartTime);
        btnEndDate = findViewById(R.id.btnEndDate);
        btnEndTime = findViewById(R.id.btnEndTime);
        btnFilter = findViewById(R.id.btnFilter);
        btnViewOnMap = findViewById(R.id.btnViewOnMap);
        rvFilteredTracks = findViewById(R.id.rvFilteredTracks);
        btnSendEmail = findViewById(R.id.btnSendEmail);
    }

    private void setupRecyclerView() {
        trackAdapter = new TrackAdapter();
        rvFilteredTracks.setLayoutManager(new LinearLayoutManager(this));
        rvFilteredTracks.setAdapter(trackAdapter);
    }

    private void updateButtonTexts() {
        btnStartDate.setText(dateFormat.format(startCalendar.getTime()));
        btnStartTime.setText(timeFormat.format(startCalendar.getTime()));
        btnEndDate.setText(dateFormat.format(endCalendar.getTime()));
        btnEndTime.setText(timeFormat.format(endCalendar.getTime()));
    }

    private void showStartDatePicker() {
        DatePickerDialog datePickerDialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    startCalendar.set(Calendar.YEAR, year);
                    startCalendar.set(Calendar.MONTH, month);
                    startCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    updateButtonTexts();
                },
                startCalendar.get(Calendar.YEAR),
                startCalendar.get(Calendar.MONTH),
                startCalendar.get(Calendar.DAY_OF_MONTH)
        );
        datePickerDialog.show();
    }

    private void showStartTimePicker() {
        TimePickerDialog timePickerDialog = new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> {
                    startCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    startCalendar.set(Calendar.MINUTE, minute);
                    startCalendar.set(Calendar.SECOND, 0);
                    updateButtonTexts();
                },
                startCalendar.get(Calendar.HOUR_OF_DAY),
                startCalendar.get(Calendar.MINUTE),
                true // 24-hour format
        );
        timePickerDialog.show();
    }

    private void showEndDatePicker() {
        DatePickerDialog datePickerDialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    endCalendar.set(Calendar.YEAR, year);
                    endCalendar.set(Calendar.MONTH, month);
                    endCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    updateButtonTexts();
                },
                endCalendar.get(Calendar.YEAR),
                endCalendar.get(Calendar.MONTH),
                endCalendar.get(Calendar.DAY_OF_MONTH)
        );
        datePickerDialog.show();
    }

    private void showEndTimePicker() {
        TimePickerDialog timePickerDialog = new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> {
                    endCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    endCalendar.set(Calendar.MINUTE, minute);
                    endCalendar.set(Calendar.SECOND, 59);
                    updateButtonTexts();
                },
                endCalendar.get(Calendar.HOUR_OF_DAY),
                endCalendar.get(Calendar.MINUTE),
                true // 24-hour format
        );
        timePickerDialog.show();
    }

    private void applyFilter() {
        if (startCalendar.getTimeInMillis() > endCalendar.getTimeInMillis()) {
            Toast.makeText(this, "Start time must be before end time", Toast.LENGTH_SHORT).show();
            return;
        }

        String mobile = sessionManager.getMobileNumber();
        if (mobile != null) {
            long startTime = startCalendar.getTimeInMillis();
            long endTime = endCalendar.getTimeInMillis();

            String filterInfo = "Filter: " + dateTimeFormat.format(startCalendar.getTime()) +
                    " to " + dateTimeFormat.format(endCalendar.getTime());
            Toast.makeText(this, filterInfo, Toast.LENGTH_LONG).show();

            db.locationTrackDao()
                    .getTracksByDateRange(mobile, startTime, endTime)
                    .observe(this, new Observer<List<LocationTrack>>() {
                        @Override
                        public void onChanged(List<LocationTrack> locationTracks) {
                            currentFilteredTracks = locationTracks;
                            if (locationTracks != null && !locationTracks.isEmpty()) {
                                trackAdapter.setTracks(locationTracks);
                                Toast.makeText(FilterLocationActivity.this,
                                        locationTracks.size() + " records found", Toast.LENGTH_SHORT).show();
                            } else {
                                trackAdapter.setTracks(locationTracks);
                                Toast.makeText(FilterLocationActivity.this,
                                        "No records found for this time range", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
        }
    }
    private void showEmailDialog() {
        if (currentFilteredTracks == null || currentFilteredTracks.isEmpty()) {
            Toast.makeText(this, "No data to send. Please apply filter first.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create dialog to get email address
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Send Email");
        builder.setMessage("Enter recipient email address:");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        input.setHint("email@example.com");
        builder.setView(input);

        builder.setPositiveButton("Send", (dialog, which) -> {
            String email = input.getText().toString().trim();
            if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show();
            } else {
                sendEmailWithCsv(email);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void sendEmailWithCsv(String recipientEmail) {
        String mobile = sessionManager.getMobileNumber();
        long startTime = startCalendar.getTimeInMillis();
        long endTime = endCalendar.getTimeInMillis();

        // Generate CSV file
        File csvFile = CsvHelper.generateLocationTrackCsv(
                this, currentFilteredTracks, mobile, startTime, endTime);

        if (csvFile == null || !csvFile.exists()) {
            Toast.makeText(this, "Failed to generate CSV file", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create email intent
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("message/rfc822");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{recipientEmail});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "A-Track Location Data - " + mobile);
        emailIntent.putExtra(Intent.EXTRA_TEXT,
                "Please find attached the location tracking data from " +
                        dateTimeFormat.format(startCalendar.getTime()) + " to " +
                        dateTimeFormat.format(endCalendar.getTime()));

        // Attach CSV file
        Uri fileUri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".provider",
                csvFile);
        emailIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(emailIntent, "Send email using..."));
            Toast.makeText(this, "Opening email app...", Toast.LENGTH_SHORT).show();
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadAllTracks() {
        String mobile = sessionManager.getMobileNumber();
        if (mobile != null) {
            db.locationTrackDao().getAllTracks(mobile).observe(this, new Observer<List<LocationTrack>>() {
                @Override
                public void onChanged(List<LocationTrack> locationTracks) {
                    if (locationTracks != null) {
                        trackAdapter.setTracks(locationTracks);
                        Toast.makeText(FilterLocationActivity.this,
                                "Total records: " + locationTracks.size(), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    private void viewOnMap() {
        String mobile = sessionManager.getMobileNumber();
        if (mobile != null) {
            Intent intent = new Intent(this, MapViewActivity.class);
            intent.putExtra("startTime", startCalendar.getTimeInMillis());
            intent.putExtra("endTime", endCalendar.getTimeInMillis());
            intent.putExtra("mobileNumber", mobile);
            startActivity(intent);
        }
    }
}