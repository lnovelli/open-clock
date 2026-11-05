package com.openclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Credential-protected Data Layer synchronization begins after first unlock. */
public final class PhoneSyncBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)) {
            PhoneSyncRepair.schedule(context, true);
            PhoneDismissalJob.schedule(context);
        }
    }
}
