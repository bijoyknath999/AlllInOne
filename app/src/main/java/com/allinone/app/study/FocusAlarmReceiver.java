package com.allinone.app.study;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires when a focus session (or break) ends — even if the app is in the background or closed. */
public class FocusAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        long endAt = intent.getLongExtra("end_at", 0);
        if (endAt <= 0) return;
        FocusController.completeFromAlarm(ctx, endAt);
    }
}
