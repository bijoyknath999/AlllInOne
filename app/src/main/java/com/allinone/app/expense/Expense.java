package com.allinone.app.expense;

public class Expense {
    public static final String[] CATEGORIES = {
        "Food", "Transport", "Shopping", "Bills", "Health", "Entertainment", "Other"
    };

    public long id;
    public String category;
    public double amount;
    public String note;
    public long dateMillis;

    public Expense() {}

    public Expense(long id, String category, double amount, String note, long dateMillis) {
        this.id = id;
        this.category = category;
        this.amount = amount;
        this.note = note;
        this.dateMillis = dateMillis;
    }
}