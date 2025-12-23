package com.example.a_track;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.a_track.adapter.SessionAdapter;
import com.example.a_track.database.AppDatabase;
import com.example.a_track.database.Session;
import com.example.a_track.utils.SessionManager;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class FilterSessionActivity extends AppCompatActivity {

    private Button btnStartDate, btnStartTime, btnEndDate, btnEndTime, btnFilter;
    private RecyclerView rvFilteredSessions;

    private AppDatabase db;
    private SessionManager sessionManager;
    private SessionAdapter sessionAdapter;

    private Calendar startCalendar, endCalendar;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault());
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_session);

        initViews();
        db = AppDatabase.getInstance(this);
        sessionManager = new SessionManager(this);

        startCalendar = Calendar.getInstance();
        endCalendar = Calendar.getInstance();

        // Set default time range (last 7 days)
        //startCalendar.add(Calendar.DAY_OF_MONTH, -7);
        startCalendar.set(Calendar.HOUR_OF_DAY, 0);
        startCalendar.set(Calendar.MINUTE, 0);
        startCalendar.set(Calendar.SECOND, 0);

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

        // Load all sessions initially
        loadAllSessions();
    }

    private void initViews() {
        btnStartDate = findViewById(R.id.btnStartDate);
        btnStartTime = findViewById(R.id.btnStartTime);
        btnEndDate = findViewById(R.id.btnEndDate);
        btnEndTime = findViewById(R.id.btnEndTime);
        btnFilter = findViewById(R.id.btnFilter);
        rvFilteredSessions = findViewById(R.id.rvFilteredSessions);
    }

    private void setupRecyclerView() {
        sessionAdapter = new SessionAdapter();
        rvFilteredSessions.setLayoutManager(new LinearLayoutManager(this));
        rvFilteredSessions.setAdapter(sessionAdapter);
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
                true
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
                true
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

            db.sessionDao()
                    .getSessionsByDateRange(mobile, startTime, endTime)
                    .observe(this, new Observer<List<Session>>() {
                        @Override
                        public void onChanged(List<Session> sessions) {
                            if (sessions != null && !sessions.isEmpty()) {
                                sessionAdapter.setSessions(sessions);
                                Toast.makeText(FilterSessionActivity.this,
                                        sessions.size() + " sessions found", Toast.LENGTH_SHORT).show();
                            } else {
                                sessionAdapter.setSessions(sessions);
                                Toast.makeText(FilterSessionActivity.this,
                                        "No sessions found for this time range", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
        }
    }

    private void loadAllSessions() {
        String mobile = sessionManager.getMobileNumber();
        if (mobile != null) {
            db.sessionDao().getSessionsByUser(mobile).observe(this, new Observer<List<Session>>() {
                @Override
                public void onChanged(List<Session> sessions) {
                    if (sessions != null) {
                        sessionAdapter.setSessions(sessions);
                        Toast.makeText(FilterSessionActivity.this,
                                "Total sessions: " + sessions.size(), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }
}