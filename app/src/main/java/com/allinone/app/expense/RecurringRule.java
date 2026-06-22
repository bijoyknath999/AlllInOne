package com.allinone.app.expense;

import java.util.Calendar;

/** A scheduled transaction that auto-posts on an interval (salary, rent, subscriptions). */
public class RecurringRule {

    public static final String DAILY   = "DAILY";
    public static final String WEEKLY  = "WEEKLY";
    public static final String MONTHLY = "MONTHLY";
    public static final String YEARLY  = "YEARLY";

    public long id;
    public String type;        // Expense.TYPE_EXPENSE / TYPE_INCOME
    public String category;
    public double amount;
    public String note;
    public long accountId;
    public long toAccountId;   // for transfers (unused for income/expense)
    public String intervalType; // DAILY/WEEKLY/MONTHLY/YEARLY
    public int intervalN;       // every N units (e.g. every 2 weeks)
    public long nextMillis;     // next time this should post
    public boolean enabled;

    public RecurringRule() {}

    public RecurringRule(long id, String type, String category, double amount, String note,
                         long accountId, long toAccountId, String intervalType, int intervalN,
                         long nextMillis, boolean enabled) {
        this.id = id;
        this.type = type;
        this.category = category;
        this.amount = amount;
        this.note = note;
        this.accountId = accountId;
        this.toAccountId = toAccountId;
        this.intervalType = intervalType;
        this.intervalN = intervalN;
        this.nextMillis = nextMillis;
        this.enabled = enabled;
    }

    /** Advances a timestamp by one interval period. */
    public long advance(long fromMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(fromMillis);
        int n = Math.max(1, intervalN);
        switch (intervalType) {
            case DAILY:   c.add(Calendar.DAY_OF_MONTH, n); break;
            case WEEKLY:  c.add(Calendar.WEEK_OF_YEAR, n); break;
            case YEARLY:  c.add(Calendar.YEAR, n); break;
            case MONTHLY:
            default:      c.add(Calendar.MONTH, n); break;
        }
        return c.getTimeInMillis();
    }

    public String intervalLabel() {
        int n = Math.max(1, intervalN);
        String unit;
        switch (intervalType) {
            case DAILY:  unit = "day"; break;
            case WEEKLY: unit = "week"; break;
            case YEARLY: unit = "year"; break;
            default:     unit = "month"; break;
        }
        return n == 1 ? ("Every " + unit) : ("Every " + n + " " + unit + "s");
    }
}
