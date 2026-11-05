package com.openclock;

import android.content.Context;

/** Watch-local alarm commits never publish or modify phone state. */
final class WearSync {
    static final String LOCAL_CHANGED = "com.openclock.WATCH_ALARMS_CHANGED";
    private static final java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService fallback = java.util.concurrent.Executors.newSingleThreadExecutor();
    // A persisted recovery job must exist before a dismissal can enter the journal.
    // Failure is advisory: never prevent a local alarm from being stopped.
    static void beforeCommit(Context c) { PhoneDismissalJob.beforeCommit(c); }
    static boolean enqueueGenericDismissal(org.json.JSONObject state) throws org.json.JSONException {
        return AlarmStore.addGenericDismissal(state);
    }
    static void afterLocalDismissal(Context c,org.json.JSONObject dismissed) {
        if(dismissed!=null) PhoneMirror.localDismissed(c,dismissed);
    }
    static void afterPhoneFallbackOccurrence(Context c) {
        Context app=c.getApplicationContext();
        fallback.execute(() -> {
            PhoneDismissalJob.schedule(app);
            android.os.UserManager users=app.getSystemService(android.os.UserManager.class);
            if(users!=null&&users.isUserUnlocked()) PhoneSync.reconcileCached(app);
        });
    }
    static void afterCommit(Context c) {
        // Store commits hold the store lock. Queue the in-app UI signal instead
        // of doing a Binder call there; never publish watch state to the phone.
        if (!queued.compareAndSet(false, true)) return;
        Context app = c.getApplicationContext();
        main.post(() -> {
            queued.set(false);
            app.sendBroadcast(new android.content.Intent(LOCAL_CHANGED).setPackage(app.getPackageName()));
            ComplicationUpdateJob.changed(app, false);
            PhoneDismissalJob.schedule(app);
        });
    }
    private WearSync() { }
}
