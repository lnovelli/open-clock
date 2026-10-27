package com.openclock;

import android.content.*;

public final class RestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent) {
        // Session bootCount distinguishes a prior reboot from the later unlock
        // BOOT_COMPLETED. Never dismiss a session started during this locked boot.
        AlarmStore.rescheduleAll(c);
        String problem=AlarmStore.accessProblem(c);
        if(problem!=null) AlarmNotifications.warning(c,problem);
    }
}
