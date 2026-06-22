package com.allinone.app.expense;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.allinone.app.R;
import com.allinone.app.databinding.ActivityLoansBinding;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class LoansActivity extends AppCompatActivity {

    private ActivityLoansBinding b;
    private ExpenseDb db;
    private MoneyPrefs prefs;
    private final SimpleDateFormat dfmt = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        EdgeToEdge.enable(this);
        b = ActivityLoansBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        UiKit.applyInsets(b.getRoot());
        db = new ExpenseDb(this);
        prefs = new MoneyPrefs(this);
        b.btnBack.setOnClickListener(v -> finish());
        b.btnAdd.setOnClickListener(v -> showAddLoan());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        double receive = db.totalReceivable();
        double owe = db.totalPayable();
        b.tvReceive.setText(prefs.format(receive));
        b.tvOwe.setText(prefs.format(owe));
        double net = receive - owe;
        b.tvNet.setText(prefs.formatSigned(net));
        b.tvNet.setTextColor(net >= 0 ? getColor(R.color.income_green) : getColor(R.color.color_primary));

        b.llList.removeAllViews();
        List<Loan> open = db.queryLoans(0, Loan.STATUS_OPEN);
        List<Loan> settled = db.queryLoans(0, Loan.STATUS_SETTLED);

        if (open.isEmpty() && settled.isEmpty()) {
            b.llList.addView(UiKit.emptyHint(this, "No loans yet.\nTap + to record money you lent or borrowed."));
            return;
        }
        if (!open.isEmpty()) {
            b.llList.addView(UiKit.heading(this, "Open"));
            for (Loan l : open) addLoanRow(l);
        }
        if (!settled.isEmpty()) {
            b.llList.addView(UiKit.heading(this, "Settled"));
            for (Loan l : settled) addLoanRow(l);
        }
    }

    private void addLoanRow(Loan l) {
        boolean lent = Loan.LENT.equals(l.direction);
        int dot = lent ? getColor(R.color.income_green) : getColor(R.color.color_primary);
        StringBuilder sub = new StringBuilder(lent ? "Lent" : "Borrowed");
        sub.append("  •  ").append(dfmt.format(new java.util.Date(l.dateMillis)));
        boolean overdue = l.dueMillis > 0 && Loan.STATUS_OPEN.equals(l.status)
            && l.dueMillis < System.currentTimeMillis();
        if (l.dueMillis > 0) sub.append(overdue ? "  •  OVERDUE" : "  •  due " + dfmt.format(new java.util.Date(l.dueMillis)));

        String right;
        int rightColor;
        if (Loan.STATUS_SETTLED.equals(l.status)) {
            right = "Settled";
            rightColor = getColor(R.color.text_secondary);
        } else {
            right = prefs.format(l.outstanding);
            rightColor = overdue ? getColor(R.color.color_primary) : getColor(R.color.text_primary);
        }
        View row = UiKit.row(this, dot, l.personName, sub.toString(), right, rightColor, v -> showDetail(l.id));
        row.setOnLongClickListener(v -> { confirmDeleteLoan(l); return true; });
        b.llList.addView(row);
    }

    // ── Add loan ──────────────────────────────────────────────────────────────
    private void showAddLoan() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 22);
        box.setPadding(p, p, p, p);

        final EditText etPerson = input("Person name", InputType.TYPE_CLASS_TEXT);
        box.addView(etPerson);

        final List<Person> people = db.queryPeople();
        if (!people.isEmpty()) {
            TextView pick = link("Pick existing person");
            pick.setOnClickListener(v -> {
                String[] names = new String[people.size()];
                for (int i = 0; i < people.size(); i++) names[i] = people.get(i).name;
                new AlertDialog.Builder(this).setTitle("People")
                    .setItems(names, (d, w) -> etPerson.setText(names[w])).show();
            });
            box.addView(pick);
        }

        final String[] dir = {Loan.LENT};
        box.addView(spacer());
        final TextView tvLent = chip("I lent (they owe me)", true);
        final TextView tvBorrowed = chip("I borrowed (I owe)", false);
        LinearLayout dirRow = new LinearLayout(this);
        dirRow.setOrientation(LinearLayout.HORIZONTAL);
        dirRow.addView(tvLent);
        dirRow.addView(tvBorrowed);
        box.addView(dirRow);
        tvLent.setOnClickListener(v -> { dir[0] = Loan.LENT; styleChip(tvLent, true); styleChip(tvBorrowed, false); });
        tvBorrowed.setOnClickListener(v -> { dir[0] = Loan.BORROWED; styleChip(tvLent, false); styleChip(tvBorrowed, true); });

        final EditText etAmount = input("Amount", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(spacer());
        box.addView(etAmount);

        final Calendar date = Calendar.getInstance();
        final TextView tvDate = picker("Date: " + dfmt.format(date.getTime()));
        tvDate.setOnClickListener(v -> new DatePickerDialog(this, (pk, y, m, d) -> {
            date.set(y, m, d); tvDate.setText("Date: " + dfmt.format(date.getTime()));
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show());
        box.addView(spacer());
        box.addView(tvDate);

        final long[] due = {0};
        final TextView tvDue = picker("Due date: none");
        tvDue.setOnClickListener(v -> {
            Calendar d = Calendar.getInstance();
            new DatePickerDialog(this, (pk, y, m, dd) -> {
                Calendar c = Calendar.getInstance();
                c.set(y, m, dd, 23, 59, 0);
                due[0] = c.getTimeInMillis();
                tvDue.setText("Due date: " + dfmt.format(c.getTime()));
            }, d.get(Calendar.YEAR), d.get(Calendar.MONTH), d.get(Calendar.DAY_OF_MONTH)).show();
        });
        tvDue.setOnLongClickListener(v -> { due[0] = 0; tvDue.setText("Due date: none"); return true; });
        box.addView(spacer());
        box.addView(tvDue);

        final EditText etNote = input("Note (optional)", InputType.TYPE_CLASS_TEXT);
        box.addView(spacer());
        box.addView(etNote);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
            .setTitle("New Loan")
            .setView(sv)
            .setPositiveButton("Save", (d, w) -> {
                String name = etPerson.getText().toString().trim();
                if (name.isEmpty()) { toast("Enter a person"); return; }
                double amount;
                try { amount = Double.parseDouble(etAmount.getText().toString().trim()); }
                catch (Exception e) { toast("Invalid amount"); return; }
                if (amount <= 0) { toast("Amount must be positive"); return; }

                long personId = findOrCreatePerson(name);
                Loan l = new Loan();
                l.personId = personId;
                l.direction = dir[0];
                l.principal = amount;
                l.dateMillis = date.getTimeInMillis();
                l.dueMillis = due[0];
                l.note = etNote.getText().toString().trim();
                l.status = Loan.STATUS_OPEN;
                db.insertLoan(l);
                render();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private long findOrCreatePerson(String name) {
        for (Person p : db.queryPeople()) {
            if (p.name.equalsIgnoreCase(name)) return p.id;
        }
        Person p = new Person();
        p.name = name;
        return db.insertPerson(p);
    }

    // ── Loan detail ─────────────────────────────────────────────────────────────
    private void showDetail(long loanId) {
        final Loan l = db.getLoan(loanId);
        if (l == null) return;
        boolean lent = Loan.LENT.equals(l.direction);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 22);
        box.setPadding(p, p, p, p);

        box.addView(detailLine(lent ? "You lent to" : "You borrowed from", l.personName));
        box.addView(detailLine("Principal", prefs.format(l.principal)));
        box.addView(detailLine("Paid", prefs.format(l.paid)));
        box.addView(detailLine("Outstanding", prefs.format(l.outstanding)));
        box.addView(detailLine("Date", dfmt.format(new java.util.Date(l.dateMillis))));
        if (l.dueMillis > 0) box.addView(detailLine("Due", dfmt.format(new java.util.Date(l.dueMillis))));
        if (l.note != null && !l.note.isEmpty()) box.addView(detailLine("Note", l.note));

        box.addView(UiKit.heading(this, "Repayments"));
        List<LoanPayment> pays = db.queryPayments(loanId);
        if (pays.isEmpty()) {
            box.addView(emptyText("No repayments yet"));
        } else {
            for (LoanPayment pay : pays) {
                View r = UiKit.row(this, 0, prefs.format(pay.amount),
                    dfmt.format(new java.util.Date(pay.dateMillis)), null, 0, null);
                r.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this).setTitle("Delete repayment?")
                        .setPositiveButton("Delete", (d, w) -> { db.deletePayment(pay.id, loanId); reopen(loanId); })
                        .setNegativeButton("Cancel", null).show();
                    return true;
                });
                box.addView(r);
            }
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        AlertDialog.Builder bld = new AlertDialog.Builder(this)
            .setTitle(lent ? "Loan — receivable" : "Loan — payable")
            .setView(sv)
            .setNegativeButton("Close", null)
            .setNeutralButton("Delete", (d, w) -> confirmDeleteLoan(l));
        if (Loan.STATUS_OPEN.equals(l.status)) {
            bld.setPositiveButton("Add repayment", (d, w) -> showAddRepayment(l));
        }
        bld.show();
    }

    private void reopen(long loanId) { render(); showDetail(loanId); }

    private void showAddRepayment(final Loan l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 22);
        box.setPadding(p, p, p, p);

        final EditText etAmount = input("Amount (outstanding " + prefs.format(l.outstanding) + ")",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etAmount.setText(String.format(Locale.getDefault(), "%.2f", l.outstanding));
        box.addView(etAmount);

        final Calendar date = Calendar.getInstance();
        final TextView tvDate = picker("Date: " + dfmt.format(date.getTime()));
        tvDate.setOnClickListener(v -> new DatePickerDialog(this, (pk, y, m, d) -> {
            date.set(y, m, d); tvDate.setText("Date: " + dfmt.format(date.getTime()));
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show());
        box.addView(spacer());
        box.addView(tvDate);

        new AlertDialog.Builder(this)
            .setTitle("Add repayment")
            .setView(box)
            .setPositiveButton("Save", (d, w) -> {
                double amount;
                try { amount = Double.parseDouble(etAmount.getText().toString().trim()); }
                catch (Exception e) { toast("Invalid amount"); return; }
                if (amount <= 0) { toast("Amount must be positive"); return; }
                LoanPayment pay = new LoanPayment();
                pay.loanId = l.id;
                pay.amount = amount;
                pay.dateMillis = date.getTimeInMillis();
                db.insertPayment(pay);
                reopen(l.id);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmDeleteLoan(Loan l) {
        new AlertDialog.Builder(this)
            .setTitle("Delete loan?")
            .setMessage("This removes the loan and its repayments.")
            .setPositiveButton("Delete", (d, w) -> { db.deleteLoan(l.id); render(); })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── small view helpers ──────────────────────────────────────────────────────
    private View detailLine(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 5));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFFB0B0C8);
        l.setTextSize(13f);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(14f);
        v.setGravity(Gravity.END);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        row.addView(l);
        row.addView(v);
        return row;
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

    private TextView link(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.color_primary));
        tv.setTextSize(12f);
        tv.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 8), 0, 0);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private TextView emptyText(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFF606080);
        tv.setTextSize(13f);
        tv.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
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
