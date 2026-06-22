package com.allinone.app.expense;

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

    private final List<Expense> items;
    private final String currency;
    private final Map<String, Integer> categoryColors;
    private final Map<Long, String> accountNames;
    private final Listener listener;

    public interface Listener {
        void onEdit(Expense e);
        void onDelete(Expense e);
    }

    public ExpenseAdapter(List<Expense> items, String currency,
                          Map<String, Integer> categoryColors,
                          Map<Long, String> accountNames, Listener listener) {
        this.items = items;
        this.currency = currency;
        this.categoryColors = categoryColors;
        this.accountNames = accountNames;
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
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        h.b.tvDate.setText(sdf.format(new Date(e.dateMillis)));

        if (e.isTransfer()) {
            h.b.tvCategory.setText("Transfer");
            h.b.tvCategory.setBackground(pill(h.b.getRoot().getContext(), 0xFF455A64));
            String from = accountNames.get(e.accountId);
            String to = accountNames.get(e.toAccountId);
            String flow = (from == null ? "?" : from) + " → " + (to == null ? "?" : to);
            h.b.tvNote.setText(flow);
            h.b.tvNote.setVisibility(View.VISIBLE);
            h.b.tvAmount.setText(currency + String.format(Locale.getDefault(), "%,.2f", e.amount));
            h.b.tvAmount.setTextColor(0xFFB0B0C8);
        } else {
            String cat = e.category == null ? "Other" : e.category;
            h.b.tvCategory.setText(cat);
            h.b.tvCategory.setBackground(pill(h.b.getRoot().getContext(), colorFor(cat)));
            boolean hasNote = e.note != null && !e.note.isEmpty();
            h.b.tvNote.setText(hasNote ? e.note : "");
            h.b.tvNote.setVisibility(hasNote ? View.VISIBLE : View.GONE);
            if (e.isIncome()) {
                h.b.tvAmount.setText("+ " + currency + String.format(Locale.getDefault(), "%,.2f", e.amount));
                h.b.tvAmount.setTextColor(0xFF4CAF50);
            } else {
                h.b.tvAmount.setText("- " + currency + String.format(Locale.getDefault(), "%,.2f", e.amount));
                h.b.tvAmount.setTextColor(0xFFE53935);
            }
        }

        h.b.btnDelete.setOnClickListener(v -> listener.onDelete(e));
        h.b.getRoot().setOnClickListener(v -> listener.onEdit(e));
    }

    @Override
    public int getItemCount() { return items.size(); }

    private int colorFor(String cat) {
        Integer c = categoryColors.get(cat);
        return c != null ? c : 0xFF607D8B;
    }

    /** A rounded pill background for the category / type badge. */
    private android.graphics.drawable.GradientDrawable pill(android.content.Context ctx, int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(ctx.getResources().getDisplayMetrics().density * 20);
        return d;
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemExpenseBinding b;
        VH(ItemExpenseBinding b) { super(b.getRoot()); this.b = b; }
    }
}
