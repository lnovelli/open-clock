package com.openclock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Retained UI operation: never cancel a possibly committed write when its window stops. */
final class WatchUiWork {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean MUTATING = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.Set<MainActivity> WINDOWS = new java.util.HashSet<>();
    private static WatchUiWork active; // MAIN only; MUTATING is the worker-readable admission flag.
    MainActivity owner;
    String phase;
    volatile String draftRequestId = "";
    private Context app;
    private Consumer<MainActivity> result;
    private WatchUiData completedData;
    private boolean finishing;
    private volatile long generation;
    boolean begin(String message) {
        if (owner == null || phase != null || !MUTATING.compareAndSet(false, true)) return false;
        generation++; phase = message; finishing = false; active = this;
        app = owner.getApplicationContext();
        notifyWindows(false);
        return true;
    }
    void attach(MainActivity activity) { owner = activity; WINDOWS.add(activity); deliver(); }
    void detach(MainActivity activity) { WINDOWS.remove(activity); if (owner == activity) owner = null; }
    private static void notifyWindows(boolean refresh) { for (MainActivity activity : WINDOWS) activity.workChanged(refresh); }
    String visiblePhase() { return phase != null ? phase : active == null ? null : active.phase; }
    void finish(Consumer<MainActivity> completion) {
        final long operation = generation;
        MAIN.post(() -> {
            if (generation != operation || phase == null || finishing) return;
            finishing = true;
            phase = "Updating display…";
            notifyWindows(false);
            // Finish independently of Activity lifetime: a stopped/finished window
            // must not strand the process-wide admission gate or cancel a write.
            long enqueuedAt = android.os.SystemClock.elapsedRealtime();
            IO.execute(() -> {
                WatchUiData fresh;
                WatchUiTiming.start(android.os.SystemClock.elapsedRealtime() - enqueuedAt);
                try { fresh = WatchUiData.read(app, true); }
                catch (Exception unavailable) { fresh = new WatchUiData(); fresh.key = "unavailable"; }
                finally { WatchUiTiming.finish(); }
                final WatchUiData loaded = fresh;
                MAIN.post(() -> {
                    completedData = loaded; result = completion;
                    phase = null; active = null; MUTATING.set(false);
                    deliver();
                    notifyWindows(true);
                });
            });
        });
    }
    /** Complete a successful read-only lookup without preparing another display snapshot. */
    void finishReadOnly(Consumer<MainActivity> completion) {
        final long operation = generation;
        MAIN.post(() -> {
            if (generation != operation || phase == null || finishing) return;
            finishing = true;
            completedData = null; result = completion;
            phase = null; active = null; MUTATING.set(false);
            deliver();
            notifyWindows(false);
        });
    }
    private void deliver() {
        if (owner == null || result == null) return;
        Consumer<MainActivity> completion = result; result = null;
        if (completedData != null) owner.acceptWorkData(completedData);
        completedData = null;
        owner.showWork(); completion.accept(owner);
    }
    void syncing() {
        if (phase != null && !finishing) { phase = "Synchronizing with phone…"; notifyWindows(false); }
    }
    static boolean inFlight() { return MUTATING.get(); }
    boolean busy() { return phase != null || inFlight(); }
}
