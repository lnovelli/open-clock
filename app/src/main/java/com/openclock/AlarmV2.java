package com.openclock;

import android.content.Context;
import org.json.*;
import java.util.*;

/** v2 identity/CAS and bounded durable operation journal; callers hold AlarmStore.class. */
final class AlarmV2 {
    private static final int CAPACITY=128, JOURNAL_BYTES=384_000;
    static final class Failure extends Exception {
        final String code;
        Failure(String code,String message) { super(message); this.code=code; }
    }
    private static final class Mutation {
        JSONObject request, response, origin;
        String binding, uuid, operationId, op;
        long id, expiry, deadline, baseRevision;
        boolean committed, create, uncertain, group;
    }
    // Scoped to the serialized synchronous mutation, never retained across calls/threads.
    private static Mutation pending;
    private static Failure fail(String code) { return new Failure(code,code); }
    private static void fields(JSONObject value,String... allowed) throws Exception {
        Set<String> names=new HashSet<>(Arrays.asList(allowed)); Iterator<String> keys=value.keys();
        while(keys.hasNext()) if(!names.contains(keys.next())) throw fail("INVALID_ARGUMENT");
    }
    private static String uuid(JSONObject object,String key) throws Exception {
        Object value=object.opt(key);
        if(!(value instanceof String)||!AlarmIdentity.uuid((String)value)) throw fail("INVALID_ARGUMENT");
        return (String)value;
    }
    private static long integer(JSONObject object,String key,long min) throws Exception {
        Object value=object.opt(key);
        if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min) throw fail("INVALID_ARGUMENT");
        return ((Number)value).longValue();
    }
    private static JSONObject object(JSONObject object,String key) throws Exception {
        if(!(object.opt(key) instanceof JSONObject)) throw fail("INVALID_ARGUMENT"); return object.getJSONObject(key);
    }
    static JSONObject view(Context c,JSONObject stored) throws Exception {
        JSONObject out=AlarmStore.publicAlarm(stored);
        out.put("uuid",stored.getString("_uuid")).put("revision",stored.getLong("_revision"))
            .put("origin",stored.has("_origin")?new JSONObject(stored.getJSONObject("_origin").toString()):JSONObject.NULL);
        String problem=AlarmStore.accessProblem(c);
        if(out.optBoolean("enabled")&&problem!=null) out.put("schedulingError",problem).put("nextTriggerAt",0);
        return out;
    }
    private static JSONObject groupView(JSONObject group) throws Exception {
        if(group==null) throw new IllegalStateException("Missing group target");
        return new JSONObject().put("id",group.getLong("id")).put("name",group.getString("name"))
            .put("revision",group.getLong("_revision"));
    }
    private static JSONObject select(JSONObject state,JSONObject r) throws Exception {
        int count=(r.has("alarmUuid")?1:0)+(r.has("id")?1:0)+(r.has("origin")?1:0);
        if(count!=1) throw fail("INVALID_ARGUMENT");
        String uuid=r.has("alarmUuid")?uuid(r,"alarmUuid"):null;
        long id=r.has("id")?integer(r,"id",1):0;
        JSONObject origin=r.has("origin")?AlarmIdentity.origin(object(r,"origin")):null;
        JSONObject found=null; JSONArray rows=state.getJSONArray("alarms");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i); if(row.optBoolean("_phoneFallback")) continue;
            boolean match=uuid!=null?uuid.equals(row.optString("_uuid")):id>0?id==row.getLong("id"):
                row.has("_origin")&&AlarmIdentity.key(origin).equals(AlarmIdentity.key(row.getJSONObject("_origin")));
            if(match) { if(found!=null) throw fail("AMBIGUOUS"); found=row; }
        }
        if(found==null) throw fail("NOT_FOUND"); return found;
    }
    private static JSONObject receipt(JSONObject state,String id) throws Exception {
        JSONArray receipts=state.optJSONArray("_binderOperations");
        if(receipts!=null) for(int i=0;i<receipts.length();i++) {
            JSONObject receipt=receipts.getJSONObject(i); if(id.equals(receipt.getString("operationId"))) return receipt;
        }
        return null;
    }
    private static void compact(JSONObject state,long now) throws Exception {
        JSONArray old=state.optJSONArray("_binderOperations"), kept=new JSONArray();
        long floor=state.optLong("_binderClosedThrough",0);
        if(old!=null) for(int i=0;i<old.length();i++) {
            JSONObject row=old.getJSONObject(i);
            if(row.getLong("expiresAt")<=now) floor=Math.max(floor,row.getLong("issuedAt")); else kept.put(row);
        }
        if(kept.length()>=CAPACITY) throw fail("CAPACITY");
        state.put("_binderOperations",kept).put("_binderClosedThrough",floor);
    }
    static void validateJournal(JSONObject state) throws Exception {
        if(!state.has("_binderOperations")&&!state.has("_binderClosedThrough")) return;
        integer(state,"_binderClosedThrough",0);
        JSONArray rows=state.getJSONArray("_binderOperations"); Set<String> ids=new HashSet<>();
        if(rows.length()>CAPACITY||rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>JOURNAL_BYTES)
            throw new IllegalStateException("Invalid operation journal");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i); String operationId=uuid(row,"operationId");
            if(!ids.add(operationId)||!(row.opt("binding") instanceof String)) throw new IllegalStateException("Invalid operation receipt");
            JSONObject request=new JSONObject(row.getString("binding")), response=row.getJSONObject("response");
            long issued=integer(row,"issuedAt",1), expires=integer(row,"expiresAt",1);
            if(expires<=issued||expires-issued>300_000||issued!=integer(request,"issuedAt",1)||expires!=integer(request,"expiresAt",1)
                ||!operationId.equals(uuid(request,"operationId"))||!operationId.equals(response.getString("operationId"))||!response.getBoolean("ok"))
                throw new IllegalStateException("Invalid operation binding");
        }
    }
    interface SaveObserver { void afterSave(Context c,JSONObject before,JSONObject after); }
    static JSONObject dispatch(Context c,JSONObject r) throws Exception { return dispatch(c,r,null); }
    static JSONObject dispatch(Context c,JSONObject r,SaveObserver observer) throws Exception {
        if(!Thread.holdsLock(AlarmStore.class)) throw new IllegalStateException("Store lock required");
        String op=r.optString("op"); JSONObject out=new JSONObject().put("ok",true);
        if(op.equals("alarms.v2.capabilities")) { fields(r,"op"); return out.put("version",2); }
        if(op.equals("alarms.v2.list")) {
            fields(r,"op","offset","limit");
            long offset=r.has("offset")?integer(r,"offset",0):0, limit=r.has("limit")?integer(r,"limit",1):1;
            if(offset>500||limit>5) throw fail("INVALID_ARGUMENT");
            JSONArray source=AlarmStore.state(c).getJSONArray("alarms"), result=new JSONArray(); int total=0;
            for(int i=0;i<source.length();i++) if(!source.getJSONObject(i).optBoolean("_phoneFallback")) {
                if(total>=offset&&result.length()<limit) result.put(view(c,source.getJSONObject(i))); total++;
            }
            long next=offset+result.length();
            return out.put("alarms",result).put("offset",offset).put("total",total).put("nextOffset",next<total?next:JSONObject.NULL);
        }
        if(op.equals("alarms.v2.get")) {
            fields(r,"op","alarmUuid","id","origin"); return out.put("alarm",view(c,select(AlarmStore.state(c),r)));
        }
        if(op.equals("alarms.v2.status")) {
            fields(r,"op","operationId"); JSONObject receipt=receipt(AlarmStore.state(c),uuid(r,"operationId"));
            if(receipt==null) throw fail("UNKNOWN"); return new JSONObject(receipt.getJSONObject("response").toString());
        }
        if(op.equals("alarms.v2.groups.list")) {
            fields(r,"op","offset","limit");
            long offset=r.has("offset")?integer(r,"offset",0):0, limit=r.has("limit")?integer(r,"limit",1):1;
            if(offset>100||limit>5) throw fail("INVALID_ARGUMENT");
            JSONArray source=AlarmStore.state(c).getJSONArray("groups"), result=new JSONArray();
            for(int i=(int)offset;i<source.length()&&result.length()<limit;i++) result.put(groupView(source.getJSONObject(i)));
            long next=offset+result.length();
            return out.put("groups",result).put("offset",offset).put("total",source.length())
                .put("nextOffset",next<source.length()?next:JSONObject.NULL);
        }
        if(op.startsWith("alarms.v2.groups.")) return groupMutation(c,r,op);
        boolean create=op.equals("alarms.v2.create"), immediate=op.equals("alarms.v2.triggerNow"),
            update=op.equals("alarms.v2.update"), delete=op.equals("alarms.v2.delete");
        if(!create&&!immediate&&!update&&!delete) throw fail("UNKNOWN_OPERATION");
        if(create) fields(r,"op","alarmUuid","operationId","issuedAt","expiresAt","alarm","origin");
        else if(update) fields(r,"op","alarmUuid","operationId","issuedAt","expiresAt","expectedRevision","alarm","origin");
        else if(delete) fields(r,"op","alarmUuid","operationId","issuedAt","expiresAt","expectedRevision");
        else fields(r,"op","alarmUuid","operationId","issuedAt","expiresAt","alarm");
        Mutation m=new Mutation(); m.request=new JSONObject(r.toString()); m.op=op;
        m.uuid=uuid(r,"alarmUuid"); m.operationId=uuid(r,"operationId"); m.create=create||immediate;
        long admittedElapsed=android.os.SystemClock.elapsedRealtime();
        long issued=integer(r,"issuedAt",1), expires=integer(r,"expiresAt",1), now=System.currentTimeMillis();
        if(expires<=issued||expires-issued>300_000) throw fail("INVALID_ARGUMENT");
        if(update||delete) integer(r,"expectedRevision",1);
        if(r.has("origin")) m.origin=AlarmIdentity.origin(object(r,"origin"));
        JSONObject patch=null;
        if(!delete) {
            patch=r.has("alarm")?new JSONObject(object(r,"alarm").toString()):new JSONObject();
            if(!immediate&&!r.has("alarm")) throw fail("INVALID_ARGUMENT");
            if(immediate) fields(patch,"label","sound","vibrate","vibrationPattern","snoozeEnabled","snoozeMinutes","snoozeCount","groupId");
            // Same strict public patch and scalar types as the existing companion edit protocol.
            if(patch.length()>0) {
                JSONObject validation=new JSONObject(patch.toString());
                if(validation.opt("label") instanceof String) {
                    String label=validation.getString("label");
                    if(label.length()>200) throw fail("INVALID_ARGUMENT");
                    validation.put("label",label.trim());
                }
                AlarmStore.validateWearPatch(validation);
            }
            if(create&&(!patch.has("hour")||!patch.has("minute"))) throw fail("INVALID_ARGUMENT");
        }
        m.binding=AlarmIdentity.normalized(r);
        JSONObject state=AlarmStore.state(c), priorReceipt=receipt(state,m.operationId);
        if(priorReceipt!=null) {
            if(!m.binding.equals(priorReceipt.getString("binding"))) throw fail("CONFLICT");
            return new JSONObject(priorReceipt.getJSONObject("response").toString());
        }
        if(now>=expires||issued>now+5_000||issued<=state.optLong("_binderClosedThrough",0)) throw fail("EXPIRED");
        compact(state,now); // Capacity is checked before any registration; eviction only persists with the mutation.
        if(issued<=state.optLong("_binderClosedThrough",0)) throw fail("EXPIRED");
        JSONObject old=null;
        if(update||delete) {
            old=select(state,new JSONObject().put("alarmUuid",m.uuid));
            if(old.getLong("_revision")!=integer(r,"expectedRevision",1)) throw fail("REVISION_CONFLICT");
            if(m.origin!=null&&old.has("_origin")&&!AlarmIdentity.key(m.origin).equals(AlarmIdentity.key(old.getJSONObject("_origin")))) throw fail("CONFLICT");
            m.id=old.getLong("id");
            m.baseRevision=old.getLong("_revision");
        } else {
            JSONArray rows=state.getJSONArray("alarms");
            for(int i=0;i<rows.length();i++) if(m.uuid.equals(rows.getJSONObject(i).optString("_uuid"))) throw fail("CONFLICT");
            m.id=state.getLong("nextId");
        }
        if(m.origin!=null) {
            JSONArray rows=state.getJSONArray("alarms");
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                if(row.getLong("id")!=m.id&&row.has("_origin")&&AlarmIdentity.key(m.origin).equals(AlarmIdentity.key(row.getJSONObject("_origin")))) throw fail("ORIGIN_CONFLICT");
            }
        }
        m.expiry=expires; m.deadline=Math.addExact(admittedElapsed,expires-now);
        if(pending!=null) throw new IllegalStateException("Nested v2 mutation");
        pending=m;
        try {
            if(delete) AlarmStore.deleteAlarm(c,m.id);
            else if(immediate) {
                java.time.ZonedDateTime date=java.time.ZonedDateTime.now();
                patch.put("hour",date.getHour()).put("minute",date.getMinute()).put("date",date.toLocalDate().toString()).put("days",new JSONArray()).put("enabled",true);
                AlarmStore.triggerNow(c,patch);
            } else {
                if(update) patch.put("id",m.id);
                AlarmStore.saveAlarm(c,patch);
            }
            if(!m.committed||m.response==null) throw new IllegalStateException("Mutation did not commit");
            if(observer!=null&&(create||update)) {
                try { observer.afterSave(c,old==null?null:AlarmStore.publicAlarm(old),m.response.getJSONObject("alarm")); }
                catch(RuntimeException ignored) { /* Presentation cannot change the committed result. */ }
            }
            return new JSONObject(m.response.toString());
        } finally { pending=null; }
    }
    private static JSONObject groupMutation(Context c,JSONObject r,String op) throws Exception {
        boolean create=op.equals("alarms.v2.groups.create"), update=op.equals("alarms.v2.groups.update"),
            delete=op.equals("alarms.v2.groups.delete");
        if(!create&&!update&&!delete) throw fail("UNKNOWN_OPERATION");
        if(create) fields(r,"op","operationId","issuedAt","expiresAt","group");
        else if(update) fields(r,"op","operationId","issuedAt","expiresAt","groupId","expectedRevision","group");
        else fields(r,"op","operationId","issuedAt","expiresAt","groupId","expectedRevision");
        Mutation m=new Mutation(); m.group=true; m.create=create; m.op=op;
        m.request=new JSONObject(r.toString()); m.operationId=uuid(r,"operationId");
        long admittedElapsed=android.os.SystemClock.elapsedRealtime();
        long issued=integer(r,"issuedAt",1), expires=integer(r,"expiresAt",1), now=System.currentTimeMillis();
        if(expires<=issued||expires-issued>300_000) throw fail("INVALID_ARGUMENT");
        if(!create) { m.id=integer(r,"groupId",1); integer(r,"expectedRevision",1); }
        JSONObject input=null;
        if(!delete) {
            JSONObject group=object(r,"group"); fields(group,"name");
            Object value=group.opt("name");
            if(!(value instanceof String)||((String)value).length()>100||((String)value).trim().isEmpty()) throw fail("INVALID_ARGUMENT");
            String name=(String)value;
            for(int i=0;i<name.length();i++) {
                char ch=name.charAt(i);
                if(Character.isHighSurrogate(ch)) {
                    if(++i>=name.length()||!Character.isLowSurrogate(name.charAt(i))) throw fail("INVALID_ARGUMENT");
                } else if(Character.isLowSurrogate(ch)) throw fail("INVALID_ARGUMENT");
            }
            input=new JSONObject().put("name",name);
            if(update) input.put("id",m.id);
        }
        m.binding=AlarmIdentity.normalized(r);
        JSONObject state=AlarmStore.state(c), priorReceipt=receipt(state,m.operationId);
        if(priorReceipt!=null) {
            if(!m.binding.equals(priorReceipt.getString("binding"))) throw fail("CONFLICT");
            return new JSONObject(priorReceipt.getJSONObject("response").toString());
        }
        if(now>=expires||issued>now+5_000||issued<=state.optLong("_binderClosedThrough",0)) throw fail("EXPIRED");
        compact(state,now);
        if(issued<=state.optLong("_binderClosedThrough",0)) throw fail("EXPIRED");
        if(create) m.id=state.getLong("nextId");
        else {
            JSONObject group=AlarmStore.find(state.getJSONArray("groups"),m.id);
            if(group==null) throw fail("NOT_FOUND");
            if(group.getLong("_revision")!=integer(r,"expectedRevision",1)) throw fail("REVISION_CONFLICT");
        }
        m.expiry=expires; m.deadline=Math.addExact(admittedElapsed,expires-now);
        if(pending!=null) throw new IllegalStateException("Nested v2 mutation");
        pending=m;
        try {
            if(delete) AlarmStore.deleteGroup(c,m.id); else AlarmStore.saveGroup(c,input);
            if(!m.committed||m.response==null) throw new IllegalStateException("Mutation did not commit");
            return new JSONObject(m.response.toString());
        } finally { pending=null; }
    }
    static void beforeIdentityCommit(JSONObject state) throws Exception {
        if(pending==null||pending.committed||pending.group) return;
        JSONObject row=AlarmStore.find(state.getJSONArray("alarms"),pending.id);
        if(pending.create) {
            if(row==null) throw new IllegalStateException("Missing v2 target");
            row.put("_uuid",pending.uuid).put("_revision",1);
        }
    }
    static void recordCommit(Context c,JSONObject state) throws Exception {
        Mutation m=pending; if(m==null||m.committed) return;
        long now=System.currentTimeMillis();
        if(now>=m.expiry||m.request.getLong("issuedAt")>now+5_000||android.os.SystemClock.elapsedRealtime()>=m.deadline) throw fail("EXPIRED");
        compact(state,now);
        if(m.request.getLong("issuedAt")<=state.optLong("_binderClosedThrough",0)) throw fail("EXPIRED");
        JSONObject row=m.group?null:AlarmStore.find(state.getJSONArray("alarms"),m.id);
        if(m.origin!=null) {
            if(row==null) throw new IllegalStateException("Missing origin target");
            if(!row.has("_origin")) {
                row.put("_origin",new JSONObject(m.origin.toString()));
                if(!m.create) row.put("_revision",Math.max(row.getLong("_revision"),Math.addExact(m.baseRevision,1)));
            }
        }
        AlarmIdentity.validate(state.getJSONArray("alarms"));
        JSONObject response=new JSONObject().put("ok",true).put("operationId",m.operationId);
        if(m.op.equals("alarms.v2.groups.delete")) response.put("groupId",m.id);
        else if(m.group) response.put("group",groupView(AlarmStore.find(state.getJSONArray("groups"),m.id)));
        else if(m.op.equals("alarms.v2.delete")) response.put("alarmUuid",m.uuid);
        else response.put("alarm",view(c,row));
        JSONObject receipt=new JSONObject().put("operationId",m.operationId).put("binding",m.binding)
            .put("issuedAt",m.request.getLong("issuedAt")).put("expiresAt",m.expiry).put("response",response);
        JSONArray receipts=state.getJSONArray("_binderOperations"); receipts.put(receipt);
        if(receipts.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>JOURNAL_BYTES
                ||state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_999_968) throw fail("CAPACITY");
        m.response=response;
    }
    static long pendingExpiry() { return pending!=null&&!pending.committed?pending.expiry:0; }
    static void finishingCommit() { if(pending!=null&&!pending.committed) pending.uncertain=true; }
    static boolean retainTentative() { return pending!=null&&pending.uncertain&&!pending.committed; }
    static void checkExpiry() throws Exception {
        if(pending!=null&&!pending.committed && (System.currentTimeMillis()>=pending.expiry
                ||pending.request.getLong("issuedAt")>System.currentTimeMillis()+5_000
                ||android.os.SystemClock.elapsedRealtime()>=pending.deadline)) throw fail("EXPIRED");
    }
    static long pendingDeadline() { return pending.deadline; }
    static void didCommit() { if(pending!=null&&!pending.committed) pending.committed=true; }
    private AlarmV2() {}
}
