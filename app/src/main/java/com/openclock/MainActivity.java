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
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    // Resolved once per activity: Android recreates it when the system night mode changes.
    private int BG, WHITE, INK, MUTED, BLUE, SOFT, OFF, RED, ON_BLUE;
    private int GROUP, SUCCESS, TRACK_ON, TRACK_OFF, RIPPLE, RIPPLE_SUBTLE, RIPPLE_ON_BLUE;
    private boolean night;
    private LinearLayout root, body;
    private ScrollView scroller;
    private String screen = "home";
    private int homeScrollY;
    private ScrollView homeRestoreTarget;
    private JSONObject draft;
    private String alarmBaseline;
    private static final String VIEW_ALARM_ACTION = "com.openclock.action.VIEW_ALARM";
    private long pendingAlarmLink = -1;
    private AlertDialog alarmLinkPrompt;
    private NumberPicker hourPicker, minutePicker;
    private EditText labelInput;
    private Switch enabledSwitch, soundSwitch, vibrationSwitch, snoozeSwitch;
    private TextView dateValue, repeatNote, groupValue, snoozeValue, countValue, vibrationValue;
    private final TextView[] dayChips = new TextView[7];
    private final Set<Long> collapsed = new HashSet<>();
    private long groupEditorId = -1;
    private EditText groupName;
    private String groupBaseline;
    private final ArrayList<CheckBox> memberChecks = new ArrayList<>();
    private final ArrayList<Long> memberIds = new ArrayList<>();
    private String lastPermissionState;
    private static java.lang.ref.WeakReference<MainActivity> visibleHome = new java.lang.ref.WeakReference<>(null);
    private static final java.util.concurrent.atomic.AtomicBoolean homeRefreshPending = new java.util.concurrent.atomic.AtomicBoolean();

    @Override protected void onStart() {
        super.onStart(); visibleHome = new java.lang.ref.WeakReference<>(this);
    }
    @Override protected void onStop() {
        if (visibleHome.get() == this) visibleHome.clear();
        super.onStop();
    }
    // In-process only: external apps cannot inject a UI invalidation broadcast.
    static void wearChanged() {
        if (!homeRefreshPending.compareAndSet(false, true)) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            homeRefreshPending.set(false);
            MainActivity a = visibleHome.get();
            if (a == null || a.isFinishing() || !"home".equals(a.screen)) return;
            a.showHome();
        });
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        pendingAlarmLink = state == null ? alarmLinkId(getIntent()) : state.getLong("pendingAlarmLink", -1);
        initColors();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        View decor = getWindow().getDecorView();
        int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        int visibility = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(night ? visibility & ~lightBars : visibility | lightBars);
        if (state != null) {
            homeScrollY = state.getInt("homeScrollY", 0);
            long[] closed = state.getLongArray("collapsed");
            if (closed != null) for (long id : closed) collapsed.add(id);
            String previous = state.getString("screen", "home");
            try {
                if ("alarm".equals(previous)) {
                    draft = new JSONObject(state.getString("draft", "{}"));
                    alarmBaseline = state.getString("alarmBaseline", alarmSnapshot(draft));
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
                    groupBaseline = state.getString("groupBaseline", groupBaseline);
                    return;
                }
                if ("permissions".equals(previous)) { showPermissions(); return; }
            } catch (Exception e) { /* Fall back to the list if saved editor state is invalid. */ }
        }
        showHome();
    }

    private void initColors() {
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        BG = night ? 0xff121722 : 0xfff0f3f9;
        WHITE = night ? 0xff1e2635 : 0xffffffff; // Card surface, not text on a primary button.
        INK = night ? 0xffe9edf7 : 0xff202635;
        MUTED = night ? 0xffb5c0d4 : 0xff737d90;
        BLUE = night ? 0xffaabeff : 0xff365ee9;
        SOFT = night ? 0xff283451 : 0xffe6ecff;
        OFF = night ? 0xff9eaac0 : 0xffa4acba;
        RED = night ? 0xffff9aaa : 0xffba354a;
        ON_BLUE = night ? 0xff172348 : 0xffffffff;
        GROUP = night ? 0xff293244 : 0xffe8edf7;
        SUCCESS = night ? 0xff85d5ac : 0xff27765a;
        TRACK_ON = night ? 0xff667bb5 : 0xffaabcf9;
        TRACK_OFF = night ? 0xff4b566b : 0xffdae0eb;
        RIPPLE = night ? 0x33a8bcff : 0x22365ee9;
        RIPPLE_SUBTLE = night ? 0x20a8bcff : 0x15365ee9;
        RIPPLE_ON_BLUE = night ? 0x33172348 : 0x33ffffff;
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
        openPendingAlarmLink();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        long id = alarmLinkId(intent);
        if (id <= 0) return;
        if (alarmLinkPrompt != null) { alarmLinkPrompt.dismiss(); alarmLinkPrompt = null; }
        pendingAlarmLink = id;
        openPendingAlarmLink();
    }

    // Explicit, read-only navigation only. Alarm mutation still requires the regular editor.
    private static long alarmLinkId(Intent intent) {
        if (intent == null || !VIEW_ALARM_ACTION.equals(intent.getAction())) return -1;
        Uri uri = intent.getData();
        if (uri == null) return -1;
        String raw = uri.toString();
        String prefix = "openclock://alarm/";
        if (!raw.startsWith(prefix)) return -1;
        String number = raw.substring(prefix.length());
        if (!number.matches("[1-9][0-9]{0,18}")) return -1;
        try { return Long.parseLong(number); } catch (NumberFormatException ignored) { return -1; }
    }

    private void openPendingAlarmLink() {
        if (pendingAlarmLink <= 0 || alarmLinkPrompt != null || isFinishing() || isDestroyed()) return;
        boolean dirty = false;
        if ("alarm".equals(screen) && draft != null) {
            captureAlarm();
            dirty = alarmBaseline == null || !alarmBaseline.equals(alarmSnapshot(draft));
        } else if ("group".equals(screen)) {
            dirty = groupBaseline == null || !groupBaseline.equals(groupSnapshot());
        }
        if (!dirty) { openLinkedAlarm(); return; }
        AlertDialog prompt = new AlertDialog.Builder(this).setTitle("Open requested alarm?")
                .setMessage("This screen has unsaved changes. Discard them to open the requested alarm?")
                .setNegativeButton("Keep editing", (dialog, which) -> pendingAlarmLink = -1)
                .setPositiveButton("Discard and open", (dialog, which) -> openLinkedAlarm())
                .setOnCancelListener(dialog -> pendingAlarmLink = -1).create();
        prompt.setOnDismissListener(dialog -> { if (alarmLinkPrompt == prompt) alarmLinkPrompt = null; });
        alarmLinkPrompt = prompt;
        prompt.show();
    }

    private void openLinkedAlarm() {
        long id = pendingAlarmLink;
        pendingAlarmLink = -1;
        try {
            JSONArray alarms = AlarmStore.alarms(this);
            for (int i = 0; i < alarms.length(); i++) {
                JSONObject alarm = alarms.optJSONObject(i);
                if (alarm != null && alarm.optLong("id", -1) == id) { hideKeyboard(); editAlarm(alarm); return; }
            }
            toast("Alarm is no longer available");
        } catch (RuntimeException e) { toast("Could not load the requested alarm"); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putLong("pendingAlarmLink", pendingAlarmLink);
        rememberHomeScroll();
        state.putInt("homeScrollY", homeScrollY);
        state.putString("screen", screen);
        long[] closed = new long[collapsed.size()];
        int p = 0;
        for (Long id : collapsed) closed[p++] = id;
        state.putLongArray("collapsed", closed);
        if ("alarm".equals(screen) && draft != null) {
            captureAlarm();
            state.putString("draft", draft.toString());
            state.putString("alarmBaseline", alarmBaseline);
        } else if ("group".equals(screen)) {
            state.putLong("groupId", groupEditorId);
            state.putString("groupName", groupName.getText().toString());
            state.putString("groupBaseline", groupBaseline);
            ArrayList<Long> checked = new ArrayList<>();
            for (int i = 0; i < memberChecks.size(); i++) if (memberChecks.get(i).isChecked()) checked.add(memberIds.get(i));
            long[] ids = new long[checked.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = checked.get(i);
            state.putLongArray("members", ids);
        }
    }

    private void base(String mode) {
        rememberHomeScroll();
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
                popup.getMenu().add("Apps & access").setOnMenuItemClickListener(item -> { startActivity(new Intent(this, AccessActivity.class)); return true; });
                popup.getMenu().add("Check for updates").setOnMenuItemClickListener(item -> { UpdateChecker.show(this, "phone"); return true; });
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
        ScrollView target = scroller;
        homeRestoreTarget = target;
        target.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
            if (scroller == target && homeRestoreTarget != target && "home".equals(screen)) homeScrollY = y;
        });
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
            LinearLayout container = card(GROUP, 18);
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
            boolean isCollapsed = collapsed.contains(id);
            ImageView fold = new ImageView(this);
            fold.setImageResource(R.drawable.ic_group_chevron);
            if (night) fold.setImageTintList(ColorStateList.valueOf(MUTED));
            fold.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            fold.setPadding(dp(12), dp(12), dp(12), dp(12));
            fold.setRotation(isCollapsed ? 0f : 180f);
            fold.setContentDescription((isCollapsed ? "Expand " : "Collapse ") + group.optString("name"));
            fold.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, round(WHITE, 24)));
            fold.setOnClickListener(v -> {
                if (!collapsed.remove(id)) collapsed.add(id);
                showHome();
            });
            heading.addView(fold, new LinearLayout.LayoutParams(dp(48), dp(48)));
            heading.addView(icon("⋮", "Edit " + group.optString("name"), v -> groupMenu(v, group)), new LinearLayout.LayoutParams(dp(48), dp(48)));
            container.addView(heading);
            names.setOnClickListener(v -> { if (!collapsed.remove(id)) collapsed.add(id); showHome(); });
            if (!collapsed.contains(id)) {
                for (JSONObject alarm : members) addBlock(container, alarmCard(alarm), 0, 8);
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
            addBlock(body, alarmCard(alarm), 8);
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
        // Restore only after measurement, so ScrollView clamps against the new content,
        // not an unlaid-out (zero-height) child. Ignore callbacks from superseded views.
        target.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (target.getViewTreeObserver().isAlive()) target.getViewTreeObserver().removeOnPreDrawListener(this);
                if (scroller == target && homeRestoreTarget == target && "home".equals(screen)) {
                    target.scrollTo(0, homeScrollY);
                    homeRestoreTarget = null;
                    homeScrollY = target.getScrollY();
                }
                return true;
            }
        });
    }

    private void rememberHomeScroll() {
        // A refresh can arrive before the previous restoration is laid out; in that
        // case retain the requested position rather than capturing its temporary zero.
        if ("home".equals(screen) && scroller != null && scroller != homeRestoreTarget) {
            homeScrollY = scroller.getScrollY();
        }
    }

    private View alarmCard(JSONObject alarm) {
        boolean enabled = alarm.optBoolean("enabled", true);
        SwipeAlarmCard card = new SwipeAlarmCard(alarm);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(WHITE, 26));
        card.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout top = row();
        LinearLayout info = column();
        TextView time = text(String.format(Locale.getDefault(), "%02d:%02d", alarm.optInt("hour"), alarm.optInt("minute")), 34, enabled ? INK : OFF);
        time.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        time.setFontFeatureSettings("tnum");
        time.setMaxLines(1);
        time.setAutoSizeTextTypeUniformWithConfiguration(24, 34, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        info.addView(time);
        String label = alarm.optString("label", "").trim();
        String description = scheduleText(alarm);
        TextView schedule = text(label.isEmpty() ? description : label + " · " + description, 13, enabled ? MUTED : OFF);
        schedule.setPadding(dp(2), dp(3), dp(8), 0);
        schedule.setMaxLines(2);
        schedule.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(schedule);
        // The store owns this pending occurrence; don't hide it merely because its
        // deadline has passed while delivery is queued or the device is busy.
        long snoozedUntil = alarm.optLong("snoozedUntil", 0);
        String snoozeDescription = "";
        if (snoozedUntil > 0) {
            snoozeDescription = "Snoozed until " + new SimpleDateFormat("HH:mm · EEE, d MMM yyyy", Locale.getDefault())
                    .format(new java.util.Date(snoozedUntil));
            TextView snooze = text(snoozeDescription, 14, BLUE);
            snooze.setTypeface(null, Typeface.BOLD);
            snooze.setPadding(dp(9), dp(5), dp(9), dp(5));
            snooze.setBackground(round(SOFT, 12));
            LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(-2, -2);
            badge.topMargin = dp(8);
            info.addView(snooze, badge);
        }
        if (!alarm.isNull("schedulingError") && !alarm.optString("schedulingError", "").isEmpty()) {
            TextView error = text(alarm.optString("schedulingError"), 12, RED);
            error.setPadding(dp(2), dp(8), dp(8), 0);
            info.addView(error);
        }
        info.setContentDescription("Edit alarm " + time.getText() + ", " + schedule.getText()
                + (snoozeDescription.isEmpty() ? "" : ", " + snoozeDescription));
        info.setOnClickListener(v -> editAlarm(alarm));
        info.setOnLongClickListener(v -> { alarmMenu(alarm); return true; });
        addAlarmDeleteAccessibilityAction(info, alarm);
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
                JSONObject saved = AlarmStore.saveAlarm(this, changed);
                showHome();
                if (checked) showAlarmConfirmation(saved);
            } catch (Exception e) {
                view.setOnCheckedChangeListener(null);
                view.setChecked(enabled);
                failure("Could not change alarm", e);
                showHome();
            }
        });
        top.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(52)));
        FrameLayout swipeLayer = new FrameLayout(this);
        TextView swipeHint = text("Delete", 12, RED);
        swipeHint.setTypeface(null, Typeface.BOLD);
        swipeHint.setPadding(dp(3), 0, dp(3), 0);
        swipeHint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        swipeHint.setVisibility(View.INVISIBLE);
        swipeLayer.addView(swipeHint, new FrameLayout.LayoutParams(-1, -1));
        top.setBackground(round(WHITE, 12));
        swipeLayer.addView(top, new FrameLayout.LayoutParams(-1, -2));
        card.addView(swipeLayer);
        card.setSwipeViews(top, toggle, swipeHint);
        card.setOnClickListener(v -> editAlarm(alarm));
        card.setOnLongClickListener(v -> { alarmMenu(alarm); return true; });
        addAlarmDeleteAccessibilityAction(card, alarm);
        return card;
    }

    private void addAlarmDeleteAccessibilityAction(View view, JSONObject alarm) {
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_DISMISS, "Delete alarm"));
            }
            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
                    confirmDeleteAlarm(alarm);
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    // Observe the entire row, including its clickable info child, without consuming
    // taps, long presses, switch gestures or vertical ScrollView drags.
    private final class SwipeAlarmCard extends LinearLayout {
        private final JSONObject alarm;
        private final int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        private View content, toggle;
        private TextView hint;
        private float startX, startY;
        private long downTime;
        private boolean tracking, dragging;

        SwipeAlarmCard(JSONObject alarm) { super(MainActivity.this); this.alarm = alarm; }
        void setSwipeViews(View content, View toggle, TextView hint) {
            this.content = content; this.toggle = toggle; this.hint = hint;
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                resetSwipe();
                Rect switchBounds = new Rect();
                toggle.getDrawingRect(switchBounds);
                offsetDescendantRectToMyCoords(toggle, switchBounds);
                tracking = !switchBounds.contains((int) event.getX(), (int) event.getY());
                startX = event.getX(); startY = event.getY(); downTime = event.getEventTime();
            } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                tracking = false;
                if (dragging) { resetSwipeAppearance(); return true; }
            } else if (action == MotionEvent.ACTION_MOVE && tracking && event.getPointerCount() == 1) {
                float dx = event.getX() - startX, dy = event.getY() - startY;
                if (!dragging) {
                    // Once vertical movement or a hold wins, do not later reinterpret it as a swipe.
                    if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) tracking = false;
                    else if (event.getEventTime() - downTime >= ViewConfiguration.getLongPressTimeout()) tracking = false;
                    else if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        // Cancel the original child/card press so release cannot edit or toggle.
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        setBackground(round(SOFT, 26));
                    }
                }
                if (dragging) {
                    hint.setGravity(Gravity.CENTER_VERTICAL | (dx >= 0 ? Gravity.LEFT : Gravity.RIGHT));
                    hint.setVisibility(View.VISIBLE);
                    content.setTranslationX(Math.max(-dp(48), Math.min(dp(48), dx)));
                    return true;
                }
            }
            if (dragging) {
                if (action == MotionEvent.ACTION_UP) {
                    float dx = event.getX() - startX, dy = event.getY() - startY;
                    boolean completed = tracking && event.getPointerCount() == 1
                            && Math.abs(dx) >= Math.max(dp(96), getWidth() * 0.25f)
                            && Math.abs(dx) > Math.abs(dy) * 1.5f;
                    resetSwipe();
                    if (completed && isAttachedToWindow() && "home".equals(screen)) confirmDeleteAlarm(alarm);
                } else if (action == MotionEvent.ACTION_CANCEL) resetSwipe();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) tracking = false;
            return super.dispatchTouchEvent(event);
        }

        private void resetSwipeAppearance() {
            if (content != null) content.setTranslationX(0);
            if (hint != null) hint.setVisibility(View.INVISIBLE);
            setBackground(round(WHITE, 26));
        }
        private void resetSwipe() {
            if (dragging) {
                resetSwipeAppearance();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            }
            tracking = false; dragging = false;
        }
        @Override protected void onDetachedFromWindow() { resetSwipe(); super.onDetachedFromWindow(); }
    }

    private void alarmMenu(JSONObject alarm) {
        new AlertDialog.Builder(this).setTitle("Alarm " + timeString(alarm)).setItems(new String[]{"Edit alarm", "Delete alarm"}, (d, which) -> {
            if (which == 0) editAlarm(alarm); else confirmDeleteAlarm(alarm);
        }).show();
    }

    private void newAlarm() {
        Calendar now = Calendar.getInstance();
        draft = new JSONObject();
        try {
            draft.put("hour", now.get(Calendar.HOUR_OF_DAY)); draft.put("minute", now.get(Calendar.MINUTE));
            draft.put("date", JSONObject.NULL); draft.put("days", new JSONArray());
            draft.put("label", ""); draft.put("enabled", true); draft.put("sound", false);
            draft.put("vibrate", true); draft.put("snoozeEnabled", true);
            draft.put("snoozeMinutes", 5); draft.put("snoozeCount", 3); draft.put("groupId", JSONObject.NULL);
        } catch (Exception ignored) { }
        alarmBaseline = alarmSnapshot(draft);
        showAlarmEditor();
    }

    private void editAlarm(JSONObject alarm) {
        try { draft = cloneObject(alarm); alarmBaseline = alarmSnapshot(draft); showAlarmEditor(); }
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
        if (draft.has("id")) {
            enabledSwitch = null;
            LinearLayout enabledInfo = optionRow("Alarm enabled", "Saving enables this alarm");
            options.addView(enabledInfo);
        } else {
            enabledSwitch = addSwitchOption(options, "Alarm enabled", "Turn off to keep without scheduling", draft.optBoolean("enabled", true));
        }
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
            if (enabledSwitch != null) draft.put("enabled", enabledSwitch.isChecked());
            draft.put("sound", soundSwitch.isChecked());
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
            dayChips[i].setTextColor(active ? ON_BLUE : MUTED);
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
        final JSONObject toSave;
        try {
            toSave = cloneObject(draft);
            if (toSave.has("id")) toSave.put("enabled", true);
        } catch (Exception e) { failure("Could not save alarm", e); return; }
        if (toSave.optBoolean("enabled") && !canEnable(toSave)) return;
        if (toSave.optBoolean("enabled") && !toSave.optBoolean("sound") && !toSave.optBoolean("vibrate")) {
            new AlertDialog.Builder(this).setTitle("Silent alarm?").setMessage("Sound and vibration are both off. This alarm will only show on screen.")
                    .setNegativeButton("Review", null).setPositiveButton("Save silent alarm", (d, w) -> persistAlarm(toSave)).show();
            return;
        }
        persistAlarm(toSave);
    }

    private void persistAlarm(JSONObject toSave) {
        try {
            JSONObject saved = AlarmStore.saveAlarm(this, toSave);
            hideKeyboard();
            showHome();
            if (saved.optBoolean("enabled")) showAlarmConfirmation(saved);
            else toast("Alarm saved");
        } catch (Exception e) { failure("Could not save alarm", e); }
    }

    private boolean hasUpcomingTrigger(JSONObject alarm, long now) {
        return alarm != null && alarm.optBoolean("enabled") && alarm.isNull("schedulingError")
                && alarm.optLong("nextTriggerAt", 0) > now;
    }

    private void showAlarmConfirmation(JSONObject alarm) {
        String message = null;
        try {
            long now = System.currentTimeMillis();
            if (AlarmStore.accessProblem(this) == null && hasUpcomingTrigger(alarm, now))
                message = AlarmConfirmation.message(alarm.optLong("nextTriggerAt"), now,
                        java.time.ZoneId.systemDefault(), Locale.getDefault());
        } catch (RuntimeException ignored) { /* Saving already succeeded; don't report it as failed. */ }
        if (message == null) message = "Alarm saved; next trigger unavailable. Check the alarm list.";
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private boolean canEnable(JSONObject alarm) {
        if (!alarm.isNull("date") && !alarm.optString("date", "").isEmpty()) {
            try {
                Calendar when = Calendar.getInstance();
                when.setTime(isoFormat().parse(alarm.getString("date")));
                when.set(Calendar.HOUR_OF_DAY, alarm.optInt("hour")); when.set(Calendar.MINUTE, alarm.optInt("minute"));
                when.set(Calendar.SECOND, 0); when.set(Calendar.MILLISECOND, 0);
                if (when.getTimeInMillis() <= System.currentTimeMillis()) {
                    notice("This date has passed", "Choose a future date or clear the date to use the next occurrence.");
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
            ArrayList<JSONObject> saved = new ArrayList<>();
            for (JSONObject changed : changes) saved.add(AlarmStore.saveAlarm(this, changed));
            showHome();
            if (changes.isEmpty()) toast("No alarms to change");
            else if (!enabled) toast("Group alarms disabled");
            else if (saved.size() == 1) showAlarmConfirmation(saved.get(0));
            else {
                JSONObject earliest = null;
                long now = System.currentTimeMillis();
                for (JSONObject alarm : saved) {
                    if (!hasUpcomingTrigger(alarm, now)) { earliest = null; break; }
                    if (earliest == null || alarm.optLong("nextTriggerAt") < earliest.optLong("nextTriggerAt")) earliest = alarm;
                }
                if (earliest == null || AlarmStore.accessProblem(this) != null)
                    toast("Alarms enabled; check the list for scheduling problems");
                else {
                    String message = AlarmConfirmation.message(earliest.optLong("nextTriggerAt"), now,
                            java.time.ZoneId.systemDefault(), Locale.getDefault());
                    Toast.makeText(this, "Group enabled. Next: " + message, Toast.LENGTH_LONG).show();
                }
            }
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
        groupBaseline = groupSnapshot();
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
        permissionCard("Full-screen alarms", fullScreenAllowed(), "Allow full-screen intents to bring a ringing alarm over the lock screen. Also keep the Ringing alarms channel at High importance; DND and device settings can still block the screen. Without full-screen access, Android may show a notification instead.", v -> {
            if (Build.VERSION.SDK_INT >= 34) openSettings(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())));
            else openNotificationSettings();
        });
        LinearLayout dnd = card(WHITE, 22);
        TextView dndHeading = text("Do Not Disturb", 20, INK); dndHeading.setTypeface(null, Typeface.BOLD); dnd.addView(dndHeading);
        TextView dndDetail = text("In Android's Do Not Disturb settings, allow alarms for each mode you use. Open Clock requests alarm sound and vibration only when enabled for that alarm. Android, device settings, or a zero alarm volume may still silence them; total silence may block alarms. This is not a guarantee.", 14, MUTED);
        dndDetail.setPadding(0, dp(10), 0, 0); dnd.addView(dndDetail);
        TextView dndButton = button("Open DND alarm settings", false, v -> openDndSettings());
        LinearLayout.LayoutParams dndParams = new LinearLayout.LayoutParams(-1, dp(50)); dndParams.topMargin = dp(18); dnd.addView(dndButton, dndParams);
        TextView channelDetail = text("Optional DND access lets Open Clock request priority-mode bypass for the ringing channel, where Android permits. After granting access, check that the channel is High importance and may override DND. This does not bypass DND for separate alarm sound or vibration. Leave channel sound and vibration off to respect each alarm's choices.", 14, MUTED);
        channelDetail.setPadding(0, dp(16), 0, 0); dnd.addView(channelDetail);
        TextView policyButton = button("Open DND access", false, v -> openSettings(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        LinearLayout.LayoutParams policyParams = new LinearLayout.LayoutParams(-1, dp(50)); policyParams.topMargin = dp(18); dnd.addView(policyButton, policyParams);
        TextView channelButton = button("Open ringing channel settings", false, v -> openRingingChannelSettings());
        LinearLayout.LayoutParams channelParams = new LinearLayout.LayoutParams(-1, dp(50)); channelParams.topMargin = dp(18); dnd.addView(channelButton, channelParams);
        addBlock(body, dnd, 16);
        TextView help = text("Also check the system alarm volume, notification channel, Do Not Disturb, and any manufacturer battery restrictions. Sound uses the standard system alarm tone; vibration depends on your device.", 13, MUTED);
        help.setPadding(dp(6), dp(12), dp(6), dp(12)); body.addView(help);
    }

    private void permissionCard(String title, boolean allowed, String detail, View.OnClickListener action) {
        LinearLayout card = card(WHITE, 22);
        TextView heading = text(title, 20, INK); heading.setTypeface(null, Typeface.BOLD); card.addView(heading);
        TextView status = text(allowed ? "Allowed" : "Action needed", 14, allowed ? SUCCESS : RED);
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
    private void openDndSettings() {
        try { startActivity(new Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)); }
        catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
                toast("Find Do Not Disturb and allow alarms in the modes you use.");
            } catch (Exception again) { notice("Settings unavailable", "Open Android Settings → Do Not Disturb and allow alarms in the modes you use."); }
        }
    }
    private void openRingingChannelSettings() {
        AlarmNotifications.channels(this);
        openSettings(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, AlarmNotifications.RING_CHANNEL));
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
        if ("alarm".equals(screen)) {
            captureAlarm();
            if (alarmBaseline != null && alarmBaseline.equals(alarmSnapshot(draft))) {
                hideKeyboard(); showHome(); return;
            }
        }
        if ("group".equals(screen) && groupBaseline != null && groupBaseline.equals(groupSnapshot())) {
            hideKeyboard(); showHome(); return;
        }
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
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, round(WHITE, 24)));
        if (action != null) view.setOnClickListener(action); return view;
    }
    private TextView button(String title, boolean primary, View.OnClickListener action) {
        TextView view = text(title, 16, primary ? ON_BLUE : BLUE); view.setTypeface(null, Typeface.BOLD);
        view.setGravity(Gravity.CENTER); view.setMinHeight(dp(52)); view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? RIPPLE_ON_BLUE : RIPPLE), round(primary ? BLUE : SOFT, 18), null));
        view.setOnClickListener(action); return view;
    }
    private GradientDrawable round(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private ColorStateList tint() { return new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{BLUE, OFF}); }
    private Switch makeSwitch(boolean checked) {
        Switch view = new Switch(this); view.setChecked(checked); view.setShowText(false); view.setThumbTintList(tint());
        view.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{TRACK_ON, TRACK_OFF}));
        view.setMinHeight(dp(48)); view.setSwitchMinWidth(dp(46)); view.setGravity(Gravity.CENTER); return view;
    }
    private LinearLayout optionRow(String title, String subtitle) {
        LinearLayout line = row(); line.setMinimumHeight(dp(74)); line.setPadding(0, dp(12), 0, dp(12));
        LinearLayout labels = column(); TextView name = text(title, 17, INK); labels.addView(name);
        TextView value = text(subtitle, 13, MUTED); value.setPadding(0, dp(6), 0, 0); labels.addView(value);
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = text("›", 25, OFF); chevron.setGravity(Gravity.CENTER); line.addView(chevron, new LinearLayout.LayoutParams(dp(28), dp(48)));
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE_SUBTLE), null, round(WHITE, 10))); return line;
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
    // Group names are saved trimmed; member identity, not display order, defines a change.
    private String groupSnapshot() {
        java.util.TreeSet<Long> selected = new java.util.TreeSet<>();
        for (int i = 0; i < memberChecks.size(); i++) if (memberChecks.get(i).isChecked()) selected.add(memberIds.get(i));
        JSONArray members = new JSONArray();
        for (Long id : selected) members.put(id);
        return new JSONArray().put(groupName.getText().toString().trim()).put(members).toString();
    }
    // Compare only editable values, not transient scheduler fields or JSON key order.
    private String alarmSnapshot(JSONObject alarm) {
        JSONArray days = alarm.optJSONArray("days");
        int dayMask = 0;
        for (int day = 1; day <= 7; day++) if (containsDay(days, day)) dayMask |= 1 << day;
        return new JSONArray().put(alarm.optInt("hour", 7)).put(alarm.optInt("minute", 0))
                .put(alarm.isNull("date") ? JSONObject.NULL : alarm.optString("date"))
                .put(dayMask).put(alarm.optString("label", "").trim())
                .put(alarm.optBoolean("enabled", true)).put(alarm.optBoolean("sound", true))
                .put(alarm.optBoolean("vibrate", true)).put(alarm.optString("vibrationPattern", "basic"))
                .put(alarm.optBoolean("snoozeEnabled", true)).put(alarm.optInt("snoozeMinutes", 5))
                .put(alarm.optInt("snoozeCount", 3))
                .put(alarm.isNull("groupId") ? JSONObject.NULL : alarm.optLong("groupId"))
                .toString();
    }
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
