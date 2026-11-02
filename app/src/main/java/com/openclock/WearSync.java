package com.openclock;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import org.json.JSONObject;

/** Phone-only publisher. The watch has a no-op implementation for its local store. */
final class WearSync {
    static final String PREFIX="/openclock/v1/", META=PREFIX+"snapshot", CHUNK=PREFIX+"chunk/";
    static final String REQUEST=PREFIX+"request", COMMAND=PREFIX+"command", REPLY=PREFIX+"reply";
    static final String CREATE=PREFIX+"create", CREATE_STATUS=PREFIX+"create-status", CREATE_REPLY=PREFIX+"create-reply";
    static final String MUTATE=PREFIX+"mutate", MUTATE_STATUS=PREFIX+"mutate-status", MUTATE_REPLY=PREFIX+"mutate-reply";
    static final int CHUNK_SIZE=60_000, MAX_CHUNKS=64, MAX_RAW=4_000_000;
    private static final int JOB=0x4f4353;
    private static final AtomicBoolean dirty=new AtomicBoolean(), queued=new AtomicBoolean();
    private static final AtomicBoolean scheduleQueued=new AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicLong changes=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong scheduleRequests=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ExecutorService scheduler=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.locks.ReentrantLock uploadLock=new java.util.concurrent.locks.ReentrantLock();
    private static final Object identityLock=new Object();
    // A timed-out Data Layer operation can still complete later. Do not start a
    // newer generation until that operation settles, even if its job was stopped.
    private static volatile Task<?> unsettled;

    // No Binder, disk, network or publisher lock under the AlarmStore lock.
    // Consequently a process death between commit and asynchronous scheduling can
    // lose this advisory wakeup; a later commit or watch refresh repairs it.
    static void beforeCommit(Context c) { }
    static void afterPhoneFallbackOccurrence(Context c) { }
    static void afterCommit(Context c) {
        try { MainActivity.wearChanged(); } finally { publishSoon(c); }
    }
    static void publishSoon(Context c) {
        Context app=c.getApplicationContext();
        changes.incrementAndGet();
        dirty.set(true);
        requestSchedule(app);
        startDrain(app);
    }
    // A listener callback must not return before the platform-owned retry is
    // scheduled; otherwise process death can lose the async publisher wakeup.
    static void requestFromListener(Context c) {
        publishSoon(c);
        try {
            long deadline=android.os.SystemClock.elapsedRealtime()+3_000;
            do {
                long left=deadline-android.os.SystemClock.elapsedRealtime();
                if(left<=0) break;
                scheduler.submit(() -> {}).get(left, TimeUnit.MILLISECONDS);
            } while(scheduleQueued.get());
        } catch(InterruptedException stopped) { Thread.currentThread().interrupt(); }
        catch(Exception ignored) { /* A later event or watch refresh can retry. */ }
    }
    private static void startDrain(Context app) {
        if(!queued.compareAndSet(false,true)) return;
        worker.execute(()->{
            boolean failed=false;
            try {
                while(dirty.getAndSet(false)) {
                    try { publish(app); }
                    catch(Exception e) { dirty.set(true); failed=true; break; }
                }
            } finally {
                queued.set(false);
                // Close the race between the final dirty check and clearing queued.
                // Failures wait for a later request or the OS job's backoff.
                if(!failed && dirty.get()) startDrain(app);
            }
        });
    }
    static long changeGeneration() { return changes.get(); }
    static void jobCompleted(Context c,long startedAt) {
        // Called only after jobFinished: a commit racing a running job must get
        // a fresh job, rather than replacing/cancelling that running job.
        if(changes.get()!=startedAt) requestSchedule(c.getApplicationContext());
    }
    private static void requestSchedule(Context app) {
        scheduleRequests.incrementAndGet();
        if(!scheduleQueued.compareAndSet(false,true)) return;
        scheduler.execute(()->{
            long seen=scheduleRequests.get();
            try {
                JobScheduler jobs=app.getSystemService(JobScheduler.class);
                // A pending OR running job already supplies recovery. Never replace
                // it: the completion path handles commits made during its run.
                if(jobs.getPendingJob(JOB)==null) {
                    JobInfo job=new JobInfo.Builder(JOB,new ComponentName(app,WearSyncJob.class))
                        .setPersisted(true).setOverrideDeadline(10_000)
                        .setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
                    if(jobs.schedule(job)!=JobScheduler.RESULT_SUCCESS)
                        android.util.Log.w("WearSync","Could not schedule snapshot retry");
                }
            } catch(RuntimeException e) {
                android.util.Log.w("WearSync","Could not schedule snapshot retry",e);
            } finally {
                scheduleQueued.set(false);
                if(scheduleRequests.get()!=seen) requestSchedule(app);
            }
        });
    }
    // Capture and upload are serialized independently of identity lookup.
    static void publish(Context c) throws Exception {
        uploadLock.lockInterruptibly();
        try {
            Task<?> previous=unsettled;
            if(previous!=null && !previous.isComplete()) throw new IllegalStateException("Previous upload is still pending");
            unsettled=null;
            publishLocked(c);
        } finally { uploadLock.unlock(); }
    }
    private static void publishLocked(Context c) throws Exception {
        JSONObject snapshot=AlarmStore.wearSnapshot(c);
        snapshot.put("epoch",epoch(c));
        byte[] raw=snapshot.toString().getBytes(StandardCharsets.UTF_8);
        if(raw.length>MAX_RAW) throw new IllegalStateException("Snapshot too large");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(GZIPOutputStream gzip=new GZIPOutputStream(bytes)) { gzip.write(raw); }
        byte[] packed=bytes.toByteArray();
        int count=(packed.length+CHUNK_SIZE-1)/CHUNK_SIZE;
        if(count<1||count>MAX_CHUNKS) throw new IllegalStateException("Snapshot too large");
        long rev=snapshot.getLong("revision");
        for(int i=0;i<count;i++) {
            int start=i*CHUNK_SIZE, size=Math.min(CHUNK_SIZE,packed.length-start);
            byte[] data=ByteBuffer.allocate(12+size).putLong(rev).putInt(i).put(packed,start,size).array();
            put(c,CHUNK+i,data);
        }
        String hash=hex(MessageDigest.getInstance("SHA-256").digest(packed));
        JSONObject meta=new JSONObject().put("revision",rev).put("count",count).put("size",packed.length)
            .put("sha256",hash).put("updatedAt",snapshot.getLong("updatedAt"));
        put(c,META,meta.toString().getBytes(StandardCharsets.UTF_8));
    }
    static String epoch(Context c) {
        synchronized(identityLock) {
            android.content.SharedPreferences prefs=c.createDeviceProtectedStorageContext().getSharedPreferences("wear-publisher",Context.MODE_PRIVATE);
            String epoch=prefs.getString("epoch","");
            if(epoch.isEmpty()) { epoch=java.util.UUID.randomUUID().toString(); if(!prefs.edit().putString("epoch",epoch).commit()) throw new IllegalStateException("No publisher identity"); }
            return epoch;
        }
    }
    private static void put(Context c,String path,byte[] data) throws Exception {
        Task<?> task=Wearable.getDataClient(c).putDataItem(PutDataRequest.create(path).setData(data).setUrgent());
        try { Tasks.await(task,15,TimeUnit.SECONDS); }
        catch(java.util.concurrent.TimeoutException | InterruptedException e) {
            // Await timing out or being interrupted does not cancel the underlying
            // operation. A later publisher must not race its eventual completion.
            unsettled=task;
            throw e;
        }
    }
    static String hex(byte[] bytes) {
        StringBuilder s=new StringBuilder(bytes.length*2);
        for(byte b:bytes) s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return s.toString();
    }
    private WearSync() {}
}
