package com.allinone.app.downloader;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.allinone.app.databinding.ItemDownloadedFileBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FilesAdapter extends RecyclerView.Adapter<FilesAdapter.ViewHolder> {

    private final List<DownloadedFile> files = new ArrayList<>();
    private final Context              ctx;

    public FilesAdapter(Context context) {
        ctx = context;
    }

    public void setFiles(List<DownloadedFile> newFiles) {
        files.clear();
        files.addAll(newFiles);
        notifyDataSetChanged();
    }

    public int getFilesCount() { return files.size(); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemDownloadedFileBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        h.bind(files.get(position));
    }

    @Override
    public int getItemCount() { return files.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    class ViewHolder extends RecyclerView.ViewHolder {
        final ItemDownloadedFileBinding b;

        ViewHolder(ItemDownloadedFileBinding binding) {
            super(binding.getRoot());
            b = binding;
        }

        void bind(DownloadedFile file) {
            b.tvName.setText(file.name);
            b.tvMeta.setText(file.getFormattedSize() + "  ·  " + file.getFormattedDate());
            b.ivFileIcon.setImageResource(file.getIconRes());
            b.llIconBg.setBackgroundColor(file.getIconBgColor());

            b.getRoot().setOnClickListener(v -> openFile(file));
            b.btnMore.setOnClickListener(v -> showMenu(v.findViewById(com.allinone.app.R.id.btn_more) != null ? v : b.btnMore, file));
        }

        // ── Actions ───────────────────────────────────────────────────────────

        private void openFile(DownloadedFile file) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                String mime = file.mimeType != null ? file.mimeType : "*/*";
                if (file.contentUri != null) {
                    intent.setDataAndType(file.contentUri, mime);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
                } else if (file.path != null) {
                    intent.setDataAndType(Uri.fromFile(new File(file.path)), mime);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                ctx.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(ctx, "No app can open this file type", Toast.LENGTH_SHORT).show();
            }
        }

        private void shareFile(DownloadedFile file) {
            try {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(file.mimeType != null ? file.mimeType : "*/*");
                if (file.contentUri != null) {
                    intent.putExtra(Intent.EXTRA_STREAM, file.contentUri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else if (file.path != null) {
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(file.path)));
                }
                ctx.startActivity(Intent.createChooser(intent, "Share " + file.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(ctx, "Cannot share this file", Toast.LENGTH_SHORT).show();
            }
        }

        private void deleteFile(DownloadedFile file) {
            new AlertDialog.Builder(ctx)
                    .setTitle("Delete")
                    .setMessage("Delete \"" + file.name + "\"?")
                    .setPositiveButton("Delete", (d, w) -> {
                        boolean ok = false;
                        if (file.contentUri != null) {
                            try {
                                ctx.getContentResolver().delete(file.contentUri, null, null);
                                ok = true;
                            } catch (Exception ignored) {}
                        }
                        if (!ok && file.path != null) ok = new File(file.path).delete();

                        if (ok) {
                            int pos = files.indexOf(file);
                            if (pos >= 0) { files.remove(pos); notifyItemRemoved(pos); }
                            Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(ctx, "Could not delete file", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        private void showMenu(android.view.View anchor, DownloadedFile file) {
            PopupMenu popup = new PopupMenu(ctx, anchor);
            popup.getMenu().add(0, 0, 0, "Open");
            popup.getMenu().add(0, 1, 1, "Share");
            popup.getMenu().add(0, 2, 2, "Delete");
            popup.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 0: openFile(file);   return true;
                    case 1: shareFile(file);  return true;
                    case 2: deleteFile(file); return true;
                }
                return false;
            });
            popup.show();
        }
    }
}
