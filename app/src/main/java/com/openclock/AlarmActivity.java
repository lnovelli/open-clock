package com.openclock;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AlarmActivity extends Activity {
    private static final int BUTTON_TEXT = 0xfffaf8ff;
    private static final int SNOOZE_COLOR = 0xff605de0, CONTROL_COLOR = 0xff403c9c;
    private int textColor, softTextColor;
    private long id = -1; private String token;
    private int snoozeMinutes = 5;
    private TextView soundStatus, snoozeLabel, minus, plus;
    private View snoozeBar;
    private DismissSwipeView dismissSwipe;
    private boolean volumeDownHeld;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = new Runnable() { public void run() {
        JSONObject a = AlarmStore.active(AlarmActivity.this, id, token);
        if (a == null) finish();
        else { updateSoundStatus(a); updateSnoozeAvailability(a); handler.postDelayed(this, 500); }
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // The ringing screen intentionally stays dark even in the system light theme.
        textColor = 0xfffaf8ff;
        softTextColor = 0xffd0c4df;
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(0xff21182f);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        show(getIntent(), state);
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); show(intent, null);
    }

    private void show(Intent intent, Bundle saved) {
        handler.removeCallbacks(check);
        long incomingId = intent == null ? -1 : intent.getLongExtra("id", -1);
        String incomingToken = intent == null ? null : intent.getStringExtra("token");
        boolean same = incomingToken != null && incomingId == id && incomingToken.equals(token);
        id = incomingId; token = incomingToken;
        if (token == null) { finish(); return; }
        JSONObject a = AlarmStore.active(this, id, token);
        if (a == null) { finish(); return; }
        if (!same) snoozeMinutes = Math.max(1, Math.min(1440, a.optInt("snoozeMinutes", 5)));
        if (saved != null && saved.getLong("snoozeAlarmId", -1) == id
                && token.equals(saved.getString("snoozeSession"))) {
            snoozeMinutes = Math.max(1, Math.min(1440, saved.getInt("snoozeSelection", snoozeMinutes)));
        }
        buildScreen(a);
        handler.post(check);
        try { startForegroundService(new Intent(this, RingingService.class)); }
        catch (RuntimeException e) { AlarmNotifications.warning(this, "Continuous ringing could not start"); }
    }

    private void buildScreen(JSONObject alarm) {
        ScrollView viewport = new ScrollView(this);
        viewport.setFillViewport(true);
        viewport.setVerticalScrollBarEnabled(false);
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xff171124, 0xff211832, 0xff2c2040, 0xff21182f});
        background.setDither(true);
        viewport.setBackground(background);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(12), dp(28), dp(26));
        viewport.addView(root, new ScrollView.LayoutParams(-1, -2));

        spacer(root, 1.0f);
        String name = alarm.optString("label", "").trim();
        TextView heading = text(name.isEmpty() ? "Alarm" : name, 20, textColor);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setGravity(Gravity.CENTER); heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        long startedAt = alarm.optJSONObject("_active").optLong("startedAt", System.currentTimeMillis());
        Date started = new Date(startedAt);
        TextView time = text(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(started), 82, textColor);
        time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        time.setFontFeatureSettings("tnum"); time.setGravity(Gravity.CENTER); time.setMaxLines(1);
        time.setAutoSizeTextTypeUniformWithConfiguration(42, 82, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(-1, dp(115));
        timeParams.topMargin = dp(8); root.addView(time, timeParams);

        String pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEE MMMM d");
        TextView date = text(new SimpleDateFormat(pattern, Locale.getDefault()).format(started), 17, textColor);
        date.setGravity(Gravity.CENTER); root.addView(date, new LinearLayout.LayoutParams(-1, -2));

        soundStatus = text("", 13, softTextColor); soundStatus.setGravity(Gravity.CENTER);
        soundStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(12); root.addView(soundStatus, statusParams);
        updateSoundStatus(alarm);

        spacer(root, 2.8f);
        final long dismissId = id;
        final String dismissToken = token;
        dismissSwipe = new DismissSwipeView(this, () -> {
            if (id == dismissId && dismissToken.equals(token)) complete(false);
        });
        root.addView(dismissSwipe, new LinearLayout.LayoutParams(-1, dp(224)));
        TextView dismissHint = text("Swipe × to the ring and release to dismiss", 14, softTextColor);
        dismissHint.setGravity(Gravity.CENTER);
        root.addView(dismissHint, new LinearLayout.LayoutParams(-1, -2));

        spacer(root, 0.8f);
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(8), dp(7), dp(8), dp(7));
        bar.setBackground(shape(SNOOZE_COLOR, 40));
        snoozeBar = bar;
        minus = text("−", 32, BUTTON_TEXT); configureAdjustButton(minus, "Decrease snooze time", false);
        bar.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        snoozeLabel = text("", 16, BUTTON_TEXT); snoozeLabel.setGravity(Gravity.CENTER);
        snoozeLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        snoozeLabel.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, shape(Color.WHITE, 32)));
        snoozeLabel.setOnClickListener(v -> complete(true));
        bar.addView(snoozeLabel, new LinearLayout.LayoutParams(0, dp(48), 1));
        plus = text("+", 32, BUTTON_TEXT); configureAdjustButton(plus, "Increase snooze time", true);
        bar.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(62)));
        updateSnoozeLabel(); updateSnoozeAvailability(alarm);
        setContentView(viewport);
    }

    /** A physical touch must start on the handle and finish beyond the target ring. */
    private final class DismissSwipeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable dismissAction;
        private final float unit = getResources().getDisplayMetrics().density;
        private int pointerId = -1;
        private int keyCode = -1;
        private long keyDownTime;
        private boolean tracking;
        private float startX, startY, dx, dy;

        DismissSwipeView(Context context, Runnable dismissAction) {
            super(context);
            this.dismissAction = dismissAction;
            setFocusable(true);
            setContentDescription("Dismiss alarm. Swipe the cross outward to the ring and release, or hold Select.");
        }

        private float scale() {
            return Math.min(unit, Math.min(getWidth(), getHeight()) / 224f);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = scale(), cx = getWidth() / 2f, cy = getHeight() / 2f;
            float target = 72 * s;
            float distance = (float) Math.hypot(dx, dy);
            boolean ready = tracking && distance >= target;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ready ? 4 * s : 2 * s);
            paint.setColor(ready ? SNOOZE_COLOR : 0xffb7aaca);
            canvas.drawCircle(cx, cy, target, paint);
            paint.setStyle(Paint.Style.FILL);
            float fraction = distance > target && distance > 0 ? target / distance : 1f;
            float x = cx + dx * fraction, y = cy + dy * fraction;
            paint.setColor(ready ? CONTROL_COLOR : 0xff181121);
            canvas.drawCircle(x, y, 34 * s, paint);
            paint.setColor(BUTTON_TEXT);
            paint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            paint.setTextSize(48 * s);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("×", x, y - (paint.ascent() + paint.descent()) / 2f, paint);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    resetGesture();
                    if (event.getPointerCount() != 1 || Math.hypot(event.getX() - getWidth() / 2f,
                            event.getY() - getHeight() / 2f) > 34 * scale()) return false;
                    tracking = true;
                    pointerId = event.getPointerId(0);
                    startX = event.getX(); startY = event.getY();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    resetGesture();
                    return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    if (!tracking || event.getPointerCount() != 1) { resetGesture(); return true; }
                    int index = event.findPointerIndex(pointerId);
                    if (index < 0) { resetGesture(); return true; }
                    dx = event.getX(index) - startX; dy = event.getY(index) - startY;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        boolean completed = Math.hypot(dx, dy) >= 72 * scale();
                        resetGesture();
                        if (completed) dismissAction.run();
                    } else invalidate();
                    return true;
                default:
                    return true;
            }
        }

        private void resetGesture() {
            tracking = false; pointerId = -1; dx = 0; dy = 0;
            keyCode = -1;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
        }

        private boolean activationKey(int code) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        }

        @Override public boolean onKeyDown(int code, KeyEvent event) {
            if (!activationKey(code)) return super.onKeyDown(code, event);
            if (isEnabled() && event.getRepeatCount() == 0) {
                if (tracking) resetGesture();
                keyCode = code;
                keyDownTime = event.getDownTime();
            }
            return true;
        }

        @Override public boolean onKeyUp(int code, KeyEvent event) {
            if (!activationKey(code)) return super.onKeyUp(code, event);
            boolean held = keyCode == code && keyDownTime == event.getDownTime()
                    && !event.isCanceled() && isEnabled() && isAttachedToWindow() && hasWindowFocus()
                    && event.getEventTime() - keyDownTime >= ViewConfiguration.getLongPressTimeout();
            keyCode = -1;
            if (held) dismissAction.run();
            return true;
        }

        @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(focused, direction, previous);
            if (!focused) keyCode = -1;
        }

        @Override protected void onDetachedFromWindow() {
            resetGesture();
            super.onDetachedFromWindow();
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(Button.class.getName());
            info.setClickable(true);
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK, "Dismiss alarm"));
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK && isEnabled()
                    && isAttachedToWindow() && hasWindowFocus()) {
                resetGesture();
                dismissAction.run();
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }

    private void configureAdjustButton(TextView button, String description, boolean increment) {
        button.setGravity(Gravity.CENTER); button.setContentDescription(description);
        button.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        button.setBackground(ripple(CONTROL_COLOR, 24));
        button.setOnClickListener(v -> { snoozeMinutes = nextSnoozeMinutes(snoozeMinutes, increment); updateSnoozeLabel(); });
    }

    // One-minute choices end at 5; above that, use five-minute steps. Clamp
    // the downward transition to 5 for existing off-grid saved intervals.
    static int nextSnoozeMinutes(int current, boolean increment) {
        int value = Math.max(1, Math.min(1440, current));
        if (increment) return Math.min(1440, value + (value < 5 ? 1 : 5));
        return value <= 5 ? Math.max(1, value - 1) : Math.max(5, value - 5);
    }
    private void updateSnoozeLabel() {
        String duration = snoozeMinutes + (snoozeMinutes == 1 ? " min" : " mins");
        snoozeLabel.setText("Snooze " + duration);
        snoozeLabel.setContentDescription("Snooze for " + snoozeMinutes + (snoozeMinutes == 1 ? " minute" : " minutes"));
        minus.setEnabled(snoozeMinutes > 1); minus.setAlpha(snoozeMinutes > 1 ? 1f : 0.35f);
        plus.setEnabled(snoozeMinutes < 1440); plus.setAlpha(snoozeMinutes < 1440 ? 1f : 0.35f);
    }
    private void updateSnoozeAvailability(JSONObject a) {
        if (snoozeBar != null) snoozeBar.setVisibility(AlarmStore.canSnooze(a) ? View.VISIBLE : View.GONE);
    }
    private void updateSoundStatus(JSONObject a) {
        boolean muted = a.optJSONObject("_active").optBoolean("soundMuted");
        String text = muted ? "Sound muted for this occurrence. Vibration is unchanged." : "";
        if (soundStatus != null) {
            if (!text.contentEquals(soundStatus.getText())) soundStatus.setText(text);
            soundStatus.setVisibility(muted ? View.VISIBLE : View.GONE);
        }
    }
    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp);
        view.setTextColor(color); view.setIncludeFontPadding(false); return view;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x44ffffff), shape(color, radius), null);
    }
    private void spacer(LinearLayout root, float weight) {
        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, weight));
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putLong("snoozeAlarmId", id); state.putString("snoozeSession", token);
        state.putInt("snoozeSelection", snoozeMinutes);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event);
        // Consume DOWN, repeats and UP: never change the device's alarm volume.
        if (event.getAction() == KeyEvent.ACTION_UP) { volumeDownHeld = false; return true; }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !volumeDownHeld && event.getRepeatCount() == 0) {
            volumeDownHeld = true;
            try {
                if (token == null || !AlarmStore.muteCurrent(this, id, token)) { finish(); return true; }
                JSONObject a = AlarmStore.active(this, id, token); if (a != null) updateSoundStatus(a);
                Toast.makeText(this, "Current alarm sounds muted; vibration unchanged", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Could not mute: " + AlarmStore.message(e), Toast.LENGTH_LONG).show();
            }
        }
        return true;
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            volumeDownHeld = false;
            if (dismissSwipe != null) dismissSwipe.resetGesture();
        }
    }
    private void complete(boolean snooze) {
        try {
            if (AlarmStore.active(this, id, token) == null) { finish(); return; }
            AlarmStore.finish(this, id, token, snooze, snooze ? snoozeMinutes : null);
            getSystemService(NotificationManager.class).cancel("alarm:" + id, 11);
            AlarmStore.refreshService(this); finish();
        } catch (Exception e) { new AlertDialog.Builder(this).setTitle("Alarm unchanged").setMessage(AlarmStore.message(e)).setPositiveButton("OK", null).show(); }
    }
    @Override public void onResume() { super.onResume(); handler.removeCallbacks(check); if (token != null) handler.post(check); }
    @Override public void onPause() {
        handler.removeCallbacks(check);
        if (dismissSwipe != null) dismissSwipe.resetGesture();
        super.onPause();
    }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}