package com.allinone.app.expense;

/** A single repayment against a Loan. */
public class LoanPayment {
    public long id;
    public long loanId;
    public double amount;
    public long dateMillis;

    public LoanPayment() {}

    public LoanPayment(long id, long loanId, double amount, long dateMillis) {
        this.id = id;
        this.loanId = loanId;
        this.amount = amount;
        this.dateMillis = dateMillis;
    }
}
