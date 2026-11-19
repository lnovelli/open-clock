package com.openclock;

import android.os.SystemClock;
import android.util.Log;

/** Opt-in, content-free timings for user-initiated display preparation. */
final class WatchUiTiming {
    private static final String TAG = "OpenClockUiTiming";
    private static final ThreadLocal<Sample> CURRENT = new ThreadLocal<>();
    private static final class Sample {
        final long queueMs, startedMs;
        long phoneLockMs, phoneReadMs, watchLockMs, watchReadMs;
        Sample(long queueMs) { this.queueMs = queueMs; this.startedMs = SystemClock.elapsedRealtime(); }
    }
    static void start(long queueMs) {
        if (Log.isLoggable(TAG, Log.DEBUG)) CURRENT.set(new Sample(Math.max(0, queueMs)));
    }
    static void phoneLock(long ms) { Sample s = CURRENT.get(); if (s != null) s.phoneLockMs += Math.max(0, ms); }
    static void phoneRead(long ms) { Sample s = CURRENT.get(); if (s != null) s.phoneReadMs += Math.max(0, ms); }
    static void watchLock(long ms) { Sample s = CURRENT.get(); if (s != null) s.watchLockMs += Math.max(0, ms); }
    static void watchRead(long ms) { Sample s = CURRENT.get(); if (s != null) s.watchReadMs += Math.max(0, ms); }
    static void finish() {
        Sample s = CURRENT.get(); CURRENT.remove();
        if (s == null) return;
        long total = Math.max(0, SystemClock.elapsedRealtime() - s.startedMs);
        Log.d(TAG, "display queueMs=" + s.queueMs + " phoneLockMs=" + s.phoneLockMs + " phoneReadMs=" + s.phoneReadMs
            + " watchLockMs=" + s.watchLockMs + " watchReadMs=" + s.watchReadMs + " preparationMs=" + total);
    }
    private WatchUiTiming() { }
}
