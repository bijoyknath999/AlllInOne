package com.allinone.app.attendance;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * One office day: when you arrived, when you left, and anything you noted about it.
 *
 * <p>The day itself is the identity, not a row id — you are only ever in the office once
 * per date, so a second check-in on the same day has to amend the existing record rather
 * than start a new one. Storing it as {@code yyyy-MM-dd} keeps that key sortable and makes
 * month ranges a plain string comparison.
 */
public class AttendanceRecord {

    /** Wall-clock day, {@code yyyy-MM-dd}. Primary identity. */
    public String day = "";
    public long   id;
    /** Epoch millis, or 0 when not recorded yet. */
    public long   checkInMs;
    public long   checkOutMs;
    public String note = "";
    /**
     * True once a human has typed these times in rather than tapping the buttons. Kept so a
     * corrected day is visibly a correction, which matters when the log is what you hand to
     * payroll.
     */
    public boolean manual;

    public AttendanceRecord() {}

    public AttendanceRecord(String day) { this.day = day; }

    public boolean hasCheckIn()  { return checkInMs  > 0; }
    public boolean hasCheckOut() { return checkOutMs > 0; }
    public boolean isComplete()  { return hasCheckIn() && hasCheckOut(); }

    /**
     * Minutes between the two punches, or 0 when the day is incomplete.
     *
     * <p>Clamped at zero: a hand-typed check-out earlier than the check-in would otherwise
     * subtract from the month's total and quietly corrupt every summary above it.
     */
    public int workedMinutes() {
        if (!isComplete()) return 0;
        long ms = checkOutMs - checkInMs;
        return ms <= 0 ? 0 : (int) (ms / 60000L);
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private static final SimpleDateFormat DAY_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private static final SimpleDateFormat PRETTY_FMT =
            new SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault());

    /** {@code yyyy-MM-dd} for a moment in time. */
    public static synchronized String dayKey(long ms) {
        return DAY_FMT.format(new Date(ms));
    }

    public static String today() { return dayKey(System.currentTimeMillis()); }

    /** Midnight of a {@code yyyy-MM-dd} key, or now if it cannot be read. */
    public static synchronized long dayStartMs(String day) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(DAY_FMT.parse(day));
            return c.getTimeInMillis();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    public static synchronized String formatTime(long ms) {
        return ms > 0 ? TIME_FMT.format(new Date(ms)) : "—";
    }

    public static synchronized String formatDay(String day) {
        return PRETTY_FMT.format(new Date(dayStartMs(day)));
    }

    /** Minutes as {@code 8h 30m}, or {@code —} for an incomplete day. */
    public static String formatDuration(int minutes) {
        if (minutes <= 0) return "—";
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
