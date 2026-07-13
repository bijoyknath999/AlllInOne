package com.allinone.app.study;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** A study goal — e.g. "Finish Physics in 30 days" — with a chapter checklist. */
public class StudyPlan {

    // Curated accent palette for plans (index cycles).
    public static final int[] COLORS = {
        0xFF6C63FF, // indigo
        0xFF00BFA6, // teal
        0xFFFF7043, // deep orange
        0xFFEC407A, // pink
        0xFF42A5F5, // blue
        0xFF66BB6A, // green
        0xFFAB47BC, // purple
        0xFFFFCA28, // amber
    };

    public long id;
    public String subject;         // "Physics", "Data Structures"...
    public int color;              // ARGB
    public long startMs;           // day the plan started (midnight)
    public int targetDays;         // e.g. 30
    public boolean reminderEnabled = true;
    public int reminderHour = 19;  // 7 PM default
    public int reminderMinute = 0;
    public long createdAt;

    // Transient — filled from the chapters table when loading.
    public int totalChapters = 0;
    public int doneChapters = 0;

    public StudyPlan() {}

    public long deadlineMs() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startMs);
        c.add(Calendar.DAY_OF_MONTH, targetDays);
        return c.getTimeInMillis();
    }

    /** Day number within the plan, 1 = the start day. Can exceed targetDays if overdue. */
    public int dayNumber() {
        long today = midnight(System.currentTimeMillis());
        long start = midnight(startMs);
        long diff = (today - start) / DAY_MS;
        return (int) diff + 1;
    }

    /** Whole days left until the deadline (0 if past). */
    public int daysRemaining() {
        long today = midnight(System.currentTimeMillis());
        long deadline = midnight(deadlineMs());
        long diff = (deadline - today) / DAY_MS;
        return (int) Math.max(0, diff);
    }

    public boolean isOverdue() {
        return midnight(System.currentTimeMillis()) >= midnight(deadlineMs())
                && doneChapters < totalChapters;
    }

    public boolean isComplete() {
        return totalChapters > 0 && doneChapters >= totalChapters;
    }

    public float progress() {
        if (totalChapters == 0) return 0f;
        return Math.min(1f, doneChapters / (float) totalChapters);
    }

    public int progressPercent() {
        return Math.round(progress() * 100);
    }

    /** How many chapters the schedule expects done by end of today to finish on time. */
    public int expectedDoneToday() {
        if (totalChapters == 0 || targetDays <= 0) return 0;
        int day = Math.max(1, Math.min(dayNumber(), targetDays));
        int expected = (int) Math.ceil(totalChapters * (day / (double) targetDays));
        return Math.min(totalChapters, expected);
    }

    /** Chapters that should be finished today to keep pace. */
    public int chaptersDueToday() {
        return Math.max(0, expectedDoneToday() - doneChapters);
    }

    /** Recommended chapters/day to finish the remaining work on time. */
    public double pacePerDay() {
        int remainingChapters = totalChapters - doneChapters;
        int remainingDays = Math.max(1, daysRemaining());
        if (remainingChapters <= 0) return 0;
        return remainingChapters / (double) remainingDays;
    }

    public String statusLabel() {
        if (isComplete()) return "Completed 🎉";
        if (isOverdue())  return "Overdue";
        int behind = expectedDoneToday() - doneChapters;
        if (behind > 0)   return "Behind by " + behind;
        if (doneChapters > expectedDoneToday()) return "Ahead of schedule";
        return "On track";
    }

    public String rangeLabel() {
        SimpleDateFormat f = new SimpleDateFormat("MMM d", Locale.getDefault());
        return f.format(new Date(startMs)) + " – " + f.format(new Date(deadlineMs()));
    }

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    public static long midnight(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Next daily reminder fire time anchored to reminderHour:reminderMinute. */
    public long computeNextReminderMs() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, reminderHour);
        c.set(Calendar.MINUTE, reminderMinute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    public String reminderTimeLabel() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, reminderHour);
        c.set(Calendar.MINUTE, reminderMinute);
        return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(c.getTime());
    }
}