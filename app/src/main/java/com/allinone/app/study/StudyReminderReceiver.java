package com.allinone.app.study;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.allinone.app.R;
import com.allinone.app.StudyPlanActivity;

/** Fires the daily study reminder: tells the user which chapter to tackle today, then reschedules. */
public class StudyReminderReceiver extends BroadcastReceiver {

    public static final String CHANNEL = "study_focus";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        long id = intent.getLongExtra("plan_id", -1);
        if (id < 0) return;

        StudyDb db = new StudyDb(ctx);
        StudyPlan p = db.getPlan(id);
        if (p == null || !p.reminderEnabled) return;

        createChannel(ctx);
        showNotification(ctx, db, p);

        // Reschedule for tomorrow.
        StudyReminderScheduler.schedule(ctx, p);
    }

    private void showNotification(Context ctx, StudyDb db, StudyPlan p) {
        Intent tap = new Intent(ctx, StudyPlanActivity.class);
        tap.putExtra("plan_id", p.id);
        tap.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) p.id, tap,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title;
        String body;
        if (p.isComplete()) {
            title = p.subject + " — all done! 🎉";
            body = "You've finished every chapter. Great work!";
        } else {
            Chapter next = db.getNextChapter(p.id);
            String chapterName = next != null ? next.name : "your next chapter";
            title = "📚 Time to study " + p.subject;
            int due = p.chaptersDueToday();
            String pace = due > 0
                ? "Aim for " + due + " chapter" + (due == 1 ? "" : "s") + " today · "
                : "";
            body = pace + "Today: " + chapterName
                + " · " + p.daysRemaining() + " days left";
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_study)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setColor(p.color)
            .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(900_000 + (int) p.id, b.build());
    }

    public static void createChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL, "Study Focus", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Daily study goal reminders");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 300, 200, 300});
        nm.createNotificationChannel(ch);
    }
}
