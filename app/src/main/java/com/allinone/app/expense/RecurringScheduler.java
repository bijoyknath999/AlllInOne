package com.allinone.app.expense;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** Schedules a daily wake-up that posts due recurring transactions in the background. */
public class RecurringScheduler {

    private static final int REQUEST_CODE = 90210;

    public static void scheduleDaily(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = pendingIntent(ctx);

        // Fire ~once a day, starting at the next 09:00 local time.
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(),
            AlarmManager.INTERVAL_DAY, pi);
    }

    private static PendingIntent pendingIntent(Context ctx) {
        Intent i = new Intent(ctx, RecurringReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, i, flags);
    }
}
