package com.allinone.app.study;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A single chapter / task inside a study plan. */
public class Chapter {
    public long id;
    public long planId;
    public String name;
    public int orderIdx;
    public boolean done;
    public long doneMs;

    public Chapter() {}

    public Chapter(long planId, String name, int orderIdx) {
        this.planId = planId;
        this.name = name;
        this.orderIdx = orderIdx;
    }

    public String doneDateLabel() {
        if (!done || doneMs <= 0) return null;
        return "Done " + new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(doneMs));
    }
}
