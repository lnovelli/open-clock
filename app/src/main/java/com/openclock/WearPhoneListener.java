package com.openclock;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import java.nio.charset.StandardCharsets;

import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Only matching signed apps can use the Play-services Data Layer. */
public final class WearPhoneListener extends WearableListenerService {
    @Override public void onMessageReceived(MessageEvent e) {
        final String path=e.getPath(), node=e.getSourceNodeId();
        final byte[] body=e.getData();
        if(node==null||node.isEmpty()||node.length()>256||body==null||body.length>(WearSync.MUTATE.equals(path)?8192:1024)||
            !(WearSync.REQUEST.equals(path)||WearSync.COMMAND.equals(path)||WearSync.CREATE.equals(path)||WearSync.CREATE_STATUS.equals(path)||WearSync.MUTATE.equals(path)||WearSync.MUTATE_STATUS.equals(path))) return;
        // WearableListenerService dispatches on a background thread. Finish the
        // bounded request before returning so its service binding protects it.
        handle(node,path,body);
    }
    private static long integer(JSONObject obj,String key) throws Exception {
        return new java.math.BigDecimal(obj.get(key).toString()).longValueExact();
    }
    private void handleCreate(String node,String path,byte[] body) throws Exception {
        java.io.DataInputStream in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(body));
        if(in.readUnsignedByte()!=1) return;
        String req=in.readUTF(), epoch=in.readUTF(), nonce=in.readUTF();
        long issued=in.readLong(), now=System.currentTimeMillis();
        if(!req.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||
            !nonce.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||
            !WearSync.epoch(this).equals(epoch)) return;
        // A receipt lookup is read-only and can reconcile even when device clocks differ.
        if(WearSync.CREATE.equals(path)&&(issued>now+5_000||issued<now-15_000)) return;
        long deadlineElapsed=android.os.SystemClock.elapsedRealtime()+Math.max(0,issued+15_000-now);
        JSONObject alarm=null;
        if(WearSync.CREATE.equals(path)) {
            int hour=in.readUnsignedByte(), minute=in.readUnsignedByte();
            String date=in.readUTF(), label=in.readUTF(); int flags=in.readUnsignedByte();
            if(hour>23||minute>59||label.length()>200||!label.equals(label.trim())||flags>3) return;
            if(!date.isEmpty()) AlarmTimes.parseDate(date);
            alarm=new JSONObject().put("hour",hour).put("minute",minute).put("label",label)
                .put("sound",(flags&1)!=0).put("vibrate",(flags&2)!=0);
            if(!date.isEmpty()) alarm.put("date",date);
        }
        if(in.available()!=0) return;
        JSONObject receipt=null;
        try {
            receipt=alarm==null?AlarmStore.wearCreateReceipt(this,node,req):
                AlarmStore.wearCreate(this,alarm,node,req,issued+15_000,deadlineElapsed);
        } catch(Exception ignored) { /* A failure can follow commit: only reconcile, never retry. */ }
        JSONObject reply=new JSONObject().put("requestId",req).put("epoch",epoch).put("nonce",nonce)
            .put("status",receipt==null?"unknown":"created");
        if(receipt!=null) {
            reply.put("alarmId",receipt.getLong("alarmId")).put("revision",receipt.getLong("revision"));
            WearSync.publishSoon(this);
        }
        Wearable.getMessageClient(this).sendMessage(node,WearSync.CREATE_REPLY,reply.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void handleMutation(String node,String path,byte[] body) throws Exception {
        java.io.DataInputStream in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(body));
        if(in.readUnsignedByte()!=1) return;
        String req=in.readUTF(), epoch=in.readUTF(), nonce=in.readUTF();
        long issued=in.readLong(), now=System.currentTimeMillis();
        String uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        if(!req.matches(uuid)||!nonce.matches(uuid)||!epoch.matches(uuid)||!WearSync.epoch(this).equals(epoch)) return;
        boolean write=WearSync.MUTATE.equals(path);
        if(write&&(issued>now+5_000||issued<now-15_000)) return;
        long deadline=android.os.SystemClock.elapsedRealtime()+Math.max(0,issued+15_000-now);
        String op=""; long id=0, revision=0; JSONObject patch=null;
        if(write) {
            int code=in.readUnsignedByte(); if(code!=1&&code!=2) return;
            op=code==1?"edit":"delete"; id=in.readLong(); revision=in.readLong();
            if(id<1||revision<0) return;
            if(code==1) { patch=new JSONObject(in.readUTF()); AlarmStore.validateWearPatch(patch); }
        }
        if(in.available()!=0) return;
        JSONObject receipt=null; boolean capacity=false;
        try {
            receipt=write?AlarmStore.wearMutation(this,node,epoch,req,op,id,revision,patch,issued+15_000,deadline):
                AlarmStore.wearMutationReceipt(this,node,epoch,req);
        } catch(AlarmStore.WearReceiptCapacityException full) {
            // This exception is raised before commit, after checking for an existing receipt.
            capacity=true;
        } catch(Exception ignored) { /* Commit may already have succeeded; never retry the write. */ }
        JSONObject reply=new JSONObject().put("requestId",req).put("epoch",epoch).put("nonce",nonce)
            .put("status",receipt!=null?"committed":capacity?"capacity":"unknown");
        if(receipt!=null) {
            reply.put("op",receipt.getString("op")).put("alarmId",receipt.getLong("alarmId"))
                .put("revision",receipt.getLong("revision")).put("fingerprint",receipt.getString("fingerprint"));
            WearSync.publishSoon(this);
        }
        Wearable.getMessageClient(this).sendMessage(node,WearSync.MUTATE_REPLY,reply.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void handle(String node,String path,byte[] body) {
        try {
            CapabilityInfo info=Tasks.await(Wearable.getCapabilityClient(this)
                .getCapability("openclock_watch_v1",CapabilityClient.FILTER_REACHABLE),5,TimeUnit.SECONDS);
            boolean found=false; for(Node n:info.getNodes()) if(node.equals(n.getId())) found=true;
            if(!found) return;
            if(WearSync.REQUEST.equals(path)) { WearSync.requestFromListener(this); return; }
            if(WearSync.CREATE.equals(path)||WearSync.CREATE_STATUS.equals(path)) { handleCreate(node,path,body); return; }
            if(WearSync.MUTATE.equals(path)||WearSync.MUTATE_STATUS.equals(path)) { handleMutation(node,path,body); return; }
            JSONObject cmd=new JSONObject(new String(body,StandardCharsets.UTF_8));
            String req=cmd.optString("requestId","");
            if(!req.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return;
            boolean ok=false; long committedRevision=-1; String result="Could not update phone alarm; refresh on watch";
            try {
                String op=cmd.optString("op");
                if(cmd.length()!=7||!WearSync.epoch(this).equals(cmd.optString("epoch"))||
                    !(cmd.opt("id") instanceof Number)||!(cmd.opt("issuedAt") instanceof Number)) throw new IllegalArgumentException();
                long id=integer(cmd,"id"), issued=integer(cmd,"issuedAt"), now=System.currentTimeMillis();
                if(id<1||issued>now+5_000||issued<now-15_000) throw new IllegalArgumentException();
                if("setEnabled".equals(op)) {
                    if(!(cmd.opt("enabled") instanceof Boolean)||!(cmd.opt("revision") instanceof Number)) throw new IllegalArgumentException();
                    long revision=integer(cmd,"revision"); if(revision<0) throw new IllegalArgumentException();
                    AlarmStore.wearSetEnabled(this,revision,id,cmd.getBoolean("enabled"),issued+15_000);
                } else if("finish".equals(op)) {
                    if(!(cmd.opt("token") instanceof String)||!(cmd.opt("snooze") instanceof Boolean)) throw new IllegalArgumentException();
                    String token=cmd.getString("token");
                    if(!token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException();
                    AlarmStore.wearFinish(this,id,token,cmd.getBoolean("snooze"),issued+15_000);
                } else throw new IllegalArgumentException();
                ok=true; result="Phone alarm updated";
                try { committedRevision=AlarmStore.wearSnapshot(this).getLong("revision"); } catch(Exception ignored) { }
            } catch(Exception ignored) { }
            if(ok) WearSync.publishSoon(this);
            JSONObject reply=new JSONObject().put("requestId",req).put("ok",ok).put("message",result);
            if(ok && committedRevision>=0) reply.put("revision",committedRevision);
            Wearable.getMessageClient(this).sendMessage(node,WearSync.REPLY,reply.toString().getBytes(StandardCharsets.UTF_8));
        } catch(Exception ignored) { }
    }
}
