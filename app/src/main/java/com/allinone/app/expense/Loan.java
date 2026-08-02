package com.allinone.app.expense;

/** A loan record: money you LENT (they owe you) or BORROWED (you owe them). */
public class Loan {

    public static final String LENT     = "LENT";      // you gave money out → asset
    public static final String BORROWED = "BORROWED";  // you took money in  → liability

    public static final String STATUS_OPEN    = "OPEN";
    public static final String STATUS_SETTLED = "SETTLED";

    public long id;
    public long personId;
    public String direction;   // LENT or BORROWED
    public double principal;
    public long dateMillis;
    public long dueMillis;     // 0 = no due date
    public String note;
    public String status;      // OPEN or SETTLED

    // Computed at query time.
    public String personName;
    public double paid;        // sum of repayments
    public double outstanding; // principal - paid

    public Loan() {}

    public Loan(long id, long personId, String direction, double principal,
                long dateMillis, long dueMillis, String note, String status) {
        this.id = id;
        this.personId = personId;
        this.direction = direction;
        this.principal = principal;
        this.dateMillis = dateMillis;
        this.dueMillis = dueMillis;
        this.note = note;
        this.status = status;
    }
}
