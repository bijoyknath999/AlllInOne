package com.allinone.app.expense;

/** A single transaction: money spent (EXPENSE) or money received (INCOME). */
public class Expense {

    public static final String TYPE_EXPENSE = "EXPENSE";
    public static final String TYPE_INCOME  = "INCOME";

    // Kept for backward compatibility (legacy default category list).
    public static final String[] CATEGORIES = Category.DEFAULT_EXPENSE_NAMES;

    public long id;
    public String type = TYPE_EXPENSE;
    public String category;
    public double amount;
    public String note;
    public long dateMillis;

    public Expense() {}

    public Expense(long id, String type, String category, double amount, String note, long dateMillis) {
        this.id = id;
        this.type = type;
        this.category = category;
        this.amount = amount;
        this.note = note;
        this.dateMillis = dateMillis;
    }

    public boolean isIncome()  { return TYPE_INCOME.equals(type); }
    public boolean isExpense() { return !isIncome(); }

    /** Signed amount for net calculations: income +, expense -. */
    public double signedAmount() {
        return isIncome() ? amount : -amount;
    }
}
