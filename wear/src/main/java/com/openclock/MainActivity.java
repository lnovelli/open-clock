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
import android.view.HapticFeedbackConstants;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Locale;

/** Small, self-contained watch UI. The watch store and phone cache are separate authorities. */
public class MainActivity extends Activity {
    static final String ACTION_OPEN_COMPLICATION = "com.openclock.action.OPEN_NEXT_ALARM";
    private static final int BG = Color.BLACK, CARD = 0xff28292d, WHITE = 0xfff3f3f5;
    private static final int MUTED = 0xffbec0c8, BLUE = 0xffa7bfff, RED = 0xffffb4ab;
    private static final long FRESH_MS = 5 * 60 * 1000L;
    private WatchUiData data = new WatchUiData();
    private WatchUiWork work;
    private android.widget.FrameLayout windowRoot;
    private View progress;
    private boolean loading, reloadAgain, refreshAfterWork;
    private long dataGeneration;
    private int savedListScroll;
    private boolean renderedListAvailable, restoringListScroll;
    private LinearLayout content;
    private ScrollView scroll;
    private SwipeDismissFrameLayout swipeRoot;
    private int page; // 0 list, 1 time, 2 options
    private int hour, minute, month, day;
    private boolean hasDate, sound = false, vibrate = true;
    private boolean createOnPhone;
    private boolean interruptedOperation;
    private long editingId = -1;
    private String editingOriginal = "";
    private String editingTarget = "";
    private boolean dateChanged, dateChosen;
    private String chosenDate = "";
    private String draftCreateId = "", renderedCreateStatus = "";
    private String label = "";
    private String phoneNotice = "";
    private boolean registered, resumed;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean complicationScrollPending;
    private String complicationScrollKey;
    private View complicationScrollRow;
    private final Runnable expireComplicationScroll = () -> complicationScrollPending = false;
    private final Runnable agePhoneSnapshot = new Runnable() {
        @Override public void run() {
            if (!registered) return;
            if (page == 0) renderListIfChanged();
            uiHandler.postDelayed(this, 60_000L);
        }
    };
    private int renderedPage = -1;
    private String renderedListState = "";
    private final BroadcastReceiver phoneChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            reloadData();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Object retained = getLastNonConfigurationInstance();
        work = retained instanceof WatchUiWork ? (WatchUiWork) retained : new WatchUiWork();
        interruptedOperation = state != null && !(retained instanceof WatchUiWork)
            && state.getBoolean("operationActive", false);
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
            dateChosen = state.getBoolean("dateChosen", false);
            chosenDate = state.getString("chosenDate", "");
        }
        if (state == null && ACTION_OPEN_COMPLICATION.equals(getIntent().getAction())) beginComplicationScroll();
        if (state != null) savedListScroll = state.getInt("listScroll", 0);
        render();
        reloadData();
    }

    @Override public Object onRetainNonConfigurationInstance() { return work; }
    @Override protected void onDestroy() { work.detach(this); super.onDestroy(); }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!ACTION_OPEN_COMPLICATION.equals(intent.getAction())) return;
        if (work.busy()) return;
        if (page != 0) {
            // Leave an in-progress editor and its draft intact underneath the new list.
            startActivity(new Intent(this, MainActivity.class).setAction(ACTION_OPEN_COMPLICATION));
            return;
        }
        beginComplicationScroll();
        render();
    }

    private void beginComplicationScroll() {
        complicationScrollPending = true;
        uiHandler.removeCallbacks(expireComplicationScroll);
        uiHandler.postDelayed(expireComplicationScroll, 5_000L);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (work != null && work.busy()) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && complicationScrollPending) {
            complicationScrollPending = false;
            uiHandler.removeCallbacks(expireComplicationScroll);
        }
        return super.dispatchTouchEvent(event);
    }

    private String nextKnownAlarmKey() {
        return data.nextKey;
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PhoneSync.ACTION_CHANGED);
        filter.addAction(WearSync.LOCAL_CHANGED);
        filter.addAction("com.openclock.PHONE_MIRROR_CHANGED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneChanged, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(phoneChanged, filter);
        registered = true;
        work.attach(this); showWork(); reloadData();
        if (interruptedOperation) toast("Previous update may have completed. Check the list before saving again.");
        uiHandler.postDelayed(agePhoneSnapshot, 60_000L);
        try { PhoneSync.requestSnapshot(this); }
        catch (Exception e) { phoneNotice = "Phone refresh unavailable."; }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (page == 0) renderListIfChanged();
    }

    @Override protected void onPause() { resumed = false; super.onPause(); }

    @Override protected void onStop() {
        work.detach(this);
        uiHandler.removeCallbacks(agePhoneSnapshot);
        uiHandler.removeCallbacks(expireComplicationScroll);
        complicationScrollPending = false;
        if (registered) { unregisterReceiver(phoneChanged); registered = false; }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page); out.putInt("hour", hour); out.putInt("minute", minute);
        out.putInt("month", month); out.putInt("day", day); out.putBoolean("hasDate", hasDate);
        out.putBoolean("sound", sound); out.putBoolean("vibrate", vibrate); out.putString("label", label);
        out.putBoolean("createOnPhone", createOnPhone);
        out.putString("draftCreateId", work.draftRequestId.isEmpty() ? draftCreateId : work.draftRequestId);
        out.putLong("editingId", editingId); out.putString("editingOriginal", editingOriginal);
        out.putString("editingTarget", editingTarget); out.putBoolean("dateChanged", dateChanged);
        out.putBoolean("dateChosen", dateChosen);
        out.putString("chosenDate", chosenDate);
        out.putBoolean("operationActive", work.phase != null || interruptedOperation);
        out.putInt("listScroll", scroll != null && renderedListAvailable && !restoringListScroll && scroll.isLaidOut()
            ? scroll.getScrollY() : savedListScroll);
    }

    @Override public void onBackPressed() {
        if (work.busy()) return;
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
        return data.key + ":" + phoneNotice;
    }
    private void renderListIfChanged() {
        reloadData();
    }
    private void render() {
        if (scroll != null && renderedPage == 0 && renderedListAvailable && !restoringListScroll && scroll.isLaidOut())
            savedListScroll = scroll.getScrollY();
        final int oldScroll = page == 0 ? savedListScroll : 0;
        complicationScrollKey = page == 0 && complicationScrollPending ? nextKnownAlarmKey() : null;
        complicationScrollRow = null;
        if (page == 0) renderedListState = listStateKey();
        renderedPage = page;
        renderedListAvailable = page == 0 && data.available;
        restoringListScroll = false;
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
        windowRoot = new android.widget.FrameLayout(this);
        windowRoot.addView(root);
        setContentView(windowRoot);
        progress = null;
        showWork();
        if (page == 0) list(); else if (page == 1) timePage(); else optionsPage();
        if (scroll != null && complicationScrollPending && complicationScrollRow != null) {
            final ScrollView listScroll = scroll;
            final View target = complicationScrollRow;
            listScroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    if (listScroll.getViewTreeObserver().isAlive()) listScroll.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (complicationScrollPending && page == 0 && scroll == listScroll && target.isAttachedToWindow()) {
                        complicationScrollPending = false;
                        uiHandler.removeCallbacks(expireComplicationScroll);
                        listScroll.scrollTo(0, target.getTop() - (listScroll.getHeight() - target.getHeight()) / 2);
                    }
                    return true;
                }
            });
        } else if (oldScroll > 0 && scroll != null && data.available && !complicationScrollPending) {
            final ScrollView listScroll = scroll;
            restoringListScroll = true;
            listScroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    if (listScroll.getViewTreeObserver().isAlive()) listScroll.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (scroll == listScroll && !complicationScrollPending) {
                        listScroll.scrollTo(0, oldScroll); restoringListScroll = false;
                    }
                    return true;
                }
            });
        }
    }
    void workChanged(boolean refresh) {
        showWork();
        if (work.busy()) {
            if (loading) refreshAfterWork = true;
            return;
        }
        if (refresh || refreshAfterWork) { refreshAfterWork = false; reloadData(); }
        else if (registered && page == 0 && (renderedPage != 0 || !listStateKey().equals(renderedListState))) render();
        else if (registered && page == 2 && createOnPhone && !draftCreateStatus().equals(renderedCreateStatus)) render();
    }
    void showWork() {
        if (windowRoot == null) return;
        if (progress != null) { windowRoot.removeView(progress); progress = null; }
        String phase = work.visiblePhase();
        if (phase == null) {
            if (swipeRoot != null) { swipeRoot.setSwipeable(true); swipeRoot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO); }
            return;
        }
        swipeRoot.setSwipeable(false);
        swipeRoot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LinearLayout shield = column(); shield.setGravity(Gravity.CENTER);
        shield.setPadding(dp(36), dp(28), dp(36), dp(28));
        shield.setBackgroundColor(0xbb000000); shield.setClickable(true); shield.setFocusable(true);
        // Keep the visible watch awake only while this progress view is attached.
        shield.setKeepScreenOn(true);
        shield.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        shield.addView(new android.widget.ProgressBar(this), new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView message = text(phase, 15, WHITE, true); message.setGravity(Gravity.CENTER);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2); mp.topMargin = dp(12);
        shield.addView(message, mp);
        windowRoot.addView(shield, new android.widget.FrameLayout.LayoutParams(-1, -1)); progress = shield;
        shield.setFocusableInTouchMode(true); shield.requestFocus();
    }
    void acceptWorkData(WatchUiData fresh) { dataGeneration++; data = fresh; }
    private void reloadData() {
        // A read started under the admission fence may skip committed-receipt cleanup.
        // Ensure one post-work refresh, but a normal later reload already satisfies it.
        refreshAfterWork = work.busy();
        if (loading) { reloadAgain = true; return; }
        loading = true;
        final long generation = dataGeneration;
        Context app = getApplicationContext();
        WatchUiWork.IO.execute(() -> {
            WatchUiData fresh;
            try { fresh = WatchUiData.read(app); }
            catch (Exception unavailable) { fresh = new WatchUiData(); fresh.key = "unavailable"; }
            final WatchUiData loaded = fresh;
            uiHandler.post(() -> {
                loading = false;
                if (!isDestroyed() && generation == dataGeneration) {
                    data = loaded;
                    if (!data.available) phoneNotice = "Alarm state unavailable; no new action is safe. Reopen to retry.";

                    if (registered && !work.busy()) {
                        if (page == 0 && (renderedPage != 0 || !listStateKey().equals(renderedListState))) render();
                        else if (page == 2 && createOnPhone && !draftCreateStatus().equals(renderedCreateStatus)) render();
                    }
                }
                if (reloadAgain) { reloadAgain = false; reloadData(); }

            });
        });
    }
    interface DiskAction { JSONObject run(Context app) throws Exception; }
    private void diskAction(DiskAction action, boolean leaveEditor, boolean confirm) {
        if (!work.begin("Updating watch storage…")) return;
        WatchUiWork operation = work; Context app = getApplicationContext();
        WatchUiWork.IO.execute(() -> {
            try {
                JSONObject saved = action.run(app);
                operation.finish(a -> {
                    if (leaveEditor) a.page = 0;
                    a.render();
                    if (confirm) {
                        JSONObject current = saved == null ? null : AlarmStore.find(a.data.alarms, saved.optLong("id", -1));
                        a.showAlarmConfirmation(current, false);
                    }
                });
            } catch (Exception failure) {
                String message = AlarmStore.message(failure);
                operation.finish(a -> { a.render(); a.toast(message); });
            }
        });
    }
    interface PhoneAction { void run(Context app, WatchUiWork operation) throws Exception; }
    private void startPhoneOperation(PhoneAction action) { startPhoneOperation("Updating sync storage…", action); }
    private void startPhoneOperation(String phase, PhoneAction action) {
        if (!work.begin(phase)) return;
        WatchUiWork operation = work; Context app = getApplicationContext();
        WatchUiWork.IO.execute(() -> {
            try {
                action.run(app, operation);
                uiHandler.post(operation::syncing);
            } catch (Exception failure) {
                operation.finish(a -> { a.phoneNotice = "Phone result unknown; check the phone before trying again."; a.render(); a.toast(a.phoneNotice); });
            }
        });
    }
    private void clearPhoneStatus(boolean creation, String request) {
        if (!work.begin("Updating sync storage…")) return;
        WatchUiWork operation = work; Context app = getApplicationContext();
        WatchUiWork.IO.execute(() -> {
            try {
                boolean cleared = creation ? PhoneSync.forgetCreate(app, request) : PhoneSync.forgetMutation(app, request);
                operation.finish(a -> {
                    a.phoneNotice = cleared ? "Local request status cleared; check the phone before another change."
                        : "Request still being checked; try later.";
                    a.render(); a.toast(a.phoneNotice);
                });
            } catch (Exception unavailable) {
                operation.finish(a -> { a.render(); a.toast("Local request status unavailable; check the phone"); });
            }
        });
    }
    private void refreshPhone() {
        startPhoneOperation("Synchronizing with phone…", (app, op) -> {
            long deadline = android.os.SystemClock.elapsedRealtime() + 25_000L;
            try { awaitExplicitRefresh(op, PhoneSync.requestExplicitRefresh(app), deadline); }
            catch (Exception unavailable) {
                op.finish(a -> { a.phoneNotice = "Phone refresh unavailable; cached state may be stale."; a.render(); a.toast(a.phoneNotice); });
            }
        });
    }
    private static void awaitExplicitRefresh(WatchUiWork operation, PhoneSync.RefreshAttempt attempt, long deadline) {
        // A successful DataItem read can precede the phone's asynchronous publication,
        // even when the data differs from our baseline. It is not a request receipt.
        String status = PhoneSync.explicitRefreshStatus(attempt);
        boolean failed = "send_failed".equals(status) || "read_failed".equals(status)
                || "local_incomplete".equals(status) || "owner_changed".equals(status);
        if (failed || "newer".equals(status) || android.os.SystemClock.elapsedRealtime() >= deadline) {
            String notice = "newer".equals(status) ? "Newer phone snapshot received."
                : "send_failed".equals(status) ? "Phone refresh request failed; cached state may be stale."
                : "read_failed".equals(status) ? "Phone snapshot could not be read or accepted; cached state may be stale."
                : "local_incomplete".equals(status) ? "Phone snapshot read; local sync incomplete. Check cached state."
                : "owner_changed".equals(status) ? "Phone identity changed during refresh; check the phone list."
                : "unchanged".equals(status) ? "Same phone snapshot read; refresh not confirmed. Cached state may be stale."
                : "read".equals(status) ? "Phone snapshot read; refresh not confirmed. Cached state may be stale."
                : "Phone refresh not confirmed; cached state may be stale.";
            operation.finish(a -> { a.phoneNotice = notice; a.render(); a.toast(notice); });
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(() -> WatchUiWork.IO.execute(() ->
                awaitExplicitRefresh(operation, attempt, deadline)), 400L);
        }
    }
    private void phoneResult(WatchUiWork operation, String status, long id, long revision, String epoch, boolean closeEditor) {
        Context app = getApplicationContext();
        if (!"created".equals(status) && !"committed".equals(status)) {
            operation.finish(a -> {
                if (!operation.draftRequestId.isEmpty()) a.draftCreateId = operation.draftRequestId;
                a.phoneNotice = "not_sent".equals(status) ? "Request not sent. Check the connection and previous request."
                    : "Phone result unconfirmed; check the phone before trying again. It may already have changed.";
                a.render(); a.toast(a.phoneNotice);
            });
            return;
        }
        WatchUiWork.IO.execute(() -> {
            try {
                String ownerEpoch = epoch;
                if (ownerEpoch.isEmpty()) {
                    JSONObject receipt = "created".equals(status) ? PhoneSync.pendingCreate(app) : PhoneSync.pendingMutation(app);
                    if (receipt != null) ownerEpoch = receipt.optString("epoch");
                }
                PhoneSync.requestSnapshot(app);
                awaitPhone(operation, app, ownerEpoch, revision, id, null, id > 0, closeEditor, -1,
                    android.os.SystemClock.elapsedRealtime() + 15_000L);
            } catch (Exception unavailable) {
                operation.finish(a -> { a.phoneNotice = "Phone acknowledged; local confirmation unavailable. Check the phone."; a.render(); a.toast(a.phoneNotice); });
            }
        });
    }
    private static void awaitPhone(WatchUiWork operation, Context app, String epoch, long revision,
            long id, Boolean expected, boolean confirm, boolean closeEditor, long updatedAfter, long deadline) {
        // Runs on IO. Poll only accepted snapshots; never replay a mutation on timeout.
        JSONObject snapshot;
        try { synchronized (PhoneSync.class) { snapshot = new JSONObject(PhoneSync.cached(app).toString()); } }
        catch (Exception unavailable) { snapshot = new JSONObject(); }
        boolean matched = phoneSnapshotMatched(snapshot, epoch, revision, id, expected, updatedAfter);
        if (matched || android.os.SystemClock.elapsedRealtime() >= deadline) {
            final boolean observed = matched;
            operation.finish(a -> {
                if (closeEditor) a.page = 0;
                a.phoneNotice = observed ? "Phone list updated." : updatedAfter >= 0
                    ? "Phone refresh not confirmed; cached state may be stale."
                    : "Phone acknowledged the change; updated list not confirmed. Check the phone before retrying.";
                a.render();
                JSONObject current = null;
                JSONObject latest = a.snapshot(); JSONArray latestRows = latest.optJSONArray("alarms");
                if (observed && confirm && epoch.equals(latest.optString("epoch"))
                        && latest.optLong("revision", -1) >= revision && latestRows != null)
                    current = AlarmStore.find(latestRows, id);
                if (current != null && current.optBoolean("enabled")) a.showAlarmConfirmation(current, true);
                else a.toast(a.phoneNotice);
            });
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(() -> WatchUiWork.IO.execute(() ->
                awaitPhone(operation, app, epoch, revision, id, expected, confirm, closeEditor, updatedAfter, deadline)), 400L);
        }
    }
    static boolean phoneSnapshotMatched(JSONObject snapshot, String epoch, long revision,
            long id, Boolean expected, long updatedAfter) {
        boolean matched = updatedAfter >= 0 ? snapshot.optLong("updatedAt") > updatedAfter
            : revision >= 0 && !epoch.isEmpty() && epoch.equals(snapshot.optString("epoch")) && snapshot.optLong("revision", -1) >= revision;
        if (!matched || expected == null) return matched;
        JSONArray rows = snapshot.optJSONArray("alarms");
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && row.optLong("id") == id)
                return row.opt("enabled") instanceof Boolean && row.optBoolean("enabled") == expected;
        }
        return false;
    }
    private void title(String s) { TextView t = text(s, 24, WHITE, true); t.setGravity(Gravity.CENTER); add(t, 0); }
    private void section(String s) { TextView t = text(s, 16, WHITE, true); add(t, 20); }
    private void note(String s) { TextView t = text(s, 12, MUTED, false); add(t, 8); }
    private Button button(String text, int color, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(WHITE); b.setMinHeight(dp(48)); b.setBackground(shape(color, 28));
        b.setOnClickListener(v -> { if (!work.busy() && data.available) action.run(); }); return b;
    }
    private void list() {
        title("Alarm");
        if (!data.available) { note("unavailable".equals(data.key) ? "Alarm state unavailable; reopen to retry." : "Loading alarm state…"); return; }
        activeControls();
        permissions();
        section("Watch alarms");
        try {
            JSONArray alarms = data.alarms;
            if (alarms.length() == 0) note("No watch alarms yet");
            for (JSONObject a : data.orderedAlarms) add(alarmRow(a, false), 8);
        } catch (Exception e) { note("Watch alarms unavailable: " + AlarmStore.message(e)); }
        add(button("+  Add on watch", 0xff3d465d, () -> beginCreate(false)), 10);
        section("Phone alarms");
        Button createPhone = button("+  Add phone alarm", 0xff3d465d, () -> beginCreate(true));
        createPhone.setEnabled(!createBlocked());
        add(createPhone, 10);
        phoneList();
        syncStatus();
        if (connected(snapshot())) add(button("Refresh phone", CARD, this::refreshPhone), 10);
        add(button("Test double shake", CARD, () -> startActivity(new Intent(this, ShakeTestActivity.class))), 20);
        add(button("Check for updates", CARD, () -> UpdateChecker.show(this, "watch")), 20);
    }

    private void activeControls() {
        JSONArray local = data.active;
        java.util.List<JSONObject> mirrors = data.mirrors;
        if (local.length() == 0 && mirrors.isEmpty()) return;
        section("Ringing now");
        for (int i = 0; i < local.length(); i++) {
            JSONObject alarm = local.optJSONObject(i);
            if (alarm == null || alarm.optJSONObject("_active") == null) continue;
            String label = alarm.optString("label", "").trim();
            String origin = alarm.optBoolean("_phoneFallback") ? "Phone fallback" : "Watch alarm";
            String text = origin + (label.isEmpty() ? "" : ": " + label);
            long id = alarm.optLong("id");
            String token = alarm.optJSONObject("_active").optString("token");
            add(button(text, 0xff4b287f, () -> {
                openActive(id, token, false);
            }), 8);
        }
        for (JSONObject alarm : mirrors) {
            String label = alarm.optString("label", "").trim();
            String text = "Phone alarm" + (label.isEmpty() ? "" : ": " + label);
            long id = alarm.optLong("id"); String token = alarm.optString("token");
            add(button(text, 0xff4b287f, () -> {
                openActive(id, token, true);
            }), 8);
        }
    }

    private void openActive(long id, String token, boolean mirror) {
        if (!work.begin("Opening ringing alarm…")) return;
        WatchUiWork operation = work; Context app = getApplicationContext();
        WatchUiWork.IO.execute(() -> {
            try {
                JSONObject current = mirror ? PhoneMirror.current(app, id, token) : AlarmStore.active(app, id, token);
                if (current == null) operation.finish(a -> { });
                else operation.finishReadOnly(a -> a.startActivity(mirror ? PhoneMirror.openIntent(a, current) : AlarmNotifications.alertIntent(a, current)));
            } catch (Exception unavailable) { operation.finish(a -> a.toast("Ringing state unavailable; reopen controls")); }
        });
    }
    private boolean createBlocked() {
        try {
            JSONObject pendingCreate = data.creation;
            return pendingCreate != null && (!"created".equals(pendingCreate.optString("status"))
                    || (page != 0 && createOnPhone && !draftCreateId.isEmpty()
                    && draftCreateId.equals(pendingCreate.optString("requestId"))));
        } catch (RuntimeException unreadable) { return true; }
    }

    private String draftCreateStatus() {
        try {
            JSONObject pendingCreate = data.creation;
            return pendingCreate != null && draftCreateId.equals(pendingCreate.optString("requestId"))
                    ? pendingCreate.optString("status", "unknown") : "";
        } catch (RuntimeException unreadable) { return "unavailable"; }
    }

    private void createStatus() {
        JSONObject pendingCreate;
        try { pendingCreate = data.creation; }
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
                    clearPhoneStatus(true, requestId);
                }).show();
        }), 8);
    }

    private void checkCreateResult() {
        startPhoneOperation("Synchronizing with phone…", (app, op) -> PhoneSync.reconcileCreate(app, (status, request, alarm, revision) ->
            phoneResult(op, status, alarm, revision, "", false)));
    }

    private boolean mutationBlocked() {
        try { return data.mutation != null; }
        catch (RuntimeException unreadable) { return true; }
    }
    private boolean receiptObserved(JSONObject receipt, JSONObject cache) {
        return receipt != null && receipt.optString("epoch", "").equals(cache.optString("epoch", ""))
            && !receipt.optString("epoch", "").isEmpty() && receipt.optLong("revision", -1) >= 0
            && cache.optLong("revision", -1) >= receipt.optLong("revision", -1);
    }
    private void mutationStatus() {
        JSONObject p;
        try { p = data.mutation; }
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
                // Cleared only by an explicit IO action; rendering never writes disk.
                note("Phone confirmed the change; clearing its local status is pending.");
                add(button("Clear confirmed change", CARD, () -> {
                    clearPhoneStatus(false, request);
                }), 8);
                return;
            }
            note("Phone confirmed the change; waiting for the updated list.");
            add(button("Refresh phone", CARD, this::refreshPhone), 8);
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
                clearPhoneStatus(false, request);
            }).show()), 8);
    }
    private void checkMutationResult() {
        startPhoneOperation("Synchronizing with phone…", (app, op) -> PhoneSync.reconcileMutation(app, (status, request, alarm, revision) ->
            phoneResult(op, status, alarm, revision, "", false)));
    }


    private void beginCreate(boolean phone) {
        Calendar c = Calendar.getInstance();
        hour = c.get(Calendar.HOUR_OF_DAY); minute = c.get(Calendar.MINUTE);
        month = c.get(Calendar.MONTH) + 1; day = c.get(Calendar.DAY_OF_MONTH);
        hasDate = false; sound = false; vibrate = true; label = "";
        editingId = -1; editingOriginal = ""; editingTarget = ""; dateChanged = false; dateChosen = false; chosenDate = "";
        draftCreateId = ""; work.draftRequestId = ""; interruptedOperation = false;
        createOnPhone = phone; page = 1; render();
    }

    private void beginEdit(JSONObject alarm, boolean phone, JSONObject target) {
        try {
            if (phone && (target == null || mutationBlocked())) { toast("Check the previous phone change first"); return; }
            interruptedOperation = false;
            editingId = alarm.getLong("id"); editingOriginal = alarm.toString();
            editingTarget = phone ? target.toString() : "";
            hour = alarm.getInt("hour"); minute = alarm.getInt("minute");
            label = alarm.optString("label", ""); sound = alarm.optBoolean("sound", true);
            vibrate = alarm.optBoolean("vibrate", true); dateChanged = false; dateChosen = false;
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
        try { problem = data.accessProblem; }
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

        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            android.app.NotificationChannel mirror = nm == null ? null : nm.getNotificationChannel(PhoneMirror.CHANNEL);
            if (mirror == null || mirror.getImportance() < NotificationManager.IMPORTANCE_HIGH) {
                note("Set Phone alarms notifications to High for prominent alerts.");
                add(button("Phone alarm channel", CARD, () -> channelSettings(PhoneMirror.CHANNEL)), 8);
            }
        } catch (RuntimeException unavailable) {
            note("Phone alarm notification settings could not be checked.");
            add(button("Notification settings", CARD, this::notificationSettings), 8);
        }
    }
    private void channelSettings(String channel) {
        try { startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, channel)); }
        catch (Exception e) { notificationSettings(); }
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
        if (complicationScrollPending && complicationScrollRow == null
                && ((phone ? "phone:" : "watch:") + a.optLong("id", -1)).equals(complicationScrollKey))
            complicationScrollRow = row;
        row.setPadding(dp(14), dp(8), dp(8), dp(8)); row.setMinimumHeight(dp(66)); row.setBackground(shape(CARD, 26));
        LinearLayout details = column(); details.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = text(String.format(Locale.getDefault(), "%02d:%02d", a.optInt("hour"), a.optInt("minute")), 22, WHITE, true);
        LinearLayout timeLine = new LinearLayout(this); timeLine.setGravity(Gravity.CENTER_VERTICAL);
        timeLine.addView(time, new LinearLayout.LayoutParams(-2, -2, 1));
        AlarmOutputIcons.addTo(timeLine, a, MUTED, 14);
        details.addView(timeLine, new LinearLayout.LayoutParams(-2, -2)); String l = a.optString("label", "").trim();
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
        String ownership=a.optString("ownershipWarning",a.optString("fallbackNotice",""));
        if(!ownership.isEmpty()) details.addView(text(ownership,11,BLUE,false));
        String error = a.optString("schedulingError", "");
        if (!error.isEmpty() && !"null".equals(error)) { TextView e = text("Not scheduled: " + error, 11, RED, false); details.addView(e); }
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        final long id = a.optLong("id", -1); final boolean old = a.optBoolean("enabled", false);
        Switch toggle = new Switch(this); toggle.setMinWidth(dp(48)); toggle.setMinHeight(dp(48));
        toggle.setChecked(old); toggle.setContentDescription((phone ? "Phone" : "Watch") + " alarm " + time.getText() + (l.isEmpty() ? "" : ", " + l));
        if (phone && (id <= 0 || !connected(snapshot()))) toggle.setEnabled(false);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (work.busy()) { button.setOnCheckedChangeListener(null); button.setChecked(old); return; }
            if (phone) {
                button.setOnCheckedChangeListener(null); button.setChecked(old);
                phoneToggle(id, checked);
            } else {
                diskAction(app -> AlarmStore.saveAlarm(app, new JSONObject().put("id", id).put("enabled", checked)), false, checked);
            }
        });
        row.addView(toggle);
        details.setContentDescription((phone ? "Phone" : "Watch") + " alarm " + time.getText() + AlarmOutputIcons.description(a)
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
        if (work.busy() || !data.available) return;
        if (!phone) {
            if (directDelete) confirmDelete(displayed, false, null);
            else if (actions) alarmActions(displayed, false, null);
            else beginEdit(displayed, false, null);
            return;
        }
        // A no-op phone publication can advance the revision without redrawing
        // this row. Resolve it at the moment of the tap, but only if the
        // displayed alarm still matches the authoritative cached fields.
        if (mutationBlocked()) { toast("Check the previous phone change first"); return; }
        if (!work.begin("Reading phone alarm…")) return;
        WatchUiWork operation = work; Context app = getApplicationContext();
        WatchUiWork.IO.execute(() -> {
            JSONObject target = PhoneSync.captureMutationTarget(app, displayed.optLong("id", -1));
            JSONObject current = target == null ? null : target.optJSONObject("alarm");
            if (current == null || !sameEditableAlarm(displayed, current)) {
                operation.finish(a -> a.toast("Phone changed or disconnected; refresh the list"));
                return;
            }
            operation.finishReadOnly(a -> {
                if (directDelete) a.confirmDelete(current, true, target);
                else if (actions) a.alarmActions(current, true, target);
                else a.beginEdit(current, true, target);
            });
        });
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
                else diskAction(app -> { deleteLocalIfUnchanged(app, alarm); return null; }, false, false);
            }).show();
    }
    private static void deleteLocalIfUnchanged(Context app, JSONObject expected) throws Exception {
        long id = expected.optLong("id", -1);
        synchronized (AlarmStore.class) {
            JSONArray alarms = AlarmStore.alarms(app); JSONObject current = null;
            for (int i = 0; i < alarms.length(); i++) {
                JSONObject a = alarms.optJSONObject(i);
                if (a != null && a.optLong("id", -1) == id) { current = a; break; }
            }
            if (current == null || !sameEditableAlarm(expected, current))
                throw new IllegalStateException("Alarm changed; return to the list and reopen it");
            AlarmStore.deleteAlarm(app, id);
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
        return data.phone;
    }
    private boolean connected(JSONObject o) { return o.optBoolean("connected", false); }
    private void syncStatus() {
        try {
            JSONArray snoozes=data.snoozes;
            for(int i=0;i<snoozes.length();i++) {
                JSONObject request=snoozes.getJSONObject(i);
                if("expired".equals(request.optString("state"))) note("Snooze missed while watch unavailable; check both devices.");
                if("pending".equals(request.optString("state"))) note(request.has("receipt")?
                    "Phone snooze committed; watch fallback registration pending. Original watch ring retained.":"Phone snooze unconfirmed; watch-only recovery pending.");
                else if("branched".equals(request.optString("state"))) {
                    JSONObject branch=AlarmStore.find(data.alarms,request.optLong("branchId"));
                    if(branch!=null&&branch.optBoolean("enabled")) note(request.has("receipt")?
                        "Phone also committed this snooze. Independent watch snooze retained; possible duplicates.":"Watch-only snooze; phone result unknown. Possible duplicates.");
                }
            }
        } catch(Exception unavailable) { note("Snooze ownership state unavailable"); }
        createStatus();
        mutationStatus();
        JSONObject cache = snapshot(); boolean online = connected(cache); long updated = cache.optLong("updatedAt", 0);
        long age = System.currentTimeMillis() - updated;
        String fallbackProblem = data.fallbackProblem;
        if (!fallbackProblem.isEmpty()) note("Watch phone-alarm fallback problem: " + fallbackProblem);
        if (!online) note("Phone unavailable. Cached alarms may be stale; controls are disabled.");
        else if (updated <= 0 || age < 0 || age > FRESH_MS) note("Phone connected · snapshot is stale or not yet available.");
        if (!phoneNotice.isEmpty()) note(phoneNotice);
    }
    private void phoneList() {
        JSONObject cache = snapshot(); boolean online = connected(cache);
        WatchSchedule.View committed;
        try { committed=data.schedule; }
        catch(Exception unavailable) { note("Committed watch fallback state unavailable"); return; }
        for(int i=0;i<committed.retained.length();i++) {
            JSONObject row=committed.retained.optJSONObject(i);
            TextView retained=text("Retained phone fallback (uncertain): "+row.optString("label")+" · "+
                (row.optBoolean("ringing")?"ringing":row.optLong("nextTriggerAt")>0?
                    new java.text.SimpleDateFormat("HH:mm · EEE, d MMM",Locale.getDefault()).format(new java.util.Date(row.optLong("nextTriggerAt"))):"check scheduling"),12,BLUE,false);
            add(retained,8);
            if(complicationScrollPending&&complicationScrollRow==null&&("fallback:"+row.optLong("id")).equals(complicationScrollKey)) complicationScrollRow=retained;
        }
        JSONArray alarms = committed.phone, groups = cache.optJSONArray("groups");
        if (alarms == null) { note(online ? "Waiting for phone alarms…" : "No cached phone alarms."); return; }
        if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i); if (g == null || g.optLong("id", -1) <= 0) continue;
            long groupId = g.optLong("id");
            TextView heading = text(g.optString("name", "Group"), 14, BLUE, true); add(heading, 14);
            java.util.List<JSONObject> rows = data.grouped.get(groupId);
            if (rows != null) for (JSONObject a : rows) add(alarmRow(a, true), 8);
            if (rows == null || rows.isEmpty()) note("No alarms in this group");
        }
        int rest = 0;
        for (JSONObject a : data.other) {
            if (rest++ == 0 && groups != null && groups.length() > 0) { TextView h = text("Other alarms", 14, BLUE, true); add(h, 14); }
            add(alarmRow(a, true), 8);
        }
        if (alarms.length() == 0 && (groups == null || groups.length() == 0)) note("No phone alarms in snapshot");
    }
    private void phoneToggle(long id, boolean enabled) {
        if (id <= 0 || !connected(snapshot())) { toast("Phone unavailable; change not sent."); return; }
        startPhoneOperation((app, op) -> {
            synchronized (PhoneSync.class) {
                JSONObject base = PhoneSync.cached(app);
                String epoch = base.optString("epoch");
                JSONArray rows = base.optJSONArray("alarms"); JSONObject row = null;
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject candidate = rows.optJSONObject(i);
                    if (candidate != null && candidate.optLong("id", -1) == id) { row = candidate; break; }
                }
                // Capture only the row paired with the command's cached revision.
                final String cachedDate = row != null && row.opt("date") instanceof String ? row.optString("date") : "";
                final int cachedHour = row == null ? -1 : row.optInt("hour", -1);
                final int cachedMinute = row == null ? -1 : row.optInt("minute", -1);
                PhoneSync.commandWithRevision(app, new JSONObject().put("op", "setEnabled").put("id", id).put("enabled", enabled),
                    (ok, safeMessage, revision) -> {
                        if (!ok && enabled && WatchDateChoice.appearsPast(cachedDate, cachedHour, cachedMinute,
                                System.currentTimeMillis(), ZoneId.systemDefault())) op.finish(a -> {
                            a.phoneNotice = "Cached phone date " + cachedDate + " and time appear past on this watch. "
                                + "The cache or phone clock/zone may differ; edit the date on the phone if needed. "
                                + "Phone result unconfirmed; check the phone before retrying. It may already have changed.";
                            a.render(); a.toast(a.phoneNotice);
                        });
                        else if (!ok) phoneResult(op, "unknown", id, -1, epoch, false);
                        else if (revision < 0) op.finish(a -> {
                            a.phoneNotice = "Phone acknowledged the change; updated list not confirmed. Check the phone before retrying.";
                            a.render(); a.toast(a.phoneNotice);
                        });
                        else WatchUiWork.IO.execute(() -> awaitPhone(op, app, epoch, revision, id, enabled,
                            enabled, false, -1, android.os.SystemClock.elapsedRealtime() + 15_000L));
                    });
            }
        });
    }



    private NumberPicker picker(int min, int max, int value, NumberPicker.OnValueChangeListener listener) {
        NumberPicker p = new NumberPicker(this); p.setMinValue(min); p.setMaxValue(max); p.setValue(value);
        p.setWrapSelectorWheel(true); p.setFormatter(v -> String.format(Locale.getDefault(), "%02d", v));
        p.setOnValueChangedListener(listener); return p;
    }
    private void timePage() {
        title("Time");
        LinearLayout pickers = new LinearLayout(this); pickers.setGravity(Gravity.CENTER);
        NumberPicker h = picker(0, 23, hour, (p, old, val) -> { hour = val; p.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); });
        NumberPicker m = picker(0, 59, minute, (p, old, val) -> { minute = val; p.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); });
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
        boolean blocked = interruptedOperation || (createOnPhone && (editingId <= 0 ? createBlocked() : mutationBlocked()));
        Button saveButton = button(interruptedOperation ? "Check list first" : blocked ? "Phone pending" : "Save", 0xff43557d, this::save);
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
        final int[] selection = {month, day};
        final String draft = saveDraftKey();
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER);
        NumberPicker m = picker(1, 12, month, (p, old, val) -> selection[0] = val);
        NumberPicker d = picker(1, 31, day, (p, old, val) -> selection[1] = val);
        m.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS); d.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        m.setContentDescription("Month"); d.setContentDescription("Day of month");
        row.addView(m, new LinearLayout.LayoutParams(0, dp(125), 1)); row.addView(text("/", 22, WHITE, true));
        row.addView(d, new LinearLayout.LayoutParams(0, dp(125), 1));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Month / day").setView(row)
            .setNegativeButton("Cancel", null)
            .setNeutralButton(hasDate ? "Clear" : null, (x, y) -> {
                if (!editorReady() || !draft.equals(saveDraftKey())) return;
                hasDate = false; chosenDate = ""; dateChosen = false; dateChanged = originalDateChanged(null); render();
            })
            .setPositiveButton("Use date", null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                if (!editorReady() || !draft.equals(saveDraftKey())) { dialog.dismiss(); return; }
                LocalDate chosen = WatchDateChoice.resolve(selection[0], selection[1], hour, minute,
                        System.currentTimeMillis(), ZoneId.systemDefault());
                dialog.dismiss();
                boolean repeats = false;
                if (editingId > 0) {
                    JSONArray days = new JSONObject(editingOriginal).optJSONArray("days"); repeats = days != null && days.length() > 0;
                }
                String warning = repeats ? " This replaces the repeating schedule." : "";
                new AlertDialog.Builder(this).setTitle("One-time date")
                    .setMessage("Alarm for " + chosen + "." + warning)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Use date", (d1, w) -> {
                        if (!editorReady() || !draft.equals(saveDraftKey())) return;
                        month = selection[0]; day = selection[1]; hasDate = true; chosenDate = chosen.toString();
                        dateChosen = true; dateChanged = originalDateChanged(chosenDate); render();
                    }).show();
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
    private void save() {
        saveChecked(null, null, null, false);
    }
    private boolean editorReady() {
        return registered && resumed && !isFinishing() && !isDestroyed() && page == 2 && !work.busy();
    }
    private String saveDraftKey() {
        return new JSONArray().put(page).put(hour).put(minute).put(month).put(day).put(hasDate)
            .put(sound).put(vibrate).put(label).put(createOnPhone).put(editingId)
            .put(editingOriginal).put(editingTarget).put(dateChanged).put(dateChosen).put(chosenDate)
            .put(draftCreateId).put(interruptedOperation).toString();
    }
    private void saveChecked(String expectedDraft, LocalDate confirmedDate, String expectedZone, boolean scheduleConfirmed) {
        if (!editorReady()) return;
        if (interruptedOperation) { toast("Previous update may have completed. Return to the list and check before saving again."); return; }
        if (expectedDraft != null && !expectedDraft.equals(saveDraftKey())) {
            toast("Alarm changed; review it and save again"); return;
        }
        try {
            ZoneId zone = ZoneId.systemDefault(); long now = System.currentTimeMillis();
            if (expectedZone != null && !expectedZone.equals(zone.getId()))
                throw new IllegalArgumentException("Time zone changed; review the alarm and save again");
            LocalDate date = hasDate && (editingId <= 0 || dateChanged || dateChosen)
                ? WatchDateChoice.resolve(month, day, hour, minute, now, zone) : null;
            if (confirmedDate != null && !confirmedDate.equals(date))
                throw new IllegalArgumentException("Date changed; review the alarm and save again");
            // An unchosen existing fixed date stays fixed, even when the time is edited.
            // Do not pre-judge a phone-owned alarm using the watch's clock or zone.
            if (!createOnPhone && hasDate && editingId > 0 && date == null) {
                JSONObject original = new JSONObject(editingOriginal);
                if (!original.optBoolean("enabled", false) || scheduleChanged())
                    AlarmTimes.next(hour, minute, chosenDate, java.util.Collections.emptySet(), now, zone);
            }
            String draft = saveDraftKey(); String zoneId = zone.getId();
            if (date != null && confirmedDate == null && WatchDateChoice.needsConfirmation(date, chosenDate, now, zone)) {
                int year = java.time.Instant.ofEpochMilli(now).atZone(zone).getYear();
                String notice = date.getYear() == year + 1
                    ? "This alarm will be set for next year: " + date + ". Save?"
                    : date.getYear() > year ? "This alarm will be set for a future year: " + date + ". Save?"
                    : "Save alarm for " + date + "?";
                new AlertDialog.Builder(this).setTitle("Confirm alarm date")
                    .setMessage(notice)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save", (d, w) -> saveChecked(draft, date, zoneId, false)).show();
            } else if (editingId > 0 && (scheduleChanged() || (date != null && originalDateChanged(date.toString()))) && !scheduleConfirmed) {
                new AlertDialog.Builder(this).setTitle("Change schedule?")
                    .setMessage("Changing the schedule can stop an active ring or snooze.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save", (d, w) -> saveChecked(draft, date, zoneId, true)).show();
            } else {
                if (date != null) { chosenDate = date.toString(); dateChanged = originalDateChanged(chosenDate); }
                if (editingId > 0) saveEdit(date);
                else if (createOnPhone) saveOnPhone(date); else saveResolved(date);
            }
        } catch (Exception e) { toast(AlarmStore.message(e)); }
    }
    private boolean scheduleChanged() {
        try {
            JSONObject old = new JSONObject(editingOriginal);
            return hour != old.getInt("hour") || minute != old.getInt("minute") || dateChanged;
        } catch (Exception e) { return true; }
    }
    private void saveEdit(LocalDate date) throws Exception {
        JSONObject original = new JSONObject(editingOriginal);
        if (!editChanged() && original.optBoolean("enabled", false)) { page = 0; render(); return; }
        JSONObject patch = new JSONObject().put("id", editingId).put("enabled", true);
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
        final long id = editingId;
        diskAction(app -> {
            synchronized (AlarmStore.class) {
                JSONObject current = AlarmStore.find(AlarmStore.alarms(app), id);
                if (current == null || !sameEditableAlarm(original, current))
                    throw new IllegalStateException("Alarm changed; return to the list and reopen it");
                return AlarmStore.saveAlarm(app, patch);
            }
        }, true, true);
    }
    private static boolean sameEditableAlarm(JSONObject a, JSONObject b) {
        for (String key : new String[]{"hour", "minute", "label", "sound", "vibrate", "date", "days",
                "enabled", "groupId", "snoozeEnabled", "snoozeMinutes", "snoozeCount", "vibrationPattern"})
            if (!String.valueOf(a.opt(key)).equals(String.valueOf(b.opt(key)))) return false;
        return true;
    }
    private void savePhoneEdit(JSONObject withId) throws Exception {
        if (mutationBlocked()) { toast("Check the previous phone change first"); return; }
        JSONObject target = new JSONObject(editingTarget);
        JSONObject patch = new JSONObject(withId.toString()); patch.remove("id");
        startPhoneOperation((app, op) -> PhoneSync.editAlarm(app, target, patch, (status, request, alarm, revision) ->
            phoneResult(op, status, alarm, revision, target.optString("epoch"), true)));
    }
    private void deletePhoneAlarm(JSONObject target) {
        if (mutationBlocked()) { toast("Check the previous phone change first"); return; }
        startPhoneOperation((app, op) -> PhoneSync.deleteAlarm(app, target, (status, request, alarm, revision) ->
            phoneResult(op, status, 0, revision, target.optString("epoch"), false)));
    }
    private void saveOnPhone(LocalDate date) throws Exception {
        if (createBlocked()) { toast("Check the previous phone request first"); return; }
        String cleanLabel = label.trim();
        if (cleanLabel.length() > 200) { toast("Label is too long"); return; }
        JSONObject alarm = new JSONObject().put("hour", hour).put("minute", minute)
            .put("label", cleanLabel).put("date", date == null ? JSONObject.NULL : date.toString())
            .put("sound", sound).put("vibrate", vibrate);
        startPhoneOperation((app, op) -> {
            JSONObject before = PhoneSync.pendingCreate(app);
            String previous = before == null ? "" : before.optString("requestId");
            PhoneSync.createAlarm(app, alarm, (status, request, id, revision) -> {
                if (!request.isEmpty()) op.draftRequestId = request;
                phoneResult(op, status, id, revision, "", true);
            });
            JSONObject after = PhoneSync.pendingCreate(app);
            if (after != null && !previous.equals(after.optString("requestId"))) op.draftRequestId = after.optString("requestId");
        });
    }
    private void saveResolved(LocalDate date) {
        try {
            JSONObject a = new JSONObject().put("hour", hour).put("minute", minute).put("label", label.trim())
                .put("date", date == null ? JSONObject.NULL : date.toString()).put("days", new JSONArray())
                .put("sound", sound).put("vibrate", vibrate).put("enabled", true).put("groupId", JSONObject.NULL);
            diskAction(app -> AlarmStore.saveAlarm(app, a), true, true);
        } catch (Exception e) { toast(AlarmStore.message(e)); }
    }
    private void showAlarmConfirmation(JSONObject alarm, boolean phone) {
        String message = null;
        try {
            long now = System.currentTimeMillis();
            if ((phone || (data.available && data.accessProblem == null)) && alarm != null
                    && alarm.optBoolean("enabled") && alarm.isNull("schedulingError"))
                message = AlarmConfirmation.message(alarm.optLong("nextTriggerAt", 0), now,
                        ZoneId.systemDefault(), Locale.getDefault());
        } catch (RuntimeException ignored) { /* Save succeeded; formatting must not report it as failed. */ }
        toast(message == null ? (phone ? "Phone alarm" : "Watch alarm")
                + " saved; next trigger unavailable. Check the alarm list." : message);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
