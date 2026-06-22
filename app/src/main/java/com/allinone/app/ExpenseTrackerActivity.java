package com.allinone.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
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
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.allinone.app.databinding.ActivityExpenseTrackerBinding;
import com.allinone.app.expense.Account;
import com.allinone.app.expense.AccountsActivity;
import com.allinone.app.expense.CategoriesActivity;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ExpenseTrackerActivity extends AppCompatActivity {

    private static final int MODE_WEEK  = 0;
    private static final int MODE_MONTH = 1;
    private static final int MODE_RANGE = 2;

    private ActivityExpenseTrackerBinding binding;
    private ExpenseDb db;
    private MoneyPrefs prefs;
    private ExpenseAdapter adapter;
    private final List<Expense> expenses = new ArrayList<>();
    private final Map<String, Integer> categoryColors = new HashMap<>();
    private final Map<Long, String> accountNames = new HashMap<>();

    private double budget = 0;
    private int viewMode = MODE_WEEK;
    private long selectedAccountId = 0; // 0 = all accounts
    private String search = "";

    private final Calendar cursor = Calendar.getInstance();
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

        rangeFrom.add(Calendar.DAY_OF_MONTH, -30);

        prefs = new MoneyPrefs(this);
        budget = prefs.getBudget();
        db = new ExpenseDb(this);

        // Auto-post any due recurring transactions, then keep the daily alarm armed.
        RecurringEngine.postDue(this);
        RecurringScheduler.scheduleDaily(this);

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnReports.setOnClickListener(v -> startActivity(new Intent(this, ReportsActivity.class)));
        binding.btnMenu.setOnClickListener(this::showMenu);
        binding.llBudgetTap.setOnClickListener(v -> showBudgetDialog());
        binding.fabAdd.setOnClickListener(v -> showEntryDialog(null));

        binding.etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { search = s.toString(); load(); }
        });

        setupRecyclerView();
        setupTabs();
        setupNav();
        setupRangePickers();
        animateIn();
    }

    @Override
    protected void onResume() {
        super.onResume();
        budget = prefs.getBudget();
        refreshAccountsBar();
        load();
    }

    // ── Navigation menu ───────────────────────────────────────────────────────
    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Accounts");
        pm.getMenu().add(0, 2, 1, "Categories & Budgets");
        pm.getMenu().add(0, 3, 2, "Loans (borrow / lend)");
        pm.getMenu().add(0, 4, 3, "Recurring");
        pm.getMenu().add(0, 5, 4, "Reports");
        pm.getMenu().add(0, 6, 5, "Settings");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: startActivity(new Intent(this, AccountsActivity.class)); return true;
                case 2: startActivity(new Intent(this, CategoriesActivity.class)); return true;
                case 3: startActivity(new Intent(this, LoansActivity.class)); return true;
                case 4: startActivity(new Intent(this, RecurringActivity.class)); return true;
                case 5: startActivity(new Intent(this, ReportsActivity.class)); return true;
                case 6: startActivity(new Intent(this, SettingsActivity.class)); return true;
            }
            return false;
        });
        pm.show();
    }

    // ── Accounts bar ──────────────────────────────────────────────────────────
    private void refreshAccountsBar() {
        binding.llAccounts.removeAllViews();
        List<Account> accounts = db.queryAccounts(false);

        binding.llAccounts.addView(accountChip("All", prefs.format(db.totalAccountsBalance()),
            selectedAccountId == 0, () -> { selectedAccountId = 0; refreshAccountsBar(); load(); }));

        for (Account a : accounts) {
            binding.llAccounts.addView(accountChip(a.name, prefs.format(a.balance),
                selectedAccountId == a.id, () -> { selectedAccountId = a.id; refreshAccountsBar(); load(); }));
        }
    }

    private View accountChip(String name, String balance, boolean selected, Runnable onClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        box.setLayoutParams(lp);
        box.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        box.setClickable(true);
        box.setFocusable(true);

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextColor(selected ? getColor(R.color.color_primary) : getColor(R.color.text_secondary));
        tvName.setTextSize(12f);
        tvName.setTypeface(tvName.getTypeface(), android.graphics.Typeface.BOLD);

        TextView tvBal = new TextView(this);
        tvBal.setText(balance);
        tvBal.setTextColor(getColor(R.color.text_primary));
        tvBal.setTextSize(13f);

        box.addView(tvName);
        box.addView(tvBal);
        box.setOnClickListener(v -> onClick.run());
        return box;
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────
    private void setupRecyclerView() {
        adapter = new ExpenseAdapter(expenses, prefs.getCurrency(), categoryColors, accountNames,
            new ExpenseAdapter.Listener() {
                public void onEdit(Expense e) { showEntryDialog(e); }
                public void onDelete(Expense e) { confirmDelete(e); }
            });
        binding.rvExpenses.setLayoutManager(new LinearLayoutManager(this));
        binding.rvExpenses.setAdapter(adapter);
    }

    private void confirmDelete(Expense e) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Transaction")
            .setMessage("Remove this transaction?")
            .setPositiveButton("Delete", (d, w) -> { db.delete(e.id); refreshAccountsBar(); load(); })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Tabs / nav / range (period selection) ──────────────────────────────────
    private void setupTabs() {
        binding.btnWeek.setOnClickListener(v  -> switchMode(MODE_WEEK));
        binding.btnMonth.setOnClickListener(v -> switchMode(MODE_MONTH));
        binding.btnRange.setOnClickListener(v -> switchMode(MODE_RANGE));
    }

    private void switchMode(int mode) {
        viewMode = mode;
        cursor.setTimeInMillis(System.currentTimeMillis());
        updateTabStyles();
        boolean isRange = mode == MODE_RANGE;
        binding.llRangePicker.setVisibility(isRange ? View.VISIBLE : View.GONE);
        binding.btnPrev.setVisibility(isRange ? View.INVISIBLE : View.VISIBLE);
        binding.btnNext.setVisibility(isRange ? View.INVISIBLE : View.VISIBLE);
        load();
    }

    private void updateTabStyles() {
        int on  = getColor(R.color.color_primary);
        int off = getColor(R.color.text_secondary);
        int selBg = R.drawable.bg_chip_selected, unselBg = R.drawable.bg_chip_unselected;
        binding.btnWeek.setBackgroundResource(viewMode == MODE_WEEK ? selBg : unselBg);
        binding.btnWeek.setTextColor(viewMode == MODE_WEEK ? on : off);
        binding.btnMonth.setBackgroundResource(viewMode == MODE_MONTH ? selBg : unselBg);
        binding.btnMonth.setTextColor(viewMode == MODE_MONTH ? on : off);
        binding.btnRange.setBackgroundResource(viewMode == MODE_RANGE ? selBg : unselBg);
        binding.btnRange.setTextColor(viewMode == MODE_RANGE ? on : off);
    }

    private void setupNav() {
        binding.btnPrev.setOnClickListener(v -> {
            if (viewMode == MODE_WEEK) cursor.add(Calendar.WEEK_OF_YEAR, -1);
            else cursor.add(Calendar.MONTH, -1);
            load();
        });
        binding.btnNext.setOnClickListener(v -> {
            if (viewMode == MODE_WEEK) cursor.add(Calendar.WEEK_OF_YEAR, 1);
            else cursor.add(Calendar.MONTH, 1);
            load();
        });
    }

    private void setupRangePickers() {
        updateRangeLabels();
        binding.tvRangeFrom.setOnClickListener(v -> new DatePickerDialog(this, (p, y, m, d) -> {
            rangeFrom.set(y, m, d);
            rangeFrom.set(Calendar.HOUR_OF_DAY, 0); rangeFrom.set(Calendar.MINUTE, 0);
            updateRangeLabels();
            if (viewMode == MODE_RANGE) load();
        }, rangeFrom.get(Calendar.YEAR), rangeFrom.get(Calendar.MONTH), rangeFrom.get(Calendar.DAY_OF_MONTH)).show());

        binding.tvRangeTo.setOnClickListener(v -> new DatePickerDialog(this, (p, y, m, d) -> {
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

    // ── Load ────────────────────────────────────────────────────────────────────
    private void load() {
        rebuildLookups();
        long[] range = getRange();
        expenses.clear();
        expenses.addAll(db.queryFiltered(range[0], range[1], null, null, selectedAccountId, search));
        adapter.notifyDataSetChanged();
        binding.rvExpenses.scheduleLayoutAnimation(); // slide items in on each refresh

        double income = 0, expense = 0;
        for (Expense e : expenses) {
            if (e.isIncome()) income += e.amount;
            else if (e.isExpense()) expense += e.amount;
        }
        double net = income - expense;

        binding.tvIncome.setText(prefs.format(income));
        binding.tvExpense.setText(prefs.format(expense));
        binding.tvNet.setText(prefs.format(net));
        binding.tvNet.setTextColor(net >= 0 ? getColor(R.color.income_green) : getColor(R.color.color_primary));
        binding.tvCount.setText(expenses.size() + " transaction" + (expenses.size() == 1 ? "" : "s"));
        binding.tvPeriodLabel.setText(periodLabel());
        updateBalanceViews(expense);

        boolean atPresent = isAtPresent();
        binding.btnNext.setAlpha(atPresent ? 0.3f : 1f);
        binding.btnNext.setClickable(!atPresent);
        binding.tvEmptyHint.setVisibility(expenses.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void rebuildLookups() {
        categoryColors.clear();
        for (Category c : db.queryCategories(null)) categoryColors.put(c.name, c.color);
        accountNames.clear();
        for (Account a : db.queryAccounts(true)) accountNames.put(a.id, a.name);
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
        if (viewMode == MODE_MONTH) return monthFmt.format(cursor.getTime());
        Calendar c = (Calendar) cursor.clone();
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        String start = shortFmt.format(c.getTime());
        c.add(Calendar.DAY_OF_WEEK, 6);
        String end = shortFmt.format(c.getTime()) + ", " + c.get(Calendar.YEAR);
        return start + " – " + end;
    }

    private boolean isAtPresent() {
        Calendar now = Calendar.getInstance();
        if (viewMode == MODE_RANGE) return true;
        if (viewMode == MODE_WEEK) {
            return cursor.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && cursor.get(Calendar.WEEK_OF_YEAR) == now.get(Calendar.WEEK_OF_YEAR);
        }
        return cursor.get(Calendar.YEAR) == now.get(Calendar.YEAR)
            && cursor.get(Calendar.MONTH) == now.get(Calendar.MONTH);
    }

    private void updateBalanceViews(double totalSpent) {
        if (budget <= 0) {
            binding.tvBudget.setText("Set budget");
            binding.tvLeftBalance.setText("—");
            binding.tvLeftBalance.setTextColor(getColor(R.color.text_secondary));
        } else {
            binding.tvBudget.setText(prefs.format(budget));
            double left = budget - totalSpent;
            binding.tvLeftBalance.setText(prefs.format(left));
            binding.tvLeftBalance.setTextColor(left >= 0 ? getColor(R.color.income_green) : getColor(R.color.color_primary));
        }
    }

    private void showBudgetDialog() {
        EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setHint("Enter budget amount");
        et.setTextColor(getColor(R.color.text_primary));
        et.setHintTextColor(getColor(R.color.text_hint));
        if (budget > 0) et.setText(String.format(Locale.getDefault(), "%.2f", budget));
        int pad = dp(20);
        et.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
            .setTitle("Monthly Budget")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String s = et.getText().toString().trim();
                if (s.isEmpty()) return;
                try { budget = Double.parseDouble(s); prefs.setBudget(budget); load(); }
                catch (NumberFormatException ex) { toast("Invalid amount"); }
            })
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", (d, w) -> { budget = 0; prefs.clearBudget(); load(); })
            .show();
    }

    // ── Add / Edit transaction ──────────────────────────────────────────────────
    private void showEntryDialog(final Expense existing) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_expense, null);
        TextView tvTitle    = view.findViewById(R.id.tv_dialog_title);
        TextView tvExp      = view.findViewById(R.id.tv_type_expense);
        TextView tvInc      = view.findViewById(R.id.tv_type_income);
        TextView tvTrf      = view.findViewById(R.id.tv_type_transfer);
        EditText etAmount   = view.findViewById(R.id.et_amount);
        LinearLayout llCatSection = view.findViewById(R.id.ll_category_section);
        LinearLayout llCats = view.findViewById(R.id.ll_categories);
        TextView tvAccLabel = view.findViewById(R.id.tv_account_label);
        TextView tvAccount  = view.findViewById(R.id.tv_account);
        LinearLayout llToRow = view.findViewById(R.id.ll_to_account_row);
        TextView tvToAccount = view.findViewById(R.id.tv_to_account);
        EditText etNote     = view.findViewById(R.id.et_note);
        TextView tvDate     = view.findViewById(R.id.tv_selected_date);

        final List<Account> accounts = db.queryAccounts(false);
        if (accounts.isEmpty()) { toast("Create an account first"); return; }

        final String[] selType = {existing != null ? existing.type : Expense.TYPE_EXPENSE};
        final String[] selCat = {null};
        final long[] selAcc = {existing != null && existing.accountId > 0 ? existing.accountId
            : (selectedAccountId > 0 ? selectedAccountId : accounts.get(0).id)};
        final long[] selToAcc = {existing != null ? existing.toAccountId : 0};
        final Calendar selDate = Calendar.getInstance();
        if (existing != null) selDate.setTimeInMillis(existing.dateMillis);

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        tvDate.setText(sdf.format(selDate.getTime()));
        tvAccount.setText(nameForAccount(accounts, selAcc[0]));
        if (existing != null) {
            tvTitle.setText("Edit Transaction");
            etAmount.setText(String.format(Locale.getDefault(), "%.2f", existing.amount));
            etNote.setText(existing.note);
            selCat[0] = existing.category;
            if (selToAcc[0] > 0) tvToAccount.setText(nameForAccount(accounts, selToAcc[0]));
        }

        final Runnable[] applyType = new Runnable[1];
        applyType[0] = () -> {
            String t = selType[0];
            styleTypeChip(tvExp, Expense.TYPE_EXPENSE.equals(t));
            styleTypeChip(tvInc, Expense.TYPE_INCOME.equals(t));
            styleTypeChip(tvTrf, Expense.TYPE_TRANSFER.equals(t));
            boolean transfer = Expense.TYPE_TRANSFER.equals(t);
            llCatSection.setVisibility(transfer ? View.GONE : View.VISIBLE);
            llToRow.setVisibility(transfer ? View.VISIBLE : View.GONE);
            tvAccLabel.setText(transfer ? "FROM ACCOUNT" : "ACCOUNT");
            if (!transfer) buildCategoryChips(llCats, t, selCat, existing);
        };

        tvExp.setOnClickListener(v -> { selType[0] = Expense.TYPE_EXPENSE; applyType[0].run(); });
        tvInc.setOnClickListener(v -> { selType[0] = Expense.TYPE_INCOME; applyType[0].run(); });
        tvTrf.setOnClickListener(v -> { selType[0] = Expense.TYPE_TRANSFER; applyType[0].run(); });

        tvAccount.setOnClickListener(v -> chooseAccount(accounts, -1, a -> {
            selAcc[0] = a.id; tvAccount.setText(a.name);
        }));
        tvToAccount.setOnClickListener(v -> chooseAccount(accounts, selAcc[0], a -> {
            selToAcc[0] = a.id; tvToAccount.setText(a.name);
        }));
        tvDate.setOnClickListener(v -> new DatePickerDialog(this, (p, y, m, d) -> {
            selDate.set(y, m, d);
            tvDate.setText(sdf.format(selDate.getTime()));
        }, selDate.get(Calendar.YEAR), selDate.get(Calendar.MONTH), selDate.get(Calendar.DAY_OF_MONTH)).show());

        applyType[0].run();

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
                e.note = etNote.getText().toString().trim();
                e.dateMillis = selDate.getTimeInMillis();
                if (Expense.TYPE_TRANSFER.equals(selType[0])) {
                    if (selToAcc[0] <= 0) { toast("Choose destination account"); return; }
                    if (selToAcc[0] == selAcc[0]) { toast("Accounts must differ"); return; }
                    e.category = null;
                    e.accountId = selAcc[0];
                    e.toAccountId = selToAcc[0];
                } else {
                    e.category = selCat[0];
                    e.accountId = selAcc[0];
                    e.toAccountId = 0;
                }
                if (existing != null) db.update(e); else db.insert(e);
                refreshAccountsBar();
                load();
            })
            .setNegativeButton("Cancel", null);
        if (existing != null) {
            b.setNeutralButton("Delete", (d, w) -> confirmDelete(existing));
        }
        b.show();
    }

    private void buildCategoryChips(LinearLayout container, String type, String[] selCat, Expense existing) {
        container.removeAllViews();
        String catType = Expense.TYPE_INCOME.equals(type) ? Category.TYPE_INCOME : Category.TYPE_EXPENSE;
        List<Category> cats = db.queryCategories(catType);
        if (cats.isEmpty()) return;
        // Default selection: keep existing match, else first.
        boolean matched = false;
        for (Category c : cats) if (c.name.equals(selCat[0])) matched = true;
        if (!matched) selCat[0] = cats.get(0).name;

        final List<TextView> chips = new ArrayList<>();
        for (Category cat : cats) {
            TextView chip = new TextView(this);
            chip.setText(cat.name);
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
            bg.setColor(cat.color);
            chip.setBackground(bg);
            chip.setAlpha(cat.name.equals(selCat[0]) ? 1f : 0.4f);
            chip.setOnClickListener(v -> {
                selCat[0] = cat.name;
                for (TextView c : chips) c.setAlpha(0.4f);
                chip.setAlpha(1f);
            });
            chips.add(chip);
            container.addView(chip);
        }
    }

    private void styleTypeChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chip.setTextColor(selected ? getColor(R.color.color_primary) : getColor(R.color.text_secondary));
    }

    private interface AccountPick { void on(Account a); }

    private void chooseAccount(List<Account> accounts, long excludeId, AccountPick cb) {
        final List<Account> options = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Account a : accounts) {
            if (a.id == excludeId) continue;
            options.add(a);
            labels.add(a.name + "  (" + prefs.format(a.balance) + ")");
        }
        new AlertDialog.Builder(this)
            .setTitle("Choose account")
            .setItems(labels.toArray(new String[0]), (d, which) -> cb.on(options.get(which)))
            .show();
    }

    private String nameForAccount(List<Account> accounts, long id) {
        for (Account a : accounts) if (a.id == id) return a.name;
        return accounts.isEmpty() ? "" : accounts.get(0).name;
    }

    private int dp(int dp) { return (int) (dp * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void animateIn() {
        Animation a = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        binding.cardSummary.startAnimation(a);
        Animation a2 = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        a2.setStartOffset(120);
        binding.rvExpenses.startAnimation(a2);
    }
}
