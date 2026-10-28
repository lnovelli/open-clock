package com.openclock;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

/** Platform-owned retry survives process death; publication is advisory only. */
public final class WearSyncJob extends JobService {
    // JobService callbacks and completion are serialized on the main thread.
    private final Handler main=new Handler(Looper.getMainLooper());
    private Run active;
    private static final class Run {
        final JobParameters params;
        final long generation;
        Thread thread;
        Run(JobParameters params) { this.params=params; generation=WearSync.changeGeneration(); }
    }
    @Override public boolean onStartJob(JobParameters params) {
        if(active!=null) active.thread.interrupt();
        Run run=new Run(params);
        active=run;
        run.thread=new Thread(()->{
            boolean retry=false;
            try { WearSync.publish(getApplicationContext()); } catch(Exception e) { retry=true; }
            final boolean reschedule=retry;
            main.post(()->{
                if(active!=run) return; // stopped/replaced: never call jobFinished
                active=null;
                jobFinished(run.params,reschedule);
                // jobFinished itself posts to JobServiceEngine's main-thread
                // handler. Recheck only after that message, not before the OS
                // has been told that this run is finished.
                if(!reschedule) main.post(()->WearSync.jobCompleted(getApplicationContext(),run.generation));
            });
        },"wear-snapshot-job");
        run.thread.start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        Run run=active;
        if(run!=null && run.params.getJobId()==params.getJobId()) {
            active=null;
            run.thread.interrupt();
        }
        return true;
    }
}
