package com.openclock;

import android.content.Context;

/** The shared phone alarm engine does not use watch motion sensors. */
final class WatchShakeBridge {
    static void local(Context context, boolean running) { }
    private WatchShakeBridge() { }
}
