package com.allinone.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.allinone.app.expense.Category;
import com.allinone.app.expense.Expense;
import com.allinone.app.expense.ExpenseAdapter;
import com.allinone.app.expense.ExpenseDb;
import com.allinone.app.expense.LoansActivity;
import com.allinone.app.expense.MoneyPrefs;
import com.allinone.app.expense.RecurringActivity;
import com.allinone.app.expense.RecurringEngine;
import com.allinone.app.expense.RecurringScheduler;
import com.allinone.app.expense.ReportsActivity;
import com.allinone.app.expense.SettingsActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A simple month-by-month expense tracker: set a monthly budget, add expenses and
 * income, filter by category. Borrow &amp; lend, reports, recurring and settings live
 * in the menu box under the summary card.
 */
public class ExpenseTrackerActivity extends AppCompatActivity {

    private static final int CHIPS_PER_ROW = 3;

    private ActivityExpenseTrackerBinding binding;
    private ExpenseDb db;
    private MoneyPrefs prefs;
    private ExpenseAdapter adapter;

    private final List<Expense> shown = new ArrayList<>();       // what the list displays
    private final List<Expense> monthAll = new ArrayList<>();    // everything in the month
    private final Map<String, Integer> categoryColors = new HashMap<>();

    private double budget = 0;
    private String categoryFilter = null;   // null = All
    private String search = "";

    /** Any day inside the month being viewed. */
    private final Calendar cursor = Calendar.getInstance();

    private final SimpleDateFormat monthFmt = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
    private final SimpleDateFormat monthOnlyFmt = new SimpleDateFormat("MMMM", Locale.getDefault());

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

        prefs = new MoneyPrefs(this);
        db = new ExpenseDb(this);

        // Auto-post any due recurring transactions, then keep the daily alarm armed.
        RecurringEngine.postDue(this);
        RecurringScheduler.scheduleDaily(this);

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnSearch.setOnClickListener(v -> toggleSearch());
        binding.llBudgetTap.setOnClickListener(v -> showBudgetDialog());
        binding.fabAdd.setOnClickListener(v -> showEntryDialog(null));

        binding.tileLoans.setOnClickListener(v -> open(LoansActivity.class));
        binding.tileReports.setOnClickListener(v -> open(ReportsActivity.class));
        binding.tileRecurring.setOnClickListener(v -> open(RecurringActivity.class));
        binding.tileSettings.setOnClickListener(v -> open(SettingsActivity.class));

        binding.btnPrev.setOnClickListener(v -> { cursor.add(Calendar.MONTH, -1); load(); });
        binding.btnNext.setOnClickListener(v -> { cursor.add(Calendar.MONTH, 1); load(); });
        binding.llMonthTap.setOnClickListener(v -> showMonthPicker());

        binding.etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { search = s.toString(); load(); }
        });

        adapter = new ExpenseAdapter(shown, prefs.getCurrency(), categoryColors,
            new ExpenseAdapter.Listener() {
                public void onEdit(Expense e) { showEntryDialog(e); }
                public void onDelete(Expense e) { confirmDelete(e); }
            });
        binding.rvExpenses.setLayoutManager(new LinearLayoutManager(this));
        binding.rvExpenses.setAdapter(adapter);

        animateIn();
    }

    @Override
    protected void onResume() {
        super.onResume();
        budget = prefs.getBudget();
        load();
    }

    private void open(Class<?> cls) {
        startActivity(new Intent(this, cls));
    }

    // ── Period ────────────────────────────────────────────────────────────────
    private long[] monthRange() {
        Calendar c = (Calendar) cursor.clone();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long start = c.getTimeInMillis();
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999);
        return new long[]{start, c.getTimeInMillis()};
    }

    private boolean isThisMonth() {
        Calendar now = Calendar.getInstance();
        return cursor.get(Calendar.YEAR) == now.get(Calendar.YEAR)
            && cursor.get(Calendar.MONTH) == now.get(Calendar.MONTH);
    }

    /** Month list going back two years, so old entries stay reachable in two taps. */
    private void showMonthPicker() {
        final List<Calendar> months = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        int selected = 0;
        for (int i = 0; i < 24; i++) {
            Calendar m = (Calendar) c.clone();
            months.add(m);
            labels.add(monthFmt.format(m.getTime()) + (i == 0 ? "  •  this month" : ""));
            if (m.get(Calendar.YEAR) == cursor.get(Calendar.YEAR)
                && m.get(Calendar.MONTH) == cursor.get(Calendar.MONTH)) {
                selected = i;
            }
            c.add(Calendar.MONTH, -1);
        }
        new AlertDialog.Builder(this)
            .setTitle("Jump to month")
            .setSingleChoiceItems(labels.toArray(new String[0]), selected, (d, which) -> {
                cursor.setTimeInMillis(months.get(which).getTimeInMillis());
                load();
                d.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    private void load() {
        categoryColors.clear();
        for (Category c : db.queryCategories(null)) categoryColors.put(c.name, c.color);

        long[] range = monthRange();
        monthAll.clear();
        monthAll.addAll(db.queryFiltered(range[0], range[1], null, null, search));

        // The filter chips only offer categories that actually occur this month, so the
        // row stays short and every chip leads somewhere.
        Set<String> present = new LinkedHashSet<>();
        for (Expense e : monthAll) present.add(categoryOf(e));
        if (categoryFilter != null && !present.contains(categoryFilter)) categoryFilter = null;

        shown.clear();
        double income = 0, expense = 0, shownTotal = 0;
        for (Expense e : monthAll) {
            if (e.isIncome()) income += e.amount; else expense += e.amount;
            if (categoryFilter == null || categoryFilter.equals(categoryOf(e))) {
                shown.add(e);
                shownTotal += e.amount;
            }
        }
        adapter.notifyDataSetChanged();
        binding.rvExpenses.scheduleLayoutAnimation();

        buildFilterChips(present);

        binding.tvMonthLabel.setText(isThisMonth() ? "This Month" : monthFmt.format(cursor.getTime()));
        binding.tvSpentLabel.setText(("Spent in " + monthOnlyFmt.format(cursor.getTime())).toUpperCase(Locale.getDefault()));
        binding.tvSpentBig.setText(prefs.format(expense));
        binding.tvIncome.setText(prefs.format(income));
        binding.tvExpense.setText(prefs.format(expense));

        double net = income - expense;
        binding.tvNet.setText(prefs.format(net));
        binding.tvNet.setTextColor(net >= 0 ? getColor(R.color.income_green) : getColor(R.color.color_primary_light));

        binding.tvCount.setText(shown.size() + (shown.size() == 1 ? " item  •  " : " items  •  ") + prefs.format(shownTotal));
        binding.tvEmptyHint.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        binding.tvEmptyHint.setText(categoryFilter != null
            ? ("Nothing in " + categoryFilter + " this month.")
            : "Nothing here yet.\nTap + to add your first expense.");

        updateBudgetViews(expense);

        boolean atPresent = isThisMonth();
        binding.btnNext.setAlpha(atPresent ? 0.25f : 1f);
        binding.btnNext.setEnabled(!atPresent);
    }

    private String categoryOf(Expense e) {
        return e.category == null || e.category.isEmpty() ? "Other" : e.category;
    }

    // ── Budget ────────────────────────────────────────────────────────────────
    private void updateBudgetViews(double spent) {
        if (budget <= 0) {
            binding.pbBudget.setProgress(0);
            binding.pbBudget.setProgressTintList(ColorStateList.valueOf(0xFF606080));
            binding.tvBudgetLine.setText("Tap to set your monthly budget");
            return;
        }
        double pct = spent / budget * 100.0;
        int color = pct >= 100 ? 0xFFE53935 : pct >= 80 ? 0xFFFFB300 : 0xFF4CAF50;
        binding.pbBudget.setProgress((int) Math.min(100, Math.round(pct)));
        binding.pbBudget.setProgressTintList(ColorStateList.valueOf(color));

        double left = budget - spent;
        String line = left >= 0
            ? (prefs.format(left) + " left of " + prefs.format(budget))
            : (prefs.format(-left) + " over your " + prefs.format(budget) + " budget");
        binding.tvBudgetLine.setText(line + "  •  " + Math.round(pct) + "%");
    }

    private void showBudgetDialog() {
        EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setHint("e.g. 20000");
        et.setTextColor(getColor(R.color.text_primary));
        et.setHintTextColor(getColor(R.color.text_hint));
        et.setTextSize(20f);
        et.setGravity(android.view.Gravity.CENTER);
        if (budget > 0) et.setText(String.format(Locale.getDefault(), "%.0f", budget));
        int pad = dp(20);
        et.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
            .setTitle("Monthly budget")
            .setMessage("How much do you plan to spend each month?")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String s = et.getText().toString().trim();
                if (s.isEmpty()) return;
                try {
                    budget = Double.parseDouble(s);
                    prefs.setBudget(budget);
                    load();
                } catch (NumberFormatException ex) {
                    toast("Invalid amount");
                }
            })
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove", (d, w) -> { budget = 0; prefs.clearBudget(); load(); })
            .show();
    }

    // ── Category filter ───────────────────────────────────────────────────────
    private void buildFilterChips(Set<String> categories) {
        binding.llFilters.removeAllViews();
        binding.llFilters.addView(filterChip("All", 0, categoryFilter == null,
            () -> { categoryFilter = null; load(); }));
        for (String cat : categories) {
            final String name = cat;
            Integer color = categoryColors.get(cat);
            binding.llFilters.addView(filterChip(cat, color == null ? 0xFF607D8B : color,
                cat.equals(categoryFilter), () -> { categoryFilter = name; load(); }));
        }
    }

    private View filterChip(String label, int color, boolean selected, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12.5f);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setPadding(dp(15), dp(8), dp(15), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        tv.setLayoutParams(lp);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        if (selected) {
            bg.setColor(color == 0 ? getColor(R.color.color_primary) : color);
            tv.setTextColor(Color.WHITE);
        } else {
            bg.setColor(0xFF1C1C32);
            bg.setStroke(dp(1), 0xFF2A2A4A);
            tv.setTextColor(getColor(R.color.text_secondary));
        }
        tv.setBackground(bg);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private void toggleSearch() {
        boolean visible = binding.etSearch.getVisibility() == View.VISIBLE;
        binding.etSearch.setVisibility(visible ? View.GONE : View.VISIBLE);
        if (visible) {
            binding.etSearch.setText("");
        } else {
            binding.etSearch.requestFocus();
        }
    }

    // ── Add / edit a transaction ──────────────────────────────────────────────
    private void showEntryDialog(final Expense existing) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_expense, null);
        TextView tvTitle  = view.findViewById(R.id.tv_dialog_title);
        TextView tvExp    = view.findViewById(R.id.tv_type_expense);
        TextView tvInc    = view.findViewById(R.id.tv_type_income);
        TextView tvCur    = view.findViewById(R.id.tv_currency);
        EditText etAmount = view.findViewById(R.id.et_amount);
        LinearLayout llCats = view.findViewById(R.id.ll_categories);
        EditText etNote   = view.findViewById(R.id.et_note);
        TextView tvDate   = view.findViewById(R.id.tv_selected_date);

        tvCur.setText(prefs.getCurrency());

        final String[] selType = {existing != null ? existing.type : Expense.TYPE_EXPENSE};
        final String[] selCat = {existing != null ? existing.category : null};
        final Calendar selDate = Calendar.getInstance();
        if (existing != null) {
            selDate.setTimeInMillis(existing.dateMillis);
        } else if (!isThisMonth()) {
            // Adding while browsing an older month: default the date into that month.
            selDate.setTimeInMillis(cursor.getTimeInMillis());
        }

        SimpleDateFormat sdf = new SimpleDateFormat("EEE, MMM dd, yyyy", Locale.getDefault());
        tvDate.setText(sdf.format(selDate.getTime()));

        if (existing != null) {
            tvTitle.setText("Edit Transaction");
            etAmount.setText(String.format(Locale.getDefault(), "%.2f", existing.amount));
            etNote.setText(existing.note);
        }

        final Runnable applyType = () -> {
            boolean isExpense = Expense.TYPE_EXPENSE.equals(selType[0]);
            styleTypeChip(tvExp, isExpense);
            styleTypeChip(tvInc, !isExpense);
            if (existing == null) tvTitle.setText(isExpense ? "Add Expense" : "Add Income");
            buildCategoryGrid(llCats, selType[0], selCat);
        };

        tvExp.setOnClickListener(v -> { selType[0] = Expense.TYPE_EXPENSE; applyType.run(); });
        tvInc.setOnClickListener(v -> { selType[0] = Expense.TYPE_INCOME; applyType.run(); });
        tvDate.setOnClickListener(v -> new DatePickerDialog(this, (p, y, m, d) -> {
            selDate.set(y, m, d);
            tvDate.setText(sdf.format(selDate.getTime()));
        }, selDate.get(Calendar.YEAR), selDate.get(Calendar.MONTH), selDate.get(Calendar.DAY_OF_MONTH)).show());

        applyType.run();
        etAmount.requestFocus();

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Save", (d, w) -> {
                String amtStr = etAmount.getText().toString().trim();
                if (amtStr.isEmpty()) { toast("Enter an amount"); return; }
                double amount;
                try { amount = Double.parseDouble(amtStr); }
                catch (NumberFormatException ex) { toast("Invalid amount"); return; }
                if (amount <= 0) { toast("Amount must be positive"); return; }

                Expense e = existing != null ? existing : new Expense();
                e.type = selType[0];
                e.amount = amount;
                e.category = selCat[0];
                e.note = etNote.getText().toString().trim();
                e.dateMillis = selDate.getTimeInMillis();
                if (existing != null) db.update(e); else db.insert(e);

                // Jump to the month the transaction landed in so it is never saved "into thin air".
                cursor.setTimeInMillis(e.dateMillis);
                load();
            })
            .setNegativeButton("Cancel", null);
        if (existing != null) b.setNeutralButton("Delete", (d, w) -> confirmDelete(existing));
        b.show();
    }

    /** Category chips as a tidy 3-per-row grid — every option visible at once. */
    private void buildCategoryGrid(LinearLayout container, String type, final String[] selCat) {
        container.removeAllViews();
        String catType = Expense.TYPE_INCOME.equals(type) ? Category.TYPE_INCOME : Category.TYPE_EXPENSE;
        List<Category> cats = db.queryCategories(catType);
        if (cats.isEmpty()) return;

        boolean matched = false;
        for (Category c : cats) if (c.name.equals(selCat[0])) matched = true;
        if (!matched) selCat[0] = cats.get(0).name;

        final List<TextView> chips = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < cats.size(); i++) {
            if (i % CHIPS_PER_ROW == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(8);
                row.setLayoutParams(rlp);
                container.addView(row);
            }
            final Category cat = cats.get(i);
            TextView chip = new TextView(this);
            chip.setText(cat.name);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(12.5f);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setMaxLines(1);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setPadding(dp(6), dp(10), dp(6), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            chip.setTag(cat.color);
            styleCategoryChip(chip, cat.color, cat.name.equals(selCat[0]));
            chip.setOnClickListener(v -> {
                selCat[0] = cat.name;
                for (TextView c : chips) styleCategoryChip(c, (Integer) c.getTag(), false);
                styleCategoryChip(chip, cat.color, true);
            });
            chips.add(chip);
            row.addView(chip);
        }
        // Pad the last row so the final chips keep the same width as the rows above.
        int remainder = cats.size() % CHIPS_PER_ROW;
        if (remainder != 0 && row != null) {
            for (int i = remainder; i < CHIPS_PER_ROW; i++) {
                View filler = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMarginEnd(dp(8));
                filler.setLayoutParams(lp);
                row.addView(filler);
            }
        }
    }

    private void styleCategoryChip(TextView chip, int color, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (selected) {
            bg.setColor(color);
            chip.setAlpha(1f);
            chip.setTypeface(null, Typeface.BOLD);
        } else {
            bg.setColor(0xFF1C1C32);
            bg.setStroke(dp(1), color);
            chip.setAlpha(0.75f);
            chip.setTypeface(null, Typeface.NORMAL);
        }
        chip.setBackground(bg);
    }

    private void styleTypeChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chip.setTextColor(selected ? getColor(R.color.color_primary) : getColor(R.color.text_secondary));
        chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void confirmDelete(Expense e) {
        new AlertDialog.Builder(this)
            .setTitle("Delete this entry?")
            .setMessage(categoryOf(e) + "  •  " + prefs.format(e.amount))
            .setPositiveButton("Delete", (d, w) -> { db.delete(e.id); load(); })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private int dp(int dp) { return (int) (dp * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void animateIn() {
        Animation a = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        binding.cardSummary.startAnimation(a);
        Animation a2 = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        a2.setStartOffset(90);
        binding.cardMenu.startAnimation(a2);
        Animation a3 = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        a3.setStartOffset(160);
        binding.rvExpenses.startAnimation(a3);
    }
}
