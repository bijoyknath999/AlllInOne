package com.allinone.app.reminder;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class Reminder {
    public static final int TYPE_ONCE      = 0;
    public static final int TYPE_MINUTES   = 1;
    public static final int TYPE_HOURLY    = 2;
    public static final int TYPE_DAILY     = 3;
    public static final int TYPE_WEEKLY    = 4;
    public static final int TYPE_BIWEEKLY  = 5;
    public static final int TYPE_MONTHLY   = 6;

    public long id;
    public String title;
    public String message;
    public int intervalType;
    public int intervalValue;
    public long intervalMs;
    public boolean enabled;
    public long nextFireMs;

    // Time-of-day anchor for daily/weekly/biweekly/monthly
    public int fireHour   = 8;
    public int fireMinute = 0;

    // Notification options
    public boolean sound   = true;
    public boolean vibrate = true;
    public String imageUri = null;

    public Reminder() {}

    public String intervalLabel() {
        String t = fmtTime();
        switch (intervalType) {
            case TYPE_ONCE: {
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault());
                return "Once at " + sdf.format(new Date(nextFireMs));
            }
            case TYPE_MINUTES:  return "Every " + intervalValue + " min" + (intervalValue == 1 ? "" : "s");
            case TYPE_HOURLY:   return "Every hour";
            case TYPE_DAILY:    return "Every day at " + t;
            case TYPE_WEEKLY:   return "Every week at " + t;
            case TYPE_BIWEEKLY: return "Every 2 weeks at " + t;
            case TYPE_MONTHLY:  return "Every month at " + t;
            default: return "Custom";
        }
    }

    private String fmtTime() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, fireHour);
        c.set(Calendar.MINUTE, fireMinute);
        return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(c.getTime());
    }

    /** Computes the next fire time anchored to fireHour:fireMinute for recurring types. */
    public long computeNextFireMs() {
        long now = System.currentTimeMillis();
        switch (intervalType) {
            case TYPE_ONCE:    return nextFireMs;
            case TYPE_MINUTES: return now + intervalMs;
            case TYPE_HOURLY:  return now + intervalMs;
            case TYPE_DAILY: {
                Calendar c = todayAt(fireHour, fireMinute);
                if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
                return c.getTimeInMillis();
            }
            case TYPE_WEEKLY: {
                // Preserve the original day-of-week from nextFireMs (or today for first schedule)
                Calendar ref = Calendar.getInstance();
                if (nextFireMs > 0) ref.setTimeInMillis(nextFireMs);
                Calendar c = Calendar.getInstance();
                c.set(Calendar.DAY_OF_WEEK, ref.get(Calendar.DAY_OF_WEEK));
                c.set(Calendar.HOUR_OF_DAY, fireHour);
                c.set(Calendar.MINUTE, fireMinute);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                while (c.getTimeInMillis() <= now) c.add(Calendar.WEEK_OF_YEAR, 1);
                return c.getTimeInMillis();
            }
            case TYPE_BIWEEKLY: {
                Calendar ref = Calendar.getInstance();
                if (nextFireMs > 0) ref.setTimeInMillis(nextFireMs);
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(ref.getTimeInMillis());
                c.set(Calendar.HOUR_OF_DAY, fireHour);
                c.set(Calendar.MINUTE, fireMinute);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                while (c.getTimeInMillis() <= now) c.add(Calendar.WEEK_OF_YEAR, 2);
                return c.getTimeInMillis();
            }
            case TYPE_MONTHLY: {
                Calendar ref = Calendar.getInstance();
                if (nextFireMs > 0) ref.setTimeInMillis(nextFireMs);
                int dom = ref.get(Calendar.DAY_OF_MONTH);
                Calendar c = Calendar.getInstance();
                c.set(Calendar.DAY_OF_MONTH, 1); // avoid day overflow when setting month
                c.set(Calendar.HOUR_OF_DAY, fireHour);
                c.set(Calendar.MINUTE, fireMinute);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                int maxDom = c.getActualMaximum(Calendar.DAY_OF_MONTH);
                c.set(Calendar.DAY_OF_MONTH, Math.min(dom, maxDom));
                while (c.getTimeInMillis() <= now) {
                    c.add(Calendar.MONTH, 1);
                    maxDom = c.getActualMaximum(Calendar.DAY_OF_MONTH);
                    c.set(Calendar.DAY_OF_MONTH, Math.min(dom, maxDom));
                }
                return c.getTimeInMillis();
            }
            default: return now + intervalMs;
        }
    }

    private static Calendar todayAt(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    public static long computeIntervalMs(int type, int value) {
        switch (type) {
            case TYPE_ONCE:     return 0L;
            case TYPE_MINUTES:  return value * 60_000L;
            case TYPE_HOURLY:   return 60 * 60_000L;
            case TYPE_DAILY:    return 24 * 60 * 60_000L;
            case TYPE_WEEKLY:   return 7L  * 24 * 60 * 60_000L;
            case TYPE_BIWEEKLY: return 14L * 24 * 60 * 60_000L;
            case TYPE_MONTHLY:  return 30L * 24 * 60 * 60_000L;
            default: return 60_000L;
        }
    }
}