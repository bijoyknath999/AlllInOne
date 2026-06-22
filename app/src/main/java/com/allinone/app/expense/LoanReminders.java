package com.allinone.app.expense;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.allinone.app.ExpenseTrackerActivity;
import com.allinone.app.R;

import java.util.List;

/** Posts a daily notification summarising loans that are overdue or due soon. */
public class LoanReminders {

    private static final String CHANNEL_ID = "loan_due";
    private static final int NOTIF_ID = 71001;
    private static final long SOON_WINDOW = 3L * 24 * 60 * 60 * 1000; // 3 days

    public static void check(Context ctx) {
        ExpenseDb db = new ExpenseDb(ctx);
        long now = System.currentTimeMillis();
        int overdue = 0, soon = 0;
        for (Loan l : db.queryLoans(0, Loan.STATUS_OPEN)) {
            if (l.dueMillis <= 0) continue;
            if (l.dueMillis < now) overdue++;
            else if (l.dueMillis - now <= SOON_WINDOW) soon++;
        }
        if (overdue == 0 && soon == 0) return;

        StringBuilder msg = new StringBuilder();
        if (overdue > 0) msg.append(overdue).append(" loan").append(overdue == 1 ? "" : "s").append(" overdue");
        if (soon > 0) {
            if (msg.length() > 0) msg.append(", ");
            msg.append(soon).append(" due soon");
        }
        notify(ctx, "Loan reminders", msg.toString());
    }

    private static void notify(Context ctx, String title, String text) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Loan reminders",
                NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, ExpenseTrackerActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 0, open, flags);

        NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_expense_tracker)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, nb.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted (API 33+) — silently skip.
        }
    }
}
