package com.openclock;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import org.json.*;
import java.time.ZoneId;
import java.util.*;

/** Single-process, synchronized, durable alarm store. All timestamps are epoch milliseconds.
 * saveAlarm merges omitted public fields on edits. Unknown/internal input fields are ignored.
 * snoozeCount=0 means unlimited; otherwise it is the maximum snoozes per occurrence.
 * Public reads include nextTriggerAt and schedulingError. Scheduling failure never reports success.
 */
public final class AlarmStore {
    static final long RING_TIMEOUT = 10 * 60_000L;
    private static final long RETRY_DELAY = 5 * 60_000L;
    private static final int PHONE_DISMISSAL_LIMIT = 128;
    private static boolean occurrenceId(String value) {
        return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static String bootIdentity() {
        try (java.io.BufferedReader in=new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String id=in.readLine(); return occurrenceId(id)?id:"";
        } catch(Exception e) { return ""; }
    }
    private static android.util.AtomicFile file(Context c) {
        return new android.util.AtomicFile(new java.io.File(c.createDeviceProtectedStorageContext().getNoBackupFilesDir(), "alarms-v1.json"));
    }
    private static JSONObject state(Context c) {
        android.util.AtomicFile f=file(c);
        try {
            if(!f.getBaseFile().exists()&&!new java.io.File(f.getBaseFile()+".bak").exists())
                return new JSONObject("{\"alarms\":[],\"groups\":[],\"nextId\":1}");
            byte[] data=f.readFully();
            if(data.length>2_000_000) throw new java.io.IOException("Alarm storage is too large");
            return new JSONObject(new String(data,java.nio.charset.StandardCharsets.UTF_8));
        } catch(Exception e) { throw new IllegalStateException("Alarm storage is unreadable; refusing to overwrite it",e); }
    }
    private static void commit(Context c, JSONObject state) throws Exception { commit(c,state,0,0); }
    private static void commit(Context c, JSONObject state,long expiresAt,long deadlineElapsed) throws Exception {
        // Install recovery BEFORE persistence: a committed active session must have a
        // platform-owned wakeup even if the process dies before service startup.
        JSONObject recoveryState=new JSONObject(state.toString()), priorState=AlarmStore.state(c);
        JSONArray union=recoveryState.getJSONArray("alarms"), previous=priorState.getJSONArray("alarms");
        if(priorState.has("_phoneStaged")) recoveryState.put("_phoneStaged",priorState.getJSONArray("_phoneStaged"));
        for(int i=0;i<previous.length();i++) union.put(previous.getJSONObject(i));
        try { armRecovery(c,recoveryState); }
        catch(Exception unavailable) {
            boolean needsRecovery=false;
            JSONArray proposedStages=state.optJSONArray("_phoneStaged"), priorStages=priorState.optJSONArray("_phoneStaged");
            if(proposedStages!=null&&(priorStages==null||proposedStages.length()>priorStages.length())) needsRecovery=true;
            JSONArray proposed=state.getJSONArray("alarms");
            for(int i=0;i<proposed.length();i++) {
                JSONObject a=proposed.getJSONObject(i), old=find(previous,a.optLong("id"));
                if(a.has("_token")&&(old==null||!a.optString("_token").equals(old.optString("_token")))) needsRecovery=true;
                JSONObject session=a.optJSONObject("_active"), prior=old==null?null:old.optJSONObject("_active");
                if(session!=null&&(prior==null||!session.optString("token").equals(prior.optString("token")))) needsRecovery=true;
            }
            if(needsRecovery) throw unavailable;
            // Metadata, deletion and dismissal must remain available when Android
            // refuses another registration. Previously installed recovery remains.
            AlarmNotifications.warning(c,message(unavailable));
        }
        // Register recovery before the durable mutation; watch's implementation is a no-op.
        if(expiresAt!=0&&(System.currentTimeMillis()>expiresAt||android.os.SystemClock.elapsedRealtime()>deadlineElapsed))
            throw new IllegalArgumentException("Request expired");
        // Only arm the platform dismissal job when this commit changes its
        // durable queue; ordinary alarm/snapshot commits must not churn jobs.
        if(!String.valueOf(state.opt("_genericDismissals")).equals(String.valueOf(priorState.opt("_genericDismissals"))))
            WearSync.beforeCommit(c);
        state.put("_wearRevision", Math.addExact(state.optLong("_wearRevision", 0), 1));
        if(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2_000_000)
            throw new IllegalStateException("Alarm storage limit reached");
        android.util.AtomicFile f=file(c); java.io.FileOutputStream stream=null;
        try {
            stream=f.startWrite(); stream.write(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stream.flush(); stream.getFD().sync();
            if(expiresAt!=0&&(System.currentTimeMillis()>expiresAt||android.os.SystemClock.elapsedRealtime()>deadlineElapsed))
                throw new IllegalArgumentException("Request expired");
            f.finishWrite(stream);
        } catch(Exception e) { if(stream!=null) f.failWrite(stream); throw new java.io.IOException("Could not persist alarm changes",e); }
        try { WearSync.afterCommit(c); }
        catch(RuntimeException ignored) { /* advisory sync cannot change alarm result */ }
    }
    static JSONObject find(JSONArray list, long id) {
        for (int i=0;i<list.length();i++) if (list.optJSONObject(i).optLong("id")==id) return list.optJSONObject(i);
        return null;
    }
    private static int index(JSONArray list, long id) {
        for (int i=0;i<list.length();i++) if(list.optJSONObject(i).optLong("id")==id) return i;
        return -1;
    }
    private static JSONObject publicAlarm(JSONObject a) throws JSONException {
        JSONObject out = new JSONObject(a.toString());
        Iterator<String> keys = out.keys(); List<String> remove = new ArrayList<>();
        while(keys.hasNext()) { String k=keys.next(); if(k.startsWith("_")) remove.add(k); }
        for(String k:remove) out.remove(k);
        if(!out.has("vibrationPattern")) out.put("vibrationPattern","basic");
        out.put("nextTriggerAt", a.optLong("_nextAt",0));
        // Expose pending snooze independently of the ordinary alarm time. Keep
        // due/queued snoozes visible until fire, disable, or recovery clears them.
        out.put("snoozedUntil", a.optBoolean("enabled") && !a.has("_active") ? a.optLong("_snoozeAt",0) : 0);
        out.put("schedulingError", a.opt("_error") == null ? JSONObject.NULL : a.opt("_error"));
        return out;
    }
    public static synchronized JSONArray alarms(Context c) {
        try {
            JSONArray source=state(c).getJSONArray("alarms"), result=new JSONArray();
            String problem=accessProblem(c);
            for(int i=0;i<source.length();i++) {
                JSONObject stored=source.getJSONObject(i);
                if(stored.optBoolean("_phoneFallback")) continue;
                JSONObject a=publicAlarm(stored);
                if(a.optBoolean("enabled")&&problem!=null) a.put("schedulingError",problem).put("nextTriggerAt",0);
                result.put(a);
            }
            return result;
        } catch(JSONException e) { throw new IllegalStateException(e); }
    }
    public static synchronized JSONArray groups(Context c) {
        try { return new JSONArray(state(c).getJSONArray("groups").toString()); }
        catch(JSONException e) { throw new IllegalStateException(e); }
    }
    /** Watch caller: hidden phone rows share the device-protected alarm journal,
     * local ID namespace, generation tokens, recovery and registration protocol. */
    static synchronized void syncPhoneFallback(Context c, String node, JSONObject snapshot) throws Exception {
        cleanupPhoneStaged(c);
        String epoch=snapshot.getString("epoch"); long revision=snapshot.getLong("revision"), published=snapshot.getLong("updatedAt");
        if(node.isEmpty()||!epoch.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||revision<0||published<=0)
            throw new IllegalArgumentException("Invalid phone snapshot identity");
        JSONObject s=state(c); JSONArray rows=s.getJSONArray("alarms"), input=snapshot.getJSONArray("alarms");
        if(input.length()>500) throw new IllegalArgumentException("Too many phone alarms");
        boolean same=node.equals(s.optString("_phoneNode"))&&epoch.equals(s.optString("_phoneEpoch"));
        if(!phoneSnapshotAllowed(s,node,epoch,revision,published)) throw new IllegalArgumentException("Stale or retired phone snapshot");
        if(same&&(revision<s.optLong("_phoneRevision",-1)||(revision==s.optLong("_phoneRevision",-1)
                &&published<s.optLong("_phonePublished",0)))) return;
        Set<Long> ids=new HashSet<>(); List<JSONObject> source=new ArrayList<>();
        // Validate the entire input before changing registrations or state.
        for(int i=0;i<input.length();i++) {
            JSONObject row=input.getJSONObject(i); long id=integer(row,"id",0,1,Long.MAX_VALUE);
            if(!ids.add(id)||!(row.opt("enabled") instanceof Boolean)) throw new IllegalArgumentException("Invalid phone alarms");
            JSONObject clean=new JSONObject(row.toString()); clean.put("groupId",JSONObject.NULL);
            JSONObject validated=canonical(clean,null,1,new JSONArray());
            long next=integer(row,"nextTriggerAt",0,0,Long.MAX_VALUE);
            long snooze=integer(row,"snoozedUntil",0,0,Long.MAX_VALUE);
            validated.put("_sourceId",id).put("_sourceNext",next).put("_sourceSnooze",snooze);
            String pendingId=row.optString("occurrenceId","");
            String dismissedId=row.optString("lastDismissedOccurrenceId","");
            if(occurrenceId(pendingId)) validated.put("_sourceOccurrenceId",pendingId);
            if(occurrenceId(dismissedId)) validated.put("_sourceDismissedId",dismissedId);
            source.add(validated);
        }
        // Persist intent and owner before staging; leave the old rows untouched.
        JSONObject previousDesired=s.optJSONObject("_phoneDesired");
        String desiredNode=s.optString("_phoneDesiredNode",s.optString("_phoneNode"));
        if(previousDesired!=null && (!node.equals(desiredNode)||!epoch.equals(previousDesired.optString("epoch"))))
            retirePhoneEpoch(s,desiredNode,previousDesired.optString("epoch"));
        if(!same) retirePhoneEpoch(s,s.optString("_phoneNode"),s.optString("_phoneEpoch"));
        long now=System.currentTimeMillis();
        s.put("_phoneDesired",new JSONObject(snapshot.toString())).put("_phoneDesiredNode",node)
            .put("_phoneRetryAt",now+30_000);
        commit(c,s);
        JSONArray active=snapshot.optJSONArray("active");
        JSONArray activeHistory=s.optJSONArray("_phoneActiveHistory");
        if(activeHistory==null) { activeHistory=new JSONArray(); s.put("_phoneActiveHistory",activeHistory); }
        List<JSONObject> installed=new ArrayList<>(), obsolete=new ArrayList<>();
        int registrations=0, fallbackCount=0;
        for(int i=0;i<rows.length();i++) {
            JSONObject r=rows.getJSONObject(i);
            if(r.has("_token")) registrations++;
            if(r.optBoolean("_phoneFallback")) fallbackCount++;
        }
        boolean pending=false;
        try {
            for(int i=rows.length()-1;i>=0;i--) {
                JSONObject old=rows.getJSONObject(i); if(!old.optBoolean("_phoneFallback")) continue;
                boolean present=same&&node.equals(old.optString("_phoneNode"))&&epoch.equals(old.optString("_phoneEpoch"))&&ids.contains(old.optLong("_phoneId"));
                if(!present) {
                    if(protectPhoneOccurrence(old,now)) { old.put("_phoneRetired",true); pending=true; }
                    else { rows.remove(i); obsolete.add(old); fallbackCount--; }
                }
            }
            for(JSONObject src:source) {
                long phoneId=src.getLong("_sourceId"); JSONObject old=null;
                for(int i=0;i<rows.length();i++) { JSONObject r=rows.getJSONObject(i);
                    if(r.optBoolean("_phoneFallback")&&node.equals(r.optString("_phoneNode"))&&epoch.equals(r.optString("_phoneEpoch"))&&r.optLong("_phoneId")==phoneId) { old=r; break; }
                }
                String dismissed=src.optString("_sourceDismissedId","");
                if(old!=null&&occurrenceId(dismissed)) {
                    JSONObject live=old.optJSONObject("_active");
                    boolean activeMatch=live!=null&&dismissed.equals(live.optString("occurrenceId"));
                    boolean queuedMatch=old.has("_token")&&dismissed.equals(old.optString("_phoneOccurrenceId"));
                    if(activeMatch||queuedMatch) {
                        JSONObject before=new JSONObject(old.toString());
                        if(activeMatch) old.remove("_active");
                        if(queuedMatch) {
                            old.remove("_token"); old.remove("_nextAt"); old.remove("_phoneOccurrenceId");
                            old.remove("_snoozeAt"); old.remove("_snoozeUsed"); old.remove("_phoneFirstAt");
                            old.remove("_phoneActiveExpiresAt");
                            if(old.getJSONArray("days").length()>0&&!old.optBoolean("_phoneRetired")) {
                                if(old.has("_scheduledDate")) old.put("_lastOccurrenceDate",old.getString("_scheduledDate"));
                                try { requireAccess(c); prepare(c,old,now,true); installed.add(new JSONObject(old.toString())); }
                                catch(Exception failure) { old.remove("_token"); old.remove("_nextAt"); old.put("_error",message(failure)).put("_retryAt",now+RETRY_DELAY); pending=true; }
                            } else old.put("enabled",false);
                            obsolete.add(before);
                        } else if(old.getJSONArray("days").length()==0||old.optBoolean("_phoneRetired")) old.put("enabled",false);
                    }
                }
                if(!src.getBoolean("enabled")) {
                    if(old!=null) {
                        if(protectPhoneOccurrence(old,now)) { old.put("_phoneRetired",true); pending=true; }
                        else { rows.remove(index(rows,old.getLong("id"))); obsolete.add(old); fallbackCount--; }
                    }
                    continue;
                }
                long target=src.getLong("_sourceNext"), snooze=src.getLong("_sourceSnooze");
                if(target==0&&snooze>0&&snooze>now-RING_TIMEOUT) target=snooze;
                boolean oneShot=src.getJSONArray("days").length()==0;
                String activeToken="", sourceOccurrence=src.optString("_sourceOccurrenceId",""); long activeExpires=0;
                String consumed=old==null?"":old.optString("_phoneConsumedOccurrenceId","");
                boolean suppressPending=(occurrenceId(dismissed)&&dismissed.equals(sourceOccurrence))||
                        (occurrenceId(consumed)&&consumed.equals(sourceOccurrence));
                if(suppressPending) {
                    target=0; snooze=0; sourceOccurrence="";
                }
                if(snooze==0&&(!oneShot||target==0)&&active!=null) for(int j=0;j<active.length();j++) {
                    JSONObject session=active.optJSONObject(j);
                    if(session==null||session.optLong("id")!=phoneId) continue;
                    long started=session.optLong("startedAt",0), expires=session.optLong("expiresAt",0);
                    String token=session.optString("token");
                    if(token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                            &&started>0&&started<=now&&now-started<RING_TIMEOUT&&expires>now
                            &&expires>=started&&expires-started<=RING_TIMEOUT+5_000) {
                        String imported=session.optString("occurrenceId","");
                        if((occurrenceId(dismissed)&&dismissed.equals(imported))||
                                (occurrenceId(consumed)&&consumed.equals(imported))) break;
                        target=started; activeToken=token; activeExpires=expires;
                        sourceOccurrence=occurrenceId(imported)?imported:"";
                    }
                    break;
                }
                if(oneShot&&suppressPending&&activeToken.isEmpty()) continue;
                String signature=src.getInt("hour")+":"+src.getInt("minute")+":"+src.optString("date")+":"+src.getJSONArray("days").toString();
                if(oneShot) signature+=":"+target;
                if(old!=null&&protectPhoneOccurrence(old,now)) {
                    // The protected local occurrence represents this live phone
                    // occurrence too. Tombstone its token before a deferred retry,
                    // or dismissal would immediately replay the same occurrence.
                    JSONObject protectedSession=old.optJSONObject("_active");
                    String protectedId=protectedSession!=null?protectedSession.optString("occurrenceId",""):old.optString("_phoneOccurrenceId","");
                    if(!activeToken.isEmpty()&&occurrenceId(sourceOccurrence)&&sourceOccurrence.equals(protectedId)) {
                        boolean recorded=false;
                        for(int j=0;j<activeHistory.length();j++) {
                            JSONObject entry=activeHistory.optJSONObject(j);
                            if(entry!=null&&node.equals(entry.optString("node"))&&epoch.equals(entry.optString("epoch"))
                                    &&phoneId==entry.optLong("id")&&activeToken.equals(entry.optString("token"))) { recorded=true; break; }
                        }
                        if(!recorded) {
                            if(activeHistory.length()>=4096) throw new IllegalStateException("Phone occurrence history capacity reached");
                            activeHistory.put(new JSONObject().put("node",node).put("epoch",epoch).put("id",phoneId).put("token",activeToken));
                        }
                    }
                    // A later authoritative re-enable must not leave the live
                    // occurrence permanently retired after it is dismissed.
                    old.remove("_phoneRetired"); pending=true;
                    continue;
                }
                // An active phone one-shot may publish nextTriggerAt=0. It does not
                // identify a new occurrence and must not revive a local tombstone.
                if(old!=null&&oneShot&&target==0) continue;
                if(old!=null&&!old.has("_error")&&!activeToken.isEmpty()&&activeToken.equals(old.optString("_phoneActiveToken"))
                        &&(oneShot||(old.optBoolean("enabled")&&old.has("_token")))) continue;
                boolean seenActive=false;
                if(!activeToken.isEmpty()) for(int j=0;j<activeHistory.length();j++) {
                    JSONObject entry=activeHistory.optJSONObject(j);
                    if(entry!=null&&node.equals(entry.optString("node"))&&epoch.equals(entry.optString("epoch"))
                            &&phoneId==entry.optLong("id")&&activeToken.equals(entry.optString("token"))) { seenActive=true; break; }
                }
                if(seenActive) {
                    if(oneShot) continue;
                    // A consumed active token must not replay, but recurrence survives.
                    activeToken=""; activeExpires=0; target=src.getLong("_sourceNext");
                    sourceOccurrence=src.optString("_sourceOccurrenceId","");
                    if(occurrenceId(sourceOccurrence)&&(sourceOccurrence.equals(dismissed)||sourceOccurrence.equals(consumed))) {
                        target=0; snooze=0; sourceOccurrence="";
                    }
                }
                if(old!=null&&signature.equals(old.optString("_phoneSignature"))&&!old.has("_error")
                        &&(oneShot||(!old.optBoolean("_phoneRetired")&&old.optBoolean("enabled")&&old.has("_token")))
                        &&activeToken.isEmpty()&&sourceOccurrence.equals(old.optString("_phoneOccurrenceId",""))
                        &&!(snooze>0&&snooze!=old.optLong("_phoneSourceSnooze",0))) {
                    for(String k:new String[]{"label","sound","vibrate","vibrationPattern","snoozeEnabled","snoozeMinutes","snoozeCount"}) old.put(k,src.get(k));
                    old.put("_phoneSourceSnooze",snooze);
                    continue; // never replay a locally dismissed one-shot
                }
                if(old==null&&(fallbackCount>=350||rows.length()>=500)) { pending=true; continue; }
                long localId=old==null?s.getLong("nextId"):old.getLong("id");
                if(old==null) s.put("nextId",Math.addExact(localId,1));
                JSONObject a=new JSONObject(src.toString()); a.remove("_sourceId"); a.remove("_sourceNext"); a.remove("_sourceSnooze");
                a.remove("_sourceOccurrenceId"); a.remove("_sourceDismissedId");
                if(occurrenceId(consumed)) a.put("_phoneConsumedOccurrenceId",consumed);
                if(target>0&&occurrenceId(sourceOccurrence)) a.put("_phoneOccurrenceId",sourceOccurrence);
                a.put("id",localId).put("_phoneFallback",true).put("_phoneNode",node).put("_phoneEpoch",epoch)
                    .put("_phoneId",phoneId).put("_phoneSignature",signature).put("_phoneSourceSnooze",snooze).put("_phoneFirstAt",target);
                if(!activeToken.isEmpty()) a.put("_phoneActiveToken",activeToken).put("_phoneActiveExpiresAt",activeExpires);
                try {
                    if(oneShot&&a.isNull("date")) {
                        if(target<=0) throw new IllegalArgumentException("Phone one-shot has no known occurrence time");
                        a.put("_occurrenceDate",java.time.Instant.ofEpochMilli(target).atZone(ZoneId.systemDefault()).toLocalDate().toString());
                    }
                    requireAccess(c);
                    // Bound old plus staged new generations, not just the final rows.
                    if(registrations>=450) throw new IllegalStateException("Phone fallback scheduling capacity reached");
                    if(!activeToken.isEmpty()&&activeHistory.length()>=4096) throw new IllegalStateException("Phone occurrence history capacity reached");
                    if(snooze>0&&snooze>now-RING_TIMEOUT&&(target==0||target==snooze)) {
                        target=snooze; a.put("_snoozeAt",snooze);
                    }
                    if(target>0&&(target>now||now-target<RING_TIMEOUT)) {
                        a.put("_token",UUID.randomUUID().toString()).put("_nextAt",target)
                            .put("_scheduledDate",java.time.Instant.ofEpochMilli(target).atZone(ZoneId.systemDefault()).toLocalDate().toString());
                        stagePhoneTrigger(c,a);
                        install(c,a);
                    } else if(oneShot) throw new IllegalArgumentException("Phone one-shot occurrence is unknown or expired");
                    else prepare(c,a,now,true);
                    installed.add(a); registrations++;
                    if(!activeToken.isEmpty()) activeHistory.put(new JSONObject().put("node",node).put("epoch",epoch).put("id",phoneId).put("token",activeToken));
                } catch(Exception failure) {
                    pending=true;
                    if(old!=null) { old.put("_error",message(failure)); continue; }
                    cancel(c,a); a.remove("_token"); a.remove("_nextAt");
                    if(oneShot&&a.isNull("date")&&!a.has("_occurrenceDate")) a.put("enabled",false);
                    a.put("_error",message(failure));
                    if(!oneShot) a.put("_retryAt",now+RETRY_DELAY);
                }
                if(old==null) { rows.put(a); fallbackCount++; } else { rows.put(index(rows,localId),a); obsolete.add(old); }
            }
            s.put("_phoneNode",node).put("_phoneEpoch",epoch).put("_phoneRevision",revision).put("_phonePublished",published);
            if(pending) s.put("_phoneDesired",new JSONObject(snapshot.toString())).put("_phoneDesiredNode",node).put("_phoneRetryAt",now+RETRY_DELAY);
            else { s.remove("_phoneDesired"); s.remove("_phoneDesiredNode"); s.remove("_phoneRetryAt"); }
            JSONObject stagedState=state(c); JSONArray staged=stagedState.optJSONArray("_phoneStaged");
            if(staged==null) staged=new JSONArray();
            for(JSONObject old:obsolete) if(old.has("_token")) staged.put(new JSONObject().put("id",old.getLong("id")).put("_token",old.getString("_token")));
            if(staged.length()>0) s.put("_phoneStaged",staged);
            s.put("_wearRevision",stagedState.optLong("_wearRevision",0));
            commit(c,s);
        } catch(Exception failure) { for(JSONObject a:installed) cancel(c,a); throw failure; }
        for(JSONObject old:obsolete) cancel(c,old);
        try { cleanupPhoneStaged(c); } catch(Exception failure) { AlarmNotifications.warning(c,message(failure)); }
        refreshService(c);
    }
    // Every in-flight PendingIntent is journaled before registration. The same
    // journal also retains obsolete generations until their cancellation, so a
    // killed process cannot leak an unbounded number of platform registrations.
    private static void stagePhoneTrigger(Context c,JSONObject a) throws Exception {
        JSONObject s=state(c); JSONArray staged=s.optJSONArray("_phoneStaged");
        if(staged==null) { staged=new JSONArray(); s.put("_phoneStaged",staged); }
        staged.put(new JSONObject().put("id",a.getLong("id")).put("_token",a.getString("_token")));
        commit(c,s);
    }
    private static void cleanupPhoneStaged(Context c) throws Exception {
        JSONObject s=state(c); JSONArray staged=s.optJSONArray("_phoneStaged");
        if(staged==null) return;
        JSONArray rows=s.getJSONArray("alarms");
        for(int i=0;i<staged.length();i++) {
            JSONObject item=staged.getJSONObject(i), current=find(rows,item.getLong("id"));
            if(current==null||!item.getString("_token").equals(current.optString("_token"))) cancel(c,item);
        }
        s.remove("_phoneStaged"); commit(c,s);
    }
    private static boolean phoneSnapshotAllowed(JSONObject s,String node,String epoch,long revision,long published) {
        JSONArray retired=s.optJSONArray("_phoneRetiredEpochs");
        if(retired!=null) for(int i=0;i<retired.length();i++) {
            JSONObject r=retired.optJSONObject(i);
            if(r!=null&&node.equals(r.optString("node"))&&epoch.equals(r.optString("epoch"))) return false;
        }
        JSONObject desired=s.optJSONObject("_phoneDesired");
        if(desired!=null&&node.equals(s.optString("_phoneDesiredNode",s.optString("_phoneNode")))
                &&epoch.equals(desired.optString("epoch"))
                &&(revision<desired.optLong("revision",-1)||(revision==desired.optLong("revision",-1)
                &&published<desired.optLong("updatedAt",0)))) return false;
        return !node.equals(s.optString("_phoneNode"))||!epoch.equals(s.optString("_phoneEpoch"))
                ||(revision>=s.optLong("_phoneRevision",-1)&&(revision!=s.optLong("_phoneRevision",-1)||published>=s.optLong("_phonePublished",0)));
    }
    private static void retirePhoneEpoch(JSONObject s,String node,String epoch) throws JSONException {
        if(node.isEmpty()||epoch.isEmpty()) return;
        JSONArray retired=s.optJSONArray("_phoneRetiredEpochs");
        if(retired==null) { retired=new JSONArray(); s.put("_phoneRetiredEpochs",retired); }
        for(int i=0;i<retired.length();i++) {
            JSONObject item=retired.optJSONObject(i);
            if(item!=null&&node.equals(item.optString("node"))&&epoch.equals(item.optString("epoch"))) return;
        }
        if(retired.length()>=4096) throw new IllegalStateException("Phone epoch history capacity reached");
        retired.put(new JSONObject().put("node",node).put("epoch",epoch));
    }
    static synchronized boolean phoneSnapshotAllowed(Context c,String node,JSONObject snapshot) {
        try { return phoneSnapshotAllowed(state(c),node,snapshot.getString("epoch"),snapshot.getLong("revision"),snapshot.getLong("updatedAt")); }
        catch(Exception e) { return false; }
    }
    static synchronized boolean phoneSnapshotAccepted(Context c,String node,JSONObject snapshot) {
        try {
            JSONObject s=state(c);
            return node.equals(s.optString("_phoneNode"))&&snapshot.getString("epoch").equals(s.optString("_phoneEpoch"))
                    &&snapshot.getLong("revision")==s.optLong("_phoneRevision",-1)&&snapshot.getLong("updatedAt")==s.optLong("_phonePublished",0);
        } catch(Exception e) { return false; }
    }
    private static void retryPhoneDesired(Context c) throws Exception {
        JSONObject s=state(c), desired=s.optJSONObject("_phoneDesired");
        if(desired!=null) {
            syncPhoneFallback(c,s.optString("_phoneDesiredNode",s.optString("_phoneNode")),desired);
            if(phoneFallbackProblem(c)==null) AlarmNotifications.clearPhoneFallbackWarning(c,true);
        }
    }
    private static boolean protectPhoneOccurrence(JSONObject a,long now) {
        long due=a.optLong("_nextAt",0);
        return a.has("_active")||a.has("_snoozeAt")||(due>0&&due<=now&&now-due<RING_TIMEOUT);
    }
    static synchronized String phoneFallbackProblem(Context c) {
        try {
            String access=accessProblem(c); if(access!=null) return access;
            JSONObject state=state(c); JSONArray rows=state.getJSONArray("alarms");
            for(int i=0;i<rows.length();i++) { JSONObject a=rows.getJSONObject(i);
                if(a.optBoolean("_phoneFallback")&&a.has("_error")) return a.optString("_error");
            }
            return state.has("_phoneDesired")?"Phone fallback changes pending; retry scheduled":null;
        } catch(Exception e) { return "Phone fallback state unavailable"; }
    }
    // A snapshot and its revision must come from the same synchronized store generation.
    static synchronized JSONObject wearSnapshot(Context c) throws Exception {
        JSONObject s=state(c); JSONArray ringing=new JSONArray(), rows=s.getJSONArray("alarms"), published=alarms(c);
        long now=System.currentTimeMillis();
        for(int i=0;i<published.length();i++) {
            JSONObject row=published.getJSONObject(i), stored=find(rows,row.getLong("id"));
            String pending=stored.optString("_token","");
            String dismissed=stored.optString("_lastDismissedOccurrenceId","");
            if(occurrenceId(pending)) row.put("occurrenceId",pending);
            if(occurrenceId(dismissed)) row.put("lastDismissedOccurrenceId",dismissed);
        }
        for(int i=0;i<rows.length();i++) {
            JSONObject alarm=rows.getJSONObject(i), session=alarm.optJSONObject("_active");
            if(alarm.optBoolean("_phoneFallback")) continue;
            if(session!=null&&validSession(c,session,now)) {
                JSONObject item=new JSONObject()
                    .put("id",alarm.getLong("id")).put("token",session.getString("token"))
                    .put("startedAt",session.getLong("startedAt"))
                    .put("expiresAt",now+remainingSessionMillis(session))
                    .put("hour",alarm.getInt("hour")).put("minute",alarm.getInt("minute"))
                    .put("label",alarm.optString("label","")).put("sound",requestsSound(alarm))
                    .put("vibrate",alarm.optBoolean("vibrate")).put("canSnooze",canSnooze(alarm));
                String occurrence=session.optString("occurrenceId","");
                if(occurrenceId(occurrence)) item.put("occurrenceId",occurrence);
                ringing.put(item);
            }
        }
        return new JSONObject().put("revision",s.optLong("_wearRevision",0))
            .put("updatedAt",now).put("alarms",published).put("groups",groups(c)).put("active",ringing);
    }
    static synchronized void wearSetEnabled(Context c,long revision,long id,boolean enabled,long expiresAt) throws Exception {
        if(System.currentTimeMillis()>expiresAt) throw new IllegalArgumentException("Request expired; refresh on watch");
        JSONObject s=state(c);
        if(s.optLong("_wearRevision",0)!=revision) throw new IllegalArgumentException("Phone changed; refresh on watch");
        JSONObject a=find(s.getJSONArray("alarms"),id);
        if(a==null) throw new IllegalArgumentException("Alarm no longer exists; refresh on watch");
        if(a.optBoolean("enabled")!=enabled) saveAlarm(c,new JSONObject().put("id",id).put("enabled",enabled));
    }
    static synchronized void wearFinish(Context c,long id,String token,boolean snooze,long expiresAt) throws Exception {
        if(System.currentTimeMillis()>expiresAt||token==null)
            throw new IllegalArgumentException("Alarm occurrence expired; refresh on watch");
        JSONObject a=find(state(c).getJSONArray("alarms"),id);
        // An acknowledgement may be lost after the atomic finish commit. A retry
        // must never finish or cancel the notification for a replacement session.
        if(!snooze&&a!=null&&token.equals(a.optString("_lastDismissedSessionToken"))) return;
        if(active(c,id,token)==null)
            throw new IllegalArgumentException("Alarm occurrence expired; refresh on watch");
        finish(c,id,token,snooze,null,true);
        try { c.getSystemService(NotificationManager.class).cancel("alarm:"+id,11); }
        finally { refreshService(c); }
    }
    private static long integer(JSONObject a,String key,long def,long min,long max) throws Exception {
        if(!a.has(key)) return def;
        Object v=a.get(key);
        if(!(v instanceof Number)) throw new IllegalArgumentException(key+" must be an integer");
        long n;
        try { n=new java.math.BigDecimal(v.toString()).longValueExact(); }
        catch(ArithmeticException|NumberFormatException e) { throw new IllegalArgumentException(key+" must be an integer"); }
        if(n<min||n>max) throw new IllegalArgumentException(key+" is out of range");
        return n;
    }
    private static boolean bool(JSONObject a,String key,boolean def) throws Exception {
        if(!a.has(key)) return def;
        if(!(a.get(key) instanceof Boolean)) throw new IllegalArgumentException(key+" must be boolean");
        return a.getBoolean(key);
    }
    private static String text(JSONObject a,String key,String def,int max) throws Exception {
        if(!a.has(key)) return def;
        if(!(a.get(key) instanceof String)) throw new IllegalArgumentException(key+" must be text");
        String s=a.getString(key).trim(); if(s.length()>max) throw new IllegalArgumentException(key+" is too long");
        return s;
    }
    private static JSONObject canonical(JSONObject input, JSONObject old, long id, JSONArray groups) throws Exception {
        JSONObject x = old==null ? new JSONObject() : publicAlarm(old);
        Iterator<String> keys=input.keys(); while(keys.hasNext()) { String k=keys.next(); if(!k.startsWith("_")) x.put(k,input.get(k)); }
        JSONObject a=new JSONObject(); a.put("id",id);
        if(!x.has("hour")||!x.has("minute")) throw new IllegalArgumentException("hour and minute are required");
        a.put("hour",integer(x,"hour",0,0,23)); a.put("minute",integer(x,"minute",0,0,59));
        a.put("label",text(x,"label","",200));
        for(String key:new String[]{"enabled","sound","vibrate","snoozeEnabled"})
            a.put(key,bool(x,key,!"sound".equals(key)));
        a.put("snoozeMinutes",integer(x,"snoozeMinutes",5,1,1440));
        a.put("snoozeCount",integer(x,"snoozeCount",3,0,1000));
        String date=null;
        if(x.has("date")&&!x.isNull("date")) { date=text(x,"date",null,10); AlarmTimes.parseDate(date); }
        a.put("date",date==null?JSONObject.NULL:date);
        Set<Integer> days=new TreeSet<>();
        if(x.has("days")) {
            if(!(x.get("days") instanceof JSONArray)) throw new IllegalArgumentException("days must be an array");
            JSONArray ds=x.getJSONArray("days"); if(ds.length()>7) throw new IllegalArgumentException("At most seven weekdays");
            for(int i=0;i<ds.length();i++) { JSONObject v=new JSONObject().put("day",ds.get(i)); days.add((int)integer(v,"day",0,1,7)); }
        }
        if(date!=null&&!days.isEmpty()) throw new IllegalArgumentException("A specific date cannot repeat by weekday");
        JSONArray ds=new JSONArray(); for(int day:days) ds.put(day); a.put("days",ds);
        Object group=JSONObject.NULL;
        if(x.has("groupId")&&!x.isNull("groupId")) {
            long g=integer(x,"groupId",0,1,Long.MAX_VALUE);
            if(find(groups,g)==null) throw new IllegalArgumentException("Group does not exist");
            group=g;
        }
        a.put("groupId",group);
        String pattern=text(x,"vibrationPattern","basic",16);
        if(!Arrays.asList("basic","pulse","heartbeat").contains(pattern)) throw new IllegalArgumentException("Unknown vibrationPattern");
        a.put("vibrationPattern",pattern);
        // Never accept private input, but preserve EVERY persisted runtime field on edits.
        if(old!=null) { Iterator<String> runtime=old.keys(); while(runtime.hasNext()) {
            String key=runtime.next(); if(key.startsWith("_")) a.put(key,old.get(key));
        }}
        return a;
    }
    public static synchronized JSONObject saveAlarm(Context c, JSONObject input) throws Exception {
        return saveAlarmInternal(c,input,null,null,0,0);
    }
    /** Create a one-shot occurrence due immediately, using the normal platform alarm path. */
    static synchronized JSONObject triggerNow(Context c, JSONObject input) throws Exception {
        JSONObject s=state(c); JSONArray as=s.getJSONArray("alarms");
        if(as.length()>=500) throw new IllegalArgumentException("Alarm limit reached");
        int enabled=0;
        for(int i=0;i<as.length();i++) if(as.getJSONObject(i).optBoolean("enabled")) enabled++;
        if(enabled>=450) throw new IllegalArgumentException("At most 450 enabled alarms (scheduling recovery headroom)");
        long id=s.getLong("nextId"), now=System.currentTimeMillis();
        JSONObject a=canonical(input,null,id,s.getJSONArray("groups"));
        requireAccess(c);
        // The minute fields are for display only: the exact generation is due now,
        // not at the next occurrence of that minute (which could be tomorrow).
        a.put("_immediate",true).put("_token",UUID.randomUUID().toString()).put("_nextAt",now+1000)
            .put("_scheduledDate",java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString());
        install(c,a);
        try {
            as.put(a); s.put("nextId",Math.addExact(id,1));
            commit(c,s);
        } catch(Exception e) { cancel(c,a); throw e; }
        refreshService(c);
        return publicAlarm(a);
    }
    // The receipt and alarm share one AtomicFile commit. Receipts are never evicted:
    // forgetting an ID would make replay unsafe, including after clock changes.
    static synchronized JSONObject wearCreate(Context c, JSONObject input, String node, String requestId, long expiresAt, long deadlineElapsed) throws Exception {
        JSONObject receipt=wearCreateReceipt(c,node,requestId);
        if(receipt!=null) return receipt;
        if(System.currentTimeMillis()>expiresAt) throw new IllegalArgumentException("Request expired");
        return saveAlarmInternal(c,input,node,requestId,expiresAt,deadlineElapsed);
    }
    static synchronized JSONObject wearCreateReceipt(Context c,String node,String requestId) throws Exception {
        JSONArray receipts=state(c).optJSONArray("_wearCreates");
        if(receipts!=null) for(int i=0;i<receipts.length();i++) {
            JSONObject r=receipts.getJSONObject(i);
            if(node.equals(r.optString("node"))&&requestId.equals(r.optString("requestId"))) return new JSONObject(r.toString());
        }
        return null;
    }
    static final class WearReceiptCapacityException extends IllegalStateException {
        WearReceiptCapacityException() { super("Wear receipt capacity reached"); }
    }
    private static JSONObject saveAlarmInternal(Context c, JSONObject input, String node, String requestId, long expiresAt,long deadlineElapsed) throws Exception {
        return saveAlarmInternal(c,input,node,requestId,expiresAt,deadlineElapsed,null,-1);
    }
    private static JSONObject saveAlarmInternal(Context c, JSONObject input, String node, String requestId, long expiresAt,long deadlineElapsed,String epoch,long expectedRevision) throws Exception {
        JSONObject s=state(c); JSONArray as=s.getJSONArray("alarms");
        boolean mutation=epoch!=null;
        if(mutation&&s.optLong("_wearRevision",0)!=expectedRevision) throw new IllegalArgumentException("Phone changed");
        JSONArray receipts=null;
        if(requestId!=null) {
            String key=mutation?"_wearMutations":"_wearCreates";
            receipts=s.optJSONArray(key);
            if(receipts==null) { receipts=new JSONArray(); s.put(key,receipts); }
            if(receipts.length()>=4096) {
                if(mutation) throw new WearReceiptCapacityException();
                throw new IllegalStateException("Wear receipt limit reached");
            }
        }
        boolean edit=input.has("id"); long id=edit?integer(input,"id",0,1,Long.MAX_VALUE):s.getLong("nextId");
        JSONObject old=find(as,id); if(edit&&old==null) throw new IllegalArgumentException("Alarm does not exist");
        if(!edit&&as.length()>=500) throw new IllegalArgumentException("Alarm limit reached");
        JSONObject a=canonical(input,old,id,s.getJSONArray("groups"));
        boolean changed=old==null;
        if(old!=null) for(String key:new String[]{"hour","minute","date","days","enabled"})
            if(!String.valueOf(a.opt(key)).equals(String.valueOf(old.opt(key)))) changed=true;
        if(changed) {
            List<String> remove=new ArrayList<>(); Iterator<String> runtime=a.keys();
            while(runtime.hasNext()) { String key=runtime.next(); if(key.startsWith("_")) remove.add(key); }
            for(String key:remove) a.remove(key);
            if(old!=null&&occurrenceId(old.optString("_lastDismissedOccurrenceId","")))
                a.put("_lastDismissedOccurrenceId",old.getString("_lastDismissedOccurrenceId"));
            if(old!=null&&occurrenceId(old.optString("_lastDismissedSessionToken","")))
                a.put("_lastDismissedSessionToken",old.getString("_lastDismissedSessionToken"));
            if(a.getBoolean("enabled")) {
                int enabled=0; for(int i=0;i<as.length();i++) if(as.getJSONObject(i).optBoolean("enabled")&&as.getJSONObject(i).optLong("id")!=id) enabled++;
                if(enabled>=450) throw new IllegalArgumentException("At most 450 enabled alarms (scheduling recovery headroom)");
                requireAccess(c); prepare(c,a,System.currentTimeMillis());
            }
        }
        if(edit) as.put(index(as,id),a); else { as.put(a); s.put("nextId",id+1); }
        JSONObject receipt=null;
        try {
            if(receipts!=null) {
                if(edit!=mutation||System.currentTimeMillis()>expiresAt) throw new IllegalArgumentException("Request expired");
                receipt=new JSONObject().put("node",node).put("requestId",requestId).put("alarmId",id)
                    .put("revision",Math.addExact(s.optLong("_wearRevision",0),1));
                if(mutation) {
                    JSONObject patch=new JSONObject(input.toString()); patch.remove("id");
                    receipt.put("epoch",epoch).put("op","edit").put("baseRevision",expectedRevision)
                        .put("fingerprint",wearMutationFingerprint("edit",id,expectedRevision,patch));
                }
                receipts.put(receipt);
                // Keep idempotency receipts from crowding out future alarm state,
                // including active sessions, snoozes and ordinary phone edits.
                // Never evict receipts: a delayed/replayed create could duplicate.
                if(receipts.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256_000 ||
                        s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_000_000) {
                    if(mutation) throw new WearReceiptCapacityException();
                    throw new IllegalStateException("Wear creation receipt capacity reached");
                }
            }
            commit(c,s,receipts==null?0:expiresAt,receipts==null?0:deadlineElapsed);
        } catch(Exception e) { if(changed) cancel(c,a); throw e; }
        if(changed&&old!=null) cancel(c,old);
        refreshService(c); return receipt==null?publicAlarm(a):receipt;
    }
    /** Strict public-field allowlist; never accept runtime fields from a peer. */
    static void validateWearPatch(JSONObject patch) throws Exception {
        if(patch==null||patch.length()==0||patch.length()>13) throw new IllegalArgumentException("Empty or invalid patch");
        Iterator<String> keys=patch.keys();
        while(keys.hasNext()) {
            String k=keys.next(); Object v=patch.get(k);
            switch(k) {
                case "hour": integer(patch,k,0,0,23); break;
                case "minute": integer(patch,k,0,0,59); break;
                case "snoozeMinutes": integer(patch,k,0,1,1440); break;
                case "snoozeCount": integer(patch,k,0,0,1000); break;
                case "groupId": if(v!=JSONObject.NULL) integer(patch,k,0,1,Long.MAX_VALUE); break;
                case "enabled": case "sound": case "vibrate": case "snoozeEnabled":
                    if(!(v instanceof Boolean)) throw new IllegalArgumentException("Invalid boolean"); break;
                case "label":
                    if(!(v instanceof String)||((String)v).length()>200||!v.equals(((String)v).trim())) throw new IllegalArgumentException("Invalid label");
                    String label=(String)v;
                    for(int i=0;i<label.length();i++) {
                        char ch=label.charAt(i);
                        if(Character.isHighSurrogate(ch)) {
                            if(++i>=label.length()||!Character.isLowSurrogate(label.charAt(i))) throw new IllegalArgumentException("Invalid label text");
                        } else if(Character.isLowSurrogate(ch)) throw new IllegalArgumentException("Invalid label text");
                    } break;
                case "date":
                    if(v!=JSONObject.NULL) { if(!(v instanceof String)) throw new IllegalArgumentException("Invalid date"); AlarmTimes.parseDate((String)v); } break;
                case "vibrationPattern":
                    if(!(v instanceof String)||!Arrays.asList("basic","pulse","heartbeat").contains(v)) throw new IllegalArgumentException("Invalid pattern"); break;
                case "days":
                    if(!(v instanceof JSONArray)||((JSONArray)v).length()>7) throw new IllegalArgumentException("Invalid days");
                    Set<Long> days=new HashSet<>(); JSONArray arr=(JSONArray)v;
                    for(int i=0;i<arr.length();i++) {
                        Object d=arr.get(i); if(!(d instanceof Integer||d instanceof Long)) throw new IllegalArgumentException("Invalid day");
                        long day=((Number)d).longValue(); if(day<1||day>7||!days.add(day)) throw new IllegalArgumentException("Invalid day");
                    } break;
                default: throw new IllegalArgumentException("Unknown field");
            }
            if(Arrays.asList("hour","minute","snoozeMinutes","snoozeCount","groupId").contains(k)&&v!=JSONObject.NULL&&!(v instanceof Integer||v instanceof Long))
                throw new IllegalArgumentException("Invalid integer");
        }
    }
    static String wearMutationFingerprint(String op,long id,long revision,JSONObject patch) throws Exception {
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);
        out.writeUTF(op); out.writeLong(id); out.writeLong(revision);
        if(patch==null) out.writeInt(-1);
        else {
            List<String> keys=new ArrayList<>(); Iterator<String> it=patch.keys(); while(it.hasNext()) keys.add(it.next());
            Collections.sort(keys); out.writeInt(keys.size());
            for(String key:keys) {
                Object value=patch.get(key); out.writeUTF(key);
                if(value==JSONObject.NULL) out.writeByte(0);
                else if(value instanceof String) { out.writeByte(1); out.writeUTF((String)value); }
                else if(value instanceof Boolean) { out.writeByte(2); out.writeBoolean((Boolean)value); }
                else if(value instanceof Integer||value instanceof Long) { out.writeByte(3); out.writeLong(((Number)value).longValue()); }
                else if(value instanceof JSONArray) {
                    JSONArray arr=(JSONArray)value; out.writeByte(4); out.writeInt(arr.length());
                    for(int i=0;i<arr.length();i++) out.writeLong(((Number)arr.get(i)).longValue());
                } else throw new IllegalArgumentException("Invalid fingerprint field");
            }
        }
        out.flush(); byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        StringBuilder hex=new StringBuilder(); for(byte b:digest) hex.append(String.format(Locale.ROOT,"%02x",b&255)); return hex.toString();
    }
    static synchronized JSONObject wearMutationReceipt(Context c,String node,String epoch,String requestId) throws Exception {
        JSONArray rows=state(c).optJSONArray("_wearMutations");
        if(rows!=null) for(int i=0;i<rows.length();i++) {
            JSONObject r=rows.getJSONObject(i);
            if(node.equals(r.optString("node"))&&epoch.equals(r.optString("epoch"))&&requestId.equals(r.optString("requestId"))) return new JSONObject(r.toString());
        }
        return null;
    }
    static synchronized JSONObject wearMutation(Context c,String node,String epoch,String requestId,String op,long id,long revision,JSONObject patch,long expiresAt,long deadlineElapsed) throws Exception {
        JSONObject prior=wearMutationReceipt(c,node,epoch,requestId);
        if(prior!=null) {
            if(!op.equals(prior.optString("op"))||id!=prior.optLong("alarmId")||
                revision!=prior.optLong("baseRevision",-1)||!wearMutationFingerprint(op,id,revision,patch).equals(prior.optString("fingerprint")))
                throw new IllegalArgumentException("Conflicting request");
            return prior;
        }
        if(id<1||revision<0||System.currentTimeMillis()>expiresAt||android.os.SystemClock.elapsedRealtime()>deadlineElapsed) throw new IllegalArgumentException("Expired");
        if("edit".equals(op)) {
            validateWearPatch(patch);
            JSONObject input=new JSONObject(patch.toString()).put("id",id);
            return saveAlarmInternal(c,input,node,requestId,expiresAt,deadlineElapsed,epoch,revision);
        }
        if(!"delete".equals(op)||patch!=null) throw new IllegalArgumentException("Invalid operation");
        JSONObject s=state(c); JSONArray as=s.getJSONArray("alarms");
        if(s.optLong("_wearRevision",0)!=revision) throw new IllegalArgumentException("Phone changed");
        JSONObject old=find(as,id); if(old==null) throw new IllegalArgumentException("Alarm no longer exists");
        JSONArray receipts=s.optJSONArray("_wearMutations");
        if(receipts==null) { receipts=new JSONArray(); s.put("_wearMutations",receipts); }
        if(receipts.length()>=4096) throw new WearReceiptCapacityException();
        as.remove(index(as,id));
        JSONObject receipt=new JSONObject().put("node",node).put("epoch",epoch).put("requestId",requestId)
            .put("op","delete").put("alarmId",id).put("baseRevision",revision)
            .put("fingerprint",wearMutationFingerprint("delete",id,revision,null)).put("revision",Math.addExact(revision,1));
        receipts.put(receipt);
        if(receipts.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256_000||
            s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_000_000) throw new WearReceiptCapacityException();
        commit(c,s,expiresAt,deadlineElapsed); cancel(c,old); refreshService(c); return receipt;
    }
    public static synchronized void deleteAlarm(Context c,long id) throws Exception {
        JSONObject s=state(c); JSONArray as=s.getJSONArray("alarms"); JSONObject old=find(as,id);
        if(old==null) throw new IllegalArgumentException("Alarm does not exist");
        as.remove(index(as,id)); commit(c,s); cancel(c,old); refreshService(c);
    }
    public static synchronized JSONObject saveGroup(Context c,JSONObject input) throws Exception {
        JSONObject s=state(c); JSONArray gs=s.getJSONArray("groups");
        boolean edit=input.has("id"); long id=edit?integer(input,"id",0,1,Long.MAX_VALUE):s.getLong("nextId");
        if(edit&&find(gs,id)==null) throw new IllegalArgumentException("Group does not exist");
        if(!edit&&gs.length()>=100) throw new IllegalArgumentException("Group limit reached");
        String name=text(input,"name","",100); if(name.isEmpty()) throw new IllegalArgumentException("Group name is required");
        JSONObject g=new JSONObject().put("id",id).put("name",name);
        if(edit) gs.put(index(gs,id),g); else {gs.put(g);s.put("nextId",id+1);}
        commit(c,s); return new JSONObject(g.toString());
    }
    public static synchronized void deleteGroup(Context c,long id) throws Exception {
        JSONObject s=state(c); JSONArray gs=s.getJSONArray("groups"); int i=index(gs,id);
        if(i<0) throw new IllegalArgumentException("Group does not exist"); gs.remove(i);
        JSONArray as=s.getJSONArray("alarms");
        for(int j=0;j<as.length();j++) { JSONObject a=as.getJSONObject(j); if(a.optLong("groupId",-1)==id) a.put("groupId",JSONObject.NULL); }
        commit(c,s);
    }
    public static boolean canScheduleExactAlarms(Context c) {
        return Build.VERSION.SDK_INT<31||c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
    public static String accessProblem(Context c) {
        if(!canScheduleExactAlarms(c)) return "Allow Alarms & reminders in system Settings before enabling an alarm";
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            return "Allow notifications before enabling an alarm";
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        if(!nm.areNotificationsEnabled()) return "Notifications are disabled for Open Clock";
        AlarmNotifications.channels(c);
        NotificationChannel ch=nm.getNotificationChannel(AlarmNotifications.RING_CHANNEL);
        if(ch!=null&&ch.getImportance()<NotificationManager.IMPORTANCE_HIGH) return "Set the Ringing alarms notification channel to High importance";
        return null;
    }
    private static void requireAccess(Context c) throws Exception {
        String problem=accessProblem(c); if(problem!=null) throw new SecurityException(problem);
    }
    private static Set<Integer> days(JSONObject a) throws JSONException {
        Set<Integer> ds=new HashSet<>(); JSONArray arr=a.getJSONArray("days"); for(int i=0;i<arr.length();i++) ds.add(arr.getInt(i)); return ds;
    }
    private static long next(JSONObject a,long now) throws Exception {
        String date=a.isNull("date")?null:a.getString("date");
        // A date-less one-shot acquires a concrete occurrence on its first save;
        // restoration must never silently turn a missed one-shot into a daily alarm.
        if(date==null&&a.getJSONArray("days").length()==0&&a.has("_occurrenceDate")) date=a.getString("_occurrenceDate");
        ZoneId zone=ZoneId.systemDefault();
        long candidate=AlarmTimes.next(a.getInt("hour"),a.getInt("minute"),date,days(a),now,zone);
        if(a.getJSONArray("days").length()>0&&a.has("_lastOccurrenceDate")) {
            java.time.LocalDate day=java.time.Instant.ofEpochMilli(candidate).atZone(zone).toLocalDate();
            if(day.toString().equals(a.getString("_lastOccurrenceDate"))) {
                long end=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()-1;
                candidate=AlarmTimes.next(a.getInt("hour"),a.getInt("minute"),null,days(a),end,zone);
            }
        }
        return candidate;
    }
    static Intent triggerIntent(Context c,JSONObject a) {
        return new Intent(c,AlarmReceiver.class).setAction("com.openclock.FIRE")
            .setData(Uri.parse("openclock://trigger/"+a.optLong("id")+"/"+a.optString("_token")))
            .putExtra("id",a.optLong("id")).putExtra("token",a.optString("_token"));
    }
    private static PendingIntent trigger(Context c,JSONObject a,int flags) {
        return PendingIntent.getBroadcast(c,0,triggerIntent(c,a),flags|PendingIntent.FLAG_IMMUTABLE);
    }
    private static void prepare(Context c,JSONObject a,long now) throws Exception {
        prepare(c,a,now,false);
    }
    private static void prepare(Context c,JSONObject a,long now,boolean stagePhone) throws Exception {
        long at=a.optLong("_snoozeAt",0); if(at<=0) at=next(a,now);
        if(at<=now) at=now+1000;
        if(a.isNull("date")&&a.getJSONArray("days").length()==0&&!a.has("_occurrenceDate"))
            a.put("_occurrenceDate",java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString());
        a.put("_token",UUID.randomUUID().toString()).put("_nextAt",at);
        if(!a.has("_snoozeAt")) a.put("_scheduledDate",java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString());
        a.remove("_error");
        if(stagePhone) stagePhoneTrigger(c,a);
        install(c,a);
        a.remove("_retryAt");
    }
    private static void install(Context c,JSONObject a) throws Exception {
        long at=Math.max(a.getLong("_nextAt"),System.currentTimeMillis()+1000);
        boolean existing=trigger(c,a,PendingIntent.FLAG_NO_CREATE)!=null;
        PendingIntent fire=trigger(c,a,PendingIntent.FLAG_UPDATE_CURRENT);
        Intent show=new Intent(c,MainActivity.class).setAction("com.openclock.SHOW_ALARM").setData(Uri.parse("openclock://alarm/"+a.getLong("id")));
        PendingIntent display=PendingIntent.getActivity(c,0,show,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        try { c.getSystemService(AlarmManager.class).setAlarmClock(new AlarmManager.AlarmClockInfo(at,display),fire); }
        catch(Exception e) {
            if(!existing) fire.cancel();
            if(e instanceof SecurityException) throw e;
            throw new IllegalStateException("Android could not register the alarm",e);
        }
    }
    private static void cancel(Context c,JSONObject a) {
        if(!a.has("_token")) return;
        PendingIntent pi=trigger(c,a,PendingIntent.FLAG_NO_CREATE);
        if(pi!=null) { c.getSystemService(AlarmManager.class).cancel(pi); pi.cancel(); }
    }
    /** Permission/time/zone reconciliation: persist errors instead of claiming enabled means scheduled. */
    public static synchronized void rescheduleAll(Context c) {
        try {
            cleanupPhoneStaged(c);
            // Retry the durable phone intent before generic local-time restoration;
            // in particular an unregistered imported active token needs its replay tombstone.
            try { retryPhoneDesired(c); } catch(Exception failure) { AlarmNotifications.warning(c,message(failure)); }
            JSONObject s=state(c); JSONArray as=s.getJSONArray("alarms"); long now=System.currentTimeMillis();
            for(int i=0;i<as.length();i++) {
                JSONObject old=as.getJSONObject(i), a=new JSONObject(old.toString());
                if(a.optBoolean("_phoneFallback")&&a.has("_error")&&!a.has("_token")&&s.has("_phoneDesired")) continue;
                boolean installed=false, keepGeneration=false;
                try {
                    // A missed imported recurring session must not poison the next
                    // weekly generation with its expired phone-session deadline.
                    if(a.optBoolean("_phoneFallback")&&a.optLong("_phoneActiveExpiresAt",0)>0
                            &&a.optLong("_phoneActiveExpiresAt")<=now&&a.getJSONArray("days").length()>0) {
                        a.remove("_phoneActiveExpiresAt"); a.remove("_phoneFirstAt"); a.remove("_phoneOccurrenceId");
                    }
                    JSONObject active=a.optJSONObject("_active");
                    if(active!=null&&!validSession(c,active,now)) {
                        a.remove("_active");
                        if(a.getJSONArray("days").length()==0||a.optBoolean("_phoneRetired")) a.put("enabled",false);
                    }
                    if(!a.optBoolean("enabled")) { a.remove("_token"); a.remove("_nextAt"); }
                    else if(a.has("_active")&&a.getJSONArray("days").length()==0) { /* no next occurrence */ }
                    else {
                        requireAccess(c);
                        long due=a.optLong("_nextAt",0);
                        // A queued due trigger belongs to this occurrence, not tomorrow/next week.
                        if(a.has("_token")&&due>0&&((due<=now&&now-due<RING_TIMEOUT)
                                ||((a.optBoolean("_phoneFallback")||a.optBoolean("_immediate"))&&due>now))) {
                            install(c,a); a.remove("_error"); a.remove("_retryAt"); keepGeneration=true;
                        } else if(a.optBoolean("_phoneFallback")&&!a.has("_token")
                                &&a.optLong("_phoneFirstAt",0)>0&&a.optLong("_phoneFirstAt")>now-RING_TIMEOUT) {
                            long first=a.getLong("_phoneFirstAt");
                            a.put("_token",UUID.randomUUID().toString()).put("_nextAt",first)
                                .put("_scheduledDate",java.time.Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()).toLocalDate().toString());
                            install(c,a); installed=true; a.remove("_error"); a.remove("_retryAt");
                        } else {
                            if(a.optBoolean("_phoneFallback")&&a.getJSONArray("days").length()==0
                                    &&a.optLong("_phoneFirstAt",0)>0&&a.optLong("_phoneFirstAt")<=now-RING_TIMEOUT)
                                throw new IllegalArgumentException("Missed phone one-shot while the device was unavailable");
                            if(a.optLong("_snoozeAt",Long.MAX_VALUE)<=now-RING_TIMEOUT) {
                                a.remove("_snoozeAt"); a.remove("_snoozeUsed");
                                if(a.getJSONArray("days").length()==0) throw new IllegalArgumentException("Missed snoozed alarm while the device was unavailable");
                            }
                            long expected=a.optLong("_snoozeAt",0);
                            if(expected<=0) expected=next(a,now);
                            if(a.has("_token")&&due==expected) { install(c,a); a.remove("_error"); a.remove("_retryAt"); keepGeneration=true; }
                            else {
                                // A newly calculated local recurrence is independent of
                                // the phone generation, even if its wall time matches.
                                if(a.optBoolean("_phoneFallback")&&!a.has("_snoozeAt")) a.remove("_phoneOccurrenceId");
                                prepare(c,a,now); installed=true;
                            }
                        }
                    }
                } catch(Exception e) {
                    if(!(e instanceof SecurityException)&&!(e instanceof IllegalArgumentException)) {
                        // Quota/OS scheduling failure: retain the original valid generation.
                        old.put("_error",message(e));
                        if(old.optBoolean("enabled") && old.getJSONArray("days").length()>0)
                            old.put("_retryAt",now+RETRY_DELAY);
                        as.put(i,old);
                        commit(c,s); AlarmNotifications.warning(c,message(e)); continue;
                    }
                    a.remove("_token"); a.remove("_nextAt"); a.put("_error",message(e));
                    if(e instanceof IllegalArgumentException) { a.put("enabled",false); a.remove("_retryAt"); }
                    else if(a.getJSONArray("days").length()>0) a.put("_retryAt",now+RETRY_DELAY);
                }
                as.put(i,a);
                try { if(!a.toString().equals(old.toString())) commit(c,s); }
                catch(Exception e) { if(installed) cancel(c,a); throw e; }
                // Bound transient registrations to ONE, and never cancel an unchanged generation.
                if(!keepGeneration&&!old.optString("_token").equals(a.optString("_token"))) cancel(c,old);
                if(a.has("_error")) AlarmNotifications.warning(c,a.getString("_error"));
            }
            refreshService(c); recoverRinging(c);
        } catch(Exception e) { AlarmNotifications.warning(c,"Could not restore alarms: "+message(e)); }
    }
    static String message(Exception e) {
        if(e instanceof SecurityException||e instanceof IllegalArgumentException) return e.getMessage()==null?"Alarm access unavailable":e.getMessage();
        return "Alarm storage or scheduling failed ("+e.getClass().getSimpleName()+")";
    }
    /** Returns active state only after matching the exact persisted generation and trigger time. */
    static synchronized boolean fire(Context c,long id,String token) throws Exception {
        JSONObject s=state(c); JSONObject a=find(s.getJSONArray("alarms"),id); long now=System.currentTimeMillis();
        if(a==null||!a.optBoolean("enabled")||!token.equals(a.optString("_token"))||a.optLong("_nextAt")>now+1500) return false;
        long phoneExpiry=a.optLong("_phoneActiveExpiresAt",0);
        if(phoneExpiry>0&&phoneExpiry<=now) {
            JSONObject old=new JSONObject(a.toString());
            a.remove("_token"); a.remove("_nextAt"); a.remove("_phoneActiveExpiresAt"); a.remove("_phoneFirstAt"); a.remove("_phoneOccurrenceId");
            if(a.getJSONArray("days").length()>0&&!a.optBoolean("_phoneRetired")) {
                try { requireAccess(c); prepare(c,a,now); }
                catch(Exception e) { cancel(c,a); a.remove("_token"); a.remove("_nextAt"); a.put("_error",message(e)).put("_retryAt",now+RETRY_DELAY); }
            } else a.put("enabled",false);
            try { commit(c,s); } catch(Exception e) { cancel(c,a); throw e; }
            cancel(c,old); return false;
        }
        if(now-a.optLong("_nextAt")>=RING_TIMEOUT) { rescheduleAll(c); return false; }
        String problem=accessProblem(c);
        if(problem!=null) {
            a.remove("_token"); a.remove("_nextAt"); a.put("_error",problem); commit(c,s);
            AlarmNotifications.warning(c,problem); return false;
        }
        int count=a.optInt("_snoozeUsed",0);
        if(a.getJSONArray("days").length()>0&&!a.has("_snoozeAt"))
            a.put("_lastOccurrenceDate",a.optString("_scheduledDate",java.time.Instant.ofEpochMilli(a.optLong("_nextAt")).atZone(ZoneId.systemDefault()).toLocalDate().toString()));
        String occurrence=a.optBoolean("_phoneFallback")?a.optString("_phoneOccurrenceId",""):token;
        a.remove("_phoneOccurrenceId");
        a.remove("_immediate"); a.remove("_token"); a.remove("_nextAt"); a.remove("_snoozeAt"); a.remove("_snoozeUsed"); a.remove("_phoneFirstAt"); a.remove("_phoneActiveExpiresAt");
        JSONObject session=new JSONObject().put("token",UUID.randomUUID().toString()).put("startedAt",now)
            .put("bootCount",bootCount(c)).put("startedElapsed",android.os.SystemClock.elapsedRealtime()).put("snoozeUsed",count);
        if(occurrenceId(occurrence)) session.put("occurrenceId",occurrence);
        String boot=bootIdentity(); if(!boot.isEmpty()) session.put("bootId",boot);
        if(phoneExpiry>0) session.put("durationMillis",Math.min(RING_TIMEOUT,phoneExpiry-now));
        a.put("_active",session);
        if(a.getJSONArray("days").length()>0&&!a.optBoolean("_phoneRetired")) {
            try { requireAccess(c); prepare(c,a,now); }
            catch(Exception e) {
                cancel(c,a); a.remove("_token"); a.remove("_nextAt");
                a.put("_error",message(e)).put("_retryAt",now+RETRY_DELAY);
            }
        }
        try { commit(c,s); } catch(Exception e) { cancel(c,a); throw e; }
        if(a.has("_error")) AlarmNotifications.warning(c,a.getString("_error"));
        return true;
    }
    static synchronized JSONArray active(Context c) {
        try {
            JSONArray as=state(c).getJSONArray("alarms"), result=new JSONArray();
            long now=System.currentTimeMillis();
            for(int i=0;i<as.length();i++) {
                JSONObject a=as.getJSONObject(i);
                if(a.has("_active")&&validSession(c,a.getJSONObject("_active"),now)) result.put(a);
            }
            return result;
        } catch(JSONException e) { throw new IllegalStateException(e); }
    }
    static synchronized JSONObject active(Context c,long id,String token) {
        JSONArray as=active(c); JSONObject a=find(as,id);
        return a!=null&&token.equals(a.optJSONObject("_active").optString("token"))?a:null;
    }
    /** A mute belongs to the active token, never to the alarm's saved sound setting. */
    static boolean requestsSound(JSONObject a) {
        JSONObject session=a.optJSONObject("_active");
        return session!=null&&a.optBoolean("sound")&&!session.optBoolean("soundMuted");
    }
    /** Validate the visible occurrence before atomically muting every currently ringing session.
     * Later fires (including snoozes) create fresh _active objects and remain audible.
     */
    static synchronized boolean muteCurrent(Context c,long id,String token) throws Exception {
        JSONObject s=state(c); JSONArray as=s.getJSONArray("alarms");
        JSONObject anchor=find(as,id), session=anchor==null?null:anchor.optJSONObject("_active");
        long now=System.currentTimeMillis();
        if(token==null||session==null||!token.equals(session.optString("token"))||!validSession(c,session,now)) return false;
        boolean dirty=false;
        for(int i=0;i<as.length();i++) {
            JSONObject current=as.getJSONObject(i).optJSONObject("_active");
            if(current!=null&&validSession(c,current,now)&&!current.optBoolean("soundMuted")) {
                current.put("soundMuted",true); dirty=true;
            }
        }
        if(dirty) commit(c,s);
        refreshService(c); return true;
    }
    static boolean canSnooze(JSONObject a) {
        JSONObject active=a.optJSONObject("_active");
        return active!=null&&a.optBoolean("snoozeEnabled")&&(a.optInt("snoozeCount")==0||active.optInt("snoozeUsed")<a.optInt("snoozeCount"));
    }
    static synchronized void finish(Context c,long id,String token,boolean snooze) throws Exception {
        finish(c,id,token,snooze,null);
    }
    /** A UI-selected interval applies only to this snooze, not the saved alarm setting. */
    static synchronized void finish(Context c,long id,String token,boolean snooze,Integer minutesOverride) throws Exception {
        finish(c,id,token,snooze,minutesOverride,false);
    }
    private static void finish(Context c,long id,String token,boolean snooze,Integer minutesOverride,boolean validatedMirror) throws Exception {
        JSONObject s=state(c); JSONObject a=find(s.getJSONArray("alarms"),id);
        if(a==null||!a.has("_active")||!token.equals(a.getJSONObject("_active").optString("token"))) return;
        JSONObject old=new JSONObject(a.toString());
        JSONObject current=a.getJSONObject("_active");
        // A validated mirror session may expire in the few milliseconds between
        // wearFinish's check and this commit; it still needs its retry receipt.
        boolean explicit=!snooze&&(validatedMirror||validSession(c,current,System.currentTimeMillis()));
        boolean outboxFull=false;
        if(explicit) {
            outboxFull=!WearSync.enqueueGenericDismissal(s);
            // This receipt and the removal of this exact session commit together.
            a.put("_lastDismissedSessionToken",token);
            String occurrence=current.optString("occurrenceId","");
            if(occurrenceId(occurrence)) {
                if(a.optBoolean("_phoneFallback")) {
                    a.put("_phoneConsumedOccurrenceId",occurrence);
                    // Local-only mirror tombstone metadata; never sent to the phone.
                    JSONArray q=s.optJSONArray("_genericDismissals");
                    if(!outboxFull&&q!=null&&q.length()>0) {
                        JSONObject last=q.getJSONObject(q.length()-1);
                        last.put("fallbackNode",a.optString("_phoneNode"))
                            .put("fallbackEpoch",a.optString("_phoneEpoch"))
                            .put("fallbackId",a.optLong("_phoneId"))
                            .put("fallbackOccurrence",occurrence);
                    }
                } else a.put("_lastDismissedOccurrenceId",occurrence);
            }
        }
        if(snooze&&!canSnooze(a)) throw new IllegalArgumentException("Snooze limit reached");
        if(snooze) {
            int minutes=minutesOverride==null?a.getInt("snoozeMinutes"):minutesOverride;
            if(minutes<1||minutes>1440) throw new IllegalArgumentException("Snooze must be between 1 and 1440 minutes");
            requireAccess(c);
            a.put("_snoozeUsed",a.getJSONObject("_active").optInt("snoozeUsed")+1);
            a.put("_snoozeAt",System.currentTimeMillis()+minutes*60_000L);
            // The local snooze gets a fresh trigger token, but may still target
            // only the original phone generation, never a later phone snooze.
            String linked=current.optString("occurrenceId","");
            if(a.optBoolean("_phoneFallback")&&occurrenceId(linked)) a.put("_phoneOccurrenceId",linked);
            else if(a.optBoolean("_phoneFallback")) a.remove("_phoneOccurrenceId");
            prepare(c,a,System.currentTimeMillis());
        } else if(a.getJSONArray("days").length()==0||a.optBoolean("_phoneRetired")) a.put("enabled",false);
        a.remove("_active");
        try { commit(c,s); } catch(Exception e) { if(snooze) cancel(c,a); throw e; }
        if(outboxFull) {
            android.util.Log.w("OpenClock","Phone dismissal outbox full; dismissed locally only, phone sync failed");
            try { AlarmNotifications.warning(c,"Dismissed on watch only; phone dismissal could not be queued."); }
            catch(RuntimeException ignored) { }
        }
        if(snooze||!a.optBoolean("enabled")) cancel(c,old);
        if(a.optBoolean("_phoneFallback")) {
            try { retryPhoneDesired(c); } catch(Exception e) { AlarmNotifications.warning(c,message(e)); }
            WearSync.afterPhoneFallbackOccurrence(c);
        }
    }
    /** Mirror admission pins the identity under PhoneSync. The durable marker,
     * generic request and exact linked fallback removal share one AtomicFile write.
     * The occurrence is optional: it never gates the generic phone request. */
    static synchronized JSONObject dismissMirror(Context c,JSONObject identity) throws Exception {
        String node=identity.optString("node"), epoch=identity.optString("epoch"),
            token=identity.optString("token"), occurrence=identity.optString("occurrenceId");
        long phoneId=identity.optLong("id"), expires=identity.optLong("expiresAt"), now=System.currentTimeMillis();
        // Admission already checked liveness while the sync lock was held; do
        // not lose an admitted gesture if the session expires during the commit.
        if(node.isEmpty()||!occurrenceId(epoch)||!occurrenceId(token)||phoneId<=0||expires<=0)
            throw new IllegalArgumentException("Invalid mirror identity");
        JSONObject s=state(c), match=null; JSONArray rows=s.getJSONArray("alarms");
        JSONArray markers=s.optJSONArray("_mirrorDismissals");
        if(markers==null) { markers=new JSONArray(); s.put("_mirrorDismissals",markers); }
        for(int i=markers.length()-1;i>=0;i--) {
            JSONObject marker=markers.getJSONObject(i);
            if(marker.optLong("id")==phoneId&&token.equals(marker.optString("token")))
                return new JSONObject().put("duplicate",true);
            if(marker.optLong("expiresAt",Long.MAX_VALUE)<now-24*60*60_000L) markers.remove(i);
        }
        if(markers.length()>=4096) throw new IllegalStateException("Mirror dismissal history full");
        boolean requestQueued=addGenericDismissal(s);
        boolean ambiguous=false;
        markers.put(new JSONObject().put("id",phoneId).put("token",token).put("expiresAt",expires));
        for(int i=0;i<rows.length();i++) {
            JSONObject a=rows.getJSONObject(i), active=a.optJSONObject("_active");
            if(!occurrenceId(occurrence)||!a.optBoolean("_phoneFallback")||!node.equals(a.optString("_phoneNode"))||
                    !epoch.equals(a.optString("_phoneEpoch"))||phoneId!=a.optLong("_phoneId")) continue;
            boolean live=active!=null&&occurrence.equals(active.optString("occurrenceId"));
            boolean queued=a.has("_token")&&occurrence.equals(a.optString("_phoneOccurrenceId"));
            if(!live&&!queued) continue;
            if(match!=null) { ambiguous=true; break; }
            match=a;
        }
        if(ambiguous) match=null; // still send the generic request, but touch neither fallback
        boolean activeMatch=false; long localId=0;
        if(match!=null) {
            localId=match.getLong("id");
            JSONObject active=match.optJSONObject("_active");
            activeMatch=active!=null&&occurrence.equals(active.optString("occurrenceId"));
            boolean queuedMatch=match.has("_token")&&occurrence.equals(match.optString("_phoneOccurrenceId"));
            match.put("_phoneConsumedOccurrenceId",occurrence);
            if(activeMatch) {
                match.put("_lastDismissedSessionToken",active.optString("token"));
                match.remove("_active");
            }
            if(queuedMatch) {
                // Journal the obsolete PendingIntent with the removal. Recovery
                // cleans it even if the process dies before platform cancellation.
                JSONArray staged=s.optJSONArray("_phoneStaged");
                if(staged==null) { staged=new JSONArray(); s.put("_phoneStaged",staged); }
                staged.put(new JSONObject().put("id",localId).put("_token",match.getString("_token")));
                match.remove("_token"); match.remove("_nextAt"); match.remove("_phoneOccurrenceId");
                match.remove("_snoozeAt"); match.remove("_snoozeUsed"); match.remove("_phoneFirstAt");
                match.remove("_phoneActiveExpiresAt");
                if(match.getJSONArray("days").length()>0&&!match.optBoolean("_phoneRetired")) {
                    if(match.has("_scheduledDate")) match.put("_lastOccurrenceDate",match.getString("_scheduledDate"));
                    match.put("_retryAt",System.currentTimeMillis()+1000);
                }
            }
            if(!match.has("_active")&&!match.has("_token")&&
                    (match.getJSONArray("days").length()==0||match.optBoolean("_phoneRetired"))) match.put("enabled",false);
        }
        s.put("_wearRevision",Math.addExact(s.optLong("_wearRevision",0),1));
        // The preexisting alarm/trigger recovery and staged obsolete token survive
        // process death. No callbacks or worker dispatch under mirror/sync locks.
        journalOnly(c,s,0);
        return new JSONObject().put("duplicate",false).put("queued",requestQueued)
            .put("fallbackChanged",match!=null).put("activeChanged",activeMatch).put("localId",localId);
    }
    static synchronized Set<String> mirrorDismissalKeys(Context c) {
        Set<String> keys=new HashSet<>(); JSONArray markers=state(c).optJSONArray("_mirrorDismissals");
        if(markers!=null) for(int i=0;i<markers.length();i++) {
            JSONObject marker=markers.optJSONObject(i);
            if(marker!=null) keys.add(marker.optLong("id")+":"+marker.optString("token"));
        }
        return keys;
    }
    // A fallback dismissal can commit before its mirror preference tombstone.
    // Keep suppressing that exact phone generation even after the outbox is acked.
    static synchronized Set<String> consumedPhoneOccurrences(Context c,String node,String epoch) {
        Set<String> keys=new HashSet<>(); JSONArray rows=state(c).optJSONArray("alarms");
        if(rows!=null) for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);
            if(row==null||!row.optBoolean("_phoneFallback")||!node.equals(row.optString("_phoneNode"))||
                    !epoch.equals(row.optString("_phoneEpoch"))) continue;
            long id=row.optLong("_phoneId"); String occurrence=row.optString("_phoneConsumedOccurrenceId");
            if(id>0&&occurrenceId(occurrence)) keys.add(id+":"+occurrence);
        }
        return keys;
    }
    // Called after mirror/sync locks are released; recheck the active row before
    // canceling its per-alarm notification so a newer occurrence is not hidden.
    static synchronized void finishMirrorFallbackEffects(Context c,JSONObject result) throws Exception {
        if(!result.optBoolean("fallbackChanged")) return;
        cleanupPhoneStaged(c);
        if(result.optBoolean("activeChanged")) {
            long id=result.optLong("localId");
            JSONObject current=find(state(c).getJSONArray("alarms"),id);
            if(current==null||!current.has("_active"))
                c.getSystemService(NotificationManager.class).cancel("alarm:"+id,11);
        }
    }
    // A linked local dismissal and its delivery intent share the normal alarm commit.
    private static boolean enqueuePhoneDismissal(JSONObject s,JSONObject a,String occurrence) throws Exception {
        String node=a.optString("_phoneNode",""), epoch=a.optString("_phoneEpoch","");
        long id=a.optLong("_phoneId",0), now=System.currentTimeMillis();
        if(node.isEmpty()||!occurrenceId(epoch)||id<=0) return true; // independent legacy/local fallback
        JSONArray q=s.optJSONArray("_phoneDismissals");
        if(q==null) { q=new JSONArray(); s.put("_phoneDismissals",q); }
        boolean alreadyQueued=false;
        for(int i=q.length()-1;i>=0;i--) {
            JSONObject item=q.optJSONObject(i);
            if(item==null||item.optLong("expiresAt",0)<=now) q.remove(i);
            else if(node.equals(item.optString("node"))&&epoch.equals(item.optString("epoch"))&&
                    id==item.optLong("id")&&occurrence.equals(item.optString("occurrenceId"))) alreadyQueued=true;
        }
        if(alreadyQueued) return true;
        if(q.length()>=PHONE_DISMISSAL_LIMIT) return false;
        q.put(new JSONObject().put("requestId",UUID.randomUUID().toString()).put("node",node)
            .put("epoch",epoch).put("id",id).put("occurrenceId",occurrence)
            .put("expiresAt",Math.addExact(now,RING_TIMEOUT)).put("attempts",0));
        return true;
    }
    // Generic requests have their own journal. The old occurrence journal is never
    // dispatched by the new watch, including entries left by an earlier release.
    static boolean addGenericDismissal(JSONObject s) throws JSONException {
        long now=System.currentTimeMillis();
        JSONArray q=s.optJSONArray("_genericDismissals");
        if(q==null) { q=new JSONArray(); s.put("_genericDismissals",q); }
        for(int i=q.length()-1;i>=0;i--) if(q.optJSONObject(i)==null||q.optJSONObject(i).optLong("expiresAt")<=now) q.remove(i);
        if(q.length()>=PHONE_DISMISSAL_LIMIT) return false;
        q.put(new JSONObject().put("requestId",UUID.randomUUID().toString()).put("createdAt",now)
            .put("expiresAt",now+20_000).put("attempts",0).put("nextAt",now).put("node",""));
        return true;
    }
    static synchronized JSONArray genericDismissals(Context c) {
        JSONArray q=state(c).optJSONArray("_genericDismissals");
        try { return q==null?new JSONArray():new JSONArray(q.toString()); }
        catch(JSONException e) { throw new IllegalStateException(e); }
    }
    static long genericDismissalDelay(JSONObject item) {
        String boot=bootIdentity();
        if(!boot.isEmpty()&&boot.equals(item.optString("retryBoot"))&&item.has("retryElapsed"))
            return Math.max(0,item.optLong("retryElapsed")-android.os.SystemClock.elapsedRealtime());
        return Math.max(0,item.optLong("nextAt")-System.currentTimeMillis());
    }
    static synchronized JSONObject claimGenericDismissal(Context c,String requestId) throws Exception {
        JSONObject s=state(c); JSONArray q=s.optJSONArray("_genericDismissals");
        if(q==null) return null;
        for(int i=0;i<q.length();i++) {
            JSONObject item=q.getJSONObject(i); long now=System.currentTimeMillis();
            if(!requestId.equals(item.optString("requestId"))||item.optLong("expiresAt")<=now||
                    item.optInt("attempts")>=4||genericDismissalDelay(item)>0) continue;
            // Reserve enough time to cover the one-second discovery timeout even
            // if the process dies immediately after sending.
            item.put("attempts",item.getInt("attempts")+1).put("nextAt",now+3000)
                .put("retryBoot",bootIdentity()).put("attemptStartedAt",now)
                .put("attemptStartedElapsed",android.os.SystemClock.elapsedRealtime())
                .put("retryElapsed",android.os.SystemClock.elapsedRealtime()+3000);
            journalOnly(c,s,item.getLong("expiresAt")); return new JSONObject(item.toString());
        }
        return null;
    }
    static synchronized void finishGenericAttempt(Context c,String req,int attempt,boolean sent) throws Exception {
        JSONObject s=state(c); JSONArray q=s.optJSONArray("_genericDismissals");
        if(q==null) return;
        for(int i=0;i<q.length();i++) {
            JSONObject item=q.getJSONObject(i);
            if(req.equals(item.optString("requestId"))&&attempt==item.optInt("attempts")) {
                long baseWall=sent?System.currentTimeMillis():item.getLong("attemptStartedAt");
                long baseElapsed=sent?android.os.SystemClock.elapsedRealtime():item.getLong("attemptStartedElapsed");
                item.put("nextAt",baseWall+2000).put("retryBoot",bootIdentity())
                    .put("retryElapsed",baseElapsed+2000);
                journalOnly(c,s,0); return;
            }
        }
    }
    static synchronized boolean bindGenericNode(Context c,String req,String node) throws Exception {
        JSONObject s=state(c); JSONArray q=s.optJSONArray("_genericDismissals");
        if(q==null) return false;
        for(int i=0;i<q.length();i++) {
            JSONObject item=q.getJSONObject(i);
            if(!req.equals(item.optString("requestId"))) continue;
            if(item.optLong("expiresAt")<=System.currentTimeMillis()) return false;
            String previous=item.optString("node");
            if(!previous.isEmpty()) return previous.equals(node);
            item.put("node",node); journalOnly(c,s,item.getLong("expiresAt")); return true;
        }
        return false;
    }
    static synchronized void acknowledgeGenericDismissal(Context c,String node,String req,long created,long expires) throws Exception {
        JSONObject s=state(c); JSONArray q=s.optJSONArray("_genericDismissals");
        if(q==null) return;
        for(int i=0;i<q.length();i++) {
            JSONObject item=q.getJSONObject(i);
            if(req.equals(item.optString("requestId"))&&node.equals(item.optString("node"))&&
                    created==item.optLong("createdAt")&&expires==item.optLong("expiresAt")) {
                q.remove(i); journalOnly(c,s,0); return;
            }
        }
    }
    /** Repair stale ringing notifications after a committed or replayed remote dismissal. */
    static synchronized void reconcileDismissedNotifications(Context c) throws Exception {
        JSONArray rows=state(c).getJSONArray("alarms");
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications()) {
            String tag=n.getTag();
            if(n.getId()!=11||tag==null||!tag.matches("alarm:[1-9][0-9]{0,18}")) continue;
            long id;
            try { id=Long.parseLong(tag.substring(6)); } catch(NumberFormatException invalid) { continue; }
            JSONObject current=find(rows,id);
            if(current==null||!current.has("_active")) manager.cancel(tag,11);
        }
    }
    /** Atomically record the receipt with the active-session removals. */
    static synchronized boolean wearDismissAll(Context c,String node,String req,long created,long expires) throws Exception {
        long now=System.currentTimeMillis();
        if(expires<=now) return false;
        JSONObject s=state(c); JSONArray receipts=s.optJSONArray("_genericDismissReceipts");
        if(receipts==null) { receipts=new JSONArray(); s.put("_genericDismissReceipts",receipts); }
        for(int i=receipts.length()-1;i>=0;i--) {
            JSONObject r=receipts.getJSONObject(i);
            if(node.equals(r.optString("node"))&&req.equals(r.optString("requestId")))
                return created==r.optLong("createdAt")&&expires==r.optLong("expiresAt");
        }
        // Never evict receipts: a backward wall-clock correction must not make an
        // old, formerly expired request valid again against a later alarm.
        if(receipts.length()>=4096) return false;
        JSONArray alarms=s.getJSONArray("alarms"); ArrayList<Long> removed=new ArrayList<>();
        for(int i=0;i<alarms.length();i++) {
            JSONObject a=alarms.getJSONObject(i), session=a.optJSONObject("_active");
            if(session==null||!validSession(c,session,now)) continue;
            removed.add(a.getLong("id"));
            a.put("_lastDismissedSessionToken",session.optString("token"));
            String occurrence=session.optString("occurrenceId");
            if(occurrenceId(occurrence)) a.put("_lastDismissedOccurrenceId",occurrence);
            a.remove("_active");
            if(a.getJSONArray("days").length()==0||a.optBoolean("_phoneRetired")) a.put("enabled",false);
        }
        receipts.put(new JSONObject().put("node",node).put("requestId",req).put("createdAt",created).put("expiresAt",expires));
        s.put("_wearRevision",Math.addExact(s.optLong("_wearRevision",0),1));
        journalOnly(c,s,expires);
        new Thread(()->{
            synchronized(AlarmStore.class) { /* wait for commit lock */ }
            for(long id:removed) try {
                synchronized(AlarmStore.class) {
                    JSONObject current=find(state(c).getJSONArray("alarms"),id);
                    if(current==null||!current.has("_active")) c.getSystemService(NotificationManager.class).cancel("alarm:"+id,11);
                }
            } catch(Exception failure) { android.util.Log.w("OpenClock","Could not clear dismissed notification",failure); }
            try { refreshService(c); } catch(RuntimeException ignored) { }
            try { WearSync.afterCommit(c); } catch(RuntimeException ignored) { }
        },"dismiss-all-notify").start();
        return true;
    }
    // These outbox operations change no registrations or active sessions. Do not
    // call commit(), which arms Android recovery via Binder while holding the lock.
    private static void journalOnly(Context c,JSONObject s,long expiresAt) throws Exception {
        byte[] bytes=s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length>2_000_000) throw new IllegalStateException("Alarm storage limit reached");
        android.util.AtomicFile f=file(c); java.io.FileOutputStream stream=null;
        try {
            stream=f.startWrite(); stream.write(bytes); stream.flush(); stream.getFD().sync();
            if(expiresAt>0&&System.currentTimeMillis()>expiresAt) throw new IllegalArgumentException("Request expired");
            f.finishWrite(stream);
        } catch(Exception e) { if(stream!=null) f.failWrite(stream); throw e; }
    }
    static synchronized JSONArray pendingPhoneDismissals(Context c) {
        try {
            JSONArray q=state(c).optJSONArray("_phoneDismissals");
            return q==null?new JSONArray():new JSONArray(q.toString());
        } catch(Exception e) { throw new IllegalStateException("Could not read dismissal outbox",e); }
    }
    static synchronized JSONObject claimPhoneDismissal(Context c,String requestId) {
        try {
            JSONObject s=state(c); JSONArray q=s.optJSONArray("_phoneDismissals");
            if(q==null||requestId==null) return null;
            for(int i=0;i<q.length();i++) {
                JSONObject item=q.getJSONObject(i);
                if(!requestId.equals(item.optString("requestId"))) continue;
                long expiry=item.optLong("expiresAt",0); int attempts=Math.max(0,item.optInt("attempts",0));
                if(expiry<=System.currentTimeMillis()) return null;
                item.put("attempts",attempts==Integer.MAX_VALUE?attempts:attempts+1); journalOnly(c,s,expiry);
                return new JSONObject(item.toString());
            }
            return null;
        } catch(Exception e) { throw new IllegalStateException("Could not claim dismissal outbox item",e); }
    }
    static synchronized void removePhoneDismissal(Context c,String requestId) {
        try {
            JSONObject s=state(c); JSONArray q=s.optJSONArray("_phoneDismissals");
            if(q==null||requestId==null) return;
            for(int i=0;i<q.length();i++) if(requestId.equals(q.getJSONObject(i).optString("requestId"))) {
                q.remove(i); journalOnly(c,s,0); return;
            }
        } catch(Exception e) { throw new IllegalStateException("Could not remove dismissal outbox item",e); }
    }
    /** Exact-occurrence command; never matches a prior session by alarm ID or time. */
    static synchronized boolean wearDismissOccurrence(Context c,long phoneId,String occurrence,long expiresAt) {
        if(phoneId<=0||!occurrenceId(occurrence)||expiresAt<=System.currentTimeMillis()) return false;
        try {
            JSONObject s=state(c), a=find(s.getJSONArray("alarms"),phoneId);
            if(a==null||a.optBoolean("_phoneFallback")) return false;
            if(occurrence.equals(a.optString("_lastDismissedOccurrenceId"))) return true;
            long now=System.currentTimeMillis(); JSONObject session=a.optJSONObject("_active");
            String boot=bootIdentity();
            boolean live=session!=null&&!boot.isEmpty()&&boot.equals(session.optString("bootId"))
                &&remainingSessionMillis(session)>0&&occurrence.equals(session.optString("occurrenceId"));
            long due=a.optLong("_nextAt",0);
            boolean queued=a.optBoolean("enabled")&&occurrence.equals(a.optString("_token"))
                &&due>0&&due<=now&&now-due<RING_TIMEOUT;
            if(!live&&!queued) return false;
            JSONObject cancelled=queued?new JSONObject(a.toString()):null;
            if(live) a.remove("_active");
            if(queued) {
                a.remove("_token"); a.remove("_nextAt"); a.remove("_snoozeAt"); a.remove("_snoozeUsed");
                a.remove("_immediate");
                if(a.getJSONArray("days").length()>0) {
                    if(a.has("_scheduledDate")) a.put("_lastOccurrenceDate",a.getString("_scheduledDate"));
                    // The old generation's recovery wakeup repairs this durably.
                    a.put("_retryAt",now+1000);
                }
            }
            if(a.getJSONArray("days").length()==0&&!a.has("_active")) a.put("enabled",false);
            a.put("_lastDismissedOccurrenceId",occurrence);
            s.put("_wearRevision",Math.addExact(s.optLong("_wearRevision",0),1));
            journalOnly(c,s,expiresAt);
            // Run Binder and publisher callbacks only after leaving the store lock.
            new Thread(() -> {
                synchronized(AlarmStore.class) { /* wait for durable mutation */ }
                try { if(cancelled!=null) cancel(c,cancelled); } catch(RuntimeException ignored) { }
                // Notification tags are per alarm, not per session. Check under
                // the store lock so a newly fired session cannot be canceled.
                if(live) try {
                    synchronized(AlarmStore.class) {
                        JSONObject current=find(state(c).getJSONArray("alarms"),phoneId);
                        if(current==null||!current.has("_active"))
                            c.getSystemService(NotificationManager.class).cancel("alarm:"+phoneId,11);
                    }
                } catch(Exception failure) { android.util.Log.w("OpenClock","Could not clear dismissed alarm notification",failure); }
                try { refreshService(c); } catch(RuntimeException ignored) { }
                try { WearSync.afterCommit(c); } catch(RuntimeException ignored) { }
            },"alarm-dismiss-notify").start();
            return true;
        } catch(Exception e) { throw new IllegalStateException("Could not dismiss phone occurrence",e); }
    }
    private static int bootCount(Context c) {
        return android.provider.Settings.Global.getInt(c.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);
    }
    static long remainingSessionMillis(JSONObject session) {
        long age=session.has("startedElapsed")?android.os.SystemClock.elapsedRealtime()-session.optLong("startedElapsed")
            :System.currentTimeMillis()-session.optLong("startedAt");
        long duration=Math.max(0,Math.min(RING_TIMEOUT,session.optLong("durationMillis",RING_TIMEOUT)));
        return age<0?0:Math.max(0,duration-age);
    }
    static boolean validSession(Context c,JSONObject session,long now) {
        return session.optInt("bootCount",-2)==bootCount(c)&&remainingSessionMillis(session)>0;
    }
    private static PendingIntent recovery(Context c,int flags) {
        return PendingIntent.getBroadcast(c,0,new Intent(c,AlarmReceiver.class).setAction("com.openclock.RECOVER")
            .setData(Uri.parse("openclock://recovery")),flags|PendingIntent.FLAG_IMMUTABLE);
    }
    private static void armRecovery(Context c,JSONObject s) throws Exception {
        long now=System.currentTimeMillis(), at=Long.MAX_VALUE;
        if(s.has("_phoneStaged")&&!canScheduleExactAlarms(c))
            throw new SecurityException("Allow Alarms & reminders to recover phone fallback registrations");
        if(s.has("_phoneStaged")) at=now+30_000;
        long phoneRetry=s.optLong("_phoneRetryAt",0);
        if(s.has("_phoneDesired")&&phoneRetry>0)
            at=Math.min(at,phoneRetry<=now?now+RETRY_DELAY:Math.max(now+1000,phoneRetry));
        JSONArray as=s.getJSONArray("alarms");
        for(int i=0;i<as.length();i++) {
            JSONObject a=as.getJSONObject(i), session=a.optJSONObject("_active");
            if(session!=null&&validSession(c,session,now)) at=Math.min(at,now+30_000);
            if(a.optBoolean("enabled")&&a.has("_token")&&a.optLong("_nextAt")>0)
                at=Math.min(at,Math.max(now+30_000,a.optLong("_nextAt")+30_000));
            long retryAt=a.optLong("_retryAt",0);
            if(a.optBoolean("enabled")&&retryAt>0) {
                // A failed retry commit leaves the old deadline overdue. Back off
                // even then: persistence failure must not create a one-second loop.
                at=Math.min(at,retryAt<=now?now+RETRY_DELAY:Math.max(now+1000,retryAt));
            }
        }
        // Never block disabling/deleting/dismissing when exact access was revoked.
        if(at==Long.MAX_VALUE||!canScheduleExactAlarms(c)) {
            PendingIntent pi=recovery(c,PendingIntent.FLAG_NO_CREATE);
            if(pi!=null) { c.getSystemService(AlarmManager.class).cancel(pi); pi.cancel(); }
            return;
        }
        PendingIntent show=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        c.getSystemService(AlarmManager.class).setAlarmClock(new AlarmManager.AlarmClockInfo(at,show),recovery(c,PendingIntent.FLAG_UPDATE_CURRENT));
    }
    static synchronized void expireSessions(Context c) throws Exception {
        JSONObject s=state(c); JSONArray as=s.getJSONArray("alarms"); boolean dirty=false;
        long now=System.currentTimeMillis();
        for(int i=0;i<as.length();i++) {
            JSONObject a=as.getJSONObject(i), session=a.optJSONObject("_active");
            if(session!=null&&!validSession(c,session,now)) {
                a.remove("_active"); dirty=true;
                if(a.getJSONArray("days").length()==0||a.optBoolean("_phoneRetired")) a.put("enabled",false);
            }
        }
        if(dirty) {
            commit(c,s);
            for(int i=0;i<as.length();i++) if(!as.getJSONObject(i).has("_active"))
                c.getSystemService(NotificationManager.class).cancel("alarm:"+as.getJSONObject(i).optLong("id"),11);
            WearSync.afterPhoneFallbackOccurrence(c);
            try { retryPhoneDesired(c); } catch(Exception e) { AlarmNotifications.warning(c,message(e)); }
        }
    }
    static synchronized void recoverPending(Context c) {
        try {
            cleanupPhoneStaged(c);
            // Do not churn all future generations every 30 seconds. Only consume
            // due persisted generations; the normal restore path handles time changes.
            JSONObject journal=state(c); JSONArray as=journal.getJSONArray("alarms"); long now=System.currentTimeMillis();
            boolean retry=false;
            for(int i=0;i<as.length();i++) {
                JSONObject a=as.getJSONObject(i);
                if(a.optBoolean("enabled")&&a.has("_token")&&a.optLong("_nextAt")<=now
                        &&fire(c,a.optLong("id"),a.optString("_token")))
                    AlarmPresentation.local(c,a.optLong("id"));
                if(a.optBoolean("enabled")&&a.optLong("_retryAt",Long.MAX_VALUE)<=now)
                    retry=true;
            }
            if(journal.has("_phoneDesired")&&journal.optLong("_phoneRetryAt",Long.MAX_VALUE)<=now)
                retryPhoneDesired(c);
            // A weekly alarm whose next registration failed must not depend on the
            // user opening the app. Retry no more often than RETRY_DELAY on failure.
            if(retry) rescheduleAll(c);
            recoverRinging(c);
        } catch(Exception e) { AlarmNotifications.warning(c,message(e)); }
    }
    static synchronized void recoverRinging(Context c) {
        try {
            expireSessions(c);
            try { armRecovery(c,state(c)); } catch(Exception unavailable) { AlarmNotifications.warning(c,message(unavailable)); }
            JSONArray sessions=active(c); if(sessions.length()==0) return;
            try { c.startForegroundService(new Intent(c,RingingService.class)); }
            catch(RuntimeException blocked) {
                for(int i=0;i<sessions.length();i++) {
                    JSONObject a=sessions.getJSONObject(i);
                    AlarmNotifications.audibleFallback(c,a);
                }
                AlarmNotifications.warning(c,"Android blocked continuous ringing. Open the alarm notification for controls.");
            }
        } catch(Exception e) { AlarmNotifications.warning(c,message(e)); }
    }
    static void refreshService(Context c) {
        // Only a running service receives this private, in-process invalidation.
        Intent i=new Intent("com.openclock.REFRESH").setPackage(c.getPackageName()); c.sendBroadcast(i);
    }
    private AlarmStore() {}
}
