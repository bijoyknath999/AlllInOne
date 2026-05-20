package com.allinone.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.allinone.app.databinding.ActivityExpenseTrackerBinding;
import com.allinone.app.expense.Expense;
import com.allinone.app.expense.ExpenseAdapter;
import com.allinone.app.expense.ExpenseDb;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class ExpenseTrackerActivity extends AppCompatActivity {

    private static final int MODE_WEEK  = 0;
    private static final int MODE_MONTH = 1;
    private static final int MODE_RANGE = 2;

    private ActivityExpenseTrackerBinding binding;
    private ExpenseDb db;
    private ExpenseAdapter adapter;
    private final List<Expense> expenses = new ArrayList<>();

    private int viewMode = MODE_WEEK;
    // cursor for week/month navigation — points to any day inside the viewed period
    private final Calendar cursor = Calendar.getInstance();
    // custom range bounds
    private final Calendar rangeFrom = Calendar.getInstance();
    private final Calendar rangeTo   = Calendar.getInstance();

    private final SimpleDateFormat shortFmt = new SimpleDateFormat("MMM dd", Locale.getDefault());
    private final SimpleDateFormat longFmt  = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
    private final SimpleDateFormat monthFmt = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityExpenseTrackerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.getRoot().setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        // Default range: last 30 days → today
        rangeFrom.add(Calendar.DAY_OF_MONTH, -30);

        db = new ExpenseDb(this);
        binding.btnBack.setOnClickListener(v -> finish());
        setupRecyclerView();
        setupTabs();
        setupNav();
        setupRangePickers();
        binding.fabAdd.setOnClickListener(v -> showAddDialog());
        load();
        animateIn();
    }

    // ── RecyclerView ────────────────────────────────────────────────────────
    private void setupRecyclerView() {
        adapter = new ExpenseAdapter(expenses, e -> new AlertDialog.Builder(this)
            .setTitle("Delete Expense")
            .setMessage("Remove this expense?")
            .setPositiveButton("Delete", (d, w) -> { db.delete(e.id); load(); })
            .setNegativeButton("Cancel", null)
            .show());
        binding.rvExpenses.setLayoutManager(new LinearLayoutManager(this));
        binding.rvExpenses.setAdapter(adapter);
    }

    // ── Tabs ────────────────────────────────────────────────────────────────
    private void setupTabs() {
        binding.btnWeek.setOnClickListener(v  -> switchMode(MODE_WEEK));
        binding.btnMonth.setOnClickListener(v -> switchMode(MODE_MONTH));
        binding.btnRange.setOnClickListener(v -> switchMode(MODE_RANGE));
    }

    private void switchMode(int mode) {
        viewMode = mode;
        cursor.setTimeInMillis(Calendar.getInstance().getTimeInMillis()); // reset to today on tab switch
        updateTabStyles();
        boolean isRange = mode == MODE_RANGE;
        binding.llRangePicker.setVisibility(isRange ? View.VISIBLE : View.GONE);
        binding.btnPrev.setVisibility(isRange ? View.INVISIBLE : View.VISIBLE);
        binding.btnNext.setVisibility(isRange ? View.INVISIBLE : View.VISIBLE);
        load();
    }

    private void updateTabStyles() {
        int on  = getResources().getColor(R.color.color_primary, null);
        int off = getResources().getColor(R.color.text_secondary, null);
        int selBg   = R.drawable.bg_chip_selected;
        int unselBg = R.drawable.bg_chip_unselected;

        binding.btnWeek.setBackgroundResource(viewMode == MODE_WEEK  ? selBg : unselBg);
        binding.btnWeek.setTextColor(viewMode == MODE_WEEK  ? on : off);
        binding.btnMonth.setBackgroundResource(viewMode == MODE_MONTH ? selBg : unselBg);
        binding.btnMonth.setTextColor(viewMode == MODE_MONTH ? on : off);
        binding.btnRange.setBackgroundResource(viewMode == MODE_RANGE ? selBg : unselBg);
        binding.btnRange.setTextColor(viewMode == MODE_RANGE ? on : off);
    }

    // ── Prev / Next navigation ───────────────────────────────────────────────
    private void setupNav() {
        binding.btnPrev.setOnClickListener(v -> {
            if (viewMode == MODE_WEEK)  cursor.add(Calendar.WEEK_OF_YEAR, -1);
            else                        cursor.add(Calendar.MONTH, -1);
            load();
        });
        binding.btnNext.setOnClickListener(v -> {
            if (viewMode == MODE_WEEK)  cursor.add(Calendar.WEEK_OF_YEAR, 1);
            else                        cursor.add(Calendar.MONTH, 1);
            load();
        });
    }

    // ── Range date pickers ───────────────────────────────────────────────────
    private void setupRangePickers() {
        updateRangeLabels();

        binding.tvRangeFrom.setOnClickListener(v -> new DatePickerDialog(this, (picker, y, m, d) -> {
            rangeFrom.set(y, m, d);
            rangeFrom.set(Calendar.HOUR_OF_DAY, 0); rangeFrom.set(Calendar.MINUTE, 0);
            updateRangeLabels();
            if (viewMode == MODE_RANGE) load();
        }, rangeFrom.get(Calendar.YEAR), rangeFrom.get(Calendar.MONTH), rangeFrom.get(Calendar.DAY_OF_MONTH)).show());

        binding.tvRangeTo.setOnClickListener(v -> new DatePickerDialog(this, (picker, y, m, d) -> {
            rangeTo.set(y, m, d);
            rangeTo.set(Calendar.HOUR_OF_DAY, 23); rangeTo.set(Calendar.MINUTE, 59);
            updateRangeLabels();
            if (viewMode == MODE_RANGE) load();
        }, rangeTo.get(Calendar.YEAR), rangeTo.get(Calendar.MONTH), rangeTo.get(Calendar.DAY_OF_MONTH)).show());
    }

    private void updateRangeLabels() {
        binding.tvRangeFrom.setText(longFmt.format(rangeFrom.getTime()));
        binding.tvRangeTo.setText(longFmt.format(rangeTo.getTime()));
    }

    // ── Load data ────────────────────────────────────────────────────────────
    private void load() {
        long[] range = getRange();
        expenses.clear();
        expenses.addAll(db.query(range[0], range[1]));
        adapter.notifyDataSetChanged();

        double total = db.sumAmount(range[0], range[1]);
        binding.tvTotal.setText(String.format(Locale.getDefault(), "$%.2f", total));
        binding.tvExpenseCount.setText(expenses.size() + " transaction" + (expenses.size() == 1 ? "" : "s"));
        binding.tvPeriodLabel.setText(periodLabel());

        // Disable Next when cursor is already at/past the current period
        boolean atPresent = isAtPresent();
        binding.btnNext.setAlpha(atPresent ? 0.3f : 1f);
        binding.btnNext.setClickable(!atPresent);

        binding.tvEmptyHint.setVisibility(expenses.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private long[] getRange() {
        if (viewMode == MODE_RANGE) {
            return new long[]{rangeFrom.getTimeInMillis(), rangeTo.getTimeInMillis()};
        }
        Calendar c = (Calendar) cursor.clone();
        if (viewMode == MODE_WEEK) {
            c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0);
            long start = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_WEEK, 6);
            c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59);
            return new long[]{start, c.getTimeInMillis()};
        } else {
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0);
            long start = c.getTimeInMillis();
            c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
            c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59);
            return new long[]{start, c.getTimeInMillis()};
        }
    }

    private String periodLabel() {
        if (viewMode == MODE_RANGE) {
            return shortFmt.format(rangeFrom.getTime()) + " – " + longFmt.format(rangeTo.getTime());
        }
        if (viewMode == MODE_MONTH) {
            return monthFmt.format(cursor.getTime());
        }
        // Week: show Mon – Sun
        Calendar c = (Calendar) cursor.clone();
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        String start = shortFmt.format(c.getTime());
        c.add(Calendar.DAY_OF_WEEK, 6);
        String end = shortFmt.format(c.getTime()) + ", " + c.get(Calendar.YEAR);
        return start + " – " + end;
    }

    private boolean isAtPresent() {
        Calendar now = Calendar.getInstance();
        if (viewMode == MODE_WEEK) {
            return cursor.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && cursor.get(Calendar.WEEK_OF_YEAR) == now.get(Calendar.WEEK_OF_YEAR);
        }
        return cursor.get(Calendar.YEAR)  == now.get(Calendar.YEAR)
            && cursor.get(Calendar.MONTH) == now.get(Calendar.MONTH);
    }

    // ── Add expense dialog ───────────────────────────────────────────────────
    private void showAddDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_expense, null);
        EditText etAmount      = view.findViewById(R.id.et_amount);
        LinearLayout llCats    = view.findViewById(R.id.ll_categories);
        EditText etNote        = view.findViewById(R.id.et_note);
        TextView tvDate        = view.findViewById(R.id.tv_selected_date);

        final String[] selCat = {Expense.CATEGORIES[0]};
        final Calendar selDate = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        tvDate.setText(sdf.format(selDate.getTime()));

        int[] catColors = {0xFF388E3C, 0xFF1976D2, 0xFFF57C00, 0xFFE53935, 0xFF7B1FA2, 0xFF0097A7, 0xFF607D8B};
        TextView[] chips = new TextView[Expense.CATEGORIES.length];
        for (int i = 0; i < Expense.CATEGORIES.length; i++) {
            TextView chip = new TextView(this);
            String cat = Expense.CATEGORIES[i];
            chip.setText(cat);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(12f);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(12));
            bg.setColor(catColors[i]);
            chip.setBackground(bg);
            chip.setAlpha(i == 0 ? 1f : 0.4f);
            chip.setOnClickListener(v -> {
                selCat[0] = cat;
                for (TextView c : chips) c.setAlpha(0.4f);
                chip.setAlpha(1f);
            });
            chips[i] = chip;
            llCats.addView(chip);
        }

        tvDate.setOnClickListener(v -> new DatePickerDialog(this, (picker, y, m, d) -> {
            selDate.set(y, m, d);
            tvDate.setText(sdf.format(selDate.getTime()));
        }, selDate.get(Calendar.YEAR), selDate.get(Calendar.MONTH), selDate.get(Calendar.DAY_OF_MONTH)).show());

        new AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Add", (d, w) -> {
                String amtStr = etAmount.getText().toString().trim();
                if (amtStr.isEmpty()) { Toast.makeText(this, "Enter an amount", Toast.LENGTH_SHORT).show(); return; }
                double amount;
                try { amount = Double.parseDouble(amtStr); }
                catch (NumberFormatException ex) { Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show(); return; }
                Expense e = new Expense();
                e.category   = selCat[0];
                e.amount     = amount;
                e.note       = etNote.getText().toString().trim();
                e.dateMillis = selDate.getTimeInMillis();
                db.insert(e);
                load();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void animateIn() {
        Animation a = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        binding.cardSummary.startAnimation(a);
        Animation a2 = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        a2.setStartOffset(120);
        binding.rvExpenses.startAnimation(a2);
    }
}