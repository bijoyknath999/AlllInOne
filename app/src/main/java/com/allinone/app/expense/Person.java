package com.allinone.app.expense;

/** A person you lend money to or borrow money from. */
public class Person {
    public long id;
    public String name;
    public String phone;
    public String note;

    // Computed at query time.
    public double netReceivable; // (lent outstanding) - (borrowed outstanding); + means they owe you

    public Person() {}

    public Person(long id, String name, String phone, String note) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.note = note;
    }
}
