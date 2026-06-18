package com.allinone.app.reminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class ReminderScheduler {

    public static void schedule(Context ctx, Reminder r) {
        long nextFire;
        if (r.intervalType == Reminder.TYPE_ONCE) {
            nextFire = r.nextFireMs;
            if (nextFire <= System.currentTimeMillis()) return;
        } else {
            // Compute next fire anchored to fireHour:fireMinute — fixes drift after reboot
            nextFire = r.computeNextFireMs();
            new ReminderDb(ctx).updateNextFire(r.id, nextFire);
        }

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = buildPendingIntent(ctx, r);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextFire, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextFire, pi);
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextFire, pi);
        }
    }

    public static void cancel(Context ctx, Reminder r) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, (int) r.id,
            new Intent(ctx, ReminderReceiver.class),
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) am.cancel(pi);
    }

    private static PendingIntent buildPendingIntent(Context ctx, Reminder r) {
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        intent.putExtra("reminder_id", r.id);
        return PendingIntent.getBroadcast(ctx, (int) r.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}