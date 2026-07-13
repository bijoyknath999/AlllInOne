package com.allinone.app.study;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Schedules a daily "time to study" alarm per plan, anchored to its reminder time. */
public class StudyReminderScheduler {

    // Offset alarm request codes so they never collide with Reminder module ids.
    private static final int REQ_BASE = 900_000;

    public static void schedule(Context ctx, StudyPlan p) {
        if (!p.reminderEnabled) return;
        long next = p.computeNextReminderMs();

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = buildPendingIntent(ctx, p);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    public static void cancel(Context ctx, StudyPlan p) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ_BASE + (int) p.id,
            new Intent(ctx, StudyReminderReceiver.class),
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) am.cancel(pi);
    }

    private static PendingIntent buildPendingIntent(Context ctx, StudyPlan p) {
        Intent intent = new Intent(ctx, StudyReminderReceiver.class);
        intent.putExtra("plan_id", p.id);
        return PendingIntent.getBroadcast(ctx, REQ_BASE + (int) p.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
