package com.allinone.app.study;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.allinone.app.FocusTimerActivity;
import com.allinone.app.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Owns the focus-timer state so it survives leaving the screen.
 *
 * The countdown runs off a saved end-timestamp (not a live CountDownTimer). While running, a
 * {@link FocusTimerService} foreground service keeps an always-visible, per-second notification
 * on screen; an exact alarm is a redundant backup that finalises the session if the service is
 * ever killed. The Activity is just a live view over this state.
 */
public class FocusController {

    private static final String PREFS = "focus_timer";
    private static final String K_RUNNING = "running";
    private static final String K_PAUSED = "paused";
    private static final String K_END_AT = "end_at";
    private static final String K_TOTAL = "total_ms";
    private static final String K_REMAINING = "remaining";
    private static final String K_FOCUS_MODE = "focus_mode";
    private static final String K_FOCUS_MIN = "focus_min";
    private static final String K_LAST_DONE = "last_done_end";
    private static final String K_SESSIONS = "sessions_today";
    private static final String K_SESSIONS_DAY = "sessions_day";

    public static final String CH_RUNNING = "focus_running";
    public static final String CH_DONE = "focus_done";
    public static final int NOTIF_RUNNING = 951_000;
    private static final int NOTIF_DONE = 951_001;
    private static final int REQ_ALARM = 952_000;
    private static final int REQ_STOP = 952_001;

    public static class State {
        public boolean running;
        public boolean paused;
        public long endAt;
        public long totalMs;
        public long remaining;
        public boolean focusMode;
        public int focusMin;
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static State load(Context c) {
        SharedPreferences sp = p(c);
        State s = new State();
        s.running = sp.getBoolean(K_RUNNING, false);
        s.paused = sp.getBoolean(K_PAUSED, false);
        s.endAt = sp.getLong(K_END_AT, 0);
        s.totalMs = sp.getLong(K_TOTAL, 0);
        s.remaining = sp.getLong(K_REMAINING, 0);
        s.focusMode = sp.getBoolean(K_FOCUS_MODE, true);
        s.focusMin = sp.getInt(K_FOCUS_MIN, 25);
        return s;
    }

    public static void start(Context c, long totalMs, long remainingMs, boolean focusMode, int focusMin) {
        long endAt = System.currentTimeMillis() + remainingMs;
        p(c).edit()
            .putBoolean(K_RUNNING, true)
            .putBoolean(K_PAUSED, false)
            .putLong(K_END_AT, endAt)
            .putLong(K_TOTAL, totalMs)
            .putBoolean(K_FOCUS_MODE, focusMode)
            .putInt(K_FOCUS_MIN, focusMin)
            .apply();
        createChannels(c);
        scheduleAlarm(c, endAt);
        startService(c);
    }

    public static void pause(Context c, long remainingMs) {
        p(c).edit()
            .putBoolean(K_RUNNING, false)
            .putBoolean(K_PAUSED, true)
            .putLong(K_REMAINING, remainingMs)
            .apply();
        cancelAlarm(c);
        stopService(c);
    }

    public static void stopIdle(Context c) {
        clearStateAndAlarm(c);
        stopService(c);
    }

    /** Clears running/paused state and cancels the alarm — but does NOT touch the service. */
    public static void clearStateAndAlarm(Context c) {
        p(c).edit()
            .putBoolean(K_RUNNING, false)
            .putBoolean(K_PAUSED, false)
            .putLong(K_REMAINING, 0)
            .apply();
        cancelAlarm(c);
    }

    /** Completes the session for this endAt exactly once. Returns true if it logged a focus session. */
    public static boolean complete(Context c, long endAt) {
        SharedPreferences sp = p(c);
        if (sp.getLong(K_LAST_DONE, -1) == endAt) return false; // already handled
        boolean wasFocus = sp.getBoolean(K_FOCUS_MODE, true);
        int min = sp.getInt(K_FOCUS_MIN, 25);

        sp.edit()
            .putLong(K_LAST_DONE, endAt)
            .putBoolean(K_RUNNING, false)
            .putBoolean(K_PAUSED, false)
            .putLong(K_REMAINING, 0)
            .apply();

        boolean logged = false;
        if (wasFocus) {
            new StudyDb(c).addFocusMinutes(min);
            bumpSessions(c);
            logged = true;
            showDoneNotification(c, "Focus session complete 🎉",
                min + " min logged. Time for a short break ☕");
        } else {
            showDoneNotification(c, "Break's over",
                "Ready for another focus session?");
        }
        return logged;
    }

    /** Alarm-backup path: finalise the session and make sure the service is torn down. */
    public static void completeFromAlarm(Context c, long endAt) {
        complete(c, endAt);
        stopService(c);
    }

    // ---------- foreground service ----------

    private static void startService(Context c) {
        Intent i = new Intent(c, FocusTimerService.class).setAction(FocusTimerService.ACTION_START);
        ContextCompat.startForegroundService(c, i);
    }

    private static void stopService(Context c) {
        try {
            c.stopService(new Intent(c, FocusTimerService.class));
        } catch (Exception ignored) {}
    }

    // ---------- sessions counter (per calendar day) ----------

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static void bumpSessions(Context c) {
        SharedPreferences sp = p(c);
        String day = today();
        int n = day.equals(sp.getString(K_SESSIONS_DAY, "")) ? sp.getInt(K_SESSIONS, 0) : 0;
        sp.edit().putString(K_SESSIONS_DAY, day).putInt(K_SESSIONS, n + 1).apply();
    }

    public static int getSessionsToday(Context c) {
        SharedPreferences sp = p(c);
        return today().equals(sp.getString(K_SESSIONS_DAY, "")) ? sp.getInt(K_SESSIONS, 0) : 0;
    }

    // ---------- alarm ----------

    private static void scheduleAlarm(Context c, long endAt) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarmIntent(c, endAt);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi);
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi);
        }
    }

    private static void cancelAlarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, REQ_ALARM,
            new Intent(c, FocusAlarmReceiver.class),
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) am.cancel(pi);
    }

    private static PendingIntent alarmIntent(Context c, long endAt) {
        Intent i = new Intent(c, FocusAlarmReceiver.class);
        i.putExtra("end_at", endAt);
        return PendingIntent.getBroadcast(c, REQ_ALARM, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------- notifications ----------

    public static void createChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH_RUNNING) == null) {
            NotificationChannel ch = new NotificationChannel(
                CH_RUNNING, "Focus timer (running)", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Ongoing focus-session countdown");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_DONE) == null) {
            NotificationChannel ch = new NotificationChannel(
                CH_DONE, "Focus timer (done)", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Fires when a focus session or break ends");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 300, 200, 300});
            nm.createNotificationChannel(ch);
        }
    }

    private static PendingIntent openActivity(Context c) {
        Intent i = new Intent(c, FocusTimerActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Builds the ongoing countdown notification shown by the foreground service every second. */
    public static Notification buildRunningNotification(Context c, long remainingMs, long totalMs,
                                                        boolean focusMode) {
        long remSec = Math.max(0, remainingMs / 1000);
        String clock = String.format(Locale.US, "%02d:%02d", remSec / 60, remSec % 60);
        int max = (int) Math.max(1, totalMs / 1000);
        int progress = (int) Math.min(max, (totalMs - remainingMs) / 1000);

        Intent stop = new Intent(c, FocusTimerService.class).setAction(FocusTimerService.ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(c, REQ_STOP, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(c, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle((focusMode ? "🎯 Focus" : "☕ Break") + " · " + clock)
            .setContentText(focusMode ? "Stay off your phone — you've got this" : "Rest your eyes a moment")
            .setContentIntent(openActivity(c))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(max, progress, false)
            .setColor(0xFF6C63FF)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    public static void notifyRunning(Context c, Notification n) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_RUNNING, n);
    }

    private static void showDoneNotification(Context c, String title, String body) {
        createChannels(c);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_DONE)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openActivity(c))
            .setAutoCancel(true)
            .setColor(0xFF6C63FF)
            .setPriority(NotificationCompat.PRIORITY_HIGH);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_DONE, b.build());
    }
}
