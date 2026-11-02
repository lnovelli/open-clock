package com.openclock;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Controls one exact phone occurrence; an intent is never authority to operate it. */
public final class PhoneMirrorActivity extends Activity {
    private static final Map<String, Attempt> attempts = new HashMap<>(); // main thread only
    private static final class Attempt { boolean pending = true; long started = SystemClock.elapsedRealtime(); }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long id = -1;
    private String token, key;
    private TextView status;
    private Button dismiss, snooze;

    private final Runnable check = new Runnable() {
        @Override public void run() {
            JSONObject a = PhoneMirror.current(PhoneMirrorActivity.this, id, token);
            if (a == null) { finish(); return; }
            update(a);
            handler.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT); getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        show(getIntent());
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); show(intent);
    }
    private void show(Intent intent) {
        handler.removeCallbacks(check);
        id = intent == null ? -1 : intent.getLongExtra("id", -1);
        token = intent == null ? null : intent.getStringExtra("token");
        key = id + ":" + token;
        JSONObject a = PhoneMirror.current(this, id, token);
        if (a == null) { finish(); return; }
        build(a); handler.post(check);
        // If a background foreground-service start was restricted, retry while
        // the alarm Activity is visible rather than leaving only a silent alert.
        PhoneMirror.start(this);
        PhoneSync.requestSnapshot(this);
    }
    private void build(JSONObject a) {
        String label = a.optString("label").trim();
        java.util.Calendar scheduled = java.util.Calendar.getInstance();
        scheduled.set(java.util.Calendar.HOUR_OF_DAY, a.optInt("hour"));
        scheduled.set(java.util.Calendar.MINUTE, a.optInt("minute"));
        String time = android.text.format.DateFormat.getTimeFormat(this).format(scheduled.getTime());
        RingingScreen screen = new RingingScreen(this, label.isEmpty() ? "Phone alarm" : label, time);
        status = screen.status;
        dismiss = screen.dismiss;
        snooze = screen.snooze;
        dismiss.setContentDescription("Dismiss phone alarm. Swipe left from center, or hold Select");
        screen.dismiss.setSwipeAction(() -> complete(false));
        screen.snooze.setSwipeAction(() -> complete(true));
        setContentView(RingingNavigation.wrap(this, screen));
        update(a);
    }
    @Override public void onBackPressed() {
        complete(false);
    }
    private void update(JSONObject a) {
        Attempt attempt = attempts.get(key);
        if (attempt != null && attempt.pending && SystemClock.elapsedRealtime() - attempt.started >= 30_000) attempt.pending = false;
        boolean busy = attempt != null && attempt.pending;
        boolean available = a.optBoolean("canSnooze");
        dismiss.setEnabled(!busy); dismiss.setAlpha(busy ? .45f : 1f);
        snooze.setEnabled(!busy && available); snooze.setAlpha(!busy && available ? 1f : .45f);
        snooze.setContentDescription(available ? "Snooze phone alarm. Swipe right from center, or hold Select" : "Snooze unavailable");
        JSONObject snapshot = PhoneSync.cached(this);
        boolean connected = snapshot != null && snapshot.optBoolean("connected");
        String message = busy ? "Waiting for phone…" : attempt != null ? "Not confirmed"
                : !connected ? "Phone offline" : "";
        if (!available) message += (message.isEmpty() ? "" : "\n") + "Snooze unavailable";
        RingingScreen.setStatus(status, message);
    }
    private void complete(boolean wantSnooze) {
        Attempt existing = attempts.get(key); if (existing != null && existing.pending) return;
        JSONObject current = PhoneMirror.current(this, id, token);
        if (current == null) { PhoneSync.requestSnapshot(this); finish(); return; }
        if (wantSnooze && !current.optBoolean("canSnooze")) { update(current); return; }
        final long sentId = id; final String sentToken = token, sentKey = key;
        final android.content.Context app = getApplicationContext();
        Attempt attempt = new Attempt(); attempts.put(sentKey, attempt); update(current);
        try {
            JSONObject command = new JSONObject().put("op", "finish").put("id", sentId).put("token", sentToken).put("snooze", wantSnooze);
            PhoneSync.command(app, command, (ok, safeMessage) -> {
                // A late positive reply is still a real phone acknowledgement for this token.
                if (ok) { PhoneMirror.acknowledged(app, sentId, sentToken); attempts.remove(sentKey); }
                else { attempt.pending = false; PhoneSync.requestSnapshot(app); }
                if (!isFinishing() && sentKey.equals(key)) {
                    if (ok) finish();
                    else { JSONObject a = PhoneMirror.current(this, id, token); if (a != null) update(a); }
                }
            });
        } catch (Exception e) { attempt.pending = false; PhoneSync.requestSnapshot(app); update(current); }
    }
    @Override protected void onResume() {
        super.onResume(); handler.removeCallbacks(check);
        if (token != null) handler.post(check);
    }
    @Override protected void onPause() { handler.removeCallbacks(check); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
