package com.allinone.app.expense;

/** A spending or income category with its own colour and optional monthly budget. */
public class Category {

    public static final String TYPE_EXPENSE = "EXPENSE";
    public static final String TYPE_INCOME  = "INCOME";

    // Default seed categories (name + colour) created on first run / migration.
    public static final String[] DEFAULT_EXPENSE_NAMES = {
        "Food", "Transport", "Shopping", "Bills", "Health", "Entertainment", "Other"
    };
    public static final int[] DEFAULT_EXPENSE_COLORS = {
        0xFF388E3C, 0xFF1976D2, 0xFFF57C00, 0xFFE53935, 0xFF7B1FA2, 0xFF0097A7, 0xFF607D8B
    };
    public static final String[] DEFAULT_INCOME_NAMES = {
        "Salary", "Business", "Gift", "Interest", "Other"
    };
    public static final int[] DEFAULT_INCOME_COLORS = {
        0xFF2E7D32, 0xFF00897B, 0xFFC2185B, 0xFF5E35B1, 0xFF607D8B
    };

    public long id;
    public String name;
    public int color;
    public String type;     // EXPENSE or INCOME
    public double budget;    // monthly budget, 0 = none (expense categories only)

    public Category() {}

    public Category(long id, String name, int color, String type, double budget) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.type = type;
        this.budget = budget;
    }
}
