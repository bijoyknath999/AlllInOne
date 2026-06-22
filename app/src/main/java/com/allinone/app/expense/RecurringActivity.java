package com.allinone.app.expense;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.allinone.app.R;
import com.allinone.app.databinding.ActivityRecurringBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class RecurringActivity extends AppCompatActivity {

    private static final String[] INTERVALS = {
        RecurringRule.DAILY, RecurringRule.WEEKLY, RecurringRule.MONTHLY, RecurringRule.YEARLY
    };
    private static final String[] INTERVAL_LABELS = {"Daily", "Weekly", "Monthly", "Yearly"};

    private ActivityRecurringBinding b;
    private ExpenseDb db;
    private MoneyPrefs prefs;
    private final SimpleDateFormat dfmt = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        EdgeToEdge.enable(this);
        b = ActivityRecurringBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        UiKit.applyInsets(b.getRoot());
        db = new ExpenseDb(this);
        prefs = new MoneyPrefs(this);
        b.btnBack.setOnClickListener(v -> finish());
        b.btnAdd.setOnClickListener(v -> showEdit(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        RecurringEngine.postDue(this); // catch up if anything became due
        render();
    }

    private void render() {
        b.llList.removeAllViews();
        List<RecurringRule> rules = db.queryRecurring();
        if (rules.isEmpty()) {
            b.llList.addView(UiKit.emptyHint(this, "No recurring items.\nTap + to add salary, rent, subscriptions…"));
            return;
        }
        for (RecurringRule r : rules) {
            boolean income = Expense.TYPE_INCOME.equals(r.type);
            String title = (r.category == null ? (income ? "Income" : "Expense") : r.category);
            String sub = r.intervalLabel() + "  •  next " + dfmt.format(new java.util.Date(r.nextMillis))
                + (r.enabled ? "" : "  •  paused");
            String right = (income ? "+ " : "- ") + prefs.format(r.amount);
            int color = income ? getColor(R.color.income_green) : getColor(R.color.color_primary);
            int dot = r.enabled ? color : 0xFF606080;
            View row = UiKit.row(this, dot, title, sub, right, color, v -> showEdit(r));
            row.setOnLongClickListener(v -> { confirmDelete(r); return true; });
            b.llList.addView(row);
        }
    }

    private void showEdit(final RecurringRule existing) {
        final List<Account> accounts = db.queryAccounts(false);
        if (accounts.isEmpty()) { toast("Create an account first"); return; }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 22);
        box.setPadding(p, p, p, p);

        // type toggle
        final String[] type = {existing != null ? existing.type : Expense.TYPE_EXPENSE};
        final TextView tvExp = chip("Expense", true);
        final TextView tvInc = chip("Income", false);
        LinearLayout typeRow = new LinearLayout(this);
        typeRow.addView(tvExp); typeRow.addView(tvInc);
        box.addView(typeRow);

        final EditText etAmount = input("Amount", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (existing != null) etAmount.setText(String.format(Locale.getDefault(), "%.2f", existing.amount));
        box.addView(spacer());
        box.addView(etAmount);

        final String[] selCat = {existing != null ? existing.category : null};
        final LinearLayout catContainer = new LinearLayout(this);
        catContainer.setOrientation(LinearLayout.VERTICAL);
        box.addView(spacer());
        box.addView(catContainer);

        final long[] selAcc = {existing != null ? existing.accountId : accounts.get(0).id};
        final TextView tvAcc = picker("Account: " + nameFor(accounts, selAcc[0]));
        tvAcc.setOnClickListener(v -> {
            String[] names = new String[accounts.size()];
            for (int i = 0; i < accounts.size(); i++) names[i] = accounts.get(i).name;
            new AlertDialog.Builder(this).setTitle("Account").setItems(names, (d, w) -> {
                selAcc[0] = accounts.get(w).id; tvAcc.setText("Account: " + accounts.get(w).name);
            }).show();
        });
        box.addView(spacer());
        box.addView(tvAcc);

        final String[] interval = {existing != null ? existing.intervalType : RecurringRule.MONTHLY};
        final TextView tvInterval = picker("Repeat: " + labelFor(interval[0]));
        tvInterval.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Repeat")
            .setItems(INTERVAL_LABELS, (d, w) -> { interval[0] = INTERVALS[w]; tvInterval.setText("Repeat: " + INTERVAL_LABELS[w]); })
            .show());
        box.addView(spacer());
        box.addView(tvInterval);

        final EditText etEvery = input("Every N (1 = each period)", InputType.TYPE_CLASS_NUMBER);
        etEvery.setText(String.valueOf(existing != null ? Math.max(1, existing.intervalN) : 1));
        box.addView(spacer());
        box.addView(etEvery);

        final Calendar next = Calendar.getInstance();
        if (existing != null) next.setTimeInMillis(existing.nextMillis);
        final TextView tvNext = picker("Next: " + dfmt.format(next.getTime()));
        tvNext.setOnClickListener(v -> new DatePickerDialog(this, (pk, y, m, d) -> {
            next.set(y, m, d, 9, 0, 0); tvNext.setText("Next: " + dfmt.format(next.getTime()));
        }, next.get(Calendar.YEAR), next.get(Calendar.MONTH), next.get(Calendar.DAY_OF_MONTH)).show());
        box.addView(spacer());
        box.addView(tvNext);

        final EditText etNote = input("Note (optional)", InputType.TYPE_CLASS_TEXT);
        if (existing != null) etNote.setText(existing.note);
        box.addView(spacer());
        box.addView(etNote);

        final CheckBox cbEnabled = new CheckBox(this);
        cbEnabled.setText("Enabled");
        cbEnabled.setTextColor(getColor(R.color.text_primary));
        cbEnabled.setChecked(existing == null || existing.enabled);
        box.addView(spacer());
        box.addView(cbEnabled);

        final Runnable rebuildCats = () -> buildCats(catContainer, type[0], selCat);
        tvExp.setOnClickListener(v -> { type[0] = Expense.TYPE_EXPENSE; styleChip(tvExp, true); styleChip(tvInc, false); rebuildCats.run(); });
        tvInc.setOnClickListener(v -> { type[0] = Expense.TYPE_INCOME; styleChip(tvExp, false); styleChip(tvInc, true); rebuildCats.run(); });
        styleChip(tvExp, Expense.TYPE_EXPENSE.equals(type[0]));
        styleChip(tvInc, Expense.TYPE_INCOME.equals(type[0]));
        rebuildCats.run();

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
            .setTitle(existing == null ? "New Recurring" : "Edit Recurring")
            .setView(sv)
            .setPositiveButton("Save", (d, w) -> {
                double amount;
                try { amount = Double.parseDouble(etAmount.getText().toString().trim()); }
                catch (Exception e) { toast("Invalid amount"); return; }
                if (amount <= 0) { toast("Amount must be positive"); return; }
                int everyN = 1;
                try { everyN = Math.max(1, Integer.parseInt(etEvery.getText().toString().trim())); }
                catch (Exception ignored) {}

                RecurringRule r = existing != null ? existing : new RecurringRule();
                r.type = type[0];
                r.category = selCat[0];
                r.amount = amount;
                r.note = etNote.getText().toString().trim();
                r.accountId = selAcc[0];
                r.toAccountId = 0;
                r.intervalType = interval[0];
                r.intervalN = everyN;
                r.nextMillis = next.getTimeInMillis();
                r.enabled = cbEnabled.isChecked();
                if (existing != null) db.updateRecurring(r); else db.insertRecurring(r);
                RecurringEngine.postDue(this);
                RecurringScheduler.scheduleDaily(this);
                render();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void buildCats(LinearLayout container, String type, String[] selCat) {
        container.removeAllViews();
        String catType = Expense.TYPE_INCOME.equals(type) ? Category.TYPE_INCOME : Category.TYPE_EXPENSE;
        List<Category> cats = db.queryCategories(catType);
        if (cats.isEmpty()) return;
        boolean matched = false;
        for (Category c : cats) if (c.name.equals(selCat[0])) matched = true;
        if (!matched) selCat[0] = cats.get(0).name;

        TextView label = new TextView(this);
        label.setText("CATEGORY");
        label.setTextColor(getColor(R.color.text_secondary));
        label.setTextSize(10f);
        label.setPadding(0, 0, 0, UiKit.dp(this, 6));
        container.addView(label);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        final List<TextView> chips = new ArrayList<>();
        for (Category cat : cats) {
            TextView chip = new TextView(this);
            chip.setText(cat.name);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(12f);
            chip.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 6), UiKit.dp(this, 12), UiKit.dp(this, 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(UiKit.dp(this, 8));
            chip.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(UiKit.dp(this, 12));
            bg.setColor(cat.color);
            chip.setBackground(bg);
            chip.setAlpha(cat.name.equals(selCat[0]) ? 1f : 0.4f);
            chip.setOnClickListener(v -> {
                selCat[0] = cat.name;
                for (TextView c : chips) c.setAlpha(0.4f);
                chip.setAlpha(1f);
            });
            chips.add(chip);
            row.addView(chip);
        }
        scroll.addView(row);
        container.addView(scroll);
    }

    private void confirmDelete(RecurringRule r) {
        new AlertDialog.Builder(this)
            .setTitle("Delete recurring item?")
            .setPositiveButton("Delete", (d, w) -> { db.deleteRecurring(r.id); render(); })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private String labelFor(String interval) {
        for (int i = 0; i < INTERVALS.length; i++) if (INTERVALS[i].equals(interval)) return INTERVAL_LABELS[i];
        return "Monthly";
    }

    private String nameFor(List<Account> accounts, long id) {
        for (Account a : accounts) if (a.id == id) return a.name;
        return accounts.isEmpty() ? "" : accounts.get(0).name;
    }

    private TextView chip(String text, boolean on) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 9), UiKit.dp(this, 10), UiKit.dp(this, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(UiKit.dp(this, 5));
        tv.setLayoutParams(lp);
        tv.setTextSize(12f);
        tv.setClickable(true);
        tv.setFocusable(true);
        styleChip(tv, on);
        return tv;
    }

    private void styleChip(TextView tv, boolean on) {
        tv.setBackgroundResource(on ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        tv.setTextColor(getColor(on ? R.color.color_primary : R.color.text_secondary));
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

    private TextView picker(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextColor(getColor(R.color.color_primary));
        tv.setBackgroundResource(R.drawable.bg_input_field);
        int p = UiKit.dp(this, 12);
        tv.setPadding(p, p, p, p);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 12)));
        return v;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
