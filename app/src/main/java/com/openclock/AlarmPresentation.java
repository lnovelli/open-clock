package com.openclock;

import android.content.Context;

/** Phone alarms use Android's full-screen notification rather than direct launches. */
final class AlarmPresentation {
    static boolean fullScreenIntentSupported() { return true; }
    static void local(Context context, long id) { }
    private AlarmPresentation() { }
}
