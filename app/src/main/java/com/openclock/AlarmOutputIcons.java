package com.openclock;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import org.json.JSONObject;

/** Saved alert settings, not the current ringing session or delivery state. */
final class AlarmOutputIcons {
    static void addTo(LinearLayout line, JSONObject alarm, int color, int sizeDp) {
        if (alarm.optBoolean("sound")) add(line, R.drawable.ic_alarm_sound, color, sizeDp);
        if (alarm.optBoolean("vibrate")) add(line, R.drawable.ic_alarm_vibrate, color, sizeDp);
    }

    static String description(JSONObject alarm) {
        return ", sound " + (alarm.optBoolean("sound") ? "on" : "off")
                + ", vibration " + (alarm.optBoolean("vibrate") ? "on" : "off");
    }

    private static void add(LinearLayout line, int resource, int color, int sizeDp) {
        Context context = line.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        ImageView icon = new ImageView(context);
        icon.setImageResource(resource);
        icon.setImageTintList(ColorStateList.valueOf(color));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Math.round(sizeDp * density), Math.round(sizeDp * density));
        params.gravity = Gravity.CENTER_VERTICAL;
        params.setMarginStart(Math.round(4 * density));
        line.addView(icon, params);
    }

    private AlarmOutputIcons() { }
}
