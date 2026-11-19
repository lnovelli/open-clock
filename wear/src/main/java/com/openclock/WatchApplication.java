package com.openclock;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Watch lifecycle reconciliation must not acquire the store monitor before the first frame. */
public final class WatchApplication extends Application implements Application.ActivityLifecycleCallbacks {
    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        // Preserve the shared application's post-launch boundary: a FIRE receiver
        // must first consume its original persisted generation. Never reschedule
        // all alarms merely because a receiver created this process.
        new Handler(Looper.getMainLooper()).post(() -> WatchUiWork.IO.execute(() -> AlarmStore.recoverRinging(this)));
    }
    private void reconcile(Activity activity) {
        if (activity instanceof MainActivity)
            WatchUiWork.IO.execute(() -> AlarmStore.rescheduleAll(this));
    }
    @Override public void onActivityPreResumed(Activity activity) { if (Build.VERSION.SDK_INT >= 29) reconcile(activity); }
    @Override public void onActivityStarted(Activity activity) { if (Build.VERSION.SDK_INT < 29) reconcile(activity); }
    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityResumed(Activity a) { }
    @Override public void onActivityPaused(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) { }
}
