package com.openclock;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Alarm-only interface. Persistence and scheduling are exclusively owned by AlarmStore. */
public class MainActivity extends Activity {
    private static final int BG = 0xfff0f3f9, WHITE = 0xffffffff, INK = 0xff202635;
    private static final int MUTED = 0xff737d90, BLUE = 0xff365ee9, SOFT = 0xffe6ecff;
    private static final int OFF = 0xffa4acba, RED = 0xffba354a;
    private LinearLayout root, body;
    private ScrollView scroller;
    private String screen = "home";
    private JSONObject draft;
    private NumberPicker hourPicker, minutePicker;
    private EditText labelInput;
    private Switch enabledSwitch, soundSwitch, vibrationSwitch, snoozeSwitch;
    private TextView dateValue, repeatNote, groupValue, snoozeValue, countValue, vibrationValue;
    private final TextView[] dayChips = new TextView[7];
    private final Set<Long> collapsed = new HashSet<>();
    private long groupEditorId = -1;
    private EditText groupName;
    private final ArrayList<CheckBox> memberChecks = new ArrayList<>();
    private final ArrayList<Long> memberIds = new ArrayList<>();
    private String lastPermissionState;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        if (state != null) {
            long[] closed = state.getLongArray("collapsed");
            if (closed != null) for (long id : closed) collapsed.add(id);
            String previous = state.getString("screen", "home");
            try {
                if ("alarm".equals(previous)) {
                    draft = new JSONObject(state.getString("draft", "{}"));
                    showAlarmEditor();
                    return;
                }
                if ("group".equals(previous)) {
                    showGroupEditor(state.getLong("groupId", -1));
                    groupName.setText(state.getString("groupName", ""));
                    long[] members = state.getLongArray("members");
                    Set<Long> checked = new HashSet<>();
                    if (members != null) for (long id : members) checked.add(id);
                    for (int i = 0; i < memberChecks.size(); i++) memberChecks.get(i).setChecked(checked.contains(memberIds.get(i)));
                    return;
                }
                if ("permissions".equals(previous)) { showPermissions(); return; }
            } catch (Exception e) { /* Fall back to the list if saved editor state is invalid. */ }
        }
        showHome();
    }

    @Override protected void onResume() {
        super.onResume();
        String permissionState = exactAllowed() + ":" + notificationsAllowed() + ":" + fullScreenAllowed();
        if (!permissionState.equals(lastPermissionState)) {
            // Reconcile restored/revoked access, including expired dated alarms, without
            // rebuilding an unsaved editor or silently enabling disabled alarms.
            AlarmStore.rescheduleAll(this);
            lastPermissionState = permissionState;
        }
        // Never discard an editor when returning from a system permission page.
        if ("home".equals(screen)) showHome();
        else if ("permissions".equals(screen)) showPermissions();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("screen", screen);
        long[] closed = new long[collapsed.size()];
        int p = 0;
        for (Long id : collapsed) closed[p++] = id;
        state.putLongArray("collapsed", closed);
        if ("alarm".equals(screen) && draft != null) {
            captureAlarm();
            state.putString("draft", draft.toString());
        } else if ("group".equals(screen)) {
            state.putLong("groupId", groupEditorId);
            state.putString("groupName", groupName.getText().toString());
            ArrayList<Long> checked = new ArrayList<>();
            for (int i = 0; i < memberChecks.size(); i++) if (memberChecks.get(i).isChecked()) checked.add(memberIds.get(i));
            long[] ids = new long[checked.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = checked.get(i);
            state.putLongArray("members", ids);
        }
    }

    private void base(String mode) {
        screen = mode;
        root = column();
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top = bars.top; bottom = Math.max(bars.bottom, ime.bottom); left = bars.left; right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
    }

    private void scrollBody() {
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.setVerticalScrollBarEnabled(false);
        body = column();
        body.setPadding(dp(20), dp(8), dp(20), dp(28));
        scroller.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void header(String title, boolean back, View.OnClickListener add) {
        LinearLayout row = row();
        row.setPadding(dp(back ? 8 : 24), dp(14), dp(12), dp(10));
        if (back) row.addView(icon("‹", "Back", v -> leaveEditor()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = text(title, back ? 25 : 39, INK);
        heading.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (add != null) row.addView(icon("+", "Create alarm", add), new LinearLayout.LayoutParams(dp(48), dp(48)));
        if (!back) {
            TextView menu = icon("⋮", "More options", null);
            menu.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(this, menu);
                popup.getMenu().add("Create group").setOnMenuItemClickListener(item -> { showGroupEditor(-1); return true; });
                popup.getMenu().add("Alarm permissions").setOnMenuItemClickListener(item -> { showPermissions(); return true; });
                popup.show();
            });
            row.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        root.addView(row);
    }

    private void showHome() {
        base("home");
        header("Alarm", false, v -> newAlarm());
        scrollBody();
        if (!exactAllowed() || !notificationsAllowed() || !fullScreenAllowed()) {
            LinearLayout note = card(SOFT, 20);
            TextView title = text(!exactAllowed() ? "Allow precise alarms" : !notificationsAllowed() ? "Allow alarm notifications" : "Show alarms on the lock screen", 16, BLUE);
            title.setTypeface(null, Typeface.BOLD);
            note.addView(title);
            TextView detail = text("Review permissions so your alarms can ring and appear reliably.  ›", 13, MUTED);
            detail.setPadding(0, dp(5), 0, 0);
            note.addView(detail);
            note.setOnClickListener(v -> showPermissions());
            addBlock(body, note, 16);
        }
        JSONArray alarms = AlarmStore.alarms(this), groups = AlarmStore.groups(this);
        TextView overview = text(alarms.length() == 0 ? "A little peace of mind." : enabledCount(alarms) + " enabled · " + alarms.length() + " total", 14, MUTED);
        overview.setPadding(dp(4), dp(2), 0, dp(18));
        body.addView(overview);
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            long id = group.optLong("id");
            ArrayList<JSONObject> members = new ArrayList<>();
            for (int j = 0; j < alarms.length(); j++) {
                JSONObject a = alarms.optJSONObject(j);
                if (a != null && !a.isNull("groupId") && a.optLong("groupId") == id) members.add(a);
            }
            LinearLayout container = card(0xffe8edf7, 18);
            LinearLayout heading = row();
            LinearLayout names = column();
            TextView name = text(group.optString("name", "Group"), 20, INK);
            name.setTypeface(null, Typeface.BOLD);
            names.addView(name);
            int groupEnabled = 0;
            for (JSONObject member : members) if (member.optBoolean("enabled")) groupEnabled++;
            TextView count = text(groupEnabled + " on · " + (members.size() - groupEnabled) + " off", 13, MUTED);
            count.setPadding(0, dp(4), 0, 0);
            names.addView(count);
            heading.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            TextView fold = icon(collapsed.contains(id) ? "⌄" : "⌃", "Expand or collapse " + group.optString("name"), v -> {
                if (!collapsed.remove(id)) collapsed.add(id);
                showHome();
            });
            heading.addView(fold, new LinearLayout.LayoutParams(dp(48), dp(48)));
            heading.addView(icon("⋮", "Edit " + group.optString("name"), v -> groupMenu(v, group)), new LinearLayout.LayoutParams(dp(48), dp(48)));
            container.addView(heading);
            names.setOnClickListener(v -> { if (!collapsed.remove(id)) collapsed.add(id); showHome(); });
            if (!collapsed.contains(id)) {
                for (JSONObject alarm : members) addBlock(container, alarmCard(alarm), 0, 12);
                if (members.isEmpty()) {
                    TextView empty = text("No alarms yet · Add or assign an alarm", 14, MUTED);
                    empty.setPadding(0, dp(18), 0, dp(6));
                    empty.setOnClickListener(v -> showGroupEditor(id));
                    container.addView(empty);
                }
            }
            addBlock(body, container, 16);
        }
        Set<Long> groupIds = new HashSet<>();
        for (int i = 0; i < groups.length(); i++) if (groups.optJSONObject(i) != null) groupIds.add(groups.optJSONObject(i).optLong("id"));
        boolean ungroupedHeading = false;
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject alarm = alarms.optJSONObject(i);
            if (alarm == null) continue;
            if (!alarm.isNull("groupId") && groupIds.contains(alarm.optLong("groupId"))) continue;
            if (groups.length() > 0 && !ungroupedHeading) {
                TextView heading = text("Other alarms", 15, MUTED);
                heading.setPadding(dp(4), dp(6), 0, dp(14));
                body.addView(heading);
                ungroupedHeading = true;
            }
            addBlock(body, alarmCard(alarm), 14);
        }
        if (alarms.length() == 0 && groups.length() == 0) {
            LinearLayout empty = card(WHITE, 28);
            TextView mark = text("◷", 62, BLUE);
            mark.setGravity(Gravity.CENTER);
            empty.addView(mark);
            TextView title = text("Your next fresh start", 23, INK);
            title.setGravity(Gravity.CENTER);
            title.setTypeface(null, Typeface.BOLD);
            empty.addView(title);
            TextView sub = text("Create an alarm, choose your days,\nand leave the rest to your clock.", 15, MUTED);
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, dp(12), 0, dp(24));
            empty.addView(sub);
            empty.addView(button("Add alarm", true, v -> newAlarm()));
            body.addView(empty);
        }
        TextView addGroup = text("+  Create group", 15, BLUE);
        addGroup.setGravity(Gravity.CENTER);
        addGroup.setMinHeight(dp(56));
        addGroup.setOnClickListener(v -> showGroupEditor(-1));
        body.addView(addGroup);
    }

    private View alarmCard(JSONObject alarm) {
        boolean enabled = alarm.optBoolean("enabled", true);
        LinearLayout card = card(WHITE, 21);
        LinearLayout top = row();
        LinearLayout info = column();
        TextView time = text(String.format(Locale.getDefault(), "%02d:%02d", alarm.optInt("hour"), alarm.optInt("minute")), 49, enabled ? INK : OFF);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setFontFeatureSettings("tnum");
        time.setMaxLines(1);
        time.setAutoSizeTextTypeUniformWithConfiguration(26, 49, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        info.addView(time);
        String label = alarm.optString("label", "").trim();
        if (!label.isEmpty()) {
            TextView labelView = text(label, 16, enabled ? INK : OFF);
            labelView.setPadding(dp(2), dp(2), 0, dp(3));
            info.addView(labelView);
        }
        TextView schedule = text(scheduleText(alarm), 13, enabled ? MUTED : OFF);
        schedule.setPadding(dp(2), dp(5), dp(8), dp(2));
        info.addView(schedule);
        if (!alarm.isNull("schedulingError") && !alarm.optString("schedulingError", "").isEmpty()) {
            TextView error = text(alarm.optString("schedulingError"), 12, RED);
            error.setPadding(dp(2), dp(8), dp(8), 0);
            info.addView(error);
        }
        info.setContentDescription("Edit alarm " + time.getText() + ", " + schedule.getText());
        info.setOnClickListener(v -> editAlarm(alarm));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = makeSwitch(enabled);
        toggle.setContentDescription("Enable alarm " + time.getText());
        final boolean[] resetting = {false};
        toggle.setOnCheckedChangeListener((view, checked) -> {
            if (resetting[0]) return;
            try {
                JSONObject changed = cloneObject(alarm);
                changed.put("enabled", checked);
                if (checked && !canEnable(changed)) {
                    resetting[0] = true;
                    view.setChecked(false);
                    resetting[0] = false;
                    return;
                }
                AlarmStore.saveAlarm(this, changed);
                showHome();
            } catch (Exception e) {
                view.setOnCheckedChangeListener(null);
                view.setChecked(enabled);
                failure("Could not change alarm", e);
                showHome();
            }
        });
        top.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(52)));
        card.addView(top);
        card.setOnClickListener(v -> editAlarm(alarm));
        card.setOnLongClickListener(v -> { alarmMenu(alarm); return true; });
        return card;
    }

    private void alarmMenu(JSONObject alarm) {
        new AlertDialog.Builder(this).setTitle("Alarm " + timeString(alarm)).setItems(new String[]{"Edit alarm", "Delete alarm"}, (d, which) -> {
            if (which == 0) editAlarm(alarm); else confirmDeleteAlarm(alarm);
        }).show();
    }

    private void newAlarm() {
        Calendar next = Calendar.getInstance();
        next.add(Calendar.HOUR_OF_DAY, 1);
        draft = new JSONObject();
        try {
            draft.put("hour", next.get(Calendar.HOUR_OF_DAY)); draft.put("minute", 0);
            draft.put("date", JSONObject.NULL); draft.put("days", new JSONArray());
            draft.put("label", ""); draft.put("enabled", true); draft.put("sound", true);
            draft.put("vibrate", true); draft.put("snoozeEnabled", true);
            draft.put("snoozeMinutes", 5); draft.put("snoozeCount", 3); draft.put("groupId", JSONObject.NULL);
        } catch (Exception ignored) { }
        showAlarmEditor();
    }

    private void editAlarm(JSONObject alarm) {
        try { draft = cloneObject(alarm); showAlarmEditor(); }
        catch (Exception e) { failure("Could not open alarm", e); }
    }

    private void showAlarmEditor() {
        base("alarm");
        header(draft.has("id") ? "Edit alarm" : "New alarm", true, null);
        scrollBody();
        TextView timeHint = text("24-HOUR TIME", 12, MUTED);
        timeHint.setGravity(Gravity.CENTER);
        timeHint.setLetterSpacing(0.13f);
        timeHint.setPadding(0, dp(8), 0, dp(4));
        body.addView(timeHint);
        LinearLayout clock = row();
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(dp(14), 0, dp(14), dp(20));
        hourPicker = numberPicker(0, 23, draft.optInt("hour", 7), true);
        minutePicker = numberPicker(0, 59, draft.optInt("minute"), true);
        hourPicker.setContentDescription("Hour, 24-hour time");
        minutePicker.setContentDescription("Minute");
        clock.addView(hourPicker, new LinearLayout.LayoutParams(0, dp(164), 1));
        TextView colon = text(":", 34, INK);
        clock.addView(colon, new LinearLayout.LayoutParams(dp(24), -2));
        clock.addView(minutePicker, new LinearLayout.LayoutParams(0, dp(164), 1));
        body.addView(clock);

        LinearLayout options = card(WHITE, 20);
        LinearLayout dateRow = optionRow("Date", "");
        dateValue = (TextView) ((LinearLayout) dateRow.getChildAt(0)).getChildAt(1);
        dateRow.setOnClickListener(v -> chooseDate());
        options.addView(dateRow);
        LinearLayout days = row();
        days.setPadding(0, dp(8), 0, dp(4));
        String[] dayNames = new DateFormatSymbols().getShortWeekdays();
        for (int i = 0; i < 7; i++) {
            final int day = i + 1;
            TextView chip = text(dayNames[day].substring(0, 1), 14, MUTED);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(dp(48));
            chip.setContentDescription(new DateFormatSymbols().getWeekdays()[day]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            params.setMargins(dp(2), 0, dp(2), 0);
            days.addView(chip, params);
            dayChips[i] = chip;
            chip.setOnClickListener(v -> toggleDay(day));
        }
        options.addView(days);
        repeatNote = text("", 12, MUTED);
        repeatNote.setPadding(0, dp(5), 0, dp(14));
        options.addView(repeatNote);
        divider(options);
        TextView labelTitle = text("Label", 14, MUTED);
        labelTitle.setPadding(0, dp(16), 0, 0);
        options.addView(labelTitle);
        labelInput = new EditText(this);
        labelInput.setTextColor(INK);
        labelInput.setTextSize(17);
        labelInput.setSingleLine(true);
        labelInput.setHint("Alarm name");
        labelInput.setHintTextColor(OFF);
        labelInput.setPadding(0, dp(8), 0, dp(12));
        labelInput.setBackgroundColor(Color.TRANSPARENT);
        labelInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        labelInput.setText(draft.optString("label", ""));
        options.addView(labelInput, new LinearLayout.LayoutParams(-1, dp(52)));
        divider(options);
        enabledSwitch = addSwitchOption(options, "Alarm enabled", "Turn off to keep without scheduling", draft.optBoolean("enabled", true));
        divider(options);
        soundSwitch = addSwitchOption(options, "Alarm sound", "Standard system alarm tone", draft.optBoolean("sound", true));
        divider(options);
        vibrationSwitch = addSwitchOption(options, "Vibration", "Vibrate when the alarm rings", draft.optBoolean("vibrate", true));
        LinearLayout vibrationRow = optionRow("Vibration pattern", "");
        vibrationValue = (TextView) ((LinearLayout) vibrationRow.getChildAt(0)).getChildAt(1);
        vibrationRow.setOnClickListener(v -> chooseVibration());
        options.addView(vibrationRow);
        vibrationRow.setVisibility(vibrationSwitch.isChecked() ? View.VISIBLE : View.GONE);
        vibrationSwitch.setOnCheckedChangeListener((b, enabled) -> vibrationRow.setVisibility(enabled ? View.VISIBLE : View.GONE));
        divider(options);
        snoozeSwitch = addSwitchOption(options, "Snooze", "Pause the alarm and ring again", draft.optBoolean("snoozeEnabled", true));
        LinearLayout intervalRow = optionRow("Snooze interval", "");
        snoozeValue = (TextView) ((LinearLayout) intervalRow.getChildAt(0)).getChildAt(1);
        intervalRow.setOnClickListener(v -> chooseNumber("Snooze interval (minutes)", 1, 1440, draft.optInt("snoozeMinutes", 5), false));
        options.addView(intervalRow);
        LinearLayout countRow = optionRow("Snooze limit", "");
        countValue = (TextView) ((LinearLayout) countRow.getChildAt(0)).getChildAt(1);
        countRow.setOnClickListener(v -> chooseNumber("Snooze limit", 0, 1000, draft.optInt("snoozeCount", 3), true));
        options.addView(countRow);
        snoozeSwitch.setOnCheckedChangeListener((b, enabled) -> {
            intervalRow.setVisibility(enabled ? View.VISIBLE : View.GONE);
            countRow.setVisibility(enabled ? View.VISIBLE : View.GONE);
        });
        intervalRow.setVisibility(snoozeSwitch.isChecked() ? View.VISIBLE : View.GONE);
        countRow.setVisibility(snoozeSwitch.isChecked() ? View.VISIBLE : View.GONE);
        divider(options);
        LinearLayout groupRow = optionRow("Group", "");
        groupValue = (TextView) ((LinearLayout) groupRow.getChildAt(0)).getChildAt(1);
        groupRow.setOnClickListener(v -> chooseGroup());
        options.addView(groupRow);
        addBlock(body, options, 12);
        TextView policy = text("Choose a date for a one-time alarm, or weekdays for a weekly alarm. With neither selected, it rings once at the next occurrence.", 12, MUTED);
        policy.setPadding(dp(8), dp(8), dp(8), dp(8));
        body.addView(policy);
        if (draft.has("id")) {
            TextView delete = button("Delete alarm", false, v -> confirmDeleteAlarm(draft));
            delete.setTextColor(RED);
            body.addView(delete);
        }
        bottomActions("Save", v -> saveAlarm());
        refreshEditorValues();
    }

    private void captureAlarm() {
        if (draft == null || hourPicker == null) return;
        // Clearing focus commits typed NumberPicker input before reading its value.
        hourPicker.clearFocus(); minutePicker.clearFocus();
        try {
            draft.put("hour", hourPicker.getValue()); draft.put("minute", minutePicker.getValue());
            draft.put("label", labelInput.getText().toString().trim());
            draft.put("enabled", enabledSwitch.isChecked()); draft.put("sound", soundSwitch.isChecked());
            draft.put("vibrate", vibrationSwitch.isChecked()); draft.put("snoozeEnabled", snoozeSwitch.isChecked());
        } catch (Exception ignored) { }
    }

    private void refreshEditorValues() {
        String date = draft.optString("date", "");
        if (draft.isNull("date") || date.isEmpty()) dateValue.setText("Next occurrence");
        else {
            try { dateValue.setText(new SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(isoFormat().parse(date))); }
            catch (Exception e) { dateValue.setText(date); }
        }
        JSONArray selected = draft.optJSONArray("days");
        for (int i = 0; i < 7; i++) {
            boolean active = containsDay(selected, i + 1);
            dayChips[i].setTextColor(active ? WHITE : MUTED);
            dayChips[i].setBackground(round(active ? BLUE : BG, 24));
            dayChips[i].setSelected(active);
            dayChips[i].setContentDescription(new DateFormatSymbols().getWeekdays()[i + 1] + (active ? ", selected" : ", not selected"));
        }
        repeatNote.setText(selected != null && selected.length() > 0 ? "Repeats every week · Date cleared" : "One-time alarm · Select days to repeat");
        snoozeValue.setText(draft.optInt("snoozeMinutes", 5) + " minutes");
        int count = draft.optInt("snoozeCount", 3);
        countValue.setText(count == 0 ? "Unlimited" : count + (count == 1 ? " time" : " times"));
        groupValue.setText(groupLabel(draft));
        String pattern = draft.optString("vibrationPattern", "basic");
        vibrationValue.setText("pulse".equals(pattern) ? "Pulse" : "heartbeat".equals(pattern) ? "Heartbeat" : "Basic");
    }

    private void chooseVibration() {
        final String[] values = {"basic", "pulse", "heartbeat"};
        final String[] labels = {"Basic", "Pulse", "Heartbeat"};
        String selected = draft.optString("vibrationPattern", "basic");
        int checked = "pulse".equals(selected) ? 1 : "heartbeat".equals(selected) ? 2 : 0;
        new AlertDialog.Builder(this).setTitle("Vibration pattern")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    try { draft.put("vibrationPattern", values[which]); }
                    catch (Exception e) { failure("Could not change vibration", e); }
                    refreshEditorValues();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void toggleDay(int day) {
        JSONArray previous = draft.optJSONArray("days"), next = new JSONArray();
        boolean remove = containsDay(previous, day);
        for (int i = 1; i <= 7; i++) if (i == day ? !remove : containsDay(previous, i)) next.put(i);
        try {
            draft.put("days", next);
            if (next.length() > 0) draft.put("date", JSONObject.NULL);
        } catch (Exception ignored) { }
        refreshEditorValues();
    }

    private void chooseDate() {
        Calendar initial = Calendar.getInstance();
        try { if (!draft.isNull("date")) initial.setTime(isoFormat().parse(draft.optString("date"))); }
        catch (Exception ignored) { }
        DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, day, 0, 0, 0); selected.set(Calendar.MILLISECOND, 0);
            try { draft.put("date", isoFormat().format(selected.getTime())); draft.put("days", new JSONArray()); }
            catch (Exception ignored) { }
            refreshEditorValues();
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0); today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0);
        picker.getDatePicker().setMinDate(today.getTimeInMillis());
        picker.setButton(AlertDialog.BUTTON_NEUTRAL, "Clear date", (d, which) -> {
            try { draft.put("date", JSONObject.NULL); } catch (Exception ignored) { }
            refreshEditorValues();
        });
        picker.show();
    }

    private void chooseNumber(String title, int min, int max, int selected, boolean count) {
        NumberPicker picker = numberPicker(min, Math.max(max, selected), selected, false);
        if (count) {
            String[] labels = new String[Math.max(max, selected) + 1];
            for (int i = 0; i < labels.length; i++) labels[i] = i == 0 ? "Unlimited" : String.valueOf(i);
            picker.setDisplayedValues(labels);
        }
        LinearLayout wrap = column();
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(dp(24), dp(8), dp(24), dp(8));
        wrap.addView(picker, new LinearLayout.LayoutParams(dp(180), dp(180)));
        new AlertDialog.Builder(this).setTitle(title).setView(wrap).setNegativeButton("Cancel", null).setPositiveButton("Set", (d, which) -> {
            picker.clearFocus();
            try { draft.put(count ? "snoozeCount" : "snoozeMinutes", picker.getValue()); } catch (Exception ignored) { }
            refreshEditorValues();
        }).show();
    }

    private void chooseGroup() {
        JSONArray groups = AlarmStore.groups(this);
        String[] names = new String[groups.length() + 1];
        names[0] = "No group";
        int selected = 0;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            names[i + 1] = g == null ? "Group" : g.optString("name");
            if (g != null && !draft.isNull("groupId") && g.optLong("id") == draft.optLong("groupId")) selected = i + 1;
        }
        new AlertDialog.Builder(this).setTitle("Assign to group").setSingleChoiceItems(names, selected, (d, which) -> {
            try { draft.put("groupId", which == 0 ? JSONObject.NULL : groups.getJSONObject(which - 1).getLong("id")); }
            catch (Exception e) { failure("Could not assign group", e); }
            refreshEditorValues(); d.dismiss();
        }).setNegativeButton("Cancel", null).show();
    }

    private void saveAlarm() {
        captureAlarm();
        if (draft.optBoolean("enabled") && !canEnable(draft)) return;
        if (draft.optBoolean("enabled") && !draft.optBoolean("sound") && !draft.optBoolean("vibrate")) {
            new AlertDialog.Builder(this).setTitle("Silent alarm?").setMessage("Sound and vibration are both off. This alarm will only show on screen.")
                    .setNegativeButton("Review", null).setPositiveButton("Save silent alarm", (d, w) -> persistAlarm()).show();
            return;
        }
        persistAlarm();
    }

    private void persistAlarm() {
        try {
            AlarmStore.saveAlarm(this, draft);
            hideKeyboard();
            showHome();
            toast("Alarm saved");
        } catch (Exception e) { failure("Could not save alarm", e); }
    }

    private boolean canEnable(JSONObject alarm) {
        if (!alarm.isNull("date") && !alarm.optString("date", "").isEmpty()) {
            try {
                Calendar when = Calendar.getInstance();
                when.setTime(isoFormat().parse(alarm.getString("date")));
                when.set(Calendar.HOUR_OF_DAY, alarm.optInt("hour")); when.set(Calendar.MINUTE, alarm.optInt("minute"));
                when.set(Calendar.SECOND, 0); when.set(Calendar.MILLISECOND, 0);
                if (when.getTimeInMillis() <= System.currentTimeMillis()) {
                    notice("This date has passed", "Choose a future date or clear the date to use the next occurrence. You can also save this alarm switched off.");
                    return false;
                }
            } catch (Exception e) { notice("Invalid date", "Choose the alarm date again."); return false; }
        }
        if (!exactAllowed()) {
            new AlertDialog.Builder(this).setTitle("Allow precise alarms").setMessage("Android requires Alarms & reminders access to schedule this alarm reliably. Allow it in Settings, then return and save or enable the alarm again. Your draft will stay here.")
                    .setNegativeButton("Not now", null).setPositiveButton("Open settings", (d, w) -> openExactSettings()).show();
            return false;
        }
        if (!notificationsAllowed()) {
            new AlertDialog.Builder(this).setTitle("Allow alarm notifications").setMessage("Notifications are required to show the ringing alarm and its snooze/dismiss controls. Allow them, then save or enable the alarm again.")
                    .setNegativeButton("Not now", null).setPositiveButton("Allow", (d, w) -> requestNotifications()).show();
            return false;
        }
        return true;
    }

    private void confirmDeleteAlarm(JSONObject alarm) {
        new AlertDialog.Builder(this).setTitle("Delete alarm?").setMessage(timeString(alarm) + " will be removed and cancelled.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
                    try { AlarmStore.deleteAlarm(this, alarm.getLong("id")); hideKeyboard(); showHome(); }
                    catch (Exception e) { failure("Could not delete alarm", e); }
                }).show();
    }

    private void groupMenu(View anchor, JSONObject group) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("Edit group & alarms").setOnMenuItemClickListener(item -> { showGroupEditor(group.optLong("id")); return true; });
        popup.getMenu().add("Enable all alarms").setOnMenuItemClickListener(item -> { setGroupEnabled(group.optLong("id"), true); return true; });
        popup.getMenu().add("Disable all alarms").setOnMenuItemClickListener(item -> { setGroupEnabled(group.optLong("id"), false); return true; });
        popup.getMenu().add("Delete group").setOnMenuItemClickListener(item -> { deleteGroup(group); return true; });
        popup.show();
    }

    private void setGroupEnabled(long groupId, boolean enabled) {
        try {
            JSONArray alarms = AlarmStore.alarms(this);
            ArrayList<JSONObject> changes = new ArrayList<>();
            for (int i = 0; i < alarms.length(); i++) {
                JSONObject alarm = alarms.getJSONObject(i);
                if (alarm.isNull("groupId") || alarm.optLong("groupId") != groupId || alarm.optBoolean("enabled") == enabled) continue;
                JSONObject changed = cloneObject(alarm);
                changed.put("enabled", enabled);
                if (enabled && !canEnable(changed)) return;
                changes.add(changed);
            }
            for (JSONObject changed : changes) AlarmStore.saveAlarm(this, changed);
            showHome();
            toast(changes.isEmpty() ? "No alarms to change" : (enabled ? "Group alarms enabled" : "Group alarms disabled"));
        } catch (Exception e) {
            showHome();
            failure("Some group alarms may not have changed. Review the list", e);
        }
    }

    private void showGroupEditor(long id) {
        groupEditorId = id;
        base("group");
        header(id < 0 ? "New group" : "Edit group", true, null);
        scrollBody();
        JSONObject existing = null;
        JSONArray groups = AlarmStore.groups(this);
        for (int i = 0; i < groups.length(); i++) if (groups.optJSONObject(i) != null && groups.optJSONObject(i).optLong("id") == id) existing = groups.optJSONObject(i);
        LinearLayout card = card(WHITE, 22);
        TextView title = text("Group name", 14, MUTED);
        card.addView(title);
        groupName = new EditText(this);
        groupName.setTextSize(22); groupName.setTextColor(INK); groupName.setSingleLine();
        groupName.setHint("e.g. Workdays"); groupName.setHintTextColor(OFF);
        groupName.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        if (existing != null) groupName.setText(existing.optString("name"));
        card.addView(groupName, new LinearLayout.LayoutParams(-1, dp(60)));
        addBlock(body, card, 20);
        TextView heading = text("Alarms in this group", 20, INK);
        heading.setTypeface(null, Typeface.BOLD);
        body.addView(heading);
        TextView detail = text("Selected alarms move into this group. Deselect an alarm to keep it without a group.", 13, MUTED);
        detail.setPadding(0, dp(8), 0, dp(16));
        body.addView(detail);
        memberChecks.clear(); memberIds.clear();
        JSONArray alarms = AlarmStore.alarms(this);
        LinearLayout choices = card(WHITE, 12);
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject alarm = alarms.optJSONObject(i);
            if (alarm == null) continue;
            CheckBox check = new CheckBox(this);
            check.setTextColor(INK); check.setTextSize(16); check.setMinHeight(dp(68));
            check.setButtonTintList(tint());
            String label = alarm.optString("label", "");
            check.setText(timeString(alarm) + (label.isEmpty() ? "" : "  ·  " + label) + "\n" + groupLabel(alarm));
            check.setChecked(id >= 0 && !alarm.isNull("groupId") && alarm.optLong("groupId") == id);
            choices.addView(check, new LinearLayout.LayoutParams(-1, -2));
            memberChecks.add(check); memberIds.add(alarm.optLong("id"));
        }
        if (alarms.length() == 0) {
            TextView empty = text("Create your first alarm, then assign it here.", 14, MUTED);
            empty.setPadding(dp(8), dp(16), dp(8), dp(16)); choices.addView(empty);
        }
        addBlock(body, choices, 16);
        if (existing != null) {
            JSONObject target = existing;
            TextView delete = button("Delete group", false, v -> deleteGroup(target));
            delete.setTextColor(RED);
            body.addView(delete);
        }
        bottomActions("Save group", v -> saveGroup());
    }

    private void saveGroup() {
        String name = groupName.getText().toString().trim();
        if (name.isEmpty()) { groupName.setError("Enter a group name"); groupName.requestFocus(); return; }
        try {
            JSONObject group = new JSONObject();
            if (groupEditorId >= 0) group.put("id", groupEditorId);
            group.put("name", name);
            JSONObject saved = AlarmStore.saveGroup(this, group);
            // Retain the returned ID even if a subsequent membership update fails.
            groupEditorId = saved.getLong("id");
            JSONArray alarms = AlarmStore.alarms(this);
            Set<Long> selected = new HashSet<>();
            for (int i = 0; i < memberChecks.size(); i++) if (memberChecks.get(i).isChecked()) selected.add(memberIds.get(i));
            for (int i = 0; i < alarms.length(); i++) {
                JSONObject alarm = alarms.optJSONObject(i);
                if (alarm == null) continue;
                boolean wasMember = !alarm.isNull("groupId") && alarm.optLong("groupId") == groupEditorId;
                boolean shouldBeMember = selected.contains(alarm.optLong("id"));
                if (wasMember == shouldBeMember) continue;
                JSONObject updated = cloneObject(alarm);
                updated.put("groupId", shouldBeMember ? groupEditorId : JSONObject.NULL);
                AlarmStore.saveAlarm(this, updated);
            }
            hideKeyboard(); showHome(); toast("Group saved");
        } catch (Exception e) {
            failure("Group changes could not be completed. Some changes may already be saved; review and retry", e);
        }
    }

    private void deleteGroup(JSONObject group) {
        new AlertDialog.Builder(this).setTitle("Delete “" + group.optString("name") + "”?").setMessage("The group will be removed. Its alarms will stay, without a group.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete group", (d, w) -> {
                    try { AlarmStore.deleteGroup(this, group.getLong("id")); hideKeyboard(); showHome(); }
                    catch (Exception e) { failure("Could not delete group", e); }
                }).show();
    }

    private void showPermissions() {
        base("permissions");
        header("Alarm permissions", true, null);
        scrollBody();
        TextView intro = text("Keep your alarms ready", 25, INK);
        intro.setTypeface(null, Typeface.BOLD); body.addView(intro);
        TextView explanation = text("Android controls when alarms can ring and how they appear. Return here after changing a permission to see its current status.", 14, MUTED);
        explanation.setPadding(0, dp(12), 0, dp(22)); body.addView(explanation);
        permissionCard("Precise alarms", exactAllowed(), "Required on Android 12 and later to ring at the selected time, even while the phone is idle.", v -> openExactSettings());
        permissionCard("Notifications", notificationsAllowed(), "Shows the ringing alarm and its snooze/dismiss controls. Required on Android 13 and later.", v -> requestNotifications());
        permissionCard("Full-screen alarms", fullScreenAllowed(), "On Android 14 and later, allow full-screen intents to bring a ringing alarm over the lock screen. Without it, Android may show a notification instead.", v -> {
            if (Build.VERSION.SDK_INT >= 34) openSettings(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())));
            else openNotificationSettings();
        });
        TextView help = text("Also check the system alarm volume, notification channel, Do Not Disturb, and any manufacturer battery restrictions. Sound uses the standard system alarm tone; vibration depends on your device.", 13, MUTED);
        help.setPadding(dp(6), dp(12), dp(6), dp(12)); body.addView(help);
    }

    private void permissionCard(String title, boolean allowed, String detail, View.OnClickListener action) {
        LinearLayout card = card(WHITE, 22);
        TextView heading = text(title, 20, INK); heading.setTypeface(null, Typeface.BOLD); card.addView(heading);
        TextView status = text(allowed ? "Allowed" : "Action needed", 14, allowed ? 0xff27765a : RED);
        status.setPadding(0, dp(8), 0, dp(8)); card.addView(status);
        card.addView(text(detail, 14, MUTED));
        TextView button = button(allowed ? "Review settings" : "Allow in settings", !allowed, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50)); params.topMargin = dp(18); card.addView(button, params);
        addBlock(body, card, 16);
    }

    private boolean exactAllowed() {
        return Build.VERSION.SDK_INT < 31 || ((AlarmManager) getSystemService(ALARM_SERVICE)).canScheduleExactAlarms();
    }
    private boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT < 24 || ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).areNotificationsEnabled();
    }
    private boolean fullScreenAllowed() {
        return Build.VERSION.SDK_INT < 34 || ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).canUseFullScreenIntent();
    }
    private void openExactSettings() {
        if (Build.VERSION.SDK_INT >= 31) openSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
        else openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }
    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                new AlertDialog.Builder(this).setTitle("Show ringing alarms").setMessage("Allow notifications so you can see, snooze and dismiss alarms.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Allow", (d, w) -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41)).show();
            } else if (getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) openNotificationSettings();
            else {
                getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
            }
        } else openNotificationSettings();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 41) {
            if (!notificationsAllowed()) notice("Notifications are off", "Alarms cannot be enabled here until notifications are allowed. Open Alarm permissions to change this in Settings.");
            else toast("Notifications allowed. You can now save or enable your alarm.");
            if ("permissions".equals(screen)) showPermissions();
            else if ("home".equals(screen)) showHome();
        }
    }
    private void openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 26) openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        else openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }
    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (Exception again) { notice("Settings unavailable", "Open Android Settings → Apps → Open Clock → Permissions and notifications."); }
        }
    }

    @Override public void onBackPressed() {
        if ("home".equals(screen)) super.onBackPressed(); else leaveEditor();
    }
    private void leaveEditor() {
        if ("alarm".equals(screen) || "group".equals(screen)) {
            new AlertDialog.Builder(this).setTitle("Discard changes?").setMessage("Changes on this screen have not been saved.")
                    .setNegativeButton("Keep editing", null).setPositiveButton("Discard", (d, w) -> { hideKeyboard(); showHome(); }).show();
        } else showHome();
    }
    private void bottomActions(String save, View.OnClickListener action) {
        LinearLayout bar = row();
        bar.setPadding(dp(20), dp(10), dp(20), dp(14));
        TextView cancel = button("Cancel", false, v -> leaveEditor());
        TextView saveButton = button(save, true, action);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(52), 1); a.rightMargin = dp(12);
        bar.addView(cancel, a); bar.addView(saveButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(bar);
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout card(int color, int padding) {
        LinearLayout view = column(); view.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); view.setBackground(round(color, 26)); return view;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("kern"); view.setIncludeFontPadding(false); return view;
    }
    private TextView icon(String value, String description, View.OnClickListener action) {
        TextView view = text(value, 30, INK); view.setGravity(Gravity.CENTER); view.setContentDescription(description);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22365ee9), null, round(WHITE, 24)));
        if (action != null) view.setOnClickListener(action); return view;
    }
    private TextView button(String title, boolean primary, View.OnClickListener action) {
        TextView view = text(title, 16, primary ? WHITE : BLUE); view.setTypeface(null, Typeface.BOLD);
        view.setGravity(Gravity.CENTER); view.setMinHeight(dp(52)); view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33ffffff : 0x22365ee9), round(primary ? BLUE : SOFT, 18), null));
        view.setOnClickListener(action); return view;
    }
    private GradientDrawable round(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private ColorStateList tint() { return new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{BLUE, OFF}); }
    private Switch makeSwitch(boolean checked) {
        Switch view = new Switch(this); view.setChecked(checked); view.setShowText(false); view.setThumbTintList(tint());
        view.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{0xffaabcf9, 0xffdae0eb}));
        view.setMinHeight(dp(48)); view.setSwitchMinWidth(dp(46)); view.setGravity(Gravity.CENTER); return view;
    }
    private LinearLayout optionRow(String title, String subtitle) {
        LinearLayout line = row(); line.setMinimumHeight(dp(74)); line.setPadding(0, dp(12), 0, dp(12));
        LinearLayout labels = column(); TextView name = text(title, 17, INK); labels.addView(name);
        TextView value = text(subtitle, 13, MUTED); value.setPadding(0, dp(6), 0, 0); labels.addView(value);
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = text("›", 25, OFF); chevron.setGravity(Gravity.CENTER); line.addView(chevron, new LinearLayout.LayoutParams(dp(28), dp(48)));
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(0x15365ee9), null, round(WHITE, 10))); return line;
    }
    private Switch addSwitchOption(LinearLayout parent, String title, String detail, boolean checked) {
        LinearLayout line = row(); line.setPadding(0, dp(14), 0, dp(14)); line.setMinimumHeight(dp(82));
        LinearLayout labels = column(); labels.addView(text(title, 17, INK)); TextView sub = text(detail, 12, MUTED); sub.setPadding(0, dp(6), dp(10), 0); labels.addView(sub);
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = makeSwitch(checked); toggle.setContentDescription(title);
        line.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(48))); line.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        parent.addView(line); return toggle;
    }
    private NumberPicker numberPicker(int min, int max, int selected, boolean pad) {
        NumberPicker picker = new NumberPicker(this); picker.setMinValue(min); picker.setMaxValue(max); picker.setValue(Math.max(min, Math.min(max, selected)));
        picker.setWrapSelectorWheel(true);
        if (pad) picker.setFormatter(value -> String.format(Locale.getDefault(), "%02d", value));
        if (Build.VERSION.SDK_INT >= 29) { picker.setTextColor(INK); picker.setTextSize(dp(30)); }
        return picker;
    }
    private void divider(LinearLayout parent) { View line = new View(this); line.setBackgroundColor(BG); parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
    private void addBlock(LinearLayout parent, View child, int bottom) { addBlock(parent, child, bottom, 0); }
    private void addBlock(LinearLayout parent, View child, int bottom, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(bottom); params.topMargin = dp(top); parent.addView(child, params);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private JSONObject cloneObject(JSONObject original) throws Exception { return new JSONObject(original.toString()); }
    private boolean containsDay(JSONArray days, int day) { if (days != null) for (int i = 0; i < days.length(); i++) if (days.optInt(i) == day) return true; return false; }
    private SimpleDateFormat isoFormat() { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US); f.setLenient(false); return f; }
    private String timeString(JSONObject a) { return String.format(Locale.getDefault(), "%02d:%02d", a.optInt("hour"), a.optInt("minute")); }
    private int enabledCount(JSONArray alarms) { int count = 0; for (int i = 0; i < alarms.length(); i++) if (alarms.optJSONObject(i) != null && alarms.optJSONObject(i).optBoolean("enabled")) count++; return count; }
    private String groupLabel(JSONObject alarm) {
        if (!alarm.isNull("groupId")) {
            JSONArray groups = AlarmStore.groups(this);
            for (int i = 0; i < groups.length(); i++) { JSONObject group = groups.optJSONObject(i); if (group != null && group.optLong("id") == alarm.optLong("groupId")) return group.optString("name"); }
        }
        return "No group";
    }
    private String scheduleText(JSONObject alarm) {
        JSONArray days = alarm.optJSONArray("days");
        if (days != null && days.length() > 0) {
            if (days.length() == 7) return "Every day";
            if (days.length() == 5 && !containsDay(days, 1) && !containsDay(days, 7)) return "Weekdays · Mon–Fri";
            if (days.length() == 2 && containsDay(days, 1) && containsDay(days, 7)) return "Weekends · Sat, Sun";
            StringBuilder result = new StringBuilder();
            String[] names = new DateFormatSymbols().getShortWeekdays();
            for (int i = 1; i <= 7; i++) if (containsDay(days, i)) { if (result.length() > 0) result.append(", "); result.append(names[i]); }
            return result.toString();
        }
        Calendar when = Calendar.getInstance();
        if (!alarm.isNull("date") && !alarm.optString("date", "").isEmpty()) {
            try { when.setTime(isoFormat().parse(alarm.optString("date"))); }
            catch (Exception e) { return "Choose a valid date"; }
            when.set(Calendar.HOUR_OF_DAY, alarm.optInt("hour")); when.set(Calendar.MINUTE, alarm.optInt("minute")); when.set(Calendar.SECOND, 0); when.set(Calendar.MILLISECOND, 0);
            return (when.getTimeInMillis() <= System.currentTimeMillis() ? "Date passed · " : "Once · ") + new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(when.getTime());
        }
        when.set(Calendar.HOUR_OF_DAY, alarm.optInt("hour")); when.set(Calendar.MINUTE, alarm.optInt("minute")); when.set(Calendar.SECOND, 0); when.set(Calendar.MILLISECOND, 0);
        boolean tomorrow = when.getTimeInMillis() <= System.currentTimeMillis();
        if (tomorrow) when.add(Calendar.DAY_OF_YEAR, 1);
        return (tomorrow ? "Tomorrow" : "Today") + " · " + new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(when.getTime());
    }
    private void hideKeyboard() { View focused = getCurrentFocus(); if (focused != null) ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focused.getWindowToken(), 0); }
    private void notice(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show(); }
    private void failure(String title, Exception e) {
        String message = e.getMessage();
        notice(title, message == null || message.trim().isEmpty() ? "Android could not complete this action. Review alarm permissions and try again." : message);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
