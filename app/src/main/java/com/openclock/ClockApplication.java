package com.openclock;

import android.app.*;
import android.os.Bundle;

/** Reconcile permission revocation/grant and missed triggers when the user returns.
 * Do not reconcile on process creation: a receiver process must consume its original
 * persisted trigger generation before any reschedule can invalidate it.
 */
public final class ClockApplication extends Application implements Application.ActivityLifecycleCallbacks {
    @Override public void onCreate() {
        super.onCreate(); registerActivityLifecycleCallbacks(this);
        // Active-session recovery only; never invalidate the FIRE generation that
        // launched this process. Post until after the launching component runs.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(()->AlarmStore.recoverRinging(this));
    }
    @Override public void onActivityPreResumed(Activity activity) {
        if(activity instanceof MainActivity) AlarmStore.rescheduleAll(this);
    }
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityCreated(Activity a,Bundle b) {}
    @Override public void onActivityStarted(Activity a) {
        if(android.os.Build.VERSION.SDK_INT<29&&a instanceof MainActivity) AlarmStore.rescheduleAll(this);
    }
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a,Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}
