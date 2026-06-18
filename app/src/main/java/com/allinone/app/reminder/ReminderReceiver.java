package com.allinone.app.reminder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.core.app.NotificationCompat;

import com.allinone.app.R;
import com.allinone.app.ReminderActivity;

public class ReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL_SV = "reminders_sv"; // sound + vibrate
    private static final String CHANNEL_S  = "reminders_s";  // sound only
    private static final String CHANNEL_V  = "reminders_v";  // vibrate only
    private static final String CHANNEL_N  = "reminders_n";  // silent

    @Override
    public void onReceive(Context ctx, Intent intent) {
        long id = intent.getLongExtra("reminder_id", -1);
        if (id < 0) return;

        ReminderDb db = new ReminderDb(ctx);
        Reminder r = db.getById(id);
        if (r == null || !r.enabled) return;

        createChannels(ctx);
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
        String channelId = channelFor(r.sound, r.vibrate);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(r.title)
            .setContentText(msg)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH);

        // Try to load the user-chosen image
        Bitmap image = loadBitmap(ctx, r.imageUri);
        if (image != null) {
            builder.setLargeIcon(image);
            builder.setStyle(new NotificationCompat.BigPictureStyle()
                .bigPicture(image)
                .bigLargeIcon((Bitmap) null)); // hide large icon when expanded
        } else {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(msg));
        }

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int) r.id, builder.build());
    }

    private static Bitmap loadBitmap(Context ctx, String uriStr) {
        if (uriStr == null || uriStr.isEmpty()) return null;
        try {
            return BitmapFactory.decodeStream(
                ctx.getContentResolver().openInputStream(Uri.parse(uriStr)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String channelFor(boolean sound, boolean vibrate) {
        if (sound && vibrate)  return CHANNEL_SV;
        if (sound)             return CHANNEL_S;
        if (vibrate)           return CHANNEL_V;
        return CHANNEL_N;
    }

    public static void createChannels(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        createCh(nm, CHANNEL_SV, "Reminders",                NotificationManager.IMPORTANCE_HIGH, true,  true);
        createCh(nm, CHANNEL_S,  "Reminders (sound only)",   NotificationManager.IMPORTANCE_HIGH, true,  false);
        createCh(nm, CHANNEL_V,  "Reminders (vibrate only)", NotificationManager.IMPORTANCE_DEFAULT, false, true);
        createCh(nm, CHANNEL_N,  "Reminders (silent)",       NotificationManager.IMPORTANCE_DEFAULT, false, false);
    }

    private static void createCh(NotificationManager nm, String id, String name,
                                  int importance, boolean sound, boolean vibrate) {
        if (nm.getNotificationChannel(id) != null) return;
        NotificationChannel ch = new NotificationChannel(id, name, importance);
        ch.setDescription("Scheduled reminder notifications");
        ch.enableVibration(vibrate);
        if (vibrate) ch.setVibrationPattern(new long[]{0, 300, 200, 300});
        if (!sound)  ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }
}