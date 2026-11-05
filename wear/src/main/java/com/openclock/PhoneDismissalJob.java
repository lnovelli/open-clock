package com.openclock;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Wearable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

/** Best-effort immediate dispatch plus persisted recovery; never drains the legacy occurrence queue. */
public final class PhoneDismissalJob extends JobService {
    private static final int JOB=0x4f4359;
    private static final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final Object dispatchLock=new Object();
    private static final Object deliveryLock=new Object();
    private static boolean queued;
    private static long generation;
    private static void arm(Context context,boolean replace) {
        Context c=context.getApplicationContext();
        try {
            JobScheduler jobs=c.getSystemService(JobScheduler.class);
            if(jobs!=null && (replace||jobs.getPendingJob(JOB)==null)) {
                int result=jobs.schedule(new JobInfo.Builder(JOB,new ComponentName(c,PhoneDismissalJob.class))
                    .setPersisted(true).setMinimumLatency(1000).build());
                if(result!=JobScheduler.RESULT_SUCCESS) android.util.Log.w("PhoneDismissal","Recovery job refused");
            }
        } catch(RuntimeException e) { android.util.Log.w("PhoneDismissal","Recovery job unavailable",e); }
    }
    // Called under AlarmStore: only scheduler work here, never wait on worker/store.
    static void beforeCommit(Context c) { arm(c,true); }
    static void schedule(Context context) {
        Context c=context.getApplicationContext(); arm(c,false);
        synchronized(dispatchLock) {
            generation++;
            if(queued) return;
            queued=true;
        }
        worker.execute(()->{
            while(true) {
                long seen;
                synchronized(dispatchLock) { seen=generation; }
                drain(c);
                synchronized(dispatchLock) {
                    if(seen==generation) { queued=false; return; }
                }
            }
        });
    }
    static void expedite(Context c) { schedule(c); }
    @Override public boolean onStartJob(JobParameters params) {
        worker.execute(()->{
            try { drain(getApplicationContext()); }
            finally { jobFinished(params,false); }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
    private static void drain(Context c) {
        try {
            while(!Thread.currentThread().isInterrupted()) {
                JSONArray q=AlarmStore.genericDismissals(c); long now=System.currentTimeMillis();
                long wait=Long.MAX_VALUE; boolean attempted=false;
                for(int i=0;i<q.length();i++) {
                    JSONObject item=q.optJSONObject(i);
                    if(item==null||item.optLong("expiresAt")<=now||item.optInt("attempts")>=4) continue;
                    long delay=AlarmStore.genericDismissalDelay(item);
                    if(delay>0) { wait=Math.min(wait,delay); continue; }
                    JSONObject claimed=AlarmStore.claimGenericDismissal(c,item.getString("requestId"));
                    if(claimed==null) continue;
                    attempted=true;
                    boolean sent=false;
                    try { sent=attempt(c,claimed); }
                    finally { AlarmStore.finishGenericAttempt(c,claimed.getString("requestId"),claimed.getInt("attempts"),sent); }
                }
                if(attempted) continue;
                if(wait==Long.MAX_VALUE) return;
                Thread.sleep(Math.min(wait,2000));
            }
        } catch(InterruptedException stopped) { Thread.currentThread().interrupt(); }
        catch(Exception e) { android.util.Log.w("PhoneDismissal","Dispatch interrupted",e); }
    }
    private static boolean attempt(Context c,JSONObject item) {
        try {
            // Every discovery, including no network or multiple phones, consumes an attempt.
            CapabilityInfo info=Tasks.await(Wearable.getCapabilityClient(c)
                .getCapability("openclock_phone_v1",CapabilityClient.FILTER_REACHABLE),1,TimeUnit.SECONDS);
            if(info.getNodes().size()!=1) return false;
            String node=info.getNodes().iterator().next().getId();
            String req=item.getString("requestId");
            if(node==null||node.isEmpty()||node.length()>256) return false;
            synchronized(deliveryLock) {
                if(!AlarmStore.bindGenericNode(c,req,node)) return false;
                Wearable.getMessageClient(c).sendMessage(node,GenericDismissProtocol.REQUEST,
                    GenericDismissProtocol.encode(req,item.getLong("createdAt"),item.getLong("expiresAt"),null));
            }
            return true;
        } catch(Exception ignored) { /* One attempt consumed, including discovery/transport failure. */ }
        return false;
    }
    static void reply(Context c,String node,byte[] body) {
        if(node==null||node.isEmpty()||node.length()>256) return;
        try {
            GenericDismissProtocol r=GenericDismissProtocol.parse(body,true);
            if(r.ok) {
                synchronized(deliveryLock) {
                    AlarmStore.acknowledgeGenericDismissal(c,node,r.id,r.created,r.expires);
                }
                PhoneSync.requestSnapshot(c);
            }
        } catch(Exception ignored) { }
    }
}
