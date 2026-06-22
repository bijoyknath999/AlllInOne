package com.allinone.app.expense;

import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.allinone.app.R;
import com.allinone.app.databinding.ActivityCategoriesBinding;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class CategoriesActivity extends AppCompatActivity {

    private static final int[] PALETTE = {
        0xFF388E3C, 0xFF1976D2, 0xFFF57C00, 0xFFE53935, 0xFF7B1FA2, 0xFF0097A7, 0xFF607D8B,
        0xFF2E7D32, 0xFF00897B, 0xFFC2185B, 0xFF5E35B1, 0xFFEF6C00, 0xFF455A64, 0xFFAD1457
    };

    private ActivityCategoriesBinding b;
    private ExpenseDb db;
    private MoneyPrefs prefs;
    private String type = Category.TYPE_EXPENSE;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        EdgeToEdge.enable(this);
        b = ActivityCategoriesBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        UiKit.applyInsets(b.getRoot());
        db = new ExpenseDb(this);
        prefs = new MoneyPrefs(this);
        b.btnBack.setOnClickListener(v -> finish());
        b.btnAdd.setOnClickListener(v -> showEdit(null));
        b.tvTabExpense.setOnClickListener(v -> { type = Category.TYPE_EXPENSE; render(); });
        b.tvTabIncome.setOnClickListener(v -> { type = Category.TYPE_INCOME; render(); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        boolean exp = Category.TYPE_EXPENSE.equals(type);
        b.tvTabExpense.setBackgroundResource(exp ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        b.tvTabExpense.setTextColor(getColor(exp ? R.color.color_primary : R.color.text_secondary));
        b.tvTabIncome.setBackgroundResource(!exp ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        b.tvTabIncome.setTextColor(getColor(!exp ? R.color.color_primary : R.color.text_secondary));

        b.llList.removeAllViews();
        List<Category> cats = db.queryCategories(type);
        if (cats.isEmpty()) {
            b.llList.addView(UiKit.emptyHint(this, "No categories.\nTap + to add one."));
            return;
        }
        long[] month = monthRange();
        for (Category c : cats) {
            String sub;
            String right = null;
            int rightColor = 0;
            if (exp && c.budget > 0) {
                double spent = db.spentInCategory(c.name, month[0], month[1]);
                sub = "Budget " + prefs.format(c.budget) + "  •  spent " + prefs.format(spent);
                right = spent > c.budget ? "OVER" : "OK";
                rightColor = spent > c.budget ? getColor(R.color.color_primary) : getColor(R.color.income_green);
            } else {
                sub = exp ? "No budget set" : "Income";
            }
            View rowView = UiKit.row(this, c.color, c.name, sub, right, rightColor, v -> showEdit(c));
            rowView.setOnLongClickListener(v -> { confirmDelete(c); return true; });
            b.llList.addView(rowView);
        }
        b.llList.addView(UiKit.emptyHint(this, "Tap to edit • long-press to delete"));
    }

    private void showEdit(final Category existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 22);
        box.setPadding(p, p, p, p);

        final EditText etName = input("Category name", InputType.TYPE_CLASS_TEXT);
        if (existing != null) etName.setText(existing.name);
        box.addView(etName);

        final int[] selColor = {existing != null ? existing.color : PALETTE[0]};
        box.addView(spacer());
        box.addView(colorPicker(selColor));

        final EditText etBudget = input("Monthly budget (0 = none)",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (existing != null && existing.budget > 0)
            etBudget.setText(String.format(Locale.getDefault(), "%.2f", existing.budget));
        if (Category.TYPE_EXPENSE.equals(type)) {
            box.addView(spacer());
            box.addView(etBudget);
        }

        new AlertDialog.Builder(this)
            .setTitle(existing == null ? "New Category" : "Edit Category")
            .setView(box)
            .setPositiveButton("Save", (d, w) -> {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) { toast("Enter a name"); return; }
                double budget = 0;
                if (Category.TYPE_EXPENSE.equals(type)) {
                    try { String s2 = etBudget.getText().toString().trim();
                          if (!s2.isEmpty()) budget = Double.parseDouble(s2); }
                    catch (NumberFormatException e) { toast("Invalid budget"); return; }
                }
                Category c = existing != null ? existing : new Category();
                c.name = name; c.color = selColor[0]; c.type = type; c.budget = budget;
                if (existing != null) db.updateCategory(c); else db.insertCategory(c);
                render();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private View colorPicker(final int[] selColor) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final View[] swatches = new View[PALETTE.length];
        for (int i = 0; i < PALETTE.length; i++) {
            final int color = PALETTE[i];
            View sw = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            sw.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UiKit.dp(this, 34), UiKit.dp(this, 34));
            lp.setMarginEnd(UiKit.dp(this, 10));
            sw.setLayoutParams(lp);
            sw.setAlpha(color == selColor[0] ? 1f : 0.35f);
            swatches[i] = sw;
            sw.setOnClickListener(v -> {
                selColor[0] = color;
                for (View s : swatches) s.setAlpha(0.35f);
                v.setAlpha(1f);
            });
            row.addView(sw);
        }
        scroll.addView(row);
        return scroll;
    }

    private void confirmDelete(Category c) {
        new AlertDialog.Builder(this)
            .setTitle("Delete \"" + c.name + "\"?")
            .setMessage("Existing transactions keep this category name. Continue?")
            .setPositiveButton("Delete", (d, w) -> { db.deleteCategory(c.id); render(); })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private long[] monthRange() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0);
        long start = c.getTimeInMillis();
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59);
        return new long[]{start, c.getTimeInMillis()};
    }

    private EditText input(String hint, int type) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(type);
        et.setTextColor(getColor(R.color.text_primary));
        et.setHintTextColor(getColor(R.color.text_hint));
        et.setBackgroundResource(R.drawable.bg_input_field);
        int p = UiKit.dp(this, 12);
        et.setPadding(p, p, p, p);
        return et;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 12)));
        return v;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
