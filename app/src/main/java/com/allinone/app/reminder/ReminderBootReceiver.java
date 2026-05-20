package com.allinone.app.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

public class ReminderBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        ReminderReceiver.createChannel(ctx);

        List<Reminder> reminders = new ReminderDb(ctx).getEnabled();
        for (Reminder r : reminders) {
            ReminderScheduler.schedule(ctx, r);
        }
    }
}