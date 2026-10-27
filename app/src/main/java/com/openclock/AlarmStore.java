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
    private static void commit(Context c, JSONObject state) throws Exception {
        // Install recovery BEFORE persistence: a committed active session must have a
        // platform-owned wakeup even if the process dies before service startup.
        JSONObject recoveryState=new JSONObject(state.toString());
        JSONArray union=recoveryState.getJSONArray("alarms"), previous=AlarmStore.state(c).getJSONArray("alarms");
        for(int i=0;i<previous.length();i++) union.put(previous.getJSONObject(i));
        try { armRecovery(c,recoveryState); }
        catch(Exception unavailable) {
            boolean needsRecovery=false;
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
        android.util.AtomicFile f=file(c); java.io.FileOutputStream stream=null;
        try {
            stream=f.startWrite(); stream.write(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stream.flush(); stream.getFD().sync(); f.finishWrite(stream);
        } catch(Exception e) { if(stream!=null) f.failWrite(stream); throw new java.io.IOException("Could not persist alarm changes",e); }
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
        out.put("schedulingError", a.opt("_error") == null ? JSONObject.NULL : a.opt("_error"));
        return out;
    }
    public static synchronized JSONArray alarms(Context c) {
        try {
            JSONArray source=state(c).getJSONArray("alarms"), result=new JSONArray();
            String problem=accessProblem(c);
            for(int i=0;i<source.length();i++) {
                JSONObject a=publicAlarm(source.getJSONObject(i));
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
        for(String key:new String[]{"enabled","sound","vibrate","snoozeEnabled"}) a.put(key,bool(x,key,true));
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
        JSONObject s=state(c); JSONArray as=s.getJSONArray("alarms");
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
            if(a.getBoolean("enabled")) {
                int enabled=0; for(int i=0;i<as.length();i++) if(as.getJSONObject(i).optBoolean("enabled")&&as.getJSONObject(i).optLong("id")!=id) enabled++;
                if(enabled>=450) throw new IllegalArgumentException("At most 450 enabled alarms (scheduling recovery headroom)");
                requireAccess(c); prepare(c,a,System.currentTimeMillis());
            }
        }
        if(edit) as.put(index(as,id),a); else { as.put(a); s.put("nextId",id+1); }
        try { commit(c,s); } catch(Exception e) { if(changed) cancel(c,a); throw e; }
        if(changed&&old!=null) cancel(c,old);
        refreshService(c); return publicAlarm(a);
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
        long at=a.optLong("_snoozeAt",0); if(at<=0) at=next(a,now);
        if(at<=now) at=now+1000;
        if(a.isNull("date")&&a.getJSONArray("days").length()==0&&!a.has("_occurrenceDate"))
            a.put("_occurrenceDate",java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString());
        a.put("_token",UUID.randomUUID().toString()).put("_nextAt",at);
        if(!a.has("_snoozeAt")) a.put("_scheduledDate",java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString());
        a.remove("_error");
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
            JSONObject s=state(c); JSONArray as=s.getJSONArray("alarms"); long now=System.currentTimeMillis();
            for(int i=0;i<as.length();i++) {
                JSONObject old=as.getJSONObject(i), a=new JSONObject(old.toString());
                boolean installed=false, keepGeneration=false;
                try {
                    JSONObject active=a.optJSONObject("_active");
                    if(active!=null&&!validSession(c,active,now)) {
                        a.remove("_active");
                        if(a.getJSONArray("days").length()==0) a.put("enabled",false);
                    }
                    if(!a.optBoolean("enabled")) { a.remove("_token"); a.remove("_nextAt"); }
                    else if(a.has("_active")&&a.getJSONArray("days").length()==0) { /* no next occurrence */ }
                    else {
                        requireAccess(c);
                        long due=a.optLong("_nextAt",0);
                        // A queued due trigger belongs to this occurrence, not tomorrow/next week.
                        if(a.has("_token")&&due>0&&due<=now&&now-due<RING_TIMEOUT) {
                            install(c,a); a.remove("_error"); keepGeneration=true;
                        } else {
                            if(a.optLong("_snoozeAt",Long.MAX_VALUE)<=now-RING_TIMEOUT) {
                                a.remove("_snoozeAt"); a.remove("_snoozeUsed");
                                if(a.getJSONArray("days").length()==0) throw new IllegalArgumentException("Missed snoozed alarm while the device was unavailable");
                            }
                            long expected=a.optLong("_snoozeAt",0);
                            if(expected<=0) expected=next(a,now);
                            if(a.has("_token")&&due==expected) { install(c,a); a.remove("_error"); keepGeneration=true; }
                            else { prepare(c,a,now); installed=true; }
                        }
                    }
                } catch(Exception e) {
                    if(!(e instanceof SecurityException)&&!(e instanceof IllegalArgumentException)) {
                        // Quota/OS scheduling failure: retain the original valid generation.
                        old.put("_error",message(e));
                        if(!old.has("_token") && old.optBoolean("enabled") && old.getJSONArray("days").length()>0)
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
        if(now-a.optLong("_nextAt")>=RING_TIMEOUT) { rescheduleAll(c); return false; }
        String problem=accessProblem(c);
        if(problem!=null) {
            a.remove("_token"); a.remove("_nextAt"); a.put("_error",problem); commit(c,s);
            AlarmNotifications.warning(c,problem); return false;
        }
        int count=a.optInt("_snoozeUsed",0);
        if(a.getJSONArray("days").length()>0&&!a.has("_snoozeAt"))
            a.put("_lastOccurrenceDate",a.optString("_scheduledDate",java.time.Instant.ofEpochMilli(a.optLong("_nextAt")).atZone(ZoneId.systemDefault()).toLocalDate().toString()));
        a.remove("_token"); a.remove("_nextAt"); a.remove("_snoozeAt"); a.remove("_snoozeUsed");
        a.put("_active",new JSONObject().put("token",UUID.randomUUID().toString()).put("startedAt",now)
            .put("bootCount",bootCount(c)).put("startedElapsed",android.os.SystemClock.elapsedRealtime()).put("snoozeUsed",count));
        if(a.getJSONArray("days").length()>0) {
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
    static boolean canSnooze(JSONObject a) {
        JSONObject active=a.optJSONObject("_active");
        return active!=null&&a.optBoolean("snoozeEnabled")&&(a.optInt("snoozeCount")==0||active.optInt("snoozeUsed")<a.optInt("snoozeCount"));
    }
    static synchronized void finish(Context c,long id,String token,boolean snooze) throws Exception {
        JSONObject s=state(c); JSONObject a=find(s.getJSONArray("alarms"),id);
        if(a==null||!a.has("_active")||!token.equals(a.getJSONObject("_active").optString("token"))) return;
        JSONObject old=new JSONObject(a.toString());
        if(snooze&&!canSnooze(a)) throw new IllegalArgumentException("Snooze limit reached");
        if(snooze) {
            requireAccess(c);
            a.put("_snoozeUsed",a.getJSONObject("_active").optInt("snoozeUsed")+1);
            a.put("_snoozeAt",System.currentTimeMillis()+a.getLong("snoozeMinutes")*60_000L);
            prepare(c,a,System.currentTimeMillis());
        } else if(a.getJSONArray("days").length()==0) a.put("enabled",false);
        a.remove("_active");
        try { commit(c,s); } catch(Exception e) { if(snooze) cancel(c,a); throw e; }
        if(snooze||!a.optBoolean("enabled")) cancel(c,old);
    }
    private static int bootCount(Context c) {
        return android.provider.Settings.Global.getInt(c.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);
    }
    static long remainingSessionMillis(JSONObject session) {
        long age=session.has("startedElapsed")?android.os.SystemClock.elapsedRealtime()-session.optLong("startedElapsed")
            :System.currentTimeMillis()-session.optLong("startedAt");
        return age<0?0:Math.max(0,RING_TIMEOUT-age);
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
        JSONArray as=s.getJSONArray("alarms");
        for(int i=0;i<as.length();i++) {
            JSONObject a=as.getJSONObject(i), session=a.optJSONObject("_active");
            if(session!=null&&validSession(c,session,now)) at=Math.min(at,now+30_000);
            if(a.optBoolean("enabled")&&a.has("_token")&&a.optLong("_nextAt")>0)
                at=Math.min(at,Math.max(now+30_000,a.optLong("_nextAt")+30_000));
            long retryAt=a.optLong("_retryAt",0);
            if(a.optBoolean("enabled")&&!a.has("_token")&&retryAt>0) {
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
                if(a.getJSONArray("days").length()==0) a.put("enabled",false);
            }
        }
        if(dirty) {
            commit(c,s);
            for(int i=0;i<as.length();i++) if(!as.getJSONObject(i).has("_active"))
                c.getSystemService(NotificationManager.class).cancel("alarm:"+as.getJSONObject(i).optLong("id"),11);
        }
    }
    static synchronized void recoverPending(Context c) {
        try {
            // Do not churn all future generations every 30 seconds. Only consume
            // due persisted generations; the normal restore path handles time changes.
            JSONArray as=state(c).getJSONArray("alarms"); long now=System.currentTimeMillis();
            boolean retry=false;
            for(int i=0;i<as.length();i++) {
                JSONObject a=as.getJSONObject(i);
                if(a.optBoolean("enabled")&&a.has("_token")&&a.optLong("_nextAt")<=now)
                    fire(c,a.optLong("id"),a.optString("_token"));
                if(a.optBoolean("enabled")&&!a.has("_token")&&a.optLong("_retryAt",Long.MAX_VALUE)<=now)
                    retry=true;
            }
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
                    c.getSystemService(NotificationManager.class).notify("alarm:"+a.optLong("id"),11,AlarmNotifications.ringing(c,a));
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
