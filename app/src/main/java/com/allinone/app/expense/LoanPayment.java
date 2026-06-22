package com.allinone.app.expense;

/** A single repayment against a Loan. */
public class LoanPayment {
    public long id;
    public long loanId;
    public double amount;
    public long dateMillis;
    public long accountId; // optional, 0 = none

    public LoanPayment() {}

    public LoanPayment(long id, long loanId, double amount, long dateMillis, long accountId) {
        this.id = id;
        this.loanId = loanId;
        this.amount = amount;
        this.dateMillis = dateMillis;
        this.accountId = accountId;
    }
}
