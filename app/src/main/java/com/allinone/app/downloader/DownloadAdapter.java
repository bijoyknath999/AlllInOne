package com.allinone.app.downloader;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.allinone.app.databinding.ItemDownloadBinding;

import java.util.List;

public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.ViewHolder> {

    public interface ActionListener {
        void onPauseResume(DownloadItem item);
        void onCancel(DownloadItem item);
        void onOpen(DownloadItem item);
        void onDelete(DownloadItem item);
    }

    private final List<DownloadItem> items;
    private final ActionListener     listener;

    public DownloadAdapter(List<DownloadItem> items, ActionListener listener) {
        this.items    = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemDownloadBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        h.bind(items.get(position), listener);
    }

    @Override
    public int getItemCount() { return items.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ItemDownloadBinding b;

        ViewHolder(ItemDownloadBinding binding) {
            super(binding.getRoot());
            b = binding;
        }

        void bind(DownloadItem item, ActionListener listener) {
            // File type icon
            b.ivFileType.setImageResource(item.getFileTypeIcon());

            // Name + URL
            b.tvFileName.setText(item.fileName);
            b.tvUrl.setText(item.url);

            // Status badge
            b.tvStatus.setText(item.getStatusLabel());
            b.tvStatus.setTextColor(statusColor(item.status));

            // Progress bar
            int progress = item.getProgress();
            b.tvPercent.setText(progress + "%");
            b.progressFill.post(() -> {
                int total = ((View) b.progressFill.getParent()).getWidth();
                ViewGroup.LayoutParams lp = b.progressFill.getLayoutParams();
                lp.width = (int) (total * progress / 100f);
                b.progressFill.setLayoutParams(lp);
            });

            // Size / speed / ETA row
            b.tvSize.setText(item.getFormattedProgress());
            String speed = item.getFormattedSpeed();
            String eta   = item.getFormattedEta();
            if (!speed.isEmpty()) {
                b.tvSpeed.setVisibility(View.VISIBLE);
                b.tvSpeed.setText(speed);
            } else {
                b.tvSpeed.setVisibility(View.GONE);
            }
            if (!eta.isEmpty()) {
                b.tvEta.setVisibility(View.VISIBLE);
                b.tvEta.setText("~" + eta);
            } else {
                b.tvEta.setVisibility(View.GONE);
            }

            // Error message
            boolean showErr = item.status == DownloadItem.Status.FAILED
                    && item.errorMsg != null && !item.errorMsg.isEmpty();
            b.tvError.setVisibility(showErr ? View.VISIBLE : View.GONE);
            if (showErr) b.tvError.setText(item.errorMsg);

            // Pause / Resume button
            boolean canPR = item.status == DownloadItem.Status.DOWNLOADING
                    || item.status == DownloadItem.Status.PAUSED
                    || item.status == DownloadItem.Status.QUEUED;
            b.btnPauseResume.setVisibility(canPR ? View.VISIBLE : View.GONE);
            b.btnPauseResume.setText(item.status == DownloadItem.Status.DOWNLOADING
                    ? "PAUSE" : "RESUME");
            b.btnPauseResume.setOnClickListener(v -> listener.onPauseResume(item));

            // Cancel button
            boolean canCancel = item.status != DownloadItem.Status.COMPLETED
                    && item.status != DownloadItem.Status.CANCELLED;
            b.btnCancel.setVisibility(canCancel ? View.VISIBLE : View.GONE);
            b.btnCancel.setOnClickListener(v -> listener.onCancel(item));

            // Open button
            b.btnOpen.setVisibility(item.status == DownloadItem.Status.COMPLETED
                    ? View.VISIBLE : View.GONE);
            b.btnOpen.setOnClickListener(v -> listener.onOpen(item));

            // Delete button (for completed/failed/cancelled)
            boolean canDelete = item.status == DownloadItem.Status.COMPLETED
                    || item.status == DownloadItem.Status.FAILED
                    || item.status == DownloadItem.Status.CANCELLED;
            b.btnDelete.setVisibility(canDelete ? View.VISIBLE : View.GONE);
            b.btnDelete.setOnClickListener(v -> listener.onDelete(item));
        }

        private int statusColor(DownloadItem.Status s) {
            switch (s) {
                case DOWNLOADING: return 0xFF4CAF50;
                case PAUSED:      return 0xFFFF9800;
                case COMPLETED:   return 0xFF2196F3;
                case FAILED:
                case CANCELLED:   return 0xFFE53935;
                default:          return 0xFFB0B0C8;
            }
        }
    }
}
