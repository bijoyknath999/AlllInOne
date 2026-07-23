package com.allinone.app.attendance;

import java.util.Calendar;

/**
 * One approved or planned absence, held as an inclusive day range.
 *
 * <p>A range rather than a row per day: leave is requested and remembered as "the 12th to
 * the 15th", and expanding that into four rows means a later correction has to find and
 * rewrite all of them. The day-level view is derived when needed via {@link #covers}.
 */
public class LeaveRecord {

    public static final String[] TYPES = {
            "Casual", "Sick", "Annual", "Unpaid", "Public Holiday", "Other"
    };

    public long   id;
    /** Inclusive {@code yyyy-MM-dd} bounds. */
    public String startDay = "";
    public String endDay   = "";
    public String type     = TYPES[0];
    public String reason   = "";
    public long   createdAt;

    /** Whole days covered, inclusive of both ends; always at least 1. */
    public int days() {
        long from = AttendanceRecord.dayStartMs(startDay);
        long to   = AttendanceRecord.dayStartMs(endDay);
        if (to < from) return 1;
        // Count calendar days rather than dividing millis: a DST shift makes some days 23
        // or 25 hours long, and integer division then drops or adds a day.
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(from);
        int n = 1;
        while (c.getTimeInMillis() < to && n < 3650) {
            c.add(Calendar.DAY_OF_MONTH, 1);
            n++;
        }
        return n;
    }

    /** True when {@code day} ({@code yyyy-MM-dd}) falls inside the range. */
    public boolean covers(String day) {
        return day != null
                && day.compareTo(startDay) >= 0
                && day.compareTo(endDay) <= 0;
    }

    /** How many of this leave's days fall inside an inclusive day range. */
    public int daysWithin(String fromDay, String toDay) {
        String from = startDay.compareTo(fromDay) > 0 ? startDay : fromDay;
        String to   = endDay.compareTo(toDay)     < 0 ? endDay   : toDay;
        if (from.compareTo(to) > 0) return 0;

        LeaveRecord clipped = new LeaveRecord();
        clipped.startDay = from;
        clipped.endDay   = to;
        return clipped.days();
    }

    public String rangeLabel() {
        if (startDay.equals(endDay)) return AttendanceRecord.formatDay(startDay);
        return AttendanceRecord.formatDay(startDay) + "  →  " + AttendanceRecord.formatDay(endDay);
    }
}
