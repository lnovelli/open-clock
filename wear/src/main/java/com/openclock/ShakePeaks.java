package com.openclock;

/** Ephemeral diagnostic accumulator. Values are in radians/second; no samples are stored. */
final class ShakePeaks {
    private double xPeak, yPeak, zPeak, magnitudeSquared;
    private float vectorX, vectorY, vectorZ;
    private boolean hasSample;

    void reset() {
        xPeak = yPeak = zPeak = magnitudeSquared = 0;
        vectorX = vectorY = vectorZ = 0;
        hasSample = false;
    }

    void sample(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return;
        xPeak = Math.max(xPeak, Math.abs((double) x));
        yPeak = Math.max(yPeak, Math.abs((double) y));
        zPeak = Math.max(zPeak, Math.abs((double) z));
        double magnitude = (double) x * x + (double) y * y + (double) z * z;
        if (!hasSample || magnitude > magnitudeSquared) {
            magnitudeSquared = magnitude;
            vectorX = x; vectorY = y; vectorZ = z;
        }
        hasSample = true;
    }

    boolean hasSample() { return hasSample; }
    double xPeakDegrees() { return Math.toDegrees(xPeak); }
    double yPeakDegrees() { return Math.toDegrees(yPeak); }
    double zPeakDegrees() { return Math.toDegrees(zPeak); }
    double magnitudeDegrees() { return Math.toDegrees(Math.sqrt(magnitudeSquared)); }
    double vectorXDegrees() { return Math.toDegrees(vectorX); }
    double vectorYDegrees() { return Math.toDegrees(vectorY); }
    double vectorZDegrees() { return Math.toDegrees(vectorZ); }
}
