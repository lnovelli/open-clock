package com.openclock;

import android.app.NotificationManager;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** One process-wide listener and one target, shared by both existing ringing services.
 * All entry points and sensor callbacks run on the main looper. */
final class WatchShakeBridge implements SensorEventListener {
    private static WatchShakeBridge instance;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final String TAG = "OpenClockShake";
    private final DoubleShakeDetector detector = new DoubleShakeDetector(this::diagnostic);
    private long diagnosticAt;
    private int staleRejects;
    private SensorManager sensors;
    private boolean localService, mirrorService, registered, attempted;
    interface TestListener {
        void event(String event);
        default void gyro(float x, float y, float z) { }
    }
    private TestListener testListener;
    private Target target;
    // Keep the cooldown across target changes and unconfirmed phone commands.
    private static final long COOLDOWN_MS = 1_000;
    private long cooldownUntil, lastGyroTime;
    private boolean readyPending;
    private final Runnable announceReady = new Runnable() {
        @Override public void run() {
            if (!registered || testListener == null || !readyPending) return;
            long remaining = cooldownUntil - SystemClock.elapsedRealtime();
            if (remaining > 0) { main.postDelayed(this, remaining); return; }
            readyPending = false;
            testListener.event("ready");
        }
    };

    private static final class Target {
        final boolean local; final long id; final String token;
        Target(boolean local, long id, String token) { this.local = local; this.id = id; this.token = token; }
        boolean same(Target other) { return other != null && local == other.local && id == other.id && token.equals(other.token); }
    }
    private WatchShakeBridge(Context context) { app = context.getApplicationContext(); }
    private static int bump(int value) { return value == Integer.MAX_VALUE ? value : value + 1; }
    private void diagnostic(String category) {
        if (testListener != null && ("first".equals(category) || "timeout".equals(category)))
            testListener.event(category);
        switch (category) {
            case "first": case "second": case "timeout":
                Log.i(TAG, category); break;
            case "stale": staleRejects = bump(staleRejects); break;
            default: return;
        }
        long now = SystemClock.elapsedRealtime();
        if (diagnosticAt == 0) diagnosticAt = now;
        else if (now - diagnosticAt >= 5_000) {
            if (staleRejects != 0) Log.i(TAG, "rejections stale=" + staleRejects);
            staleRejects = 0;
            diagnosticAt = now;
        }
    }
    static void local(Context c, boolean running) { update(c, true, running); }
    static void mirror(Context c, boolean running) { update(c, false, running); }
    // Independent, foreground-only observer. It uses exactly the ringing sensor
    // pipeline, but has no alarm target and can never invoke an alarm action.
    static WatchShakeBridge startTest(Context c, TestListener listener) {
        WatchShakeBridge test = new WatchShakeBridge(c);
        test.testListener = listener;
        test.registerSensors();
        return test;
    }
    void stopTest() {
        testListener = null;
        stopListener();
    }
    private static void update(Context c, boolean local, boolean running) {
        try {
            if (instance == null) {
                if (!running) return;
                instance = new WatchShakeBridge(c);
            }
            if (local) instance.localService = running; else instance.mirrorService = running;
            instance.reconcile();
        } catch (RuntimeException ignored) {
            // Sensors must never interrupt ringing, including on devices with broken sensor services.
            if (instance != null) instance.stopListener();
        }
    }
    private Target select() {
        JSONArray local = AlarmStore.active(app);
        Target best = null;
        for (int i = 0; i < local.length(); i++) {
            JSONObject a = local.optJSONObject(i), session = a == null ? null : a.optJSONObject("_active");
            if (session == null) continue;
            Target candidate = new Target(true, a.optLong("id"), session.optString("token"));
            if (candidate.id > 0 && !candidate.token.isEmpty() && (best == null || candidate.id < best.id)) best = candidate;
        }
        // A newly fired local occurrence preempts the mirror even before its service starts.
        if (best != null) return localService ? best : null;
        if (!mirrorService) return null;
        for (JSONObject a : PhoneMirror.live(app)) {
            Target candidate = new Target(false, a.optLong("id"), a.optString("token"));
            if (candidate.id > 0 && !candidate.token.isEmpty() && (best == null || candidate.id < best.id)) best = candidate;
        }
        return best;
    }
    private void reconcile() {
        Target selected = select();
        if (selected == null) {
            target = null; stopListener(); attempted = false; return;
        }
        if (target == null || !target.same(selected)) {
            target = selected; detector.clearPair();
        }
        if (!registered && !attempted) {
            attempted = true;
            registerSensors();
        }
    }
    private void registerSensors() {
        try {
            sensors = app.getSystemService(SensorManager.class);
            Sensor gyro = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyro != null && sensors.registerListener(this, gyro, 10_000, main)) {
                registered = true; Log.i(TAG, "sensor ready");
                if (testListener != null) {
                    readyPending = true;
                    announceReady.run();
                }
            } else { Log.i(TAG, "sensors unavailable"); stopListener(); if (testListener != null) testListener.event("unavailable"); }
        } catch (RuntimeException unavailable) {
            Log.i(TAG, "sensors unavailable"); stopListener();
            if (testListener != null) testListener.event("unavailable");
        }
    }
    private void stopListener() {
        if (sensors != null) try { sensors.unregisterListener(this); } catch (RuntimeException ignored) { }
        registered = false; detector.reset(); readyPending = false; lastGyroTime = 0;
        main.removeCallbacks(announceReady);
    }
    @Override public void onSensorChanged(SensorEvent event) {
        try { process(event); }
        catch (RuntimeException unavailable) { stopListener(); if (testListener != null) testListener.event("unavailable"); }
    }
    private void process(SensorEvent event) {
        if (!registered || (target == null && testListener == null)) return;
        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        if (event.values == null || event.values.length < 3) {
            detector.reset(); diagnostic("stale"); return;
        }
        float x = event.values[0], y = event.values[1], z = event.values[2];
        long time = event.timestamp;
        // Observe valid gyro values even during cooldown. No values are persisted or logged.
        if (testListener != null && time > 0 && Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z))
            testListener.gyro(x, y, z);
        if (time <= 0 || time <= lastGyroTime || !Float.isFinite(x)
                || !Float.isFinite(y) || !Float.isFinite(z)) {
            detector.reset(); diagnostic("stale"); return;
        }
        lastGyroTime = time;
        if (SystemClock.elapsedRealtime() < cooldownUntil) {
            detector.discard(time, x); return;
        }
        if (readyPending) { main.removeCallbacks(announceReady); announceReady.run(); }
        if (!detector.sample(time, x, y, z)) return;
        Target detected = target;
        cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS;
        if (testListener != null) {
            testListener.event("detected");
            readyPending = true;
            main.removeCallbacks(announceReady);
            main.postDelayed(announceReady, COOLDOWN_MS);
            return;
        }
        try {
            if (detected.local) {
                JSONObject dismissed = null;
                synchronized (AlarmStore.class) {
                    // Select local priority without consulting mirror/sync while
                    // holding the store lock (sync itself can acquire this lock).
                    JSONArray local = AlarmStore.active(app);
                    Target best = null;
                    for (int i = 0; i < local.length(); i++) {
                        JSONObject a = local.optJSONObject(i), s = a == null ? null : a.optJSONObject("_active");
                        if (s == null) continue;
                        Target candidate = new Target(true, a.optLong("id"), s.optString("token"));
                        if (candidate.id > 0 && !candidate.token.isEmpty() && (best == null || candidate.id < best.id)) best = candidate;
                    }
                    if (!localService || !detected.same(best)) return;
                    dismissed = AlarmStore.active(app, detected.id, detected.token);
                    if (dismissed == null) return;
                    AlarmStore.finish(app, detected.id, detected.token, false);
                    try { app.getSystemService(NotificationManager.class).cancel("alarm:" + detected.id, 11); }
                    catch (RuntimeException ignored) { }
                }
                PhoneMirror.localDismissed(app, dismissed);
                AlarmStore.refreshService(app);
            } else if (mirrorService) {
                // Admission atomically checks local priority using mirror -> sync -> store.
                PhoneMirror.dismissFromShake(app, detected.id, detected.token);
            }
        } catch (Exception ignored) { /* leave normal controls and ringing intact */ }
        finally { reconcile(); }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
