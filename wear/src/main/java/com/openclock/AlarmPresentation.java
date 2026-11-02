package com.openclock;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** Wear OS does not support full-screen notification intents. */
final class AlarmPresentation {
    private static PowerManager.WakeLock screenWake;
    private static final long SCREEN_WAKE_MS = 5_000L;
    static boolean fullScreenIntentSupported() { return false; }

    /** Best effort: short, bounded screen wake; it never bypasses the keyguard. */
    @SuppressWarnings("deprecation")
    private static synchronized void wakeScreen(Context c) {
        try {
            PowerManager power = c.getSystemService(PowerManager.class);
            if (power == null || !power.isWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK)) return;
            if (screenWake == null) {
                screenWake = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP, "OpenClock:alarm-screen");
                screenWake.setReferenceCounted(false);
            }
            // Coalesce closely spaced occurrences; no periodic wakeups after dismissal.
            if (!screenWake.isHeld()) screenWake.acquire(SCREEN_WAKE_MS);
        } catch (RuntimeException blocked) {
            Log.i("OpenClockAlarm", "Watch screen wake unavailable; alarm continues");
        }
    }

    /** Attempt once for a newly committed occurrence, never for an unvalidated alarm. */
    static void local(Context c, long id) {
        try {
            JSONArray active = AlarmStore.active(c);
            for (int i = 0; i < active.length(); i++) {
                JSONObject alarm = active.optJSONObject(i);
                if (alarm != null && alarm.optLong("id", -1) == id && alarm.optJSONObject("_active") != null) {
                    wakeScreen(c);
                    c.startActivity(AlarmNotifications.alertIntent(c, alarm));
                    return;
                }
            }
        } catch (RuntimeException blocked) {
            Log.i("OpenClockAlarm", "Watch alarm screen launch unavailable; use notification");
        }
        // A successful startActivity call is not proof: Android can silently block it.
    }

    static void mirror(Context c, JSONObject alarm) {
        try {
            long id = alarm.optLong("id", -1);
            String token = alarm.optString("token", "");
            if (id > 0 && !token.isEmpty() && PhoneMirror.current(c, id, token) != null) {
                wakeScreen(c);
                c.startActivity(PhoneMirror.openIntent(c, alarm));
            }
        } catch (RuntimeException blocked) {
            Log.i("OpenClockAlarm", "Mirrored alarm screen launch unavailable; use notification");
        }
    }

    private AlarmPresentation() { }
}
