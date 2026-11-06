package com.openclock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.time.ZoneId;
import java.util.Locale;

/** Best-effort confirmation for committed third-party Binder mutations. */
final class ExternalAlarmConfirmation {
    static boolean shouldConfirm(JSONObject before, JSONObject after) {
        if (after == null || !after.optBoolean("enabled")) return false;
        if (before == null || !before.optBoolean("enabled")) return true;
        for (String key : new String[]{"hour", "minute", "date", "days"})
            if (!String.valueOf(before.opt(key)).equals(String.valueOf(after.opt(key)))) return true;
        return false;
    }

    static void afterSave(Context context, JSONObject before, JSONObject saved) {
        // A presentation problem must never change the result of a committed mutation.
        try {
            if (!shouldConfirm(before, saved)) return;
            Context app = context.getApplicationContext();
            long triggerAt = saved.optLong("nextTriggerAt", 0);
            boolean schedulingError = !saved.isNull("schedulingError");
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    long now = System.currentTimeMillis();
                    String text = null;
                    if (!schedulingError && AlarmStore.accessProblem(app) == null)
                        text = AlarmConfirmation.message(triggerAt, now, ZoneId.systemDefault(), Locale.getDefault());
                    if (text == null) text = "Alarm saved; next trigger unavailable. Check the alarm list.";
                    // Text toasts are supported from the background; custom views are not.
                    Toast.makeText(app, text, Toast.LENGTH_LONG).show();
                } catch (RuntimeException ignored) { /* The alarm was already committed. */ }
            });
        } catch (RuntimeException ignored) { /* The alarm was already committed. */ }
    }

    private ExternalAlarmConfirmation() { }
}
