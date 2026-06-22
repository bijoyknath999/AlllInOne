package com.allinone.app.expense;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Posts due recurring transactions on the daily alarm and after device boot. */
public class RecurringReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        RecurringEngine.postDue(ctx);
        LoanReminders.check(ctx);
        // Re-arm the daily alarm (alarms are cleared on reboot).
        RecurringScheduler.scheduleDaily(ctx);
    }
}
