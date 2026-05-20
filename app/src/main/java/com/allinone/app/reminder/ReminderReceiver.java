package com.allinone.app.reminder;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.allinone.app.R;
import com.allinone.app.ReminderActivity;

public class ReminderReceiver extends BroadcastReceiver {

    static final String CHANNEL_ID = "reminders";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        long id = intent.getLongExtra("reminder_id", -1);
        if (id < 0) return;

        ReminderDb db = new ReminderDb(ctx);
        Reminder r = db.getById(id);
        if (r == null || !r.enabled) return;

        createChannel(ctx);
        showNotification(ctx, r);

        if (r.intervalType == Reminder.TYPE_ONCE) {
            db.setEnabled(r.id, false);
        } else {
            ReminderScheduler.schedule(ctx, r);
        }
    }

    private void showNotification(Context ctx, Reminder r) {
        Intent tapIntent = new Intent(ctx, ReminderActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) r.id, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String msg = (r.message != null && !r.message.isEmpty()) ? r.message : r.title;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(r.title)
            .setContentText(msg)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(msg))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int) r.id, builder.build());
    }

    public static void createChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Scheduled reminder notifications");
        nm.createNotificationChannel(ch);
    }
}