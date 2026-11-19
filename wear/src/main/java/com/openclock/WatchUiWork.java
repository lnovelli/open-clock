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
    boolean begin(String message) {
        if (owner == null || phase != null || !MUTATING.compareAndSet(false, true)) return false;
        phase = message; finishing = false; active = this;
        app = owner.getApplicationContext();
        notifyWindows();
        return true;
    }
    void attach(MainActivity activity) { owner = activity; WINDOWS.add(activity); deliver(); }
    void detach(MainActivity activity) { WINDOWS.remove(activity); if (owner == activity) owner = null; }
    private static void notifyWindows() { for (MainActivity activity : WINDOWS) activity.workChanged(); }
    String visiblePhase() { return phase != null ? phase : active == null ? null : active.phase; }
    void finish(Consumer<MainActivity> completion) {
        MAIN.post(() -> {
            if (phase == null || finishing) return;
            finishing = true;
            phase = "Updating display…";
            notifyWindows();
            // Finish independently of Activity lifetime: a stopped/finished window
            // must not strand the process-wide admission gate or cancel a write.
            IO.execute(() -> {
                WatchUiData fresh;
                try { fresh = WatchUiData.read(app, true); }
                catch (Exception unavailable) { fresh = new WatchUiData(); fresh.key = "unavailable"; }
                final WatchUiData loaded = fresh;
                MAIN.post(() -> {
                    completedData = loaded; result = completion;
                    phase = null; active = null; MUTATING.set(false);
                    deliver();
                    notifyWindows();
                });
            });
        });
    }
    private void deliver() {
        if (owner == null || result == null) return;
        Consumer<MainActivity> completion = result; result = null;
        owner.acceptWorkData(completedData); completedData = null;
        owner.showWork(); completion.accept(owner);
    }
    void syncing() {
        if (phase != null && !finishing) { phase = "Synchronizing with phone…"; notifyWindows(); }
    }
    static boolean inFlight() { return MUTATING.get(); }
    boolean busy() { return phase != null || inFlight(); }
}
