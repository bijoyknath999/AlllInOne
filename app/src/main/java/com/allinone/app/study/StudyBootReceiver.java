package com.allinone.app.study;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

/** Re-arms all study reminders after a device reboot. */
public class StudyBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        StudyReminderReceiver.createChannel(ctx);
        List<StudyPlan> plans = new StudyDb(ctx).getReminderPlans();
        for (StudyPlan p : plans) {
            StudyReminderScheduler.schedule(ctx, p);
        }
    }
}
