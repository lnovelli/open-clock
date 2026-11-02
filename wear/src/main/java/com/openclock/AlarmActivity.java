package com.openclock;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Date;

/** Full-screen controls for one exact, persisted ringing occurrence. */
public final class AlarmActivity extends Activity {
    private long id = -1;
    private String token;
    private TextView status;
    private Button snoozeButton;
    private Button dismissButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = new Runnable() {
        @Override public void run() {
            try {
                JSONObject alarm = token == null ? null : AlarmStore.active(AlarmActivity.this, id, token);
                if (alarm == null) { finish(); return; }
                updateState(alarm);
                handler.postDelayed(this, 500);
            } catch (RuntimeException e) {
                AlarmNotifications.warning(AlarmActivity.this, AlarmStore.message(e));
                finish();
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        show(getIntent(), state);
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        show(intent, null);
    }

    private void show(Intent intent, Bundle saved) {
        handler.removeCallbacks(check);
        long incomingId = intent == null ? -1 : intent.getLongExtra("id", -1);
        String incomingToken = intent == null ? null : intent.getStringExtra("token");
        // A newly delivered explicit identity wins over a saved identity. The saved
        // pair is only a fallback when recreation did not retain the intent extras.
        if (incomingId == -1 && incomingToken == null && saved != null) {
            incomingId = saved.getLong("alarmId", -1);
            incomingToken = saved.getString("alarmToken");
        }
        id = incomingId;
        token = incomingToken;
        if (id <= 0 || token == null || token.isEmpty()) { finish(); return; }
        try {
            JSONObject alarm = AlarmStore.active(this, id, token);
            if (alarm == null) { finish(); return; }
            buildScreen(alarm);
            handler.post(check);
            try {
                // The service owns and reconciles audio/vibration for all sessions.
                startForegroundService(new Intent(this, RingingService.class));
            } catch (RuntimeException e) {
                AlarmNotifications.warning(this, "Continuous ringing could not start");
            }
        } catch (RuntimeException e) {
            AlarmNotifications.warning(this, AlarmStore.message(e));
            finish();
        }
    }

    private void buildScreen(JSONObject alarm) {
        String name = alarm.optString("label", "").trim();
        long startedAt = alarm.optJSONObject("_active").optLong("startedAt", System.currentTimeMillis());
        String time = android.text.format.DateFormat.getTimeFormat(this).format(new Date(startedAt));
        RingingScreen screen = new RingingScreen(this, name.isEmpty() ? "Alarm" : name, time);
        status = screen.status;
        dismissButton = screen.dismiss;
        snoozeButton = screen.snooze;
        dismissButton.setContentDescription("Dismiss alarm. Swipe left from center, or hold Select");
        screen.dismiss.setSwipeAction(() -> complete(false));
        screen.snooze.setSwipeAction(() -> complete(true));
        updateState(alarm);
        setContentView(RingingNavigation.wrap(this, screen));
    }

    @Override public void onBackPressed() {
        complete(false);
    }

    private void updateState(JSONObject alarm) {
        boolean available = AlarmStore.canSnooze(alarm);
        snoozeButton.setEnabled(available);
        snoozeButton.setAlpha(available ? 1f : 0.45f);
        int minutes = alarm.optInt("snoozeMinutes", 5);
        snoozeButton.setContentDescription(available
                ? "Snooze for " + minutes + (minutes == 1 ? " minute" : " minutes") + ". Swipe right from center, or hold Select"
                : "Snooze unavailable");
        boolean muted = alarm.optJSONObject("_active").optBoolean("soundMuted");
        String label = muted ? (available ? "Sound muted" : "Sound muted\nSnooze unavailable")
                : available ? "" : "Snooze unavailable";
        RingingScreen.setStatus(status, label);
    }

    private void complete(boolean snooze) {
        try {
            // Store operations are synchronized on this same lock. Keep validation,
            // mutation and notification cancellation together so a stale screen cannot
            // cancel a replacement occurrence's notification.
            synchronized (AlarmStore.class) {
                JSONObject alarm = token == null ? null : AlarmStore.active(this, id, token);
                if (alarm == null) { finish(); return; }
                if (snooze && !AlarmStore.canSnooze(alarm)) { updateState(alarm); return; }
                AlarmStore.finish(this, id, token, snooze);
                try {
                    getSystemService(NotificationManager.class).cancel("alarm:" + id, 11);
                } catch (RuntimeException e) {
                    // The store mutation already committed; reconciliation can retry cleanup.
                    AlarmNotifications.warning(this, "Could not clear the alarm notification");
                }
            }
            AlarmStore.refreshService(this);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Alarm unchanged: " + AlarmStore.message(e), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putLong("alarmId", id);
        state.putString("alarmToken", token);
        super.onSaveInstanceState(state);
    }

    @Override public void onResume() {
        super.onResume();
        handler.removeCallbacks(check);
        if (token != null) handler.post(check);
    }

    @Override public void onPause() {
        handler.removeCallbacks(check);
        super.onPause();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

}
