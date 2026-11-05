package com.openclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Handles an explicit notification action or clearance for one phone occurrence. */
public final class MirrorNotificationReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!"com.openclock.MIRROR_DISMISS".equals(action)
                && !"com.openclock.MIRROR_DELETE".equals(action)
                && !"com.openclock.MIRROR_FALLBACK_DELETE".equals(action)) return;
        final Context app = context.getApplicationContext();
        final long id = intent.getLongExtra("id", -1);
        final String token = intent.getStringExtra("token");
        if (id <= 0 || token == null) return;
        // A notification action is only a request; dismiss() revalidates the live
        // session and never treats local silence as a phone receipt.
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                if (PhoneMirror.dismiss(app, id, token)) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            Toast.makeText(app, "Mirror silenced; phone dismissal not confirmed", Toast.LENGTH_LONG).show());
                }
            } finally { pending.finish(); }
        }, "mirror-dismiss").start();
    }
}
