package com.allinone.app.expense;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.allinone.app.R;
import com.allinone.app.databinding.ActivitySettingsBinding;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding b;
    private ExpenseDb db;
    private MoneyPrefs prefs;

    private final ActivityResultLauncher<String> exportLauncher =
        registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
            if (uri != null) exportToCsv(uri);
        });

    private final ActivityResultLauncher<String> importLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) importFromCsv(uri);
        });

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        EdgeToEdge.enable(this);
        b = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        UiKit.applyInsets(b.getRoot());
        db = new ExpenseDb(this);
        prefs = new MoneyPrefs(this);
        b.btnBack.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        b.llList.removeAllViews();

        b.llList.addView(UiKit.heading(this, "General"));
        addRow("Currency", "Symbol used across the app: " + prefs.getCurrency(), v -> chooseCurrency());

        b.llList.addView(UiKit.heading(this, "Manage"));
        addRow("Categories & Budgets", "Custom categories and monthly limits", v -> start(CategoriesActivity.class));
        addRow("Recurring", "Scheduled income & expenses", v -> start(RecurringActivity.class));
        addRow("Loans", "Borrow / lend tracking", v -> start(LoansActivity.class));

        b.llList.addView(UiKit.heading(this, "Backup & Data"));
        addRow("Export to CSV (backup)", "Save all transactions to a .csv file", v -> {
            String name = "money_backup_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new java.util.Date()) + ".csv";
            exportLauncher.launch(name);
        });
        addRow("Import from CSV (restore)", "Load transactions from a .csv file", v -> importLauncher.launch("text/*"));
        addRow("Google Sheets sync", sheetsSubtitle(), v -> showSheets());

        b.llList.addView(UiKit.heading(this, "Danger zone"));
        View clear = UiKit.row(this, getColor(R.color.color_primary), "Clear all transactions",
            "Deletes every transaction (categories kept)", null, 0, v -> confirmClear());
        b.llList.addView(clear);

        b.llList.addView(UiKit.heading(this, "About"));
        addRow("Version", appVersion(), null);
    }

    private void addRow(String title, String sub, View.OnClickListener click) {
        b.llList.addView(UiKit.row(this, 0, title, sub, click != null ? "›" : null,
            getColor(R.color.text_secondary), click));
    }

    private void start(Class<?> cls) { startActivity(new Intent(this, cls)); }

    private void chooseCurrency() {
        new AlertDialog.Builder(this)
            .setTitle("Currency symbol")
            .setItems(MoneyPrefs.CURRENCIES, (d, w) -> { prefs.setCurrency(MoneyPrefs.CURRENCIES[w]); render(); })
            .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
            .setTitle("Clear all transactions?")
            .setMessage("This permanently deletes every transaction. Categories and loans are kept. Consider exporting a backup first.")
            .setPositiveButton("Delete all", (d, w) -> { db.deleteAllTransactions(); toast("All transactions cleared"); })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Google Sheets sync ────────────────────────────────────────────────────────
    private String sheetsSubtitle() {
        if (prefs.getSheetsUrl().isEmpty()) {
            return "Back up everything to your own Google Sheet";
        }
        return (prefs.isAutoSync() ? "Auto-sync on" : "Auto-sync off") + "  •  " + prefs.lastSyncLabel();
    }

    /** The Apps Script the user has to deploy once, kept in res/raw so it stays readable. */
    private String appsScript() {
        try (InputStream is = getResources().openRawResource(R.raw.sheets_sync_script)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void showSheets() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 22);
        box.setPadding(p, p, p, p);

        TextView info = new TextView(this);
        info.setText("Transactions, categories, loans, repayments and recurring items are all backed up to your sheet."
            + (prefs.getSheetsUrl().isEmpty() ? "" : "\n\nStatus: " + prefs.lastSyncLabel() + "."));
        info.setTextColor(getColor(R.color.text_secondary));
        info.setTextSize(13f);
        box.addView(info);

        // A device with nothing in it is almost certainly a fresh install — point at Restore.
        final boolean empty = !GoogleSheetsSync.hasLocalData(this);
        if (empty) {
            TextView hint = new TextView(this);
            hint.setText("This device has no money data yet. If you already synced from another install, paste the URL and tap Restore.");
            hint.setTextColor(getColor(R.color.color_primary));
            hint.setTextSize(12.5f);
            hint.setPadding(0, UiKit.dp(this, 10), 0, 0);
            box.addView(hint);
        }

        TextView guide = new TextView(this);
        guide.setText("▸ One-time setup guide");
        guide.setTextColor(getColor(R.color.color_primary));
        guide.setTextSize(13f);
        guide.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 12));
        guide.setClickable(true);
        guide.setOnClickListener(v -> showSheetsGuide());
        box.addView(guide);

        final EditText etUrl = new EditText(this);
        etUrl.setHint("https://script.google.com/macros/s/…/exec");
        etUrl.setText(prefs.getSheetsUrl());
        etUrl.setTextColor(getColor(R.color.text_primary));
        etUrl.setHintTextColor(getColor(R.color.text_hint));
        etUrl.setBackgroundResource(R.drawable.bg_input_field);
        etUrl.setTextSize(12f);
        int ip = UiKit.dp(this, 12);
        etUrl.setPadding(ip, ip, ip, ip);
        box.addView(etUrl);

        final CheckBox cbAuto = new CheckBox(this);
        cbAuto.setText("Auto-upload whenever data changes");
        cbAuto.setTextColor(getColor(R.color.text_primary));
        cbAuto.setTextSize(13f);
        cbAuto.setChecked(prefs.isAutoSync());
        cbAuto.setPadding(0, UiKit.dp(this, 10), 0, 0);
        box.addView(cbAuto);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
            .setTitle("Google Sheets sync")
            .setView(sv)
            .setPositiveButton("Sync now", (d, w) -> {
                String url = saveSheetsSettings(etUrl, cbAuto);
                if (url == null) return;
                if (empty) confirmEmptyPush(url); else pushNow(url);
            })
            .setNeutralButton("Restore", (d, w) -> {
                String url = saveSheetsSettings(etUrl, cbAuto);
                if (url != null) confirmRestore(url);
            })
            .setNegativeButton("Close", null)
            .show();
    }

    /** Persists the URL + toggle; returns the URL, or null when it is not usable. */
    private String saveSheetsSettings(EditText etUrl, CheckBox cbAuto) {
        String url = etUrl.getText().toString().trim();
        prefs.setSheetsUrl(url);
        prefs.setAutoSync(cbAuto.isChecked());
        render();
        if (!url.startsWith("http")) {
            toast("Enter a valid Web App URL");
            return null;
        }
        return url;
    }

    private void pushNow(String url) {
        showProgress("Uploading to Google Sheets…");
        GoogleSheetsSync.syncAll(this, url, this::syncResult);
    }

    // ── Progress ──────────────────────────────────────────────────────────────────
    private AlertDialog progress;

    /**
     * A blocking spinner for the manual sync and restore. Both rewrite the whole
     * sheet and can take a while, and it must not be dismissable — tapping away mid
     * sync would leave the user guessing whether it finished.
     */
    private void showProgress(String message) {
        dismissProgress();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int p = UiKit.dp(this, 26);
        box.setPadding(p, p, p, p);

        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(
            android.content.res.ColorStateList.valueOf(getColor(R.color.color_primary)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            UiKit.dp(this, 30), UiKit.dp(this, 30));
        blp.setMarginEnd(UiKit.dp(this, 20));
        bar.setLayoutParams(blp);
        box.addView(bar);

        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(14.5f);
        box.addView(tv);

        progress = new AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(false)
            .create();
        progress.show();
    }

    private void dismissProgress() {
        // The callback can land after the screen is gone; dismissing then throws.
        if (progress != null && progress.isShowing() && !isFinishing() && !isDestroyed()) {
            try { progress.dismiss(); } catch (Exception ignored) {}
        }
        progress = null;
    }

    @Override
    protected void onDestroy() {
        dismissProgress();
        super.onDestroy();
    }

    /**
     * Success is a toast; a failure gets a dialog, because the script's message names
     * the tab that broke and is worth reading (and copying) in full.
     */
    private void syncResult(boolean ok, String msg) {
        dismissProgress();
        // A sync outlives this screen easily now. Showing a dialog on a dead activity
        // throws, so fall back to a toast the user still sees.
        if (isFinishing() || isDestroyed()) {
            Toast.makeText(getApplicationContext(), ok ? "Sheets sync complete" : msg,
                Toast.LENGTH_LONG).show();
            return;
        }
        render();
        if (!ok) { showReport("Sync failed", msg); return; }
        // A one-liner is just a confirmation; anything longer carries detail worth
        // reading (styling the sheet rejected), so it gets the dialog.
        if (msg.contains("\n")) showReport("Upload complete", msg); else toast(msg);
    }

    /** Selectable, copyable, monospaced — meant to be pasted somewhere for diagnosis. */
    private void showReport(String title, String body) {
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(12f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int p = UiKit.dp(this, 20);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("Copy", (d, w) -> {
                copy(title, body);
                toast("Copied");
            })
            .setNegativeButton("Close", null)
            .show();
    }

    /** Uploading from a blank device would clear a sheet that still holds the history. */
    private void confirmEmptyPush(String url) {
        new AlertDialog.Builder(this)
            .setTitle("Nothing to upload")
            .setMessage("This device has no money data, so syncing now would empty your sheet. "
                + "Did you mean Restore instead?")
            .setPositiveButton("Restore instead", (d, w) -> confirmRestore(url))
            .setNeutralButton("Upload anyway", (d, w) -> pushNow(url))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmRestore(String url) {
        new AlertDialog.Builder(this)
            .setTitle("Restore from Sheet?")
            .setMessage("Everything currently on this device — transactions, categories, people, loans, "
                + "repayments and recurring items — is replaced by what is in the sheet. This cannot be undone.")
            .setPositiveButton("Replace & restore", (d, w) -> {
                showProgress("Downloading from Google Sheets…");
                GoogleSheetsSync.restore(this, url, this::syncResult);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSheetsGuide() {
        String script = appsScript();
        String steps =
            "1.  Open (or create) a Google Sheet.\n\n" +
            "2.  Menu: Extensions ▸ Apps Script.\n\n" +
            "3.  Delete any starter code, then paste the script below (tap Copy script), and Save.\n\n" +
            "4.  Click Deploy ▸ New deployment ▸ select type Web app.\n\n" +
            "5.  Set \"Execute as: Me\" and \"Who has access: Anyone\", then Deploy and authorize.\n\n" +
            "6.  Copy the Web app URL it gives you and paste it back here.\n\n" +
            "7.  Tap Sync now — tabs for Transactions, Categories, People, Loans, LoanPayments, "
            + "Recurring and Settings are filled in.\n\n" +
            "8.  On a new phone: paste the same URL and tap Restore to pull it all back.\n\n" +
            "Already using the old script? Replace it with this one and Deploy ▸ Manage deployments "
            + "▸ edit ▸ New version, so the URL stays the same.\n\n" +
            "──────── Script ────────\n\n" + script;

        TextView tv = new TextView(this);
        tv.setText(steps);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTextSize(12f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        int p = UiKit.dp(this, 20);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
            .setTitle("Set up Google Sheets")
            .setView(sv)
            .setPositiveButton("Copy script", (d, w) -> {
                copy("Apps Script", script);
                toast("Script copied — paste into Apps Script");
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    // ── Export ──────────────────────────────────────────────────────────────────
    private void exportToCsv(Uri uri) {
        List<Expense> all = db.queryAll();
        try (OutputStream os = getContentResolver().openOutputStream(uri);
             OutputStreamWriter w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            w.write("type,category,amount,note,date_ms\n");
            for (Expense e : all) {
                w.write(csv(e.type) + "," + csv(e.category) + "," + e.amount + "," +
                        csv(e.note) + "," + e.dateMillis + "\n");
            }
            toast("Exported " + all.size() + " transactions");
        } catch (IOException ex) {
            toast("Export failed: " + ex.getMessage());
        }
    }

    // ── Import (backward compatible with the old 4- and 6-column formats) ─────────
    private void importFromCsv(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String first = r.readLine();
            if (first == null) { toast("File is empty"); return; }

            String lower = first.toLowerCase(Locale.ROOT).trim();
            boolean newFormat = lower.startsWith("type");
            boolean hasHeader = newFormat || lower.startsWith("category") || lower.startsWith("\"category");

            int count = 0;
            String line = hasHeader ? r.readLine() : first;
            while (line != null) {
                if (!line.trim().isEmpty()) {
                    Expense e = parseRow(line, newFormat);
                    if (e != null) { db.insert(e); count++; }
                }
                line = r.readLine();
            }
            toast("Imported " + count + " transactions");
        } catch (IOException ex) {
            toast("Import failed: " + ex.getMessage());
        }
    }

    private Expense parseRow(String line, boolean newFormat) {
        String[] c = parseCsvLine(line);
        try {
            Expense e = new Expense();
            if (newFormat && c.length >= 5) {
                // type,category,amount,note,date_ms — a trailing account column from an
                // older backup is simply ignored.
                e.type = emptyToDefault(c[0], Expense.TYPE_EXPENSE);
                e.category = c[1];
                e.amount = Double.parseDouble(c[2]);
                e.note = c[3];
                e.dateMillis = Long.parseLong(c[4]);
            } else if (c.length >= 4) {
                // Legacy: category,amount,note,date_ms
                e.type = Expense.TYPE_EXPENSE;
                e.category = c[0];
                e.amount = Double.parseDouble(c[1]);
                e.note = c[2];
                e.dateMillis = Long.parseLong(c[3]);
            } else {
                return null;
            }
            // Anything that is not income (including legacy TRANSFER rows) is an expense.
            if (!Expense.TYPE_INCOME.equals(e.type)) e.type = Expense.TYPE_EXPENSE;
            return e;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String emptyToDefault(String s, String def) {
        return s == null || s.trim().isEmpty() ? def : s.trim().toUpperCase(Locale.ROOT);
    }

    private String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                else if (ch == '"') inQuotes = false;
                else sb.append(ch);
            } else {
                if (ch == '"') inQuotes = true;
                else if (ch == ',') { fields.add(sb.toString()); sb.setLength(0); }
                else sb.append(ch);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "—";
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
