package com.openclock;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;
import org.json.JSONObject;

/** Coalesce committed changes and request updates promptly, with a persisted recovery job. */
public final class ComplicationUpdateJob extends JobService {
    private static final int JOB = 0x4f4343;
    private static final Object LOCK = new Object();
    private static final java.util.concurrent.ExecutorService WORK = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static String lastFingerprint;
    private static boolean workerQueued;
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
                if (!workerQueued) {
                    // Replace a job left by the old quota-based implementation as well.
                    if (running == null) schedule(c);
                    workerQueued = true;
                    try { WORK.execute(() -> drainDirect(c)); }
                    catch (RuntimeException e) { workerQueued = false; throw e; }
                }
            }
        } catch (RuntimeException e) {
            // Complication bookkeeping is advisory and must not interrupt alarms or sync.
            android.util.Log.w("OpenClockComplication", "Could not record update");
        }
    }
    private static void schedule(Context c) {
        try {
            JobScheduler scheduler = c.getSystemService(JobScheduler.class);
            JobInfo job = new JobInfo.Builder(JOB, new ComponentName(c, ComplicationUpdateJob.class))
                    .setPersisted(true).setOverrideDeadline(10_000).build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS)
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
        } catch (Exception e) { return null; }
    }
    // Returns false on an error, leaving dirty state for the recovery job or a later event.
    private static boolean drain(Context c, Run run) {
        while (true) {
            long version;
            synchronized (LOCK) {
                if (run != null && run.stopped) return false;
                SharedPreferences p = prefs(c);
                if (!p.getBoolean("dirty", false)) return true;
                version = p.getLong("version", 0);
            }
            String current = fingerprint(c);
            synchronized (LOCK) {
                if (run != null && run.stopped) return false;
                SharedPreferences p = prefs(c);
                if (p.getLong("version", 0) != version) continue;
                if (p.getBoolean("force", false) || current == null || !current.equals(lastFingerprint)) {
                    try {
                        ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, NextAlarmComplication.class)).requestUpdateAll();
                        lastFingerprint = current;
                    } catch (RuntimeException e) {
                        android.util.Log.w("OpenClockComplication", "Could not request update");
                        return false;
                    }
                }
                if (!p.edit().putBoolean("dirty", false).putBoolean("force", false).commit()) return false;
            }
        }
    }
    private static void drainDirect(Context c) {
        try {
            // Collapse the multiple store/sync notifications belonging to one change.
            Thread.sleep(500);
            while (true) {
                boolean ok = drain(c, null);
                synchronized (LOCK) {
                    if (!ok || !prefs(c).getBoolean("dirty", false)) { workerQueued = false; return; }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (LOCK) { workerQueued = false; }
        } catch (RuntimeException e) {
            synchronized (LOCK) { workerQueued = false; }
            android.util.Log.w("OpenClockComplication", "Update failed");
        }
    }
    @Override public boolean onStartJob(JobParameters params) {
        Run run = new Run(params);
        synchronized (LOCK) { running = run; ownRun = run; }
        WORK.execute(() -> {
            try { drain(getApplicationContext(), run); }
            catch (RuntimeException e) { android.util.Log.w("OpenClockComplication", "Update job failed"); }
            finally {
                synchronized (LOCK) {
                    if (!run.stopped && running == run) {
                        running = null;
                        jobFinished(params, false);
                        // A new event can arrive between the final drain and job completion.
                        if (workerQueued && prefs(getApplicationContext()).getBoolean("dirty", false))
                            schedule(getApplicationContext());
                    }
                }
            }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        synchronized (LOCK) {
            Run run = ownRun;
            if (run != null && run.params == params) { run.stopped = true; if (running == run) running = null; }
        }
        return true;
    }
}
