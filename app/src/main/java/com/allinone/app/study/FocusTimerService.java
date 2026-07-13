package com.allinone.app.study;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

/**
 * Keeps the focus timer alive and its notification always visible while running.
 *
 * A foreground-service notification can't be swiped away and survives the app going to the
 * background, so the countdown is always on screen. It updates every second and finalises the
 * session itself (the exact alarm in {@link FocusController} is a redundant backup).
 */
public class FocusTimerService extends Service {

    public static final String ACTION_START = "com.allinone.app.focus.START";
    public static final String ACTION_STOP  = "com.allinone.app.focus.STOP";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable ticker;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            FocusController.clearStateAndAlarm(this);
            stopEverything();
            return START_NOT_STICKY;
        }

        FocusController.State s = FocusController.load(this);
        long remaining = s.endAt - System.currentTimeMillis();
        if (!s.running || remaining <= 0) {
            // Nothing to run (or it already finished while we were gone).
            if (s.running && remaining <= 0) FocusController.complete(this, s.endAt);
            stopEverything();
            return START_NOT_STICKY;
        }

        startForegroundNotification(s, remaining);
        startTicking();
        return START_STICKY; // system restarts us after a kill; we re-read state above
    }

    private void startTicking() {
        if (ticker != null) handler.removeCallbacks(ticker);
        ticker = new Runnable() {
            @Override public void run() {
                FocusController.State s = FocusController.load(FocusTimerService.this);
                if (!s.running) { stopEverything(); return; }
                long remaining = s.endAt - System.currentTimeMillis();
                if (remaining <= 0) {
                    FocusController.complete(FocusTimerService.this, s.endAt);
                    stopEverything();
                    return;
                }
                Notification n = FocusController.buildRunningNotification(
                    FocusTimerService.this, remaining, s.totalMs, s.focusMode);
                FocusController.notifyRunning(FocusTimerService.this, n);
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(ticker);
    }

    private void startForegroundNotification(FocusController.State s, long remaining) {
        Notification n = FocusController.buildRunningNotification(this, remaining, s.totalMs, s.focusMode);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FocusController.NOTIF_RUNNING, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FocusController.NOTIF_RUNNING, n);
        }
    }

    private void stopEverything() {
        if (ticker != null) handler.removeCallbacks(ticker);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (ticker != null) handler.removeCallbacks(ticker);
    }
}
