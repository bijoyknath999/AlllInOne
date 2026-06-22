package com.allinone.app.expense;

/** A single transaction: an expense, an income, or a transfer between accounts. */
public class Expense {

    public static final String TYPE_EXPENSE  = "EXPENSE";
    public static final String TYPE_INCOME   = "INCOME";
    public static final String TYPE_TRANSFER = "TRANSFER";

    // Kept for backward compatibility (legacy default category list).
    public static final String[] CATEGORIES = Category.DEFAULT_EXPENSE_NAMES;

    public long id;
    public String type = TYPE_EXPENSE;
    public String category;
    public double amount;
    public String note;
    public long dateMillis;
    public long accountId;     // source account (from) — 0 = none
    public long toAccountId;   // destination account for transfers — 0 = none

    public Expense() {}

    /** Legacy 5-arg constructor (defaults to an expense with no account). */
    public Expense(long id, String category, double amount, String note, long dateMillis) {
        this.id = id;
        this.category = category;
        this.amount = amount;
        this.note = note;
        this.dateMillis = dateMillis;
        this.type = TYPE_EXPENSE;
    }

    public Expense(long id, String type, String category, double amount, String note,
                   long dateMillis, long accountId, long toAccountId) {
        this.id = id;
        this.type = type;
        this.category = category;
        this.amount = amount;
        this.note = note;
        this.dateMillis = dateMillis;
        this.accountId = accountId;
        this.toAccountId = toAccountId;
    }

    public boolean isIncome()   { return TYPE_INCOME.equals(type); }
    public boolean isTransfer() { return TYPE_TRANSFER.equals(type); }
    public boolean isExpense()  { return TYPE_EXPENSE.equals(type) || type == null; }

    /** Signed amount for net calculations: income +, expense -, transfer 0 (net-neutral). */
    public double signedAmount() {
        if (isIncome()) return amount;
        if (isTransfer()) return 0;
        return -amount;
    }
}
