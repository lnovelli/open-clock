package com.openclock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Phone snapshot and commands. The watch also schedules independent fallback alarms. */
public final class PhoneSync {
    public static final String ACTION_CHANGED="com.openclock.PHONE_SYNC_CHANGED";
    public interface Callback { void onResult(boolean ok,String safeMessage); }
    /** status: created, unknown, or not_sent. A created result has positive alarmId and revision. */
    public interface CreateCallback { void onResult(String status,String requestId,long alarmId,long revision); }
    private static final String PREFIX="/openclock/v1/", META=PREFIX+"snapshot", CHUNK=PREFIX+"chunk/",
        REQUEST=PREFIX+"request", COMMAND=PREFIX+"command", REPLY=PREFIX+"reply",
        CREATE=PREFIX+"create", CREATE_STATUS=PREFIX+"create-status", CREATE_REPLY=PREFIX+"create-reply",
        MUTATE=PREFIX+"mutate", MUTATE_STATUS=PREFIX+"mutate-status", MUTATE_REPLY=PREFIX+"mutate-reply";
    private static final int CHUNK_SIZE=60_000, MAX_CHUNKS=64, MAX_RAW=4_000_000;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService FETCH=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.ExecutorService SEND=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final AtomicBoolean fetching=new AtomicBoolean(), dirty=new AtomicBoolean(), wantsRequest=new AtomicBoolean();
    private static volatile boolean lastReadComplete;
    private static JSONObject snapshot;
    private static String owner="", reachable="";
    private static long connectedUntil, complicationConfirmedUntil, lastDiscovery;
    // Failure to record advisory complication state must never block alarm controls.
    private static boolean complicationBarrierFailed;
    private static Pending pending;
    private static final class Pending {
        final String id,node; final Callback callback;
        Pending(String id,String node,Callback callback) { this.id=id; this.node=node; this.callback=callback; }
    }
    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("phone-snapshot-v1",Context.MODE_PRIVATE); }
    private static synchronized void load(Context c) {
        if(snapshot!=null) return;
        try {
            SharedPreferences p=prefs(c); owner=p.getString("owner","");
            String raw=p.getString("snapshot","");
            snapshot=raw.length()<=MAX_RAW&&!raw.isEmpty()?new JSONObject(raw):empty();
            if(!owner.isEmpty()&&!raw.isEmpty()) {
                if(!AlarmStore.phoneSnapshotAllowed(c,owner,snapshot)) { owner=""; snapshot=empty(); }
                else PhoneFallback.reconcile(c,owner,snapshot);
            }
        } catch(Exception e) { owner=""; snapshot=empty(); }
    }
    private static JSONObject empty() {
        try { return new JSONObject().put("alarms",new JSONArray()).put("groups",new JSONArray()).put("updatedAt",0); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }
    static synchronized void reconcileCached(Context context) {
        Context c=context.getApplicationContext(); load(c);
        if(!owner.isEmpty()&&snapshot.optLong("updatedAt",0)>0) PhoneFallback.reconcile(c,owner,snapshot);
    }
    public static synchronized JSONObject cached(Context context) {
        Context c=context.getApplicationContext(); load(c);
        long now=SystemClock.elapsedRealtime();
        boolean connected=!reachable.isEmpty()&&reachable.equals(owner)&&now<connectedUntil;
        try { snapshot.put("connected",connected); } catch(Exception ignored) { }
        // A cache read is not an explicit refresh: keep discovery bounded and do
        // not ask the phone to republish on every redraw or ringing-screen tick.
        if(lastDiscovery==0||now-lastDiscovery>20_000) { lastDiscovery=Math.max(1,now); startFetch(c,false); }
        return snapshot;
    }
    // A provider read must never trigger discovery or a publication feedback loop.
    static synchronized JSONObject complicationSnapshot(Context context) {
        load(context.getApplicationContext());
        try {
            JSONObject copy = new JSONObject(snapshot.toString());
            long left = !owner.isEmpty() && owner.equals(reachable)
                    ? Math.max(0, complicationConfirmedUntil - SystemClock.elapsedRealtime()) : 0;
            JSONObject creation = pendingCreate(context), mutation = pendingMutation(context);
            boolean unresolved = complicationBarrierFailed || pending != null || commandPrefs(context).contains("barrier")
                    || !createActive.isEmpty() || !mutationActive.isEmpty()
                    || !receiptCovered(creation, "created", copy) || !receiptCovered(mutation, "committed", copy);
            copy.put("connectedForMillis", unresolved ? 0 : left);
            copy.put("complicationUnresolved", unresolved);
            return copy;
        } catch (Exception e) { return null; }
    }
    private static boolean receiptCovered(JSONObject receipt, String status, JSONObject copy) {
        return receipt == null || (status.equals(receipt.optString("status"))
                && !owner.isEmpty() && owner.equals(receipt.optString("node"))
                && copy.optString("epoch").equals(receipt.optString("epoch"))
                && receipt.optLong("revision", -1) >= 0
                && copy.optLong("revision", -1) >= receipt.optLong("revision"));
    }
    private static SharedPreferences commandPrefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("complication-command-v1", Context.MODE_PRIVATE);
    }
    // Unknown outcomes require a phone snapshot after the bounded command window;
    // a merely newer revision is not sufficient.
    private static void coverCommand(Context c, JSONObject copy) {
        try {
            if(pending!=null) return;
            SharedPreferences p=commandPrefs(c); String raw=p.getString("barrier", "");
            if(raw.isEmpty()) return;
            JSONObject b=new JSONObject(raw);
            if(ComplicationPolicy.commandCovered(b,owner,copy))
                p.edit().remove("barrier").commit();
        } catch(Exception ignored) { }
    }
    public static void requestSnapshot(Context context) { startFetch(context.getApplicationContext(),true); }
    static void changed(Context c) { startFetch(c.getApplicationContext(),false); }
    static void capabilityChanged(Context c) {
        synchronized (PhoneSync.class) { complicationConfirmedUntil=0; }
        broadcast(c.getApplicationContext());
    }
    // Called on the WearableListenerService background thread. Keep its binding
    // alive for a bounded fetch rather than returning immediately after enqueue.
    static boolean fromListener(Context context, boolean request) {
        startFetch(context.getApplicationContext(),request);
        long deadline=SystemClock.elapsedRealtime()+25_000;
        try {
            do {
                long left=deadline-SystemClock.elapsedRealtime();
                if(left<=0) return false;
                FETCH.submit(() -> {}).get(left, TimeUnit.MILLISECONDS);
                // A drain can enqueue its coalesced successor after this barrier.
            } while(fetching.get()||dirty.get()||wantsRequest.get());
            return lastReadComplete;
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return false; }
        catch (Exception ignored) { return false; /* A later event or job retries. */ }
    }
    private static void broadcast(Context c) { MAIN.post(()->{
        c.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(c.getPackageName()));
        ComplicationUpdateJob.changed(c, false);
    }); }
    private static void startFetch(Context c,boolean request) {
        if(request) wantsRequest.set(true);
        if(!fetching.compareAndSet(false,true)) { dirty.set(true); return; }
        FETCH.execute(()->{
            try {
                // Repeat once if events arrived while reading; later data events retry too.
                for(int pass=0;pass<2;pass++) {
                    dirty.set(false); discoverAndRead(c,wantsRequest.getAndSet(false));
                    if(!dirty.get()&&!wantsRequest.get()) break;
                }
            } finally { fetching.set(false); if(dirty.getAndSet(false)||wantsRequest.get()) startFetch(c,false); }
        });
    }
    private static String onePhone(Context c) throws Exception {
        CapabilityInfo info=Tasks.await(Wearable.getCapabilityClient(c).getCapability("openclock_phone_v1",CapabilityClient.FILTER_REACHABLE),5,TimeUnit.SECONDS);
        if(info.getNodes().size()!=1) return "";
        Node node=info.getNodes().iterator().next(); return node.getId();
    }
    private static void discoverAndRead(Context c,boolean request) {
        String node="";
        lastReadComplete=false;
        boolean discoverySucceeded=false;
        try { node=onePhone(c); discoverySucceeded=true; } catch(Exception ignored) { }
        synchronized(PhoneSync.class) {
            load(c); reachable=node; connectedUntil=SystemClock.elapsedRealtime()+30_000;
            complicationConfirmedUntil=0;
            // Discovery is not authority to delete the last accepted snapshot.
        }
        broadcast(c);
        if(node.isEmpty()) { lastReadComplete=discoverySucceeded; return; }
        if(request) Wearable.getMessageClient(c).sendMessage(node,REQUEST,new byte[0]);
        try {
            byte[] metaBytes=get(c,node,META); if(metaBytes==null||metaBytes.length>2048) return;
            JSONObject meta=new JSONObject(new String(metaBytes,StandardCharsets.UTF_8));
            long rev=meta.getLong("revision"); int count=meta.getInt("count"), size=meta.getInt("size");
            String hash=meta.getString("sha256");
            if(rev<0||count<1||count>MAX_CHUNKS||size<1||size>MAX_CHUNKS*CHUNK_SIZE||
                count!=(size+CHUNK_SIZE-1)/CHUNK_SIZE||!hash.matches("[0-9a-f]{64}")) return;
            ByteArrayOutputStream packed=new ByteArrayOutputStream(size);
            for(int i=0;i<count;i++) {
                byte[] chunk=get(c,node,CHUNK+i);
                int expected=Math.min(CHUNK_SIZE,size-i*CHUNK_SIZE);
                if(chunk==null||chunk.length!=12+expected) return;
                ByteBuffer b=ByteBuffer.wrap(chunk); if(b.getLong()!=rev||b.getInt()!=i) return;
                packed.write(chunk,12,expected);
            }
            byte[] compressed=packed.toByteArray();
            if(!hex(MessageDigest.getInstance("SHA-256").digest(compressed)).equals(hash)) return;
            ByteArrayOutputStream raw=new ByteArrayOutputStream();
            try(GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                byte[] buf=new byte[8192]; int n;
                while((n=in.read(buf))!=-1) { if(raw.size()+n>MAX_RAW) return; raw.write(buf,0,n); }
            }
            String json=new String(raw.toByteArray(),StandardCharsets.UTF_8); JSONObject next=new JSONObject(json);
            JSONArray alarms=next.getJSONArray("alarms"), groups=next.getJSONArray("groups"), active=next.getJSONArray("active");
            String epoch=next.getString("epoch");
            if(next.getLong("revision")!=rev||next.getLong("updatedAt")!=meta.getLong("updatedAt")||
                !epoch.matches("[0-9a-f-]{36}")||alarms.length()>500||groups.length()>100||active.length()>500) return;
            synchronized(PhoneSync.class) {
                // A repair reading the identical data must not continuously enqueue itself.
                if(!node.equals(owner)||!epoch.equals(snapshot.optString("epoch"))||rev!=snapshot.optLong("revision",-1)||
                        next.optLong("updatedAt")!=snapshot.optLong("updatedAt")) PhoneSyncRepair.schedule(c,false);
            }
            synchronized(PhoneSync.class) {
                if(!node.equals(reachable)) return;
                if(node.equals(owner)&&epoch.equals(snapshot.optString("epoch"))&&rev<snapshot.optLong("revision",-1)) return;
                if(node.equals(owner)&&epoch.equals(snapshot.optString("epoch"))&&rev==snapshot.optLong("revision",-1)
                        &&next.optLong("updatedAt")<snapshot.optLong("updatedAt")) return;
                // The device-protected scheduler is the epoch authority. Persist its
                // desired state first so a crash cannot accept an unrecoverable edit.
                if(!AlarmStore.phoneSnapshotAllowed(c,node,next)) return;
                PhoneFallback.reconcile(c,node,next);
                if(!AlarmStore.phoneSnapshotAccepted(c,node,next)) return;
                if(!prefs(c).edit().putString("owner",node).putString("snapshot",json).commit()) return;
                owner=node; snapshot=next; next.put("connected",true);
            }
            boolean fallbackOk=AlarmStore.phoneFallbackProblem(c)==null;
            synchronized(PhoneSync.class) {
                if(snapshot!=next||reachable.isEmpty()||!reachable.equals(owner)||SystemClock.elapsedRealtime()>=connectedUntil) return;
            }
            // Finish mirror persistence and notification admission while the listener
            // or repair job still owns its background execution lifetime.
            lastReadComplete=PhoneMirror.onSnapshot(c,next) && fallbackOk;
            synchronized(PhoneSync.class) {
                if (lastReadComplete && snapshot == next && node.equals(reachable) && node.equals(owner)) {
                    complicationConfirmedUntil = SystemClock.elapsedRealtime() + ComplicationPolicy.FRESH_MS;
                    coverCommand(c,next);
                }
            }
            broadcast(c);
        } catch(Exception ignored) { }
    }
    private static byte[] get(Context c,String node,String path) throws Exception {
        Uri uri=new Uri.Builder().scheme("wear").authority(node).path(path).build();
        DataItem item=Tasks.await(Wearable.getDataClient(c).getDataItem(uri),5,TimeUnit.SECONDS);
        return item==null?null:item.getData();
    }
    private static String hex(byte[] bytes) {
        StringBuilder s=new StringBuilder(bytes.length*2);
        for(byte b:bytes) s.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return s.toString();
    }
    public static void command(Context context,JSONObject input,Callback callback) {
        final Context c=context.getApplicationContext();
        if(callback==null) return;
        final String node,id,epoch,op,token; final long rev,alarm; final boolean value;
        try {
            if(input==null||!(input.opt("id") instanceof Number)) throw new IllegalArgumentException();
            op=input.optString("op"); alarm=new java.math.BigDecimal(input.get("id").toString()).longValueExact();
            if(alarm<1) throw new IllegalArgumentException();
            if("setEnabled".equals(op)) {
                if(input.length()!=3||!(input.opt("enabled") instanceof Boolean)) throw new IllegalArgumentException();
                value=input.getBoolean("enabled"); token="";
            } else if("finish".equals(op)) {
                if(input.length()!=4||!(input.opt("snooze") instanceof Boolean)||!(input.opt("token") instanceof String)) throw new IllegalArgumentException();
                value=input.getBoolean("snooze"); token=input.getString("token");
                if(!token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException();
            } else throw new IllegalArgumentException();
            synchronized(PhoneSync.class) {
                load(c); if(pending!=null||reachable.isEmpty()||!reachable.equals(owner)||SystemClock.elapsedRealtime()>=connectedUntil) throw new IllegalStateException();
                node=owner; rev=snapshot.getLong("revision"); epoch=snapshot.getString("epoch"); id=UUID.randomUUID().toString();
                try {
                    SharedPreferences cp=commandPrefs(c);
                    JSONObject previous=cp.contains("barrier") ? new JSONObject(cp.getString("barrier","{}")) : null;
                    JSONObject barrier=new JSONObject().put("id",id).put("node",node).put("epoch",epoch)
                            .put("revision",rev).put("ack",false).put("safeAfter",System.currentTimeMillis()+60_000L);
                    if(previous!=null) {
                        barrier.put("overlap",true);
                        if(node.equals(previous.optString("node")) && epoch.equals(previous.optString("epoch")))
                            barrier.put("safeAfter",Math.max(barrier.getLong("safeAfter"),previous.optLong("safeAfter",Long.MAX_VALUE)));
                        else barrier.put("safeAfter",Long.MAX_VALUE);
                    }
                    if(!cp.edit().putString("barrier",barrier.toString()).commit()) complicationBarrierFailed=true;
                } catch(Exception failure) { complicationBarrierFailed=true; }
                pending=new Pending(id,node,callback);
            }
        } catch(Exception e) { MAIN.post(()->callback.onResult(false,"Phone unavailable or snapshot not ready; refresh")); return; }
        broadcast(c);
        MAIN.postDelayed(()->finish(c,id,false,"Result unknown; refresh on watch"),25_000);
        SEND.execute(()->{
            try {
                if(!node.equals(onePhone(c))) { finish(c,id,false,"Phone unavailable; refresh on watch"); return; }
                long issued;
                synchronized(PhoneSync.class) {
                    if(pending==null||!id.equals(pending.id)) return;
                    issued=System.currentTimeMillis();
                    try {
                        SharedPreferences cp=commandPrefs(c); JSONObject b=new JSONObject(cp.getString("barrier","{}"));
                        if(id.equals(b.optString("id"))) {
                            b.put("safeAfter",Math.max(b.optLong("safeAfter",Long.MAX_VALUE),issued+60_000L));
                            if(!cp.edit().putString("barrier",b.toString()).commit()) complicationBarrierFailed=true;
                        }
                    } catch(Exception failure) { complicationBarrierFailed=true; }
                }
                JSONObject cmd=new JSONObject().put("op",op).put("id",alarm)
                    .put("epoch",epoch).put("requestId",id).put("issuedAt",issued);
                if("setEnabled".equals(op)) cmd.put("enabled",value).put("revision",rev);
                else cmd.put("token",token).put("snooze",value);
                Wearable.getMessageClient(c).sendMessage(node,COMMAND,cmd.toString().getBytes(StandardCharsets.UTF_8))
                    .addOnFailureListener(e->finish(c,id,false,"Result unknown; refresh on watch"));
            } catch(Exception e) { finish(c,id,false,"Result unknown; refresh on watch"); }
        });
    }
    private static CreateCallback createCallback;
    private static String createActive="";
    private static String createNonce="";
    private static long createAttempt;
    private static SharedPreferences createPrefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("phone-create-v1",Context.MODE_PRIVATE);
    }
    /** Durable last create receipt/uncertainty, or null. Treat unknown as potentially created. */
    public static synchronized JSONObject pendingCreate(Context context) {
        try {
            String raw=createPrefs(context).getString("request","");
            return raw.isEmpty()?null:new JSONObject(raw);
        } catch(Exception e) { throw new IllegalStateException("Unreadable create state",e); }
    }
    /** Explicitly discard the barrier only after the user understands an unknown may have created an alarm. */
    public static synchronized boolean forgetCreate(Context context,String requestId) {
        JSONObject p=pendingCreate(context);
        if(p==null||!p.optString("requestId").equals(requestId)||!createActive.isEmpty()) return false;
        boolean removed=createPrefs(context).edit().remove("request").commit();
        if(removed) broadcast(context.getApplicationContext());
        return removed;
    }
    /**
     * Create one phone-owned, enabled, non-repeating alarm. Fields: integer hour/minute;
     * optional exact yyyy-MM-dd date, trimmed label (<=200 UTF-16 units), boolean sound/vibrate.
     * Omitted options use store defaults. No retries; uncertainty is durable across process death.
     */
    public static void createAlarm(Context context,JSONObject input,CreateCallback callback) {
        if(callback==null) return;
        Context c=context.getApplicationContext(); String id="";
        try {
            if(input==null) throw new IllegalArgumentException();
            java.util.Iterator<String> keys=input.keys();
            while(keys.hasNext()) if(!java.util.Arrays.asList("hour","minute","date","label","sound","vibrate").contains(keys.next())) throw new IllegalArgumentException();
            Object h=input.opt("hour"), m=input.opt("minute");
            if(!((h instanceof Integer)||(h instanceof Long))||!((m instanceof Integer)||(m instanceof Long))) throw new IllegalArgumentException();
            long hour=((Number)h).longValue(), minute=((Number)m).longValue();
            if(hour<0||hour>23||minute<0||minute>59) throw new IllegalArgumentException();
            String date="", label="";
            if(input.has("date")&&!input.isNull("date")) {
                if(!(input.get("date") instanceof String)) throw new IllegalArgumentException();
                date=input.getString("date"); AlarmTimes.parseDate(date);
            }
            if(input.has("label")) {
                if(!(input.get("label") instanceof String)) throw new IllegalArgumentException();
                label=input.getString("label"); if(label.length()>200||!label.equals(label.trim())) throw new IllegalArgumentException();
            }
            for(String key:new String[]{"sound","vibrate"}) if(input.has(key)&&!(input.get(key) instanceof Boolean)) throw new IllegalArgumentException();
            boolean sound=input.optBoolean("sound",false), vibrate=input.optBoolean("vibrate",true);
            String node,epoch,nonce=UUID.randomUUID().toString(); long issued=System.currentTimeMillis();
            synchronized(PhoneSync.class) {
                load(c); JSONObject previous=pendingCreate(c);
                if(!createActive.isEmpty()||(previous!=null&&!"created".equals(previous.optString("status")))||
                    reachable.isEmpty()||!reachable.equals(owner)||SystemClock.elapsedRealtime()>=connectedUntil) throw new IllegalStateException();
                node=owner; epoch=snapshot.getString("epoch"); id=UUID.randomUUID().toString();
                JSONObject details=new JSONObject().put("hour",hour).put("minute",minute).put("label",label)
                    .put("sound",sound).put("vibrate",vibrate).put("date",date.isEmpty()?JSONObject.NULL:date);
                JSONObject record=new JSONObject().put("requestId",id).put("node",node).put("epoch",epoch)
                    .put("status","unknown").put("alarm",details);
                if(!createPrefs(c).edit().putString("request",record.toString()).commit()) throw new IllegalStateException();
                createActive=id; createNonce=nonce; createCallback=callback; createAttempt++;
            }
            broadcast(c);
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
            out.writeByte(1); out.writeUTF(id); out.writeUTF(epoch); out.writeUTF(nonce); out.writeLong(issued);
            out.writeByte((int)hour); out.writeByte((int)minute); out.writeUTF(date); out.writeUTF(label);
            out.writeByte((sound?1:0)|(vibrate?2:0)); out.flush();
            sendCreate(c,id,node,epoch,CREATE,bytes.toByteArray());
        } catch(Exception e) {
            synchronized(PhoneSync.class) {
                if(!id.isEmpty()&&id.equals(createActive)) { completeCreate(c,id,"unknown",0,0); return; }
            }
            MAIN.post(()->callback.onResult("not_sent","",0,0));
        }
    }
    /** Read-only reconciliation; never re-sends the create mutation. */
    public static void reconcileCreate(Context context,CreateCallback callback) {
        if(callback==null) return;
        Context c=context.getApplicationContext(); String id="";
        try {
            String node,epoch,nonce=UUID.randomUUID().toString();
            synchronized(PhoneSync.class) {
                load(c); JSONObject p=pendingCreate(c);
                if(p==null||!createActive.isEmpty()) throw new IllegalStateException();
                id=p.getString("requestId"); node=p.getString("node"); epoch=p.getString("epoch");
                if(!node.equals(owner)||!node.equals(reachable)||!epoch.equals(snapshot.optString("epoch"))||SystemClock.elapsedRealtime()>=connectedUntil) throw new IllegalStateException();
                if("created".equals(p.optString("status"))) {
                    String done=id; long alarm=p.getLong("alarmId"), revision=p.getLong("revision");
                    MAIN.post(()->callback.onResult("created",done,alarm,revision)); return;
                }
                createActive=id; createNonce=nonce; createCallback=callback; createAttempt++;
            }
            broadcast(c);
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
            out.writeByte(1); out.writeUTF(id); out.writeUTF(epoch); out.writeUTF(nonce); out.writeLong(System.currentTimeMillis()); out.flush();
            sendCreate(c,id,node,epoch,CREATE_STATUS,bytes.toByteArray());
        } catch(Exception e) {
            if(!id.isEmpty()) {
                synchronized(PhoneSync.class) { if(id.equals(createActive)) { completeCreate(c,id,"unknown",0,0); return; } }
                String request=id; MAIN.post(()->callback.onResult("unknown",request,0,0));
            } else MAIN.post(()->callback.onResult("not_sent","",0,0));
        }
    }
    private static void sendCreate(Context c,String id,String node,String epoch,String path,byte[] body) {
        final long attempt; synchronized(PhoneSync.class) { attempt=createAttempt; }
        MAIN.postDelayed(()->finishCreateAttempt(c,id,attempt),25_000);
        SEND.execute(()->{
            try {
                if(!node.equals(onePhone(c))) { finishCreateAttempt(c,id,attempt); return; }
                synchronized(PhoneSync.class) {
                    if(!id.equals(createActive)||attempt!=createAttempt||!node.equals(owner)||!node.equals(reachable)||
                        !epoch.equals(snapshot.optString("epoch"))||SystemClock.elapsedRealtime()>=connectedUntil) {
                        finishCreateAttempt(c,id,attempt); return;
                    }
                    Wearable.getMessageClient(c).sendMessage(node,path,body)
                        .addOnFailureListener(e->finishCreateAttempt(c,id,attempt));
                }
            } catch(Exception e) { finishCreateAttempt(c,id,attempt); }
        });
    }
    private static void finishCreateAttempt(Context c,String id,long attempt) {
        synchronized(PhoneSync.class) {
            if(attempt!=createAttempt||!id.equals(createActive)) return;
            completeCreate(c,id,"unknown",0,0);
        }
    }
    private static void completeCreate(Context c,String id,String status,long alarm,long revision) {
        CreateCallback callback=null;
        synchronized(PhoneSync.class) {
            if(id.equals(createActive)) { callback=createCallback; createCallback=null; createActive=""; createNonce=""; }
        }
        if(callback!=null) { CreateCallback cb=callback; MAIN.post(()->cb.onResult(status,id,alarm,revision)); }
        broadcast(c);
        if("created".equals(status)) startFetch(c,true);
    }
    private static void createReply(Context c,String node,byte[] body) {
        if(body==null||body.length>1024) return;
        try {
            JSONObject r=new JSONObject(new String(body,StandardCharsets.UTF_8));
            String id=r.getString("requestId"), epoch=r.getString("epoch"), nonce=r.getString("nonce"), status=r.getString("status");
            long alarm=0, revision=0;
            synchronized(PhoneSync.class) {
                load(c); JSONObject p=pendingCreate(c);
                if(p==null||!id.equals(p.optString("requestId"))||!node.equals(p.optString("node"))||
                    !epoch.equals(p.optString("epoch"))||!node.equals(owner)||!node.equals(reachable)||
                    !epoch.equals(snapshot.optString("epoch"))||SystemClock.elapsedRealtime()>=connectedUntil) return;
                if("created".equals(status)) {
                    if(!(r.opt("alarmId") instanceof Number)||!(r.opt("revision") instanceof Number)) return;
                    alarm=r.getLong("alarmId"); revision=r.getLong("revision"); if(alarm<1||revision<1) return;
                    p.put("status","created").put("alarmId",alarm).put("revision",revision);
                    if(!createPrefs(c).edit().putString("request",p.toString()).commit()) return;
                } else if(!"unknown".equals(status)) return;
                else if("created".equals(p.optString("status"))||!id.equals(createActive)||!nonce.equals(createNonce)) return;
                completeCreate(c,id,status,alarm,revision);
            }
        } catch(Exception ignored) { }
    }
    /** committed is a durable receipt; capacity/unknown may have applied; not_sent was not dispatched. */
    public interface MutationCallback { void onResult(String status,String requestId,long alarmId,long revision); }
    private static SharedPreferences mutationPrefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("phone-mutation-v1",Context.MODE_PRIVATE);
    }
    private static MutationCallback mutationCallback;
    private static String mutationActive="", mutationNonce="";
    private static long mutationAttempt;
    /** Capture once when opening an editor or delete confirmation; persist this token across recreation. */
    public static synchronized JSONObject captureMutationTarget(Context context,long alarmId) {
        try {
            Context c=context.getApplicationContext(); load(c);
            if(alarmId<1||owner.isEmpty()||!owner.equals(reachable)||SystemClock.elapsedRealtime()>=connectedUntil) return null;
            JSONArray rows=snapshot.getJSONArray("alarms"); JSONObject row=null;
            for(int i=0;i<rows.length();i++) if(rows.getJSONObject(i).optLong("id")==alarmId) { row=rows.getJSONObject(i); break; }
            String epoch=snapshot.getString("epoch"); long revision=snapshot.getLong("revision");
            if(row==null||revision<0||!epoch.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return null;
            return new JSONObject().put("node",owner).put("epoch",epoch).put("revision",revision)
                .put("alarmId",alarmId).put("requestId",UUID.randomUUID().toString()).put("alarm",new JSONObject(row.toString()));
        } catch(Exception e) { return null; }
    }
    /** Durable barrier, including submitted patch; null means none. Never treat unknown as rejection. */
    public static synchronized JSONObject pendingMutation(Context context) {
        try { String s=mutationPrefs(context).getString("request",""); return s.isEmpty()?null:new JSONObject(s); }
        catch(Exception e) { throw new IllegalStateException("Unreadable mutation state",e); }
    }
    /** Explicit UI confirmation required for unknown: it may already have changed/deleted the phone alarm. */
    public static synchronized boolean forgetMutation(Context context,String requestId) {
        JSONObject p=pendingMutation(context);
        if(p==null||!requestId.equals(p.optString("requestId"))||!mutationActive.isEmpty()) return false;
        boolean removed=mutationPrefs(context).edit().remove("request").commit();
        if(removed) broadcast(context.getApplicationContext());
        return removed;
    }
    /** Patch only supplied fields; never retry the write after uncertain dispatch. */
    public static void editAlarm(Context context,JSONObject target,JSONObject patch,MutationCallback callback) {
        mutate(context,target,"edit",patch,callback);
    }
    public static void deleteAlarm(Context context,JSONObject target,MutationCallback callback) {
        mutate(context,target,"delete",null,callback);
    }
    private static void mutate(Context context,JSONObject target,String op,JSONObject input,MutationCallback callback) {
        if(callback==null) return;
        Context c=context.getApplicationContext(); String id=""; boolean armed=false;
        try {
            if(target==null) throw new IllegalArgumentException();
            id=target.getString("requestId"); String node=target.getString("node"), epoch=target.getString("epoch");
            String uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
            if(!id.matches(uuid)||!epoch.matches(uuid)||node.isEmpty()||node.length()>256||
                !(target.opt("alarmId") instanceof Number)||!(target.opt("revision") instanceof Number)) throw new IllegalArgumentException();
            long alarm=new java.math.BigDecimal(target.get("alarmId").toString()).longValueExact();
            long revision=new java.math.BigDecimal(target.get("revision").toString()).longValueExact();
            if(alarm<1||revision<0) throw new IllegalArgumentException();
            JSONObject patch=null;
            if("edit".equals(op)) { AlarmStore.validateWearPatch(input); patch=new JSONObject(input.toString()); }
            else if(!"delete".equals(op)||input!=null) throw new IllegalArgumentException();
            String nonce=UUID.randomUUID().toString(); long issued=System.currentTimeMillis();
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
            out.writeByte(1); out.writeUTF(id); out.writeUTF(epoch); out.writeUTF(nonce); out.writeLong(issued);
            out.writeByte("edit".equals(op)?1:2); out.writeLong(alarm); out.writeLong(revision);
            if(patch!=null) out.writeUTF(patch.toString()); out.flush();
            byte[] body=bytes.toByteArray(); if(body.length>8192) throw new IllegalArgumentException();
            synchronized(PhoneSync.class) {
                load(c);
                if(pendingMutation(c)!=null||!mutationActive.isEmpty()||!node.equals(owner)||!node.equals(reachable)||
                    !epoch.equals(snapshot.optString("epoch"))||snapshot.optLong("revision",-1)!=revision||SystemClock.elapsedRealtime()>=connectedUntil)
                    throw new IllegalStateException();
                JSONObject record=new JSONObject().put("requestId",id).put("node",node).put("epoch",epoch)
                    .put("op",op).put("alarmId",alarm).put("baseRevision",revision).put("status","unknown");
                if(patch!=null) record.put("patch",patch);
                if(!mutationPrefs(c).edit().putString("request",record.toString()).commit()) throw new IllegalStateException();
                mutationActive=id; mutationNonce=nonce; mutationCallback=callback; mutationAttempt++; armed=true;
            }
            broadcast(c);
            sendMutation(c,id,node,epoch,MUTATE,body);
        } catch(Exception e) {
            synchronized(PhoneSync.class) { if(armed&&id.equals(mutationActive)) { completeMutation(c,id,"unknown",0,0); return; } }
            String request=id; boolean exists;
            synchronized(PhoneSync.class) {
                JSONObject p=pendingMutation(c); exists=false;
                if(p!=null&&request.equals(p.optString("requestId"))&&target!=null) {
                    try {
                        long alarm=target.getLong("alarmId"), revision=target.getLong("revision");
                        String attempted=AlarmStore.wearMutationFingerprint(op,alarm,revision,"edit".equals(op)?input:null);
                        String recorded=AlarmStore.wearMutationFingerprint(p.getString("op"),p.getLong("alarmId"),
                            p.getLong("baseRevision"),p.optJSONObject("patch"));
                        exists=target.getString("node").equals(p.getString("node"))&&
                            target.getString("epoch").equals(p.getString("epoch"))&&attempted.equals(recorded);
                    } catch(Exception ignored) { exists=false; }
                }
            }
            String result=exists?"unknown":"not_sent"; MAIN.post(()->callback.onResult(result,request,0,0));
        }
    }
    /** Read-only receipt lookup; absent is unknown, not a rejection. */
    public static void reconcileMutation(Context context,MutationCallback callback) {
        if(callback==null) return;
        Context c=context.getApplicationContext(); String id=""; boolean armed=false;
        try {
            String node,epoch,nonce=UUID.randomUUID().toString();
            synchronized(PhoneSync.class) {
                load(c); JSONObject p=pendingMutation(c);
                if(p==null) throw new IllegalStateException();
                id=p.getString("requestId"); node=p.getString("node"); epoch=p.getString("epoch");
                if(!mutationActive.isEmpty()) throw new IllegalStateException();
                if("committed".equals(p.optString("status"))) {
                    String done=id; long alarm=p.getLong("alarmId"), revision=p.getLong("revision");
                    MAIN.post(()->callback.onResult("committed",done,alarm,revision)); return;
                }

                if(!node.equals(owner)||!node.equals(reachable)||!epoch.equals(snapshot.optString("epoch"))||SystemClock.elapsedRealtime()>=connectedUntil) throw new IllegalStateException();
                mutationActive=id; mutationNonce=nonce; mutationCallback=callback; mutationAttempt++; armed=true;
            }
            broadcast(c);
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
            out.writeByte(1); out.writeUTF(id); out.writeUTF(epoch); out.writeUTF(nonce); out.writeLong(System.currentTimeMillis()); out.flush();
            sendMutation(c,id,node,epoch,MUTATE_STATUS,bytes.toByteArray());
        } catch(Exception e) {
            synchronized(PhoneSync.class) { if(armed&&id.equals(mutationActive)) { completeMutation(c,id,"unknown",0,0); return; } }
            String request=id; MAIN.post(()->callback.onResult(request.isEmpty()?"not_sent":"unknown",request,0,0));
        }
    }
    private static void sendMutation(Context c,String id,String node,String epoch,String path,byte[] body) {
        final long attempt; synchronized(PhoneSync.class) { attempt=mutationAttempt; }
        MAIN.postDelayed(()->finishMutationAttempt(c,id,attempt),25_000);
        SEND.execute(()->{
            try {
                if(!node.equals(onePhone(c))) { finishMutationAttempt(c,id,attempt); return; }
                synchronized(PhoneSync.class) {
                    if(!id.equals(mutationActive)||attempt!=mutationAttempt||!node.equals(owner)||!node.equals(reachable)||
                        !epoch.equals(snapshot.optString("epoch"))||SystemClock.elapsedRealtime()>=connectedUntil) {
                        finishMutationAttempt(c,id,attempt); return;
                    }
                    Wearable.getMessageClient(c).sendMessage(node,path,body).addOnFailureListener(e->finishMutationAttempt(c,id,attempt));
                }
            } catch(Exception e) { finishMutationAttempt(c,id,attempt); }
        });
    }
    private static void finishMutationAttempt(Context c,String id,long attempt) {
        synchronized(PhoneSync.class) {
            if(attempt==mutationAttempt&&id.equals(mutationActive)) completeMutation(c,id,"unknown",0,0);
        }
    }
    private static void completeMutation(Context c,String id,String status,long alarm,long revision) {
        MutationCallback cb=null;
        synchronized(PhoneSync.class) {
            if(id.equals(mutationActive)) { cb=mutationCallback; mutationCallback=null; mutationActive=""; mutationNonce=""; }
        }
        if(cb!=null) { MutationCallback result=cb; MAIN.post(()->result.onResult(status,id,alarm,revision)); }
        broadcast(c); if("committed".equals(status)) startFetch(c,true);
    }
    private static void mutationReply(Context c,String node,byte[] body) {
        if(body==null||body.length>1024) return;
        try {
            JSONObject r=new JSONObject(new String(body,StandardCharsets.UTF_8));
            String id=r.getString("requestId"), epoch=r.getString("epoch"), nonce=r.getString("nonce"), status=r.getString("status");
            synchronized(PhoneSync.class) {
                load(c); JSONObject p=pendingMutation(c);
                if(p==null||!id.equals(p.optString("requestId"))||!node.equals(p.optString("node"))||
                    !epoch.equals(p.optString("epoch"))||!node.equals(owner)||!epoch.equals(snapshot.optString("epoch"))) return;
                long alarm=0, revision=0;
                if("committed".equals(status)) {
                    if(!(r.opt("alarmId") instanceof Number)||!(r.opt("revision") instanceof Number)||!r.getString("op").equals(p.getString("op"))) return;
                    alarm=r.getLong("alarmId"); revision=r.getLong("revision");
                    if(alarm!=p.getLong("alarmId")||revision<=p.getLong("baseRevision")||
                        !r.getString("fingerprint").equals(AlarmStore.wearMutationFingerprint(p.getString("op"),alarm,p.getLong("baseRevision"),p.optJSONObject("patch")))) return;
                    p.put("status","committed").put("revision",revision);
                    if(!mutationPrefs(c).edit().putString("request",p.toString()).commit()) return;
                } else if("capacity".equals(status)) {
                    if("committed".equals(p.optString("status"))||!id.equals(mutationActive)||!nonce.equals(mutationNonce)) return;
                    // This delivery did not commit, but a delayed replay could still
                    // commit if space changes; retain the uncertainty barrier.
                    p.put("status","capacity");
                    if(!mutationPrefs(c).edit().putString("request",p.toString()).commit()) return;
                } else if(!"unknown".equals(status)||"committed".equals(p.optString("status"))||
                        !id.equals(mutationActive)||!nonce.equals(mutationNonce)) return;
                completeMutation(c,id,status,alarm,revision);
            }
        } catch(Exception ignored) { }
    }
    static void reply(Context context,String node,String path,byte[] body) {
        if(CREATE_REPLY.equals(path)) { createReply(context.getApplicationContext(),node,body); return; }
        if(MUTATE_REPLY.equals(path)) { mutationReply(context.getApplicationContext(),node,body); return; }
        if(!REPLY.equals(path)||body==null||body.length>1024) return;
        try {
            JSONObject result=new JSONObject(new String(body,StandardCharsets.UTF_8));
            String id=result.getString("requestId"); boolean ok=result.getBoolean("ok");
            synchronized(PhoneSync.class) {
                if(pending==null||!pending.node.equals(node)||!pending.id.equals(id)) return;
                if(!node.equals(owner)||!node.equals(reachable)) ok=false;
            }
            long committedRevision = result.opt("revision") instanceof Number ? result.optLong("revision",-1) : -1;
            finish(context.getApplicationContext(),id,ok,ok?"Phone alarm updated":"Phone change not confirmed; refresh on watch",committedRevision);
        } catch(Exception ignored) { }
    }
    private static void finish(Context c,String id,boolean ok,String message) {
        finish(c,id,ok,message,-1);
    }
    private static void finish(Context c,String id,boolean ok,String message,long committedRevision) {
        Callback callback;
        synchronized(PhoneSync.class) {
            if(pending==null||!pending.id.equals(id)) return;
            callback=pending.callback; pending=null;
            if(ok && committedRevision>=0) try {
                SharedPreferences cp=commandPrefs(c); JSONObject b=new JSONObject(cp.getString("barrier","{}"));
                if(id.equals(b.optString("id")) && committedRevision>=b.optLong("revision",Long.MAX_VALUE)) {
                    b.put("ack",true).put("committedRevision",committedRevision);
                    if(cp.edit().putString("barrier",b.toString()).commit()) coverCommand(c,snapshot);
                }
            } catch(Exception ignored) { }
        }
        MAIN.post(()->callback.onResult(ok,message));
        broadcast(c);
        startFetch(c,true);
    }
    private PhoneSync() { }
}
