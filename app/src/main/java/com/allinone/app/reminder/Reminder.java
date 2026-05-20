package com.allinone.app.reminder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Reminder {
    public static final int TYPE_ONCE      = 0;
    public static final int TYPE_MINUTES   = 1;
    public static final int TYPE_HOURLY    = 2;
    public static final int TYPE_DAILY     = 3;
    public static final int TYPE_WEEKLY    = 4;
    public static final int TYPE_BIWEEKLY  = 5;

    public long id;
    public String title;
    public String message;
    public int intervalType;
    public int intervalValue; // used only for TYPE_MINUTES
    public long intervalMs;   // 0 for TYPE_ONCE
    public boolean enabled;
    public long nextFireMs;

    public Reminder() {}

    public String intervalLabel() {
        switch (intervalType) {
            case TYPE_ONCE: {
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault());
                return "Once at " + sdf.format(new Date(nextFireMs));
            }
            case TYPE_MINUTES:  return "Every " + intervalValue + " minute" + (intervalValue == 1 ? "" : "s");
            case TYPE_HOURLY:   return "Every hour";
            case TYPE_DAILY:    return "Every day";
            case TYPE_WEEKLY:   return "Every week";
            case TYPE_BIWEEKLY: return "Every 2 weeks";
            default: return "Custom";
        }
    }

    public static long computeIntervalMs(int type, int value) {
        switch (type) {
            case TYPE_ONCE:     return 0L;
            case TYPE_MINUTES:  return value * 60_000L;
            case TYPE_HOURLY:   return 60 * 60_000L;
            case TYPE_DAILY:    return 24 * 60 * 60_000L;
            case TYPE_WEEKLY:   return 7L * 24 * 60 * 60_000L;
            case TYPE_BIWEEKLY: return 14L * 24 * 60 * 60_000L;
            default: return 60_000L;
        }
    }
}