package com.openclock;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A measured, round-screen-safe ringing layout shared by local and phone alarms. */
final class RingingScreen extends FrameLayout {
    final TextView status;
    final SwipeButton dismiss;
    final SwipeButton snooze;
    private final LinearLayout content;
    private final TextView heading;
    private final LinearLayout buttons;

    RingingScreen(Activity activity, String label, String time) {
        super(activity);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff241044, 0xff4b287f, 0xff7651aa}));
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        heading = text(label, 15, 0xffeee6fa);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, TypedValue.COMPLEX_UNIT_SP);
        content.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));

        TextView clock = text(time, 46, Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clock.setFontFeatureSettings("tnum");
        clock.setSingleLine(true);
        clock.setAutoSizeTextTypeUniformWithConfiguration(9, 46, 1, TypedValue.COMPLEX_UNIT_SP);
        content.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        status = text("", 11, 0xffeee6fa);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, TypedValue.COMPLEX_UNIT_SP);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        content.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        buttons = new LinearLayout(activity);
        // Keep outward directions tied to physical screen sides even in RTL locales.
        buttons.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        buttons.setGravity(Gravity.CENTER);
        dismiss = button("← Dismiss", 0xff281640, Color.WHITE, -1);
        snooze = button("Snooze →", 0xffded0f4, 0xff281640, 1);
        buttons.addView(dismiss, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams snoozeParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        snoozeParams.leftMargin = dp(4);
        buttons.addView(snooze, snoozeParams);
        content.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    }

    static void setStatus(TextView status, String message) {
        int visibility = message.isEmpty() ? View.GONE : View.VISIBLE;
        if (status.getVisibility() != visibility) status.setVisibility(visibility);
        if (!message.contentEquals(status.getText())) status.setText(message);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    private SwipeButton button(String label, int background, int foreground, int direction) {
        SwipeButton button = new SwipeButton((Activity) getContext(), direction);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(foreground);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setSingleLine(true);
        button.setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, TypedValue.COMPLEX_UNIT_SP);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(26));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x66ffffff), shape, null));
        return button;
    }

    /** Touch requires an outward drag; accessibility activation is a separate explicit action. */
    static final class SwipeButton extends Button {
        private final int direction;
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Runnable action;
        private boolean tracking, cancelled;
        private int pointerId = -1;
        private int keyCode = -1;
        private long keyDownTime;
        private float startX, startY, progress;

        SwipeButton(Activity context, int direction) {
            super(context);
            this.direction = direction;
            setClickable(true); // Exposes the standard accessibility activation action.
            setFocusable(true);
            progressPaint.setColor(direction < 0 ? 0x66ffffff : 0x40281640);
        }

        void setSwipeAction(Runnable action) { this.action = action; }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            if (!enabled) keyCode = -1;
            if (!enabled && tracking) {
                cancelled = true;
                progress = 0;
                invalidate();
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int type = event.getActionMasked();
            if (type == MotionEvent.ACTION_DOWN) {
                if (!isEnabled()) return false;
                keyCode = -1;
                tracking = true;
                pointerId = event.getPointerId(0);
                startX = event.getX();
                startY = event.getY();
                // The inner half of each control faces the center of the watch.
                cancelled = direction < 0 ? startX < getWidth() * .45f : startX > getWidth() * .55f;
                progress = 0;
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            if (!tracking) return false;
            if (type == MotionEvent.ACTION_POINTER_DOWN || type == MotionEvent.ACTION_POINTER_UP
                    || event.getPointerCount() != 1) cancelled = true;
            int index = event.findPointerIndex(pointerId);
            if (index < 0 || !isEnabled()) cancelled = true;
            if (!cancelled && index >= 0) {
                float dx = direction * (event.getX(index) - startX);
                float dy = Math.abs(event.getY(index) - startY);
                if (dy > 32 * getResources().getDisplayMetrics().density) cancelled = true;
                progress = cancelled ? 0 : Math.max(0, Math.min(1, dx / requiredDistance()));
                invalidate();
            } else {
                progress = 0;
                invalidate();
            }
            if (type == MotionEvent.ACTION_UP) {
                boolean completed = !cancelled && progress >= 1;
                resetGesture();
                if (completed) activate();
            } else if (type == MotionEvent.ACTION_CANCEL) {
                resetGesture();
            }
            return true;
        }

        private float requiredDistance() {
            return Math.max(1, Math.min(getWidth() * .65f,
                    Math.max(getWidth() * .42f, 18 * getResources().getDisplayMetrics().density)));
        }

        private void resetGesture() {
            tracking = false;
            cancelled = false;
            pointerId = -1;
            progress = 0;
            invalidate();
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }

        private boolean activate() {
            if (!isEnabled() || !isAttachedToWindow() || !hasWindowFocus() || action == null) return false;
            action.run();
            return true;
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
                    && !event.isCanceled()
                    && event.getEventTime() - keyDownTime >= ViewConfiguration.getLongPressTimeout();
            keyCode = -1;
            if (held) activate();
            return true;
        }

        // Ordinary taps, keyboard clicks and programmatic performClick do not dismiss an alarm.
        @Override public boolean performClick() { return false; }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (isEnabled()) {
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK, getContentDescription()));
            } else {
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            }
        }

        @Override public boolean performAccessibilityAction(int actionId, Bundle arguments) {
            if (actionId == AccessibilityNodeInfo.ACTION_CLICK) return activate();
            return super.performAccessibilityAction(actionId, arguments);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (progress > 0) {
                float width = getWidth() * progress;
                float left = direction < 0 ? getWidth() - width : 0;
                float radius = getHeight() / 2f;
                canvas.drawRoundRect(left, 0, left + width, getHeight(), radius, radius, progressPaint);
            }
            super.onDraw(canvas);
        }

        @Override protected void onDetachedFromWindow() {
            if (tracking) resetGesture();
            keyCode = -1;
            super.onDetachedFromWindow();
        }

        @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (!hasWindowFocus) {
                if (tracking) resetGesture();
                keyCode = -1;
            }
        }

        @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(focused, direction, previous);
            if (!focused) keyCode = -1;
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // Measure the actual child before layout, rather than changing its 1px
        // placeholder LayoutParams in onSizeChanged after it has been measured.
        int side = Math.max(0, (int) (Math.min(getMeasuredWidth(), getMeasuredHeight()) * .70f));
        // Bound every fixed row to the inscribed square, leaving at least 15%
        // for the weighted clock even when an important two-line status is shown.
        heading.getLayoutParams().height = Math.min(dp(38), (int) (side * .27f));
        status.getLayoutParams().height = Math.min(dp(30), (int) (side * .22f));
        buttons.getLayoutParams().height = Math.min(dp(52), (int) (side * .36f));
        int exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY);
        content.measure(exact, exact);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}