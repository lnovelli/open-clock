package com.openclock;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.wear.widget.DismissibleFrameLayout;
import androidx.wear.widget.SwipeDismissFrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Small, self-contained watch UI. The watch store and phone cache are separate authorities. */
public class MainActivity extends Activity {
    private static final int BG = Color.BLACK, CARD = 0xff28292d, WHITE = 0xfff3f3f5;
    private static final int MUTED = 0xffbec0c8, BLUE = 0xffa7bfff, RED = 0xffffb4ab;
    private static final long FRESH_MS = 5 * 60 * 1000L;
    private LinearLayout content;
    private ScrollView scroll;
    private SwipeDismissFrameLayout swipeRoot;
    private int page; // 0 list, 1 time, 2 options
    private int hour, minute, month, day;
    private boolean hasDate, sound = false, vibrate = true;
    private boolean createOnPhone;
    private long editingId = -1;
    private String editingOriginal = "";
    private String editingTarget = "";
    private boolean dateChanged;
    private String chosenDate = "";
    private String draftCreateId = "", renderedCreateStatus = "";
    private String label = "";
    private final Set<Long> pending = new HashSet<>();
    private final Map<Long, Long> pendingRefresh = new HashMap<>();
    private final Map<Long, Boolean> pendingExpected = new HashMap<>();
    private String phoneNotice = "";
    private boolean createBusy;
    private boolean mutationBusy;
    private boolean registered;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable agePhoneSnapshot = new Runnable() {
        @Override public void run() {
            if (!registered) return;
            if (page == 0) renderListIfChanged();
            uiHandler.postDelayed(this, 60_000L);
        }
    };
    private int renderedPage = -1;
    private String renderedListState = "";
    private long awaitingAfter;
    private final BroadcastReceiver phoneChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (WearSync.LOCAL_CHANGED.equals(intent.getAction())) {
                if (page == 0) renderListIfChanged();
                return;
            }
            long updated = snapshot().optLong("updatedAt", 0);
            reconcilePending();
            if (awaitingAfter > 0 && updated > awaitingAfter) {
                awaitingAfter = 0;
                phoneNotice = "Phone snapshot updated.";
            }
            if (page == 2 && createOnPhone && editingId > 0) {
                try {
                    JSONObject p = PhoneSync.pendingMutation(context);
                    if (p != null && "committed".equals(p.optString("status"))
                            && p.optString("requestId").equals(new JSONObject(editingTarget).optString("requestId"))) {
                        mutationBusy = false; page = 0; phoneNotice = "Phone confirmed the change.";
                    }
                } catch (Exception ignored) { }
            }
            if (page == 0) renderListIfChanged();
            else if (page == 2 && createOnPhone && !draftCreateId.isEmpty()
                    && !draftCreateStatus().equals(renderedCreateStatus)) render();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        Calendar now = Calendar.getInstance();
        hour = now.get(Calendar.HOUR_OF_DAY); minute = now.get(Calendar.MINUTE);
        month = now.get(Calendar.MONTH) + 1; day = now.get(Calendar.DAY_OF_MONTH);
        if (state != null) {
            page = state.getInt("page", 0); hour = state.getInt("hour", hour);
            minute = state.getInt("minute", minute); month = state.getInt("month", month);
            day = state.getInt("day", day); hasDate = state.getBoolean("hasDate", false);
            sound = state.getBoolean("sound", false); vibrate = state.getBoolean("vibrate", true);
            label = state.getString("label", "");
            createOnPhone = state.getBoolean("createOnPhone", false);
            draftCreateId = state.getString("draftCreateId", "");
            editingId = state.getLong("editingId", -1); editingOriginal = state.getString("editingOriginal", "");
            editingTarget = state.getString("editingTarget", ""); dateChanged = state.getBoolean("dateChanged", false);
            chosenDate = state.getString("chosenDate", "");
        }
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PhoneSync.ACTION_CHANGED);
        filter.addAction(WearSync.LOCAL_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneChanged, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(phoneChanged, filter);
        registered = true;
        uiHandler.postDelayed(agePhoneSnapshot, 60_000L);
        try { PhoneSync.requestSnapshot(this); }
        catch (Exception e) { phoneNotice = "Phone refresh unavailable."; }
    }

    @Override protected void onResume() {
        super.onResume();
        if (page == 0) renderListIfChanged();
    }

    @Override protected void onStop() {
        uiHandler.removeCallbacks(agePhoneSnapshot);
        if (registered) { unregisterReceiver(phoneChanged); registered = false; }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page); out.putInt("hour", hour); out.putInt("minute", minute);
        out.putInt("month", month); out.putInt("day", day); out.putBoolean("hasDate", hasDate);
        out.putBoolean("sound", sound); out.putBoolean("vibrate", vibrate); out.putString("label", label);
        out.putBoolean("createOnPhone", createOnPhone);
        out.putString("draftCreateId", draftCreateId);
        out.putLong("editingId", editingId); out.putString("editingOriginal", editingOriginal);
        out.putString("editingTarget", editingTarget); out.putBoolean("dateChanged", dateChanged);
        out.putString("chosenDate", chosenDate);
    }

    @Override public void onBackPressed() {
        if (page == 2) { page = 1; render(); }
        else if (page == 1) {
            if (editingId > 0 && editChanged()) {
                new AlertDialog.Builder(this).setTitle("Discard changes?").setNegativeButton("Keep editing", null)
                    .setPositiveButton("Discard", (dialog, which) -> { page = 0; render(); }).show();
            } else { page = 0; render(); }
        }
        else super.onBackPressed();
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextColor(color); v.setTextSize(sp);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private void add(View v, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); content.addView(v, p);
    }
    // Data Layer discovery and identical snapshot publications are advisory;
    // they must not replace the list view, reset focus, or interrupt a gesture.
    private String listStateKey() {
        try {
            JSONObject cache = snapshot(); long updated = cache.optLong("updatedAt", 0);
            long age = System.currentTimeMillis() - updated;
            NotificationManager nm = getSystemService(NotificationManager.class);
            JSONObject creation = PhoneSync.pendingCreate(this), mutation = PhoneSync.pendingMutation(this);
            // Both complete row arrays include snoozedUntil, so pending snooze
            // changes redraw the list without adding revisions or timestamps.
            return new JSONObject().put("watch", AlarmStore.alarms(this))
                .put("alarms", cache.optJSONArray("alarms")).put("groups", cache.optJSONArray("groups"))
                .put("connected", connected(cache)).put("fresh", updated > 0 && age >= 0 && age <= FRESH_MS)
                .put("pending", new JSONArray(new java.util.TreeSet<>(pending)))
                .put("create", creation).put("busy", createBusy).put("createObserved", receiptObserved(creation, cache))
                .put("mutation", mutation).put("mutationBusy", mutationBusy).put("mutationObserved", receiptObserved(mutation, cache))
                .put("mutationReplaced", mutation != null && !cache.optString("epoch", "").isEmpty()
                    && !mutation.optString("epoch", "").equals(cache.optString("epoch", "")))
                .put("notice", phoneNotice).put("permission", AlarmStore.accessProblem(this))
                .put("fullscreen", Build.VERSION.SDK_INT < 34 || nm == null || nm.canUseFullScreenIntent())
                .toString();
        } catch (Exception e) { return "unavailable:" + e.getClass().getName() + ":" + phoneNotice; }
    }
    private void renderListIfChanged() {
        if (page == 0 && (renderedPage != 0 || !listStateKey().equals(renderedListState))) render();
    }
    private void render() {
        final int oldScroll = scroll != null && page == renderedPage ? scroll.getScrollY() : 0;
        if (page == 0) renderedListState = listStateKey();
        renderedPage = page;
        getWindow().getDecorView().setSystemUiVisibility(page == 0 ? 0 :
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        content = column(); content.setBackgroundColor(BG);
        if (page == 0) {
            scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
            content.setPadding(dp(20), dp(28), dp(20), dp(36)); scroll.addView(content);
        } else {
            scroll = null;
            // These fixed-height steps fit the watch viewport and never scroll.
            content.setPadding(dp(26), dp(18), dp(26), dp(18));
        }
        SwipeDismissFrameLayout root = new SwipeDismissFrameLayout(this);
        // The window swipe is disabled in our theme; explicitly enable the
        // in-Activity navigator instead of letting a swipe finish the Activity.
        root.setSwipeable(true);
        root.setBackButtonDismissible(false); // The Activity handles hardware Back.
        root.addView(page == 0 ? scroll : content);
        root.registerCallback(new DismissibleFrameLayout.Callback() {
            @Override public void onDismissFinished(DismissibleFrameLayout layout) {
                if (layout == swipeRoot && !isFinishing() && !isDestroyed()) onBackPressed();
            }
        });
        swipeRoot = root;
        setContentView(root);
        if (page == 0) list(); else if (page == 1) timePage(); else optionsPage();
        if (oldScroll > 0 && scroll != null) scroll.post(() -> scroll.scrollTo(0, oldScroll));
    }
    private void title(String s) { TextView t = text(s, 24, WHITE, true); t.setGravity(Gravity.CENTER); add(t, 0); }
    private void section(String s) { TextView t = text(s, 16, WHITE, true); add(t, 20); }
    private void note(String s) { TextView t = text(s, 12, MUTED, false); add(t, 8); }
    private Button button(String text, int color, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(WHITE); b.setMinHeight(dp(48)); b.setBackground(shape(color, 28));
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void list() {
        title("Alarm");
        permissions();
        section("Watch alarms");
        try {
            JSONArray alarms = AlarmStore.alarms(this);
            if (alarms.length() == 0) note("No watch alarms yet");
            for (int i = 0; i < alarms.length(); i++) {
                JSONObject a = alarms.optJSONObject(i); if (a != null) add(alarmRow(a, false), 8);
            }
        } catch (Exception e) { note("Watch alarms unavailable: " + AlarmStore.message(e)); }
        add(button("+  Add on watch", 0xff3d465d, () -> beginCreate(false)), 10);
        section("Phone alarms");
        Button createPhone = button("+  Add phone alarm", 0xff3d465d, () -> beginCreate(true));
        createPhone.setEnabled(!createBlocked());
        add(createPhone, 10);
        createStatus();
        mutationStatus();
        phoneList();
    }

    private boolean createBlocked() {
        try {
            JSONObject pendingCreate = PhoneSync.pendingCreate(this);
            return createBusy || (pendingCreate != null && (!"created".equals(pendingCreate.optString("status"))
                    || (page != 0 && createOnPhone && !draftCreateId.isEmpty()
                    && draftCreateId.equals(pendingCreate.optString("requestId")))));
        } catch (RuntimeException unreadable) { return true; }
    }

    private String draftCreateStatus() {
        try {
            JSONObject pendingCreate = PhoneSync.pendingCreate(this);
            return pendingCreate != null && draftCreateId.equals(pendingCreate.optString("requestId"))
                    ? pendingCreate.optString("status", "unknown") : "";
        } catch (RuntimeException unreadable) { return "unavailable"; }
    }

    private void createStatus() {
        JSONObject pendingCreate;
        try { pendingCreate = PhoneSync.pendingCreate(this); }
        catch (RuntimeException unreadable) { note("Phone creation state unavailable; new phone alarms are blocked."); return; }
        if (pendingCreate == null) return;
        if ("created".equals(pendingCreate.optString("status"))) {
            long id = pendingCreate.optLong("alarmId", -1), rev = pendingCreate.optLong("revision", -1);
            JSONObject cached = snapshot(); JSONArray alarms = cached.optJSONArray("alarms");
            boolean present = pendingCreate.optString("epoch").equals(cached.optString("epoch"))
                    && cached.optLong("revision", -1) >= rev && alarms != null;
            boolean matched = false;
            if (present) for (int i = 0; i < alarms.length(); i++) {
                JSONObject alarm = alarms.optJSONObject(i);
                if (alarm != null && alarm.optLong("id", -1) == id) { matched = true; break; }
            }
            if (!matched) note("Phone confirmed the new alarm; refresh the phone list to see current state.");
            return;
        }
        note("Previous phone alarm not confirmed. It may already exist; check the phone before creating another.");
        add(button("Check phone result", CARD, this::checkCreateResult), 8);
        add(button("Clear unconfirmed", CARD, () -> {
            String requestId = pendingCreate.optString("requestId", "");
            new AlertDialog.Builder(this).setTitle("Clear unconfirmed request?")
                .setMessage("The alarm may already exist on the phone. Creating another could duplicate it. Check the phone first.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    if (PhoneSync.forgetCreate(this, requestId)) phoneNotice = "Unconfirmed request cleared. Check the phone before creating another.";
                    else phoneNotice = "Request is still being checked; try again later.";
                    render();
                }).show();
        }), 8);
    }

    private void checkCreateResult() {
        if (createBusy) { toast("Phone request is still in progress"); return; }
        createBusy = true; phoneNotice = "Checking the previous phone request…"; render();
        PhoneSync.reconcileCreate(getApplicationContext(), (status, requestId, alarmId, revision) -> {
            createBusy = false;
            phoneNotice = "created".equals(status) ? "Phone confirmed the alarm."
                    : "Still unconfirmed. Check the phone; it may already exist.";
            if (!isFinishing() && !isDestroyed() && page == 0) render();
        });
    }

    private boolean mutationBlocked() {
        try { return mutationBusy || PhoneSync.pendingMutation(this) != null; }
        catch (RuntimeException unreadable) { return true; }
    }
    private boolean receiptObserved(JSONObject receipt, JSONObject cache) {
        return receipt != null && receipt.optString("epoch", "").equals(cache.optString("epoch", ""))
            && !receipt.optString("epoch", "").isEmpty() && receipt.optLong("revision", -1) >= 0
            && cache.optLong("revision", -1) >= receipt.optLong("revision", -1);
    }
    private void mutationStatus() {
        JSONObject p;
        try { p = PhoneSync.pendingMutation(this); }
        catch (RuntimeException e) { note("Phone change state unavailable; edits and deletions are blocked."); return; }
        if (p == null) return;
        String request = p.optString("requestId", "");
        if ("committed".equals(p.optString("status"))) {
            JSONObject cache = snapshot();
            boolean seen = receiptObserved(p, cache);
            boolean phoneReplaced = !cache.optString("epoch", "").isEmpty()
                    && !p.optString("epoch", "").equals(cache.optString("epoch", ""));
            if (seen || phoneReplaced) {
                // Only a positively confirmed change whose snapshot is observed (or
                // whose phone identity changed) may be cleared automatically.
                if (!mutationBusy && PhoneSync.forgetMutation(this, request)) return;
                note("Phone confirmed the change; clearing its local status is pending.");
                if (!mutationBusy) add(button("Clear confirmed change", CARD, () -> {
                    if (!PhoneSync.forgetMutation(this, request)) toast("Change is still being checked");
                    render();
                }), 8);
                return;
            }
            note("Phone confirmed the change; waiting for the updated list.");
            add(button("Refresh phone", CARD, () -> {
                try { PhoneSync.requestSnapshot(this); } catch (Exception e) { toast("Phone refresh unavailable"); }
            }), 8);
            return;
        }
        note("capacity".equals(p.optString("status"))
            ? "Phone receipt storage is full. The change remains unconfirmed; check the phone."
            : "Previous phone change is unconfirmed; it may already have changed or deleted the alarm.");
        add(button("Check phone change", CARD, this::checkMutationResult), 8);
        add(button("Clear unconfirmed change", CARD, () -> new AlertDialog.Builder(this)
            .setTitle("Clear unconfirmed change?")
            .setMessage("The phone may already have changed or deleted the alarm. Check the phone before making another change.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> {
                if (!PhoneSync.forgetMutation(this, request)) toast("Change is still being checked");
                else phoneNotice = "Unconfirmed change cleared; check the phone.";
                render();
            }).show()), 8);
    }
    private void checkMutationResult() {
        if (mutationBusy) { toast("Phone request is still in progress"); return; }
        mutationBusy = true; phoneNotice = "Checking the phone change…"; render();
        PhoneSync.reconcileMutation(getApplicationContext(), (status, request, alarm, revision) ->
                finishPhoneMutation(status, request, false));
    }
    private void finishPhoneMutation(String status, String request, boolean editing) {
        mutationBusy = false;
        phoneNotice = "committed".equals(status) ? "Phone confirmed the change."
                : "capacity".equals(status) ? "Phone receipt storage full; check the phone."
                : "not_sent".equals(status) ? "Change not sent. Refresh and reopen the alarm."
                : "Phone change not confirmed. Check the phone before trying again.";
        if (isFinishing() || isDestroyed()) return;
        if (editing && !"not_sent".equals(status)) {
            try {
                if (page > 0 && request.equals(new JSONObject(editingTarget).optString("requestId"))) page = 0;
            } catch (Exception ignored) { }
        }
        render(); toast(phoneNotice);
    }

    private void beginCreate(boolean phone) {
        Calendar c = Calendar.getInstance();
        hour = c.get(Calendar.HOUR_OF_DAY); minute = c.get(Calendar.MINUTE);
        month = c.get(Calendar.MONTH) + 1; day = c.get(Calendar.DAY_OF_MONTH);
        hasDate = false; sound = false; vibrate = true; label = "";
        editingId = -1; editingOriginal = ""; editingTarget = ""; dateChanged = false; chosenDate = "";
        draftCreateId = "";
        createOnPhone = phone; page = 1; render();
    }

    private void beginEdit(JSONObject alarm, boolean phone, JSONObject target) {
        try {
            if (phone && (target == null || mutationBlocked())) { toast("Check the previous phone change first"); return; }
            editingId = alarm.getLong("id"); editingOriginal = alarm.toString();
            editingTarget = phone ? target.toString() : "";
            hour = alarm.getInt("hour"); minute = alarm.getInt("minute");
            label = alarm.optString("label", ""); sound = alarm.optBoolean("sound", true);
            vibrate = alarm.optBoolean("vibrate", true); dateChanged = false;
            String savedDate = alarm.isNull("date") ? "" : alarm.optString("date", "");
            chosenDate = savedDate;
            hasDate = !savedDate.isEmpty();
            if (hasDate) { LocalDate d = LocalDate.parse(savedDate); month = d.getMonthValue(); day = d.getDayOfMonth(); }
            else { Calendar now = Calendar.getInstance(); month = now.get(Calendar.MONTH) + 1; day = now.get(Calendar.DAY_OF_MONTH); }
            createOnPhone = phone; draftCreateId = ""; page = 1; render();
        } catch (Exception e) { editingId = -1; toast("Alarm is unavailable; refresh and try again"); }
    }
    private boolean editChanged() {
        try {
            JSONObject old = new JSONObject(editingOriginal);
            return hour != old.getInt("hour") || minute != old.getInt("minute")
                || !label.trim().equals(old.optString("label", "").trim())
                || sound != old.optBoolean("sound", true) || vibrate != old.optBoolean("vibrate", true)
                || dateChanged;
        } catch (Exception e) { return true; }
    }

    private void permissions() {
        String problem;
        try { problem = AlarmStore.accessProblem(this); }
        catch (Exception e) { problem = "Alarm access could not be checked"; }
        if (problem != null) {
            TextView warning = text(problem, 12, RED, false); add(warning, 12);
            if (Build.VERSION.SDK_INT >= 31) {
                AlarmManager am = getSystemService(AlarmManager.class);
                if (am != null && !am.canScheduleExactAlarms())
                    add(button("Allow alarms", CARD, () -> settings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)), 8);
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                add(button("Allow notifications", CARD, () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7)), 8);
            add(button("Notification settings", CARD, this::notificationSettings), 8);
        }
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && !nm.canUseFullScreenIntent()) {
                note("Full-screen alarm display is not allowed; alarms may appear as notifications.");
                add(button("Full-screen access", CARD, () -> settings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)), 8);
            }
        }
    }
    private void settings(String action) {
        try { startActivity(new Intent(action, Uri.parse("package:" + getPackageName()))); }
        catch (Exception e) { toast("Settings unavailable on this watch"); }
    }
    private void notificationSettings() {
        try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
        catch (Exception e) { settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS); }
    }

    private View alarmRow(JSONObject a, boolean phone) {
        LinearLayout row = new DeleteSwipeRow(a, phone); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(8), dp(8)); row.setMinimumHeight(dp(66)); row.setBackground(shape(CARD, 26));
        LinearLayout details = column(); details.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = text(String.format(Locale.getDefault(), "%02d:%02d", a.optInt("hour"), a.optInt("minute")), 22, WHITE, true);
        details.addView(time); String l = a.optString("label", "").trim();
        String schedule = schedule(a); TextView sub = text((l.isEmpty() ? "Alarm" : l) + " · " + schedule, 11, MUTED, false);
        sub.setMaxLines(3); details.addView(sub);
        // The store owns pending state: even a past deadline can remain queued
        // or have a scheduling error, and must not disappear based on the clock.
        long snoozedUntil = a.optLong("snoozedUntil", 0);
        String snoozeDescription = "";
        if (snoozedUntil > 0) {
            snoozeDescription = "Snoozed until " + new java.text.SimpleDateFormat("HH:mm · EEE, d MMM yyyy", Locale.getDefault())
                    .format(new java.util.Date(snoozedUntil));
            TextView snooze = text(snoozeDescription, 11, BLUE, true);
            snooze.setPadding(dp(6), dp(3), dp(6), dp(3));
            snooze.setBackground(shape(0xff343b50, 10));
            LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(-2, -2);
            badge.topMargin = dp(4); details.addView(snooze, badge);
        }
        String error = a.optString("schedulingError", "");
        if (!error.isEmpty() && !"null".equals(error)) { TextView e = text("Not scheduled: " + error, 11, RED, false); details.addView(e); }
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        final long id = a.optLong("id", -1); final boolean old = a.optBoolean("enabled", false);
        Switch toggle = new Switch(this); toggle.setMinWidth(dp(48)); toggle.setMinHeight(dp(48));
        toggle.setChecked(old); toggle.setContentDescription((phone ? "Phone" : "Watch") + " alarm " + time.getText() + (l.isEmpty() ? "" : ", " + l));
        if (phone && (id <= 0 || !connected(snapshot()) || pending.contains(id))) toggle.setEnabled(false);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (phone) {
                button.setOnCheckedChangeListener(null); button.setChecked(old);
                phoneToggle(id, checked);
            } else {
                try { AlarmStore.saveAlarm(this, new JSONObject().put("id", id).put("enabled", checked)); }
                catch (Exception e) { toast(AlarmStore.message(e)); }
                render();
            }
        });
        row.addView(toggle);
        details.setContentDescription((phone ? "Phone" : "Watch") + " alarm " + time.getText()
                + ", " + sub.getText() + (snoozeDescription.isEmpty() ? "" : ", " + snoozeDescription)
                + (!error.isEmpty() && !"null".equals(error) ? ", Not scheduled: " + error : "")
                + ". Tap to edit; swipe left to delete, or touch and hold for actions.");
        details.setOnClickListener(v -> openAlarmRow(a, phone, false));
        details.setOnLongClickListener(v -> { openAlarmRow(a, phone, true); return true; });
        row.setOnClickListener(v -> openAlarmRow(a, phone, false));
        row.setOnLongClickListener(v -> { openAlarmRow(a, phone, true); return true; });
        return row;
    }
    /** Intercept only a physical leftward horizontal drag; leave taps, switches,
     * vertical scrolling and the parent navigator's rightward swipe alone. */
    private final class DeleteSwipeRow extends LinearLayout {
        private final JSONObject displayed;
        private final boolean phone;
        private final int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        private float startX, startY;
        private int pointer = -1;
        private boolean valid, dragging;

        DeleteSwipeRow(JSONObject displayed, boolean phone) {
            super(MainActivity.this); this.displayed = displayed; this.phone = phone;
        }
        private void cancelGesture() { valid = false; pointer = -1; }
        private void disallowParentIntercept(boolean disallow) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
        }
        @Override public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (!hasFocus) cancelGesture();
        }
        @Override protected void onDetachedFromWindow() {
            cancelGesture(); super.onDetachedFromWindow();
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pointer = event.getPointerId(0); startX = event.getX(); startY = event.getY();
                // A leftward switch drag must remain a switch gesture, not delete.
                Rect switchBounds = new Rect();
                View toggle = getChildAt(getChildCount() - 1);
                if (toggle != null) {
                    toggle.getDrawingRect(switchBounds);
                    offsetDescendantRectToMyCoords(toggle, switchBounds);
                }
                valid = toggle == null || !switchBounds.contains((int) startX, (int) startY);
                dragging = false;
            } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                cancelGesture();
            } else if (action == MotionEvent.ACTION_MOVE && valid) {
                int index = event.findPointerIndex(pointer);
                if (event.getPointerCount() != 1 || index < 0) cancelGesture();
                else {
                    float dx = event.getX(index) - startX, dy = event.getY(index) - startY;
                    if (dx < -slop && -dx > Math.abs(dy)) {
                        dragging = true;
                        disallowParentIntercept(true);
                        return true;
                    }
                    // A scroll or rightward swipe must not later become a delete.
                    if (Math.abs(dy) > slop || dx > slop) cancelGesture();
                }
            } else if (action == MotionEvent.ACTION_UP) cancelGesture();
            return super.onInterceptTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            // A touch on the row background targets the row itself, so there is
            // no child for onInterceptTouchEvent to intercept on later moves.
            if (!dragging && event.getActionMasked() == MotionEvent.ACTION_MOVE && valid
                    && event.getPointerCount() == 1) {
                int index = event.findPointerIndex(pointer);
                if (index < 0) cancelGesture();
                else {
                    float dx = event.getX(index) - startX, dy = event.getY(index) - startY;
                    if (dx < -slop && -dx > Math.abs(dy)) {
                        dragging = true;
                        disallowParentIntercept(true);
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.onTouchEvent(cancel); cancel.recycle();
                    } else if (Math.abs(dy) > slop || dx > slop) cancelGesture();
                }
            } else if (!dragging && (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                    || event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL
                    || event.getPointerCount() != 1)) cancelGesture();
            if (!dragging) return super.onTouchEvent(event);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_CANCEL || event.getPointerCount() != 1) cancelGesture();
            if (action == MotionEvent.ACTION_UP) {
                int index = event.findPointerIndex(pointer);
                float dx = index < 0 ? 0 : event.getX(index) - startX;
                float dy = index < 0 ? 0 : event.getY(index) - startY;
                boolean complete = valid && index >= 0 && hasWindowFocus() && isAttachedToWindow()
                        && page == 0 && -dx >= Math.max(dp(48), getWidth() * .30f)
                        && -dx > Math.abs(dy);
                cancelGesture(); dragging = false;
                disallowParentIntercept(false);
                if (complete) openAlarmRow(displayed, phone, true, true);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                dragging = false; disallowParentIntercept(false);
            }
            return true;
        }
    }
    private void openAlarmRow(JSONObject displayed, boolean phone, boolean actions) {
        openAlarmRow(displayed, phone, actions, false);
    }
    private void openAlarmRow(JSONObject displayed, boolean phone, boolean actions, boolean directDelete) {
        if (!phone) {
            if (directDelete) confirmDelete(displayed, false, null);
            else if (actions) alarmActions(displayed, false, null);
            else beginEdit(displayed, false, null);
            return;
        }
        // A no-op phone publication can advance the revision without redrawing
        // this row. Resolve it at the moment of the tap, but only if the
        // displayed alarm still matches the authoritative cached fields.
        JSONObject target = PhoneSync.captureMutationTarget(this, displayed.optLong("id", -1));
        JSONObject current = target == null ? null : target.optJSONObject("alarm");
        if (target == null || current == null || !sameEditableAlarm(displayed, current)) {
            toast("Phone changed or disconnected; refresh the list"); renderListIfChanged(); return;
        }
        if (mutationBlocked()) { toast("Check the previous phone change first"); return; }
        if (directDelete) confirmDelete(current, true, target);
        else if (actions) alarmActions(current, true, target);
        else beginEdit(current, true, target);
    }
    private void alarmActions(JSONObject alarm, boolean phone, JSONObject target) {
        new AlertDialog.Builder(this).setTitle(phone ? "Phone alarm" : "Watch alarm")
            .setItems(new String[]{"Delete alarm"}, (dialog, which) -> confirmDelete(alarm, phone, target)).show();
    }
    private void confirmDelete(JSONObject alarm, boolean phone, JSONObject target) {
        new AlertDialog.Builder(this)
            .setMessage(phone ? "Delete this alarm on the phone? This also stops any active ring or snooze."
                    : "Delete this watch alarm? This also stops any active ring or snooze.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
                if (phone) deletePhoneAlarm(target);
                else { try { deleteLocalIfUnchanged(alarm); } catch (Exception e) { toast(AlarmStore.message(e)); } render(); }
            }).show();
    }
    private void deleteLocalIfUnchanged(JSONObject expected) throws Exception {
        long id = expected.optLong("id", -1);
        synchronized (AlarmStore.class) {
            JSONArray alarms = AlarmStore.alarms(this); JSONObject current = null;
            for (int i = 0; i < alarms.length(); i++) {
                JSONObject a = alarms.optJSONObject(i);
                if (a != null && a.optLong("id", -1) == id) { current = a; break; }
            }
            if (current == null || !sameEditableAlarm(expected, current))
                throw new IllegalStateException("Alarm changed; return to the list and reopen it");
            AlarmStore.deleteAlarm(this, id);
        }
    }
    private String schedule(JSONObject a) {
        if (a.has("date") && !a.isNull("date")) return a.optString("date", "");
        JSONArray days = a.optJSONArray("days");
        if (days == null || days.length() == 0) return "Once";
        if (days.length() == 7) return "Every day";
        String[] names = {"", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < days.length(); i++) { int d = days.optInt(i); if (d >= 1 && d <= 7) { if (s.length() > 0) s.append(", "); s.append(names[d]); } }
        return s.length() == 0 ? "Schedule unavailable" : s.toString();
    }
    private JSONObject snapshot() {
        try { JSONObject o = PhoneSync.cached(this); return o == null ? new JSONObject() : o; }
        catch (Exception e) { return new JSONObject(); }
    }
    private boolean connected(JSONObject o) { return o.optBoolean("connected", false); }
    private void phoneList() {
        JSONObject cache = snapshot(); boolean online = connected(cache); long updated = cache.optLong("updatedAt", 0);
        long age = System.currentTimeMillis() - updated;
        if (!online) note("Phone unavailable. Cached alarms may be stale; controls are disabled.");
        else if (updated <= 0 || age < 0 || age > FRESH_MS) note("Phone connected · snapshot is stale or not yet available.");

        if (!phoneNotice.isEmpty()) note(phoneNotice);
        JSONArray alarms = cache.optJSONArray("alarms"), groups = cache.optJSONArray("groups");
        if (alarms == null) { note(online ? "Waiting for phone alarms…" : "No cached phone alarms."); return; }
        Set<Long> shown = new HashSet<>();
        if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i); if (g == null || g.optLong("id", -1) <= 0) continue;
            long groupId = g.optLong("id");
            TextView heading = text(g.optString("name", "Group"), 14, BLUE, true); add(heading, 14);
            int count = 0;
            for (int j = 0; j < alarms.length(); j++) {
                JSONObject a = alarms.optJSONObject(j);
                if (a != null && !a.isNull("groupId") && a.optLong("groupId", -1) == groupId) {
                    add(alarmRow(a, true), 8); shown.add(a.optLong("id", -1)); count++;
                }
            }
            if (count == 0) note("No alarms in this group");
        }
        int rest = 0;
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject a = alarms.optJSONObject(i); if (a == null || shown.contains(a.optLong("id", -1))) continue;
            if (rest++ == 0 && groups != null && groups.length() > 0) { TextView h = text("Other alarms", 14, BLUE, true); add(h, 14); }
            add(alarmRow(a, true), 8);
        }
        if (alarms.length() == 0 && (groups == null || groups.length() == 0)) note("No phone alarms in snapshot");
        if (online) add(button("Refresh phone", CARD, () -> {
            if (connected(snapshot())) {
                try { awaitingAfter = snapshot().optLong("updatedAt", 0); if (awaitingAfter <= 0) awaitingAfter = 1;
                    phoneNotice = "Refresh requested; waiting for phone."; PhoneSync.requestSnapshot(this); }
                catch (Exception e) { phoneNotice = "Phone refresh unavailable."; }
                render();
            }
            else { phoneNotice = "Phone unavailable; refresh not sent."; render(); }
        }), 10);
    }
    private void phoneToggle(long id, boolean enabled) {
        if (id <= 0 || !connected(snapshot())) { phoneNotice = "Phone unavailable; change not sent."; render(); return; }
        try {
            final long baseRevision = snapshot().optLong("revision", -1);
            JSONObject command = new JSONObject().put("op", "setEnabled").put("id", id).put("enabled", enabled);
            pending.add(id); pendingExpected.put(id, enabled); phoneNotice = "Change pending on phone…"; render();
            PhoneSync.command(this, command, (ok, safeMessage) -> {
                if (ok) {
                    phoneNotice = "Phone accepted the request; awaiting a fresh snapshot.";
                    awaitingAfter = snapshot().optLong("updatedAt", 0); if (awaitingAfter <= 0) awaitingAfter = 1;
                    pendingRefresh.put(id, baseRevision);
                    // The updated snapshot may arrive before the acknowledgement.
                    reconcilePending();
                    if (!pending.contains(id)) phoneNotice = "Phone alarm updated.";
                    try { if (connected(snapshot())) PhoneSync.requestSnapshot(this); }
                    catch (Exception e) { phoneNotice = "Phone accepted the request; refresh unavailable. Cached state may be stale."; }
                    uiHandler.postDelayed(() -> {
                        if (java.util.Objects.equals(pendingRefresh.get(id), baseRevision)) {
                            pendingRefresh.remove(id); pendingExpected.remove(id); pending.remove(id);
                            phoneNotice = "Phone accepted the change; the displayed snapshot may be stale. Refresh to confirm.";
                            if (!isFinishing() && !isDestroyed() && page == 0) render();
                        }
                    }, 15_000L);
                } else {
                    pending.remove(id); pendingRefresh.remove(id); pendingExpected.remove(id);
                    phoneNotice = "Phone change not confirmed" + (safeMessage == null || safeMessage.isEmpty() ? "." : ": " + safeMessage);
                }
                if (!isFinishing() && !isDestroyed() && page == 0) render();
            });
        } catch (Exception e) { pending.remove(id); pendingRefresh.remove(id); pendingExpected.remove(id); phoneNotice = "Phone change could not be sent."; render(); }
    }

    private void reconcilePending() {
        JSONObject current = snapshot();
        long revision = current.optLong("revision", -1);
        JSONArray alarms = current.optJSONArray("alarms");
        if (alarms == null) return;
        java.util.Iterator<Map.Entry<Long, Long>> it = pendingRefresh.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> entry = it.next();
            Boolean expected = pendingExpected.get(entry.getKey());
            if (expected == null || revision <= entry.getValue()) continue;
            for (int i = 0; i < alarms.length(); i++) {
                JSONObject alarm = alarms.optJSONObject(i);
                if (alarm != null && alarm.optLong("id", -1) == entry.getKey()
                        && alarm.optBoolean("enabled") == expected) {
                    pending.remove(entry.getKey()); pendingExpected.remove(entry.getKey()); it.remove(); break;
                }
            }
        }
    }

    private NumberPicker picker(int min, int max, int value, NumberPicker.OnValueChangeListener listener) {
        NumberPicker p = new NumberPicker(this); p.setMinValue(min); p.setMaxValue(max); p.setValue(value);
        p.setWrapSelectorWheel(true); p.setFormatter(v -> String.format(Locale.getDefault(), "%02d", v));
        p.setOnValueChangedListener(listener); return p;
    }
    private void timePage() {
        title("Time");
        LinearLayout pickers = new LinearLayout(this); pickers.setGravity(Gravity.CENTER);
        NumberPicker h = picker(0, 23, hour, (p, old, val) -> hour = val);
        NumberPicker m = picker(0, 59, minute, (p, old, val) -> minute = val);
        h.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        m.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        h.setContentDescription("Hour, 24 hour clock"); m.setContentDescription("Minute");
        pickers.addView(h, new LinearLayout.LayoutParams(0, -1, 1));
        TextView colon = text(":", 24, WHITE, true); pickers.addView(colon);
        pickers.addView(m, new LinearLayout.LayoutParams(0, -1, 1));
        content.addView(pickers, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(button("Next", 0xff43557d, () -> { page = 2; render(); }),
                new LinearLayout.LayoutParams(-1, dp(48)));
    }
    private void optionsPage() {
        TextView heading = text("Customize", 20, WHITE, true); heading.setGravity(Gravity.CENTER);
        content.addView(heading, new LinearLayout.LayoutParams(-1, dp(26)));
        LinearLayout row1 = new LinearLayout(this); row1.setGravity(Gravity.CENTER);
        Button labelButton = button(label.trim().isEmpty() ? "Label" : "Label ✓", CARD, this::labelDialog);
        labelButton.setContentDescription(label.trim().isEmpty() ? "Add alarm label" : "Alarm label: " + label.trim());
        Button dateButton = button(hasDate ? "Date\n" + chosenDate : "Date", CARD, this::dateDialog);
        dateButton.setMaxLines(2);
        dateButton.setContentDescription(hasDate ? "Alarm date " + chosenDate + "; change or clear" : "Add alarm date");
        compactPair(row1, labelButton, dateButton);
        dateButton.setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        content.addView(row1, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout row2 = new LinearLayout(this); row2.setGravity(Gravity.CENTER);
        Button soundButton = button(sound ? "Sound on" : "Sound off", CARD, () -> { sound = !sound; render(); });
        Button vibrationButton = button(vibrate ? "Vibrate on" : "Vibrate off", CARD, () -> { vibrate = !vibrate; render(); });
        compactPair(row2, soundButton, vibrationButton);
        content.addView(row2, new LinearLayout.LayoutParams(-1, 0, 1));
        renderedCreateStatus = draftCreateStatus();
        boolean blocked = createOnPhone && (editingId <= 0 ? createBlocked() : mutationBlocked());
        Button saveButton = button(blocked ? "Phone pending" : "Save", 0xff43557d, this::save);
        saveButton.setEnabled(!blocked); saveButton.setAlpha(blocked ? .5f : 1f);
        content.addView(saveButton, new LinearLayout.LayoutParams(-1, dp(48)));
    }
    private void compactPair(LinearLayout row, Button first, Button second) {
        first.setTextSize(12); second.setTextSize(12); first.setMinHeight(0); second.setMinHeight(0);
        first.setPadding(dp(3), 0, dp(3), 0); second.setPadding(dp(3), 0, dp(3), 0);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -1, 1);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -1, 1);
        left.setMargins(0, dp(3), dp(3), dp(3)); right.setMargins(dp(3), dp(3), 0, dp(3));
        row.addView(first, left); row.addView(second, right);
    }
    private void labelDialog() {
        final String before = label;
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextColor(WHITE); input.setHintTextColor(MUTED);
        input.setTextSize(14); input.setHint("Label (optional)"); input.setText(label);
        input.setContentDescription("Alarm label");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { label = s.toString(); }
            public void afterTextChanged(Editable e) { }
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Label").setView(input)
            .setNegativeButton("Cancel", (d, w) -> { label = before; })
            .setPositiveButton("Done", (d, w) -> { label = input.getText().toString(); if (page == 2) render(); }).create();
        dialog.setOnCancelListener(d -> { label = before; }); dialog.show();
    }
    private void dateDialog() {
        final int previousMonth = month, previousDay = day;
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER);
        NumberPicker m = picker(1, 12, month, (p, old, val) -> month = val);
        NumberPicker d = picker(1, 31, day, (p, old, val) -> day = val);
        m.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS); d.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        m.setContentDescription("Month"); d.setContentDescription("Day of month");
        row.addView(m, new LinearLayout.LayoutParams(0, dp(125), 1)); row.addView(text("/", 22, WHITE, true));
        row.addView(d, new LinearLayout.LayoutParams(0, dp(125), 1));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Month / day").setView(row)
            .setNegativeButton("Cancel", (x, y) -> { month = previousMonth; day = previousDay; if (page == 2) render(); })
            .setNeutralButton(hasDate ? "Clear" : null, (x, y) -> { hasDate = false; chosenDate = ""; dateChanged = originalDateChanged(null); if (page == 2) render(); })
            .setPositiveButton("Use date", null).create();
        dialog.setOnCancelListener(x -> { month = previousMonth; day = previousDay; if (page == 2) render(); });
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                LocalDate chosen = resolveDate(); dialog.dismiss();
                boolean repeats = false;
                if (editingId > 0) {
                    JSONArray days = new JSONObject(editingOriginal).optJSONArray("days"); repeats = days != null && days.length() > 0;
                }
                String warning = repeats ? " This replaces the repeating schedule." : "";
                new AlertDialog.Builder(this).setTitle("One-time date")
                    .setMessage("Alarm for " + chosen + "." + warning)
                    .setNegativeButton("Cancel", (d1, w) -> { month = previousMonth; day = previousDay; })
                    .setPositiveButton("Use date", (d1, w) -> {
                        hasDate = true; chosenDate = chosen.toString(); dateChanged = originalDateChanged(chosenDate); if (page == 2) render();
                    }).setOnCancelListener(d1 -> { month = previousMonth; day = previousDay; }).show();
            }
            catch (Exception e) { toast(AlarmStore.message(e)); }
        }));
        dialog.show();
    }
    private boolean originalDateChanged(String date) {
        if (editingId <= 0) return true;
        try {
            JSONObject old = new JSONObject(editingOriginal);
            String previous = old.isNull("date") ? null : old.optString("date", null);
            return !java.util.Objects.equals(previous, date);
        } catch (Exception e) { return true; }
    }
    /** Resolve at save time so a draft crossing midnight does not silently schedule in the past. */
    private LocalDate resolveDate() {
        ZoneId zone = ZoneId.systemDefault(); long now = System.currentTimeMillis();
        int year = Instant.ofEpochMilli(now).atZone(zone).getYear();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        for (int y = year; y <= year + 8 && y <= 9999; y++) {
            LocalDate date;
            try {
                date = LocalDate.of(y, month, day);
            } catch (java.time.DateTimeException invalidDate) { continue; }
            if (date.isBefore(today)) continue;
            if (zone.getRules().getValidOffsets(date.atTime(hour, minute)).isEmpty())
                throw new IllegalArgumentException("That time doesn't exist on this date due to daylight saving time");
            try {
                AlarmTimes.next(hour, minute, date.toString(), java.util.Collections.emptySet(), now, zone);
                return date;
            } catch (IllegalArgumentException alreadyPassed) { /* Today's time has passed. */ }
        }
        throw new IllegalArgumentException("No future valid date");
    }
    private void save() {
        try {
            LocalDate date = hasDate && (editingId <= 0 || dateChanged) ? resolveDate() : null;
            if (date != null && !date.toString().equals(chosenDate))
                throw new IllegalArgumentException("Date changed; choose and confirm it again");
            if (editingId > 0 && scheduleChanged()) {
                new AlertDialog.Builder(this).setTitle("Change schedule?")
                    .setMessage("Changing the schedule can stop an active ring or snooze.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
                        try { saveEdit(date); } catch (Exception e) { toast(AlarmStore.message(e)); }
                    }).show();
            } else if (editingId > 0) saveEdit(date);
            else if (createOnPhone) saveOnPhone(date); else saveResolved(date);
        } catch (Exception e) { toast(AlarmStore.message(e)); }
    }
    private boolean scheduleChanged() {
        try {
            JSONObject old = new JSONObject(editingOriginal);
            return hour != old.getInt("hour") || minute != old.getInt("minute") || dateChanged;
        } catch (Exception e) { return true; }
    }
    private void saveEdit(LocalDate date) throws Exception {
        if (!editChanged()) { page = 0; render(); return; }
        JSONObject original = new JSONObject(editingOriginal);
        JSONObject patch = new JSONObject().put("id", editingId);
        if (hour != original.getInt("hour")) patch.put("hour", hour);
        if (minute != original.getInt("minute")) patch.put("minute", minute);
        if (!label.trim().equals(original.optString("label", "").trim())) patch.put("label", label.trim());
        if (sound != original.optBoolean("sound", true)) patch.put("sound", sound);
        if (vibrate != original.optBoolean("vibrate", true)) patch.put("vibrate", vibrate);
        if (dateChanged) {
            patch.put("date", date == null ? JSONObject.NULL : date.toString());
            if (date != null) patch.put("days", new JSONArray());
        }
        if (createOnPhone) { savePhoneEdit(patch); return; }
        synchronized (AlarmStore.class) {
            JSONArray alarms = AlarmStore.alarms(this); JSONObject current = null;
            for (int i = 0; i < alarms.length(); i++) {
                JSONObject a = alarms.optJSONObject(i);
                if (a != null && a.optLong("id", -1) == editingId) { current = a; break; }
            }
            if (current == null || !sameEditableAlarm(original, current))
                throw new IllegalStateException("Alarm changed; return to the list and reopen it");
            AlarmStore.saveAlarm(this, patch);
        }
        page = 0; render(); toast("Watch alarm updated");
    }
    private boolean sameEditableAlarm(JSONObject a, JSONObject b) {
        for (String key : new String[]{"hour", "minute", "label", "sound", "vibrate", "date", "days",
                "enabled", "groupId", "snoozeEnabled", "snoozeMinutes", "snoozeCount", "vibrationPattern"})
            if (!String.valueOf(a.opt(key)).equals(String.valueOf(b.opt(key)))) return false;
        return true;
    }
    private void savePhoneEdit(JSONObject withId) throws Exception {
        if (mutationBlocked()) { toast("Check the previous phone change first"); return; }
        JSONObject target = new JSONObject(editingTarget);
        JSONObject patch = new JSONObject(withId.toString()); patch.remove("id");
        mutationBusy = true; render();
        PhoneSync.editAlarm(getApplicationContext(), target, patch, (status, request, alarm, revision) ->
                finishPhoneMutation(status, request, true));
    }
    private void deletePhoneAlarm(JSONObject target) {
        if (mutationBlocked()) { toast("Check the previous phone change first"); return; }
        mutationBusy = true; phoneNotice = "Deleting on phone…"; render();
        PhoneSync.deleteAlarm(getApplicationContext(), target, (status, request, alarm, revision) ->
                finishPhoneMutation(status, request, false));
    }
    private void saveOnPhone(LocalDate date) throws Exception {
        if (createBlocked()) { toast("Check the previous phone request first"); return; }
        String cleanLabel = label.trim();
        if (cleanLabel.length() > 200) { toast("Label is too long"); return; }
        JSONObject alarm = new JSONObject().put("hour", hour).put("minute", minute)
                .put("label", cleanLabel).put("date", date == null ? JSONObject.NULL : date.toString())
                .put("sound", sound).put("vibrate", vibrate);
        JSONObject previous = PhoneSync.pendingCreate(this);
        String oldRequest = previous == null ? "" : previous.optString("requestId", "");
        createBusy = true;
        PhoneSync.createAlarm(getApplicationContext(), alarm, (result, requestId, alarmId, revision) -> {
            createBusy = false;
            if ("created".equals(result)) {
                phoneNotice = "Phone confirmed the alarm; updating the list.";
                if (page == 2 && createOnPhone) page = 0;
            } else if ("not_sent".equals(result)) {
                phoneNotice = "Phone unavailable or request not sent. Check the connection and previous request.";
            } else {
                phoneNotice = "Phone alarm not confirmed; check the phone before trying again.";
            }
            if (!isFinishing() && !isDestroyed()) {
                if (page == 0 || (page == 2 && createOnPhone)) render();
                toast(phoneNotice);
            }
        });
        JSONObject current = PhoneSync.pendingCreate(this);
        if (current != null && !oldRequest.equals(current.optString("requestId", "")))
            draftCreateId = current.optString("requestId", "");
        render();
    }
    private void saveResolved(LocalDate date) {
        try {
            JSONObject a = new JSONObject().put("hour", hour).put("minute", minute).put("label", label.trim())
                .put("date", date == null ? JSONObject.NULL : date.toString()).put("days", new JSONArray())
                .put("sound", sound).put("vibrate", vibrate).put("enabled", true).put("groupId", JSONObject.NULL);
            AlarmStore.saveAlarm(this, a);
            page = 0; render(); toast(date == null ? "Watch alarm saved" : "Watch alarm saved for " + date.format(DateTimeFormatter.ISO_LOCAL_DATE));
        } catch (Exception e) { toast(AlarmStore.message(e)); }
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
