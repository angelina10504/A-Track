package com.example.a_track.adapter;


import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.a_track.R;
import com.example.a_track.database.LocationTrack;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.TrackViewHolder> {

    private List<LocationTrack> tracks = new ArrayList<>();
    private SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault());

    @NonNull
    @Override
    public TrackViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_location_track, parent, false);
        return new TrackViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TrackViewHolder holder, int position) {
        LocationTrack track = tracks.get(position);

        String dateTime = dateFormat.format(new Date(track.getDateTime()));
        holder.tvTrackDateTime.setText(dateTime);

        String location = String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f",
                track.getLatitude(), track.getLongitude());
        holder.tvTrackLocation.setText(location);

        String speed = String.format(Locale.getDefault(), "%.2f km/h",
                track.getSpeed());
        holder.tvTrackSpeed.setText(speed);

        boolean locationSynced = track.getSynced() == 1;
        boolean hasPhoto = track.getPhotoPath() != null && !track.getPhotoPath().isEmpty();
        boolean photoSynced = track.getPhotoSynced() == 1;

        if (locationSynced && (!hasPhoto || photoSynced)) {
            // Fully synced (location synced + no photo OR photo also synced) - GREEN
            holder.itemView.setBackgroundColor(Color.parseColor("#C8E6C9")); // Light green
        } else if (locationSynced && hasPhoto && !photoSynced) {
            // Location synced but photo pending - YELLOW
            holder.itemView.setBackgroundColor(Color.parseColor("#FFF9C4")); // Light yellow
        } else {
            // Not synced - WHITE
            holder.itemView.setBackgroundColor(Color.WHITE);
        }

    }

    @Override
    public int getItemCount() {
        return tracks.size();
    }

    public void setTracks(List<LocationTrack> tracks) {
        this.tracks = tracks;
        notifyDataSetChanged();
    }

    static class TrackViewHolder extends RecyclerView.ViewHolder {
        TextView tvTrackDateTime, tvTrackLocation, tvTrackSpeed;

        public TrackViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTrackDateTime = itemView.findViewById(R.id.tvTrackDateTime);
            tvTrackLocation = itemView.findViewById(R.id.tvTrackLocation);
            tvTrackSpeed = itemView.findViewById(R.id.tvTrackSpeed);
        }
    }
}