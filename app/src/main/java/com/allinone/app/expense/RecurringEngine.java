package com.allinone.app.expense;

import android.content.Context;

/** Posts any due recurring transactions, catching up on every missed occurrence. */
public class RecurringEngine {

    /** @return number of transactions posted. Safe to call often (e.g. on app open). */
    public static int postDue(Context ctx) {
        ExpenseDb db = new ExpenseDb(ctx);
        long now = System.currentTimeMillis();
        int posted = 0;
        for (RecurringRule r : db.queryDueRecurring(now)) {
            long next = r.nextMillis;
            int guard = 0; // never loop forever on a misconfigured rule
            while (next <= now && guard < 1000) {
                Expense e = new Expense();
                e.type = r.type;
                e.category = r.category;
                e.amount = r.amount;
                e.note = r.note == null ? "" : r.note;
                e.dateMillis = next;
                db.insert(e);
                posted++;
                next = r.advance(next);
                guard++;
            }
            r.nextMillis = next;
            db.updateRecurring(r);
        }
        return posted;
    }
}
