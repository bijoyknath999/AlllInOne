package com.allinone.app.expense;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.allinone.app.databinding.ActivityAccountsBinding;

import java.util.List;
import java.util.Locale;

public class AccountsActivity extends AppCompatActivity {

    private ActivityAccountsBinding b;
    private ExpenseDb db;
    private MoneyPrefs prefs;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        EdgeToEdge.enable(this);
        b = ActivityAccountsBinding.inflate(getLayoutInflater());
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
        render();
    }

    private void render() {
        b.llList.removeAllViews();
        List<Account> accounts = db.queryAccounts(true);
        b.tvTotal.setText("Total balance: " + prefs.format(db.totalAccountsBalance()));
        if (accounts.isEmpty()) {
            b.llList.addView(UiKit.emptyHint(this, "No accounts yet.\nTap + to add one."));
            return;
        }
        for (Account a : accounts) {
            String sub = a.type + (a.archived ? "  •  archived" : "");
            int color = a.balance >= 0 ? getColor(com.allinone.app.R.color.income_green)
                                       : getColor(com.allinone.app.R.color.color_primary);
            View rowView = UiKit.row(this, 0, a.name, sub, prefs.format(a.balance), color,
                v -> showEdit(a));
            rowView.setOnLongClickListener(v -> { confirmDelete(a); return true; });
            b.llList.addView(rowView);
        }
        b.llList.addView(UiKit.emptyHint(this, "Tap an account to edit • long-press to delete"));
    }

    private void confirmDelete(Account a) {
        new AlertDialog.Builder(this)
            .setTitle("Delete \"" + a.name + "\"?")
            .setMessage("Transactions on this account will remain but show no account. This can't be undone.")
            .setPositiveButton("Delete", (d, w) -> { db.deleteAccount(a.id); render(); })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showEdit(final Account existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 22);
        box.setPadding(p, p, p, p);

        final EditText etName = input("Account name", InputType.TYPE_CLASS_TEXT);
        if (existing != null) etName.setText(existing.name);
        box.addView(etName);

        final String[] type = {existing != null ? existing.type : Account.TYPES[0]};
        final TextView tvType = picker(type[0]);
        tvType.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Account type")
            .setItems(Account.TYPES, (d, w) -> { type[0] = Account.TYPES[w]; tvType.setText(type[0]); })
            .show());
        box.addView(spacer());
        box.addView(tvType);

        final EditText etOpening = input("Opening balance (0.00)",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        if (existing != null) etOpening.setText(String.format(Locale.getDefault(), "%.2f", existing.openingBalance));
        box.addView(spacer());
        box.addView(etOpening);

        AlertDialog.Builder dlg = new AlertDialog.Builder(this)
            .setTitle(existing == null ? "New Account" : "Edit Account")
            .setView(box)
            .setPositiveButton("Save", (d, w) -> {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) { toast("Enter a name"); return; }
                double opening = 0;
                try { String o = etOpening.getText().toString().trim();
                      if (!o.isEmpty()) opening = Double.parseDouble(o); }
                catch (NumberFormatException e) { toast("Invalid balance"); return; }
                Account a = existing != null ? existing : new Account();
                a.name = name; a.type = type[0]; a.openingBalance = opening;
                if (existing != null) db.updateAccount(a); else db.insertAccount(a);
                render();
            })
            .setNegativeButton("Cancel", null);
        if (existing != null) {
            dlg.setNeutralButton(existing.archived ? "Unarchive" : "Archive", (d, w) -> {
                existing.archived = !existing.archived;
                db.updateAccount(existing);
                render();
            });
        }
        dlg.show();
    }

    private EditText input(String hint, int type) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(type);
        et.setTextColor(getColor(com.allinone.app.R.color.text_primary));
        et.setHintTextColor(getColor(com.allinone.app.R.color.text_hint));
        et.setBackgroundResource(com.allinone.app.R.drawable.bg_input_field);
        int p = UiKit.dp(this, 12);
        et.setPadding(p, p, p, p);
        return et;
    }

    private TextView picker(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextColor(getColor(com.allinone.app.R.color.color_primary));
        tv.setBackgroundResource(com.allinone.app.R.drawable.bg_input_field);
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
