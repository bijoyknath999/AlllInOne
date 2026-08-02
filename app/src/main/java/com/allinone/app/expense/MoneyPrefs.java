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
