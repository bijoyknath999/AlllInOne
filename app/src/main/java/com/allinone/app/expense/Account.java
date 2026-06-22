package com.allinone.app.expense;

/** A money container: Cash, Bank, Card, Savings, Wallet, etc. */
public class Account {

    public static final String[] TYPES = {"Cash", "Bank", "Card", "Savings", "Wallet", "Other"};

    public long id;
    public String name;
    public String type;
    public double openingBalance;
    public boolean archived;

    // Computed at query time (opening + income - expense ± transfers). Not stored.
    public double balance;

    public Account() {}

    public Account(long id, String name, String type, double openingBalance, boolean archived) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.openingBalance = openingBalance;
        this.archived = archived;
    }
}
