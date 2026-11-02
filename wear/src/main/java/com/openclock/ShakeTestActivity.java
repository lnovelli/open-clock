package com.openclock;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.wear.widget.DismissibleFrameLayout;
import androidx.wear.widget.SwipeDismissFrameLayout;
import java.util.Locale;

/** A foreground-only diagnostic; it never sends alarm actions. */
public final class ShakeTestActivity extends Activity {
    private WatchShakeBridge session;
    private TextView status, count, peaksView;
    private final ShakePeaks peaks = new ShakePeaks();
    private long lastDisplayAt;
    private boolean resumed;
    private int detections;

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER);
        return view;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // The system honors this only while the diagnostic window is visible.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (state != null) detections = state.getInt("detections", 0);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL); content.setGravity(Gravity.CENTER);
        content.setPadding(dp(24), dp(18), dp(24), dp(18)); content.setBackgroundColor(Color.BLACK);
        TextView heading = label("Shake test", 16, 0xfff3f3f5);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        status = label("Preparing…", 13, 0xffa7bfff);
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));
        count = label("Detected: " + detections, 13, 0xffbec0c8);
        content.addView(count, new LinearLayout.LayoutParams(-1, -2));
        peaksView = label("Peak X / Y / Z (°/s)\n— / — / —\nMax total: —\nAt max X / Y / Z\n— / — / —", 12, 0xfff3f3f5);
        content.addView(peaksView, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button reset = new Button(this); reset.setText("Reset"); reset.setAllCaps(false); reset.setTextSize(13);
        reset.setTextColor(0xfff3f3f5); reset.setMinHeight(dp(48)); reset.setMinWidth(0); reset.setPadding(0, 0, 0, 0);
        GradientDrawable resetBackground = new GradientDrawable(); resetBackground.setColor(0xff3d465d); resetBackground.setCornerRadius(dp(28));
        reset.setBackground(resetBackground);
        reset.setOnClickListener(v -> { peaks.reset(); lastDisplayAt = 0; showPeaks(); });
        actions.addView(reset, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button done = new Button(this); done.setText("Done"); done.setAllCaps(false); done.setTextSize(14);
        done.setTextColor(0xfff3f3f5); done.setMinHeight(dp(48)); done.setMinWidth(0); done.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable(); background.setColor(0xff3d465d); background.setCornerRadius(dp(28));
        done.setBackground(background); done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams doneButton = new LinearLayout.LayoutParams(0, dp(48), 1); doneButton.leftMargin = dp(4);
        actions.addView(done, doneButton);
        LinearLayout.LayoutParams buttons = new LinearLayout.LayoutParams(-1, -2); buttons.topMargin = dp(6);
        content.addView(actions, buttons);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.addView(content);
        SwipeDismissFrameLayout root = new SwipeDismissFrameLayout(this);
        root.setSwipeable(true); root.setBackButtonDismissible(false); root.addView(scroll);
        root.registerCallback(new DismissibleFrameLayout.Callback() {
            @Override public void onDismissFinished(DismissibleFrameLayout layout) { finish(); }
        });
        setContentView(root);
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true; status.setText("Preparing…");
        session = WatchShakeBridge.startTest(this, new WatchShakeBridge.TestListener() {
            @Override public void event(String event) { onEvent(event); }
            @Override public void gyro(float x, float y, float z) { onGyro(x, y, z); }
        });
    }
    private void onGyro(float x, float y, float z) {
        if (!resumed) return;
        peaks.sample(x, y, z);
        long now = SystemClock.elapsedRealtime();
        // Accumulate every sample, but avoid reformatting/layout at sensor frequency.
        if (lastDisplayAt == 0 || now - lastDisplayAt >= 200) {
            lastDisplayAt = now;
            showPeaks();
        }
    }
    private void showPeaks() {
        if (!peaks.hasSample()) {
            peaksView.setText("Peak X / Y / Z (°/s)\n— / — / —\nMax total: —\nAt max X / Y / Z\n— / — / —");
            return;
        }
        peaksView.setText(String.format(Locale.ROOT,
                "Peak X / Y / Z (°/s)\n%.0f / %.0f / %.0f\nMax total: %.0f°/s\nAt max X / Y / Z\n%+.0f / %+.0f / %+.0f",
                peaks.xPeakDegrees(), peaks.yPeakDegrees(), peaks.zPeakDegrees(), peaks.magnitudeDegrees(),
                peaks.vectorXDegrees(), peaks.vectorYDegrees(), peaks.vectorZDegrees()));
    }
    private void onEvent(String event) {
        if (!resumed) return;
        switch (event) {
            case "ready": status.setText("Ready\nShake twice"); break;
            case "arming": status.setText("Preparing…"); break;
            case "first": status.setText("First shake detected\nShake again"); break;
            case "timeout": status.setText("Timed out\nTry again"); break;
            case "detected":
                if (detections < Integer.MAX_VALUE) detections++;
                count.setText("Detected: " + detections);
                status.setText("Double shake\ndetected!");
                break;
            case "unavailable": status.setText("Sensors unavailable"); break;
            default: break;
        }
    }
    @Override protected void onPause() {
        resumed = false;
        if (session != null) { session.stopTest(); session = null; }
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putInt("detections", detections);
    }
}
