package com.example.a_track.adapter;



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

        String location = String.format(Locale.getDefault(), "Lat: %.2f, Lng: %.2f",
                track.getLatitude(), track.getLongitude());
        holder.tvTrackLocation.setText(location);

        String speed = String.format(Locale.getDefault(), "Sp: %.2f m/s",
                track.getSpeed());
        holder.tvTrackSpeed.setText(speed);

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