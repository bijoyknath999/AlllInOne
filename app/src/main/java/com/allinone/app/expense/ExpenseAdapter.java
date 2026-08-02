package com.allinone.app.expense;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.allinone.app.databinding.ItemExpenseBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ExpenseAdapter extends RecyclerView.Adapter<ExpenseAdapter.VH> {

    private static final int DEFAULT_COLOR = 0xFF607D8B;

    private final List<Expense> items;
    private final String currency;
    private final Map<String, Integer> categoryColors;
    private final Listener listener;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, MMM dd", Locale.getDefault());

    public interface Listener {
        void onEdit(Expense e);
        void onDelete(Expense e);
    }

    public ExpenseAdapter(List<Expense> items, String currency,
                          Map<String, Integer> categoryColors, Listener listener) {
        this.items = items;
        this.categoryColors = categoryColors;
        this.currency = currency;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(ItemExpenseBinding.inflate(
            LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Expense e = items.get(pos);
        String cat = e.category == null || e.category.isEmpty() ? "Other" : e.category;
        int color = colorFor(cat);

        h.b.tvCatDot.setText(cat.substring(0, 1).toUpperCase(Locale.getDefault()));
        h.b.tvCatDot.setBackground(circle(color));
        h.b.tvCategory.setText(cat);
        h.b.tvDate.setText(dateFmt.format(new Date(e.dateMillis)));

        boolean hasNote = e.note != null && !e.note.trim().isEmpty();
        h.b.tvNote.setText(hasNote ? e.note : "");
        h.b.tvNote.setVisibility(hasNote ? View.VISIBLE : View.GONE);

        String amount = currency + String.format(Locale.getDefault(), "%,.2f", e.amount);
        h.b.tvAmount.setText((e.isIncome() ? "+ " : "- ") + amount);
        h.b.tvAmount.setTextColor(e.isIncome() ? 0xFF4CAF50 : 0xFFE53935);

        h.b.btnDelete.setOnClickListener(v -> listener.onDelete(e));
        h.b.getRoot().setOnClickListener(v -> listener.onEdit(e));
    }

    @Override
    public int getItemCount() { return items.size(); }

    private int colorFor(String cat) {
        Integer c = categoryColors.get(cat);
        return c != null ? c : DEFAULT_COLOR;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemExpenseBinding b;
        VH(ItemExpenseBinding b) { super(b.getRoot()); this.b = b; }
    }
}
