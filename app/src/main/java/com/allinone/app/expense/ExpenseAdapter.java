package com.allinone.app.expense;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.allinone.app.databinding.ItemExpenseBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ExpenseAdapter extends RecyclerView.Adapter<ExpenseAdapter.VH> {

    private final List<Expense> items;
    private final OnDeleteListener listener;

    public interface OnDeleteListener {
        void onDelete(Expense e);
    }

    public ExpenseAdapter(List<Expense> items, OnDeleteListener listener) {
        this.items = items;
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
        h.b.tvCategory.setText(e.category);
        h.b.tvAmount.setText(String.format(Locale.getDefault(), "- $%.2f", e.amount));
        h.b.tvNote.setText(e.note != null && !e.note.isEmpty() ? e.note : "");
        h.b.tvNote.setVisibility(
            e.note != null && !e.note.isEmpty()
                ? android.view.View.VISIBLE
                : android.view.View.GONE);
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        h.b.tvDate.setText(sdf.format(new Date(e.dateMillis)));
        int color = categoryColor(h.b.getRoot().getContext(), e.category);
        h.b.tvCategory.setBackgroundColor(color);
        h.b.btnDelete.setOnClickListener(v -> listener.onDelete(e));
    }

    @Override
    public int getItemCount() { return items.size(); }

    private int categoryColor(android.content.Context ctx, String cat) {
        if (cat == null) return 0xFF607D8B;
        switch (cat) {
            case "Food":          return 0xFF388E3C;
            case "Transport":     return 0xFF1976D2;
            case "Shopping":      return 0xFFF57C00;
            case "Bills":         return 0xFFE53935;
            case "Health":        return 0xFF7B1FA2;
            case "Entertainment": return 0xFF0097A7;
            default:              return 0xFF607D8B;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemExpenseBinding b;
        VH(ItemExpenseBinding b) { super(b.getRoot()); this.b = b; }
    }
}