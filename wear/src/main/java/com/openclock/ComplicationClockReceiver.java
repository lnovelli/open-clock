package com.openclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ComplicationClockReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ComplicationUpdateJob.changed(context, true);
    }
}
