package com.example.a_track.adapter;


import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.a_track.R;
import com.example.a_track.database.Session;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.SessionViewHolder> {

    private List<Session> sessions = new ArrayList<>();
    private SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault());

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session, parent, false);
        return new SessionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        Session session = sessions.get(position);

        holder.tvSessionId.setText("Session #" + session.getId());

        String loginTime = "Login: " + dateFormat.format(new Date(session.getLoginTime()));
        holder.tvLoginTime.setText(loginTime);

        if (session.getLogoutTime() != null) {
            String logoutTime = "Logout: " + dateFormat.format(new Date(session.getLogoutTime()));
            holder.tvLogoutTime.setText(logoutTime);

            long durationMillis = session.getLogoutTime() - session.getLoginTime();
            long hours = durationMillis / (1000 * 60 * 60);
            long minutes = (durationMillis % (1000 * 60 * 60)) / (1000 * 60);
            String duration = String.format(Locale.getDefault(), "Duration: %d hours %d minutes", hours, minutes);
            holder.tvDuration.setText(duration);
        } else {
            holder.tvLogoutTime.setText("Logout: Active Session");
            holder.tvDuration.setText("Duration: Ongoing");
        }
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    public void setSessions(List<Session> sessions) {
        this.sessions = sessions;
        notifyDataSetChanged();
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {
        TextView tvSessionId, tvLoginTime, tvLogoutTime, tvDuration;

        public SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSessionId = itemView.findViewById(R.id.tvSessionId);
            tvLoginTime = itemView.findViewById(R.id.tvLoginTime);
            tvLogoutTime = itemView.findViewById(R.id.tvLogoutTime);
            tvDuration = itemView.findViewById(R.id.tvDuration);
        }
    }
}