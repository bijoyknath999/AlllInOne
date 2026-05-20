package com.allinone.app.reminder;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.allinone.app.databinding.ItemReminderBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ReminderAdapter extends RecyclerView.Adapter<ReminderAdapter.VH> {

    private final List<Reminder> items;
    private final Listener listener;

    public interface Listener {
        void onToggle(Reminder r, boolean enabled);
        void onDelete(Reminder r);
    }

    public ReminderAdapter(List<Reminder> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemReminderBinding.inflate(
            LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Reminder r = items.get(pos);
        h.b.tvTitle.setText(r.title);
        h.b.tvInterval.setText(r.intervalLabel());

        if (r.nextFireMs > System.currentTimeMillis()) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault());
            h.b.tvNextFire.setText("Next: " + sdf.format(new Date(r.nextFireMs)));
        } else {
            h.b.tvNextFire.setText(r.enabled ? "Pending…" : "Disabled");
        }

        h.b.switchEnabled.setOnCheckedChangeListener(null);
        h.b.switchEnabled.setChecked(r.enabled);
        h.b.switchEnabled.setOnCheckedChangeListener((btn, checked) -> listener.onToggle(r, checked));
        h.b.btnDelete.setOnClickListener(v -> listener.onDelete(r));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ItemReminderBinding b;
        VH(ItemReminderBinding b) { super(b.getRoot()); this.b = b; }
    }
}