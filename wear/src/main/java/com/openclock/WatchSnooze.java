package com.openclock;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;
import java.util.concurrent.*;

/** All watch snooze controls converge here. Network I/O never holds engine/UI locks. */
final class WatchSnooze {
    private static final ExecutorService WORK=Executors.newFixedThreadPool(4);
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor();
    private static final Set<String> ACTIVE=ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String,CompletableFuture<JSONObject>> WAIT=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Long> LOOKED=new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean recovering=new java.util.concurrent.atomic.AtomicBoolean();
    interface Callback { void done(boolean ok,String message); }
    private static void done(Callback cb,boolean ok,String message) {
        if(cb!=null) new android.os.Handler(android.os.Looper.getMainLooper()).post(()->cb.done(ok,message));
    }
    static boolean local(Context context,long id,String token,Integer minutes,Callback callback) throws Exception {
        Context c=context.getApplicationContext(); JSONObject a=AlarmStore.active(c,id,token);
        if(a==null) return true;
        if(!a.optBoolean("_phoneFallback")) {
            synchronized(AlarmStore.class) {
                if(AlarmStore.active(c,id,token)==null) return true;
                AlarmStore.finish(c,id,token,true,minutes);
                try { c.getSystemService(android.app.NotificationManager.class).cancel("alarm:"+id,11); }
                catch(RuntimeException repair) { AlarmNotifications.warning(c,"Snooze committed; notification cleanup pending"); }
            }
            try { AlarmStore.refreshService(c); } catch(RuntimeException repair) { AlarmNotifications.warning(c,"Snooze committed; ringing refresh pending"); }
            done(callback,true,"Snoozed on watch"); return true;
        }
        start(c,a,id,token,false,minutes,callback); return true;
    }
    static void mirror(Context context,long id,String token,Integer minutes,Callback callback) throws Exception {
        Context c=context.getApplicationContext(); JSONObject a=PhoneMirror.current(c,id,token);
        if(a==null) { done(callback,false,"Phone occurrence no longer active"); return; }
        // An older phone may not publish remaining-count metadata on its mirror.
        // Prefer an exactly joined active fallback, whose engine has the durable count.
        if(!a.has("snoozeUsed")) {
            JSONObject identity=PhoneSync.mirrorIdentity(c,id,token); JSONArray active=AlarmStore.active(c);
            if(identity!=null) for(int i=0;i<active.length();i++) {
                JSONObject local=active.getJSONObject(i);
                if(local.optBoolean("_phoneFallback")&&identity.optString("node").equals(local.optString("_phoneNode"))
                    &&identity.optString("epoch").equals(local.optString("_phoneEpoch"))&&id==local.optLong("_phoneId")
                    &&SnoozeProtocol.uuid(identity.optString("occurrenceId"))&&identity.getString("occurrenceId").equals(local.getJSONObject("_active").optString("occurrenceId"))) {
                    start(c,local,local.getLong("id"),local.getJSONObject("_active").getString("token"),false,minutes,callback); return;
                }
            }
        }
        start(c,PhoneSync.snoozeTemplate(c,a),0,token,true,minutes,callback);
    }
    private static void start(Context c,JSONObject a,long localId,String token,boolean mirror,Integer minutes,Callback callback) throws Exception {
        JSONObject identity=PhoneSync.snoozeIdentity(c,a,mirror);
        JSONObject record=AlarmStore.beginWatchSnooze(c,a,localId,token,identity,minutes==null?a.optInt("snoozeMinutes",5):minutes);
        String id=record.getString("requestId");
        if(!ACTIVE.add(id)) { done(callback,false,"Snooze request already pending"); return; }
        java.util.concurrent.atomic.AtomicBoolean answered=new java.util.concurrent.atomic.AtomicBoolean();
        Callback once=(ok,message)->{ if(answered.compareAndSet(false,true)) done(callback,ok,message); };
        // Independent of the transport queue: many simultaneous notification actions
        // must not postpone a local snooze behind other peers' network waits.
        DEADLINES.schedule(()->{
            if(!ACTIVE.contains(id)) return;
            try {
                JSONArray requests=AlarmStore.watchSnoozeRequests(c);
                for(int i=0;i<requests.length();i++) {
                    JSONObject current=requests.getJSONObject(i);
                    if(!id.equals(current.optString("requestId"))||!"pending".equals(current.optString("state"))) continue;
                    JSONObject result=AlarmStore.completeWatchSnooze(c,id,current.optJSONObject("receipt"),true);
                    try { effects(c,result); } catch(Exception repair) { AlarmNotifications.warning(c,"Snooze committed; presentation/sync repair pending"); }
                    report(c,result,once); break;
                }
            } catch(Exception failure) { done(once,false,"Snooze not completed: "+AlarmStore.message(failure)); }
        },10,TimeUnit.SECONDS);
        WORK.execute(()->run(c,record,record.optBoolean("fresh"),once));
    }
    private static void run(Context c,JSONObject record,boolean fresh,Callback callback) {
        String id=record.optString("requestId"); CompletableFuture<JSONObject> wait=new CompletableFuture<>(); WAIT.put(id,wait);
        try {
            JSONObject receipt=record.optJSONObject("receipt");
            boolean sourceLive=record.optLong("localId")>0?
                AlarmStore.active(c,record.optLong("localId"),record.optString("localToken"))!=null:
                PhoneMirror.current(c,record.optLong("phoneId"),record.optString("localToken"))!=null;
            if(receipt==null&&record.has("request")&&(!fresh||sourceLive)) {
                try {
                    // Recovery performs status lookup ONLY. An uncertain write is never replayed.
                    PhoneSync.sendSnooze(c,record,!fresh);
                    receipt=wait.get(5,TimeUnit.SECONDS);
                } catch(Exception unavailable) { /* branch, retaining explicit uncertainty */ }
            }
            boolean mirrorLive=record.optLong("localId")!=0||PhoneMirror.current(c,record.optLong("phoneId"),record.optString("localToken"))!=null;
            JSONObject result=AlarmStore.completeWatchSnooze(c,id,receipt,mirrorLive);
            try { effects(c,result); } catch(Exception repair) { AlarmNotifications.warning(c,"Snooze committed; presentation/sync repair pending"); }
            report(c,result,callback);
        } catch(Exception failure) {
            AlarmNotifications.warning(c,"Snooze not completed: "+AlarmStore.message(failure)+". Original alarm retained unless changed elsewhere.");
            done(callback,false,"Snooze not completed: "+AlarmStore.message(failure));
        } finally { WAIT.remove(id); ACTIVE.remove(id); }
    }
    private static void report(Context c,JSONObject result,Callback callback) throws Exception {
        String state=result.getString("state");
        boolean ok="confirmed".equals(state)||"branched".equals(state);
        String message="confirmed".equals(state)?"Snoozed on phone":"branched".equals(state)
            ?"Watch-only snooze; phone may still ring. Possible duplicate alarms.":"pending".equals(state)
            ?"Phone committed; watch fallback not registered. Original watch alarm retained; recovery pending.":"expired".equals(state)
            ?"Snooze missed while watch unavailable; check both devices.":result.has("receipt")
            ?"Phone snooze committed earlier; occurrence changed. Check both devices.":"Occurrence changed; snooze not applied";
        if("branched".equals(state)||"pending".equals(state)||"expired".equals(state)) AlarmNotifications.warning(c,message);
        done(callback,ok,message);
    }
    private static void effects(Context c,JSONObject result) throws Exception {
        if(!"pending".equals(result.optString("state"))&&!"superseded".equals(result.optString("state"))) {
            long local=result.optLong("localId");
            synchronized(AlarmStore.class) {
                if(local>0&&AlarmStore.active(c,local,result.optString("localToken"))==null) {
                    // Do not cancel a newer session's notification.
                    boolean newer=false; JSONArray active=AlarmStore.active(c);
                    for(int i=0;i<active.length();i++) if(active.getJSONObject(i).optLong("id")==local) newer=true;
                    if(!newer) c.getSystemService(android.app.NotificationManager.class).cancel("alarm:"+local,11);
                }
            }
            if(result.optLong("phoneId")>0&&SnoozeProtocol.uuid(result.optString("phoneToken")))
                PhoneMirror.acknowledged(c,result.optLong("phoneId"),result.optString("phoneToken"));
            AlarmStore.refreshService(c);
            PhoneSync.reconcileCached(c);
            PhoneSync.requestSnapshot(c);
        }
        ComplicationUpdateJob.changed(c,false);
    }
    static void reply(Context c,String node,byte[] body) {
        if(body==null||body.length>4096) return;
        try {
            JSONObject response=new JSONObject(new String(body,java.nio.charset.StandardCharsets.UTF_8));
            if(response.getInt("version")!=2) return;
            String status=response.getString("status");
            JSONObject request=response.getJSONObject("request"); SnoozeProtocol.validate(request);
            JSONObject receipt="committed".equals(status)?response.getJSONObject("receipt"):null;
            if(receipt!=null&&!SnoozeProtocol.same(request,receipt)) return;
            if(receipt==null&&!"rejected".equals(status)&&!"capacity".equals(status)&&!"unknown".equals(status)) return;
            JSONArray requests=AlarmStore.watchSnoozeRequests(c);
            for(int i=0;i<requests.length();i++) {
                JSONObject record=requests.getJSONObject(i);
                if(!node.equals(record.optString("node"))||!record.optString("requestId").equals(request.optString("requestId"))
                    ||record.optJSONObject("request")==null||!SnoozeProtocol.same(record.getJSONObject("request"),request)) continue;
                if(receipt==null) {
                    CompletableFuture<JSONObject> waiting=WAIT.get(record.getString("requestId"));
                    if(waiting!=null) waiting.complete(null);
                    return;
                }
                // Persist late receipts even after timeout/restart; never cancel independent branches.
                JSONObject result=AlarmStore.completeWatchSnooze(c,record.getString("requestId"),receipt,true);
                CompletableFuture<JSONObject> waiting=WAIT.get(record.getString("requestId"));
                if(waiting!=null) waiting.complete(receipt);
                effects(c,result); return;
            }
        } catch(Exception invalid) { /* Not proof of commit. Keep recovery/branch visible. */ }
    }
    static void recover(Context context) {
        Context c=context.getApplicationContext();
        if(!recovering.compareAndSet(false,true)) return;
        WORK.execute(()->{
            try {
                JSONArray requests=AlarmStore.watchSnoozeRequests(c); long now=android.os.SystemClock.elapsedRealtime();
                for(int i=0;i<requests.length();i++) {
                    JSONObject r=requests.getJSONObject(i); String id=r.getString("requestId"), state=r.optString("state");
                    boolean pending="pending".equals(state), lookup="branched".equals(state)&&r.has("request")&&!r.has("receipt")
                        &&System.currentTimeMillis()-r.optLong("createdAt")<SnoozeProtocol.LOOKUP_RETENTION;
                    if(!pending&&!lookup) continue;
                    Long previous=LOOKED.get(id);
                    if(previous!=null&&now-previous<60_000) continue;
                    if(ACTIVE.add(id)) { LOOKED.put(id,now); WORK.execute(()->run(c,r,false,null)); }
                }
                Set<String> retained=new java.util.HashSet<>();
                for(int i=0;i<requests.length();i++) retained.add(requests.getJSONObject(i).getString("requestId"));
                LOOKED.keySet().retainAll(retained);
            } catch(Exception failure) { AlarmNotifications.warning(c,"Watch snooze recovery unavailable"); }
            finally { recovering.set(false); }
        });
    }
    private WatchSnooze() { }
}
