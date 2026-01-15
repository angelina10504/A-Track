package com.example.a_track.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {User.class, Session.class, LocationTrack.class}, version = 6, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static AppDatabase instance;

    // Migration from version 5 to 6 - adds photoSynced column
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Add photoSynced column with default value 0
            database.execSQL("ALTER TABLE location_tracks ADD COLUMN photoSynced INTEGER NOT NULL DEFAULT 0");
        }
    };

    // Keep your old migrations if users are upgrading from older versions
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Add your previous migration code here if any
        }
    };

    public abstract UserDao userDao();
    public abstract SessionDao sessionDao();
    public abstract LocationTrackDao locationTrackDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "atrack_database"
                    )
                    .addMigrations(MIGRATION_5_6)  // Add the new migration
                    // If you have users on older versions, add all migrations:
                    // .addMigrations(MIGRATION_1_2, MIGRATION_2_3, ..., MIGRATION_5_6)
                    .build();
        }
        return instance;
    }
}