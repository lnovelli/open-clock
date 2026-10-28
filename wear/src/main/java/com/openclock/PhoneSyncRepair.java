package com.openclock;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

/** A delayed read repairs partially delivered snapshot generations and post-unlock boot state. */
public final class PhoneSyncRepair extends JobService {
    private static final int READ_JOB = 0x4f4357, REQUEST_JOB = 0x4f4358;
    private static final java.util.concurrent.atomic.AtomicLong readRequests = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong refreshRequests = new java.util.concurrent.atomic.AtomicLong();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.Map<Integer, Run> active = new java.util.HashMap<>();
    private static final class Run {
        final JobParameters params;
        final long startedAt;
        Thread worker;
        Run(JobParameters params, long startedAt) { this.params = params; this.startedAt = startedAt; }
    }

    private static java.util.concurrent.atomic.AtomicLong requests(boolean request) {
        return request ? refreshRequests : readRequests;
    }

    static void schedule(Context context, boolean request) {
        Context c = context.getApplicationContext();
        requests(request).incrementAndGet();
        try {
            JobScheduler jobs = c.getSystemService(JobScheduler.class);
            int id = request ? REQUEST_JOB : READ_JOB;
            // A read repair must not suppress a boot/reconnect republish request.
            if (jobs.getPendingJob(id) != null) return;
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("request", request);
            JobInfo job = new JobInfo.Builder(id, new ComponentName(c, PhoneSyncRepair.class))
                    .setPersisted(true).setMinimumLatency(10_000).setOverrideDeadline(30_000)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setExtras(extras).build();
            if (jobs.schedule(job) != JobScheduler.RESULT_SUCCESS)
                android.util.Log.w("PhoneSyncRepair", "Could not schedule snapshot repair");
        } catch (RuntimeException e) {
            android.util.Log.w("PhoneSyncRepair", "Could not schedule snapshot repair");
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        boolean request = params.getExtras().getBoolean("request");
        Run run = new Run(params, requests(request).get());
        Run previous = active.put(params.getJobId(), run);
        if (previous != null) previous.worker.interrupt();
        run.worker = new Thread(() -> {
            boolean complete = PhoneSync.fromListener(getApplicationContext(), request);
            main.post(() -> {
                if (active.get(params.getJobId()) != run) return;
                active.remove(params.getJobId());
                // Bound retries for permanently malformed/unavailable snapshots;
                // later data or capability events start a fresh repair cycle.
                android.content.SharedPreferences prefs = getSharedPreferences("phone-sync-repair-v1", MODE_PRIVATE);
                String key = "failures-" + params.getJobId();
                int failures = complete ? 0 : prefs.getInt(key, 0) + 1;
                boolean retry = !complete && failures < 4;
                prefs.edit().putInt(key, retry ? failures : 0).apply();
                jobFinished(params, retry);
                // A data event during a running job must get another repair after
                // this job finishes, even if its own snapshot read was incomplete.
                if (!retry && requests(request).get() != run.startedAt) schedule(getApplicationContext(), request);
            });
        }, "phone-snapshot-repair");
        run.worker.start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        Run run = active.get(params.getJobId());
        if (run != null && run.params == params) { active.remove(params.getJobId()); run.worker.interrupt(); }
        return true;
    }
}
