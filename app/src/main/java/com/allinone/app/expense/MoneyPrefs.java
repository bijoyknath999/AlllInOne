package com.allinone.app.expense;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Centralised settings for the money manager (currency symbol, global budget). */
public class MoneyPrefs {

    private static final String PREFS_NAME = "expense_prefs";
    private static final String KEY_BUDGET   = "budget";
    private static final String KEY_CURRENCY = "currency";
    private static final String KEY_SHEETS_URL = "sheets_url";
    private static final String KEY_AUTO_SYNC  = "sheets_auto_sync";
    private static final String KEY_LAST_SYNC  = "sheets_last_sync";

    // Common currency symbols offered in Settings.
    public static final String[] CURRENCIES = {"$", "€", "£", "₹", "৳", "¥", "₩", "₽", "R$", "A$", "C$"};

    private final SharedPreferences prefs;

    public MoneyPrefs(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public double getBudget() {
        return Double.longBitsToDouble(prefs.getLong(KEY_BUDGET, Double.doubleToLongBits(0)));
    }

    public void setBudget(double v) {
        prefs.edit().putLong(KEY_BUDGET, Double.doubleToLongBits(v)).apply();
    }

    public void clearBudget() {
        prefs.edit().remove(KEY_BUDGET).apply();
    }

    public String getCurrency() {
        return prefs.getString(KEY_CURRENCY, "$");
    }

    public void setCurrency(String c) {
        prefs.edit().putString(KEY_CURRENCY, c).apply();
    }

    public String getSheetsUrl() {
        return prefs.getString(KEY_SHEETS_URL, "");
    }

    public void setSheetsUrl(String url) {
        prefs.edit().putString(KEY_SHEETS_URL, url == null ? "" : url.trim()).apply();
    }

    /** When on, every add/edit/delete uploads to the sheet automatically. */
    public boolean isAutoSync() {
        return prefs.getBoolean(KEY_AUTO_SYNC, true);
    }

    public void setAutoSync(boolean on) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, on).apply();
    }

    /** Epoch millis of the last successful sync, or 0 if never synced. */
    public long getLastSync() {
        return prefs.getLong(KEY_LAST_SYNC, 0);
    }

    public void setLastSync(long millis) {
        prefs.edit().putLong(KEY_LAST_SYNC, millis).apply();
    }

    /** "never" / "just now" / "12 min ago" / "3 days ago". */
    public String lastSyncLabel() {
        long t = getLastSync();
        if (t <= 0) return "never synced";
        long mins = (System.currentTimeMillis() - t) / 60000L;
        if (mins < 1) return "synced just now";
        if (mins < 60) return "synced " + mins + " min ago";
        long hours = mins / 60;
        if (hours < 24) return "synced " + hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        return "synced " + days + (days == 1 ? " day ago" : " days ago");
    }

    /** Formats an amount with the current currency symbol, e.g. "$1,234.50" / "-$99.00". */
    public String format(double amount) {
        String body = getCurrency() + String.format(Locale.getDefault(), "%,.2f", Math.abs(amount));
        return amount < 0 ? "-" + body : body;
    }

    /** Formats a signed amount with leading + / - and the currency symbol. */
    public String formatSigned(double amount) {
        String sign = amount < 0 ? "-" : "+";
        return sign + getCurrency() + String.format(Locale.getDefault(), "%,.2f", Math.abs(amount));
    }
}
