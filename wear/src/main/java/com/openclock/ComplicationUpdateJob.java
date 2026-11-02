package com.openclock;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;
import org.json.JSONObject;

/** Persisted, event-driven coalescing; two initial credits, then one per five minutes. */
public final class ComplicationUpdateJob extends JobService {
    private static final int JOB = 0x4f4343;
    private static final Object LOCK = new Object();
    private static final java.util.concurrent.ExecutorService WORK = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static String lastFingerprint;
    private static Run running;
    private static final class Run {
        final JobParameters params;
        volatile boolean stopped;
        Run(JobParameters params) { this.params = params; }
    }
    private volatile Run ownRun;

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("complication-update-v2", MODE_PRIVATE);
    }
    static void changed(Context context, boolean force) {
        try {
            Context c = context.getApplicationContext();
            synchronized (LOCK) {
                SharedPreferences p = prefs(c);
                if (!p.edit().putLong("version", p.getLong("version", 0) + 1)
                        .putBoolean("dirty", true).putBoolean("force", force || p.getBoolean("force", false)).commit()) return;
                if (running == null) schedule(c, false);
            }
        } catch (RuntimeException e) {
            // Complication bookkeeping is advisory and must not interrupt alarms or sync.
            android.util.Log.w("OpenClockComplication", "Could not record update");
        }
    }
    // Reset conservatively on reboot or unavailable boot identity, never gaining free credits.
    private static long credits(Context c, SharedPreferences p) {
        long now = SystemClock.elapsedRealtime();
        int boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        long value;
        if (!p.contains("credit")) value = ComplicationPolicy.CAPACITY;
        else if (boot >= 0 && boot == p.getInt("boot", -2) && now >= p.getLong("anchor", Long.MAX_VALUE))
            value = ComplicationPolicy.refill(p.getLong("credit", 0), now - p.getLong("anchor", now));
        else value = 0;
        if (!p.edit().putLong("credit", value).putLong("anchor", now).putInt("boot", boot).commit()) return -1;
        return value;
    }
    private static void schedule(Context c, boolean replace) {
        try {
            JobScheduler scheduler = c.getSystemService(JobScheduler.class);
            if (!replace && scheduler.getPendingJob(JOB) != null) return;
            if (Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1) < 0) {
                android.util.Log.w("OpenClockComplication", "Boot identity unavailable; update deferred to system");
                return;
            }
            SharedPreferences p = prefs(c);
            long available = credits(c, p);
            if (available < 0) return;
            long delay = ComplicationPolicy.delay(available);
            JobInfo.Builder job = new JobInfo.Builder(JOB, new ComponentName(c, ComplicationUpdateJob.class))
                    .setPersisted(true).setOverrideDeadline(delay + 10_000);
            if (delay > 0) job.setMinimumLatency(delay);
            if (scheduler.schedule(job.build()) != JobScheduler.RESULT_SUCCESS)
                android.util.Log.w("OpenClockComplication", "Could not schedule update");
        } catch (RuntimeException e) { android.util.Log.w("OpenClockComplication", "Could not schedule update"); }
    }
    private static String fingerprint(Context c) {
        try {
            JSONObject local = AlarmStore.wearSnapshot(c);
            UserManager users = c.getSystemService(UserManager.class);
            JSONObject phone = users != null && users.isUserUnlocked() ? PhoneSync.complicationSnapshot(c) : null;
            return local.getJSONArray("alarms").toString() + "/" + local.getJSONArray("active")
                    + "/" + (phone == null ? "locked" : phone.optString("epoch") + "/" + phone.optLong("revision", -1)
                    + "/" + phone.optLong("updatedAt") + "/" + phone.optJSONArray("alarms")
                    + "/" + phone.optJSONArray("active") + "/" + phone.optBoolean("complicationUnresolved", true)
                    + "/" + (phone.optLong("connectedForMillis") > 0));
        } catch (Exception e) { return "unavailable"; }
    }
    @Override public boolean onStartJob(JobParameters params) {
        Run run = new Run(params);
        synchronized (LOCK) { running = run; ownRun = run; }
        WORK.execute(() -> {
            try { runJob(params, run); }
            catch (RuntimeException e) {
                android.util.Log.w("OpenClockComplication", "Update job failed");
                synchronized (LOCK) {
                    if (!run.stopped && running == run) { running = null; jobFinished(params, false); }
                }
            }
        });
        return true; // Keep the process bound until the executor finishes.
    }
    private void runJob(JobParameters params, Run run) {
        Context c = getApplicationContext();
        long version;
        boolean force;
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            version = p.getLong("version", 0); force = p.getBoolean("force", false);
        }
        String current = fingerprint(c);
        synchronized (LOCK) {
            if (run.stopped || running != run) return;
            SharedPreferences p = prefs(c);
            boolean needsPush = force || !current.equals(lastFingerprint);
            boolean success = !needsPush;
            if (needsPush) {
                long credit = credits(c, p);
                if (credit < 0) success = true; // Retry only on another event or system request.
                if (credit >= ComplicationPolicy.INTERVAL) {
                    if (!p.edit().putLong("credit", credit - ComplicationPolicy.INTERVAL).commit()) {
                        success = true; // Persistence failure: no request and no retry loop.
                    } else try {
                        ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, NextAlarmComplication.class)).requestUpdateAll();
                        lastFingerprint = current; success = true;
                    } catch (RuntimeException e) {
                        android.util.Log.w("OpenClockComplication", "Could not request update");
                        // Do not retry forever; a later event or periodic system request repairs this.
                        success = true;
                    }
                }
            }
            boolean clearFailed = false;
            if (success && p.getLong("version", 0) == version)
                clearFailed = !p.edit().putBoolean("dirty", false).putBoolean("force", false).commit();
            boolean again = !clearFailed && p.getBoolean("dirty", true);
            jobFinished(params, false);
            running = null;
            if (again) schedule(c, true);
        }
    }
    @Override public boolean onStopJob(JobParameters params) {
        synchronized (LOCK) {
            Run run = ownRun;
            if (run != null && run.params == params) { run.stopped = true; if (running == run) running = null; }
        }
        return true;
    }
}
