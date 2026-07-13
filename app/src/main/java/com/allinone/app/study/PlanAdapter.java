package com.allinone.app.study;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.allinone.app.databinding.ItemStudyPlanBinding;

import java.util.List;

public class PlanAdapter extends RecyclerView.Adapter<PlanAdapter.VH> {

    public interface Listener {
        void onOpen(StudyPlan p);
    }

    private final List<StudyPlan> items;
    private final Listener listener;

    public PlanAdapter(List<StudyPlan> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemStudyPlanBinding.inflate(
            LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        StudyPlan p = items.get(pos);

        h.b.ring.setRingColor(p.color);
        h.b.ring.setProgress(p.progress());
        h.b.tvPercent.setText(p.progressPercent() + "%");
        h.b.tvPercent.setTextColor(p.color);

        h.b.tvSubject.setText(p.subject);
        h.b.tvChapters.setText(p.doneChapters + " / " + p.totalChapters + " chapters");

        String right;
        int color;
        if (p.isComplete()) {
            right = "Completed";
            color = 0xFF66BB6A;
        } else if (p.isOverdue()) {
            right = "Overdue";
            color = 0xFFE53935;
        } else {
            right = p.daysRemaining() + "d left";
            color = 0xFFB0B0C8;
        }
        h.b.tvDaysLeft.setText(right);
        h.b.tvDaysLeft.setTextColor(color);

        h.b.tvStatus.setText(p.statusLabel());
        h.b.getRoot().setOnClickListener(v -> listener.onOpen(p));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ItemStudyPlanBinding b;
        VH(ItemStudyPlanBinding b) { super(b.getRoot()); this.b = b; }
    }
}
