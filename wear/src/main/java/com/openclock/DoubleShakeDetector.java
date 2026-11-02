package com.openclock;

/** Negative-X double excursion detector. Input is rad/s and monotonic sensor nanoseconds. */
final class DoubleShakeDetector {
    private static final float THRESHOLD = (float) -Math.toRadians(700);
    private static final long MAX_SEPARATION = 500_000_000L;
    interface Diagnostics { void event(String category); }
    private final Diagnostics diagnostics;
    private long previous, firstPeak;
    private float deepest;
    private boolean below;

    DoubleShakeDetector() { this(null); }
    DoubleShakeDetector(Diagnostics diagnostics) { this.diagnostics = diagnostics; }
    private void event(String category) { if (diagnostics != null) diagnostics.event(category); }
    void reset() {
        previous = firstPeak = 0;
        below = false;
        deepest = 0;
    }
    // Drop a partial pair on a target change without inventing a new crossing.
    void clearPair() { firstPeak = 0; }
    // Observe the threshold state during cooldown without accumulating a pair.
    void discard(long time, float x) {
        previous = time;
        firstPeak = 0;
        below = x <= THRESHOLD;
    }
    boolean sample(long time, float x, float y, float z) {
        if (time <= 0 || time <= previous || !Float.isFinite(x)
                || !Float.isFinite(y) || !Float.isFinite(z)) {
            // Discard the partial gesture, but do not admit an older timestamp on the next sample.
            firstPeak = 0;
            below = false;
            event("stale");
            return false;
        }
        previous = time;
        boolean nowBelow = x <= THRESHOLD;
        // Do not expire a still-running excursion: a deeper sample can move its peak time.
        if (firstPeak != 0 && !below && time - firstPeak > MAX_SEPARATION) {
            firstPeak = 0;
            event("timeout");
        }
        if (nowBelow) {
            if (!below) {
                below = true;
                if (firstPeak != 0) {
                    firstPeak = 0;
                    event("second");
                    return true;
                }
                firstPeak = time;
                deepest = x;
                event("first");
            } else if (firstPeak != 0 && x < deepest) {
                deepest = x;
                firstPeak = time;
            }
        } else {
            below = false;
            if (firstPeak != 0 && time - firstPeak > MAX_SEPARATION) {
                firstPeak = 0;
                event("timeout");
            }
        }
        return false;
    }
}
