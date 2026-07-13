package com.allinone.app.study;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.allinone.app.R;
import com.allinone.app.databinding.ItemChapterBinding;

import java.util.List;

public class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.VH> {

    public interface Listener {
        void onToggle(Chapter ch, boolean done);
        void onDelete(Chapter ch);
    }

    private final List<Chapter> items;
    private final Listener listener;
    private int accent = 0xFF6C63FF;
    private long nextChapterId = -1; // highlight "today's chapter"

    public ChapterAdapter(List<Chapter> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    public void setAccent(int color) { this.accent = color; }
    public void setNextChapterId(long id) { this.nextChapterId = id; }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemChapterBinding.inflate(
            LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Chapter ch = items.get(pos);

        h.b.tvName.setText(ch.name);
        int paint = h.b.tvName.getPaintFlags();
        if (ch.done) {
            h.b.tvName.setPaintFlags(paint | Paint.STRIKE_THRU_TEXT_FLAG);
            h.b.tvName.setAlpha(0.55f);
        } else {
            h.b.tvName.setPaintFlags(paint & (~Paint.STRIKE_THRU_TEXT_FLAG));
            h.b.tvName.setAlpha(1f);
        }

        String sub = ch.doneDateLabel();
        boolean isNext = !ch.done && ch.id == nextChapterId;
        if (isNext) {
            h.b.tvSub.setText("Today's chapter");
            h.b.tvSub.setTextColor(accent);
            h.b.tvSub.setVisibility(android.view.View.VISIBLE);
        } else if (sub != null) {
            h.b.tvSub.setText(sub);
            h.b.tvSub.setTextColor(0xFF66BB6A);
            h.b.tvSub.setVisibility(android.view.View.VISIBLE);
        } else {
            h.b.tvSub.setVisibility(android.view.View.GONE);
        }

        h.b.check.setImageResource(ch.done
            ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
        h.b.check.setColorFilter(ch.done ? 0xFF66BB6A : accent);

        h.b.check.setOnClickListener(v -> listener.onToggle(ch, !ch.done));
        h.b.getRoot().setOnClickListener(v -> listener.onToggle(ch, !ch.done));
        h.b.btnDelete.setOnClickListener(v -> listener.onDelete(ch));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ItemChapterBinding b;
        VH(ItemChapterBinding b) { super(b.getRoot()); this.b = b; }
    }
}
