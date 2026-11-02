package com.openclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

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
        if (id <= 0 || token == null || PhoneMirror.current(app, id, token) == null) return;
        // goAsync holds the receiver briefly, not for the full phone timeout. A late
        // response is best effort if the process survives; never equate send with ack.
        final PendingResult pending = goAsync();
        final AtomicBoolean finished = new AtomicBoolean();
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable end = () -> { if (finished.compareAndSet(false, true)) pending.finish(); };
        handler.postDelayed(end, 8000);
        try {
            JSONObject command = new JSONObject().put("op", "finish").put("id", id)
                    .put("token", token).put("snooze", false);
            PhoneSync.command(app, command, (ok, safeMessage) -> {
                if (ok) {
                    PhoneMirror.acknowledged(app, id, token);
                } else {
                    PhoneSync.requestSnapshot(app);
                    // A cleared notification is not proof the phone stopped ringing.
                    JSONObject current = PhoneMirror.current(app, id, token);
                    if (current != null) PhoneMirror.post(app, current, PhoneMirror.remaining(app, current));
                }
                handler.removeCallbacks(end);
                end.run();
            });
        } catch (Exception unavailable) {
            handler.removeCallbacks(end);
            end.run();
        }
    }
}
