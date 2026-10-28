package com.openclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.UserManager;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** All authorization and state changes, including dispatch, share LOCK. */
final class AccessPolicy {
    static final Object LOCK = new Object();
    private static boolean failed;
    private static final String CHANNEL = "access_requests";
    private static final int LIMIT = 100;
    // Monotonic process-local throttle resists wall-clock changes while running.
    private static final java.util.Map<String,Long> recentNotices=new java.util.HashMap<>();
    static final class Identity {
        final String pkg, cert; final int uid; final long installed;
        Identity(String p, String c, int u, long i) { pkg=p; cert=c; uid=u; installed=i; }
        boolean matches(JSONObject r) { return pkg.equals(r.optString("pkg")) && cert.equals(r.optString("cert")) && uid==r.optInt("uid",-1) && installed==r.optLong("installed",-1); }
        boolean matchesIdentity(Identity other) { return uid==other.uid && installed==other.installed && pkg.equals(other.pkg) && cert.equals(other.cert); }
    }
    static Identity identify(Context c, int uid) throws Exception {
        PackageManager pm=c.getPackageManager();
        String[] names=pm.getPackagesForUid(uid);
        if(names==null || names.length!=1 || names[0]==null || names[0].length()>255) throw new SecurityException();
        int flags=Build.VERSION.SDK_INT>=28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo pi=pm.getPackageInfo(names[0],flags);
        // Even a single visible sharedUserId member is ambiguous. QUERY_ALL_PACKAGES is
        // declared so getPackagesForUid cannot hide another installed member.
        if(pi.applicationInfo==null || pi.applicationInfo.uid!=uid || pi.sharedUserId!=null || pi.firstInstallTime<=0) throw new SecurityException();
        Signature[] sigs=Build.VERSION.SDK_INT>=28 ? (pi.signingInfo==null?null:pi.signingInfo.getApkContentsSigners()) : pi.signatures;
        if(sigs==null || sigs.length!=1 || sigs[0]==null) throw new SecurityException();
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
        StringBuilder h=new StringBuilder(); for(byte b:digest) h.append(String.format(java.util.Locale.US,"%02x",b & 255));
        return new Identity(names[0],h.toString(),uid,pi.firstInstallTime);
    }
    private static Context storage(Context c) {
        if(!c.getSystemService(UserManager.class).isUserUnlocked()) throw new IllegalStateException("User locked");
        Context app=c.getApplicationContext();
        if(app.isDeviceProtectedStorage()) throw new IllegalStateException("Credential storage unavailable");
        return app;
    }
    private static AtomicFile file(Context c) { return new AtomicFile(new File(storage(c).getNoBackupFilesDir(),"access-v1.json")); }
    static JSONObject load(Context c) throws Exception {
        if(failed) throw new IllegalStateException("Access storage failed");
        AtomicFile f=file(c);
        if(!f.getBaseFile().exists() && !new File(f.getBaseFile()+".bak").exists()) return new JSONObject().put("version",1).put("records",new JSONArray()).put("lastNotice",0);
        byte[] bytes=f.readFully(); if(bytes.length>100_000) throw new IllegalStateException();
        JSONObject s=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(s.getInt("version")!=1 || s.getJSONArray("records").length()>LIMIT || s.getLong("lastNotice")<0) throw new IllegalStateException();
        JSONArray a=s.getJSONArray("records"); java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(int i=0;i<a.length();i++) {
            JSONObject r=a.getJSONObject(i); String pkg=r.getString("pkg"), cert=r.getString("cert"), state=r.getString("state"), token=r.getString("token");
            if(pkg.length()==0 || pkg.length()>255 || !seen.add(pkg) || !cert.matches("[0-9a-f]{64}") || r.getInt("uid")<0 || r.getLong("installed")<=0 || !token.matches("[0-9a-f-]{36}") || r.getLong("notice")<0 || !(state.equals("pending") || state.equals("allow") || state.equals("block") || state.equals("declined"))) throw new IllegalStateException();
        }
        return s;
    }
    static void save(Context c, JSONObject state) throws Exception {
        byte[] bytes=state.toString().getBytes(StandardCharsets.UTF_8); if(bytes.length>100_000) throw new IllegalStateException();
        AtomicFile f=file(c); FileOutputStream out=null;
        try {
            out=f.startWrite(); out.write(bytes); out.flush(); out.getFD().sync(); f.finishWrite(out); out=null;
            if(!java.util.Arrays.equals(bytes,f.readFully())) throw new java.io.IOException("Access readback failed");
        } catch(Exception e) { if(out!=null) f.failWrite(out); failed=true; throw e; }
    }
    static JSONObject find(JSONArray a,String pkg) { for(int i=0;i<a.length();i++) { JSONObject r=a.optJSONObject(i); if(r!=null && pkg.equals(r.optString("pkg"))) return r; } return null; }
    static String label(Context c,String pkg) {
        try {
            CharSequence raw=c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg,0));
            String s=raw==null?"":raw.toString(); StringBuilder b=new StringBuilder();
            for(int i=0;i<s.length() && b.length()<60;i++) { char ch=s.charAt(i); if(!Character.isISOControl(ch) && Character.getType(ch)!=Character.FORMAT && !Character.isSurrogate(ch)) b.append(ch); }
            String safe=b.toString().trim(); return safe.isEmpty()?pkg:safe;
        } catch(Exception e) { return pkg; }
    }
    static String authorize(Context c,int uid,String raw) {
        IntentApi.Activity activity=new IntentApi.Activity();
        activity.outcome="unavailable";
        String verifiedPackage=null;
        try {
        synchronized(LOCK) {
            try {
                Identity id=identify(c,uid); verifiedPackage=id.pkg;
                JSONObject s=load(c); JSONArray a=s.getJSONArray("records"); JSONObject r=find(a,id.pkg);
                activity.outcome="denied";
                if(r!=null && !id.matches(r)) { // Replacement cannot inherit approval or suppression.
                    for(int i=0;i<a.length();i++) if(a.getJSONObject(i)==r) { a.remove(i); break; }
                    r=null; recentNotices.remove(id.pkg); save(c,s);
                }
                if(r!=null && "allow".equals(r.getString("state"))) {
                    if(!id.matchesIdentity(identify(c,uid))) throw new SecurityException();
                    activity.outcome="failed";
                    return IntentApi.execute(c,raw,activity);
                }
                if(r!=null && "block".equals(r.getString("state"))) {
                    activity.outcome="blocked";
                    return IntentApi.denied("ACCESS_BLOCKED","Access blocked by user");
                }
                if(r==null) {
                    if(a.length()>=LIMIT) return IntentApi.denied("ACCESS_DENIED","Access list is full; manage it in Open Clock");
                    r=new JSONObject().put("pkg",id.pkg).put("uid",id.uid).put("cert",id.cert).put("installed",id.installed).put("state","pending").put("token",UUID.randomUUID().toString()).put("notice",0);
                    a.put(r); save(c,s);
                }
                long now=System.currentTimeMillis(), prev=r.getLong("notice"), elapsed=android.os.SystemClock.elapsedRealtime();
                Long recent=recentNotices.get(id.pkg);
                // Persisted wall time survives restart; rollback resets that fallback. During
                // this process, monotonic time prevents clock changes bypassing the limit.
                boolean due=recent!=null ? elapsed-recent>=3600000L : (prev==0 || now<prev || now-prev>=3600000L);
                if(due && canNotify(c)) {
                    r.put("state","pending").put("token",UUID.randomUUID().toString()); save(c,s);
                    if(notifyRequest(c,id,r.getString("token"))) {
                        recentNotices.put(id.pkg,elapsed); r.put("notice",now); save(c,s);
                    }
                }
                return IntentApi.denied("ACCESS_DENIED","Not approved; review Apps & access and retry");
            } catch(SecurityException | PackageManager.NameNotFoundException e) { activity.outcome="unavailable"; return IntentApi.denied("IDENTITY_UNAVAILABLE","Caller identity cannot be verified"); }
            catch(Exception e) { activity.outcome="unavailable"; return IntentApi.denied("ACCESS_UNAVAILABLE","Access verification unavailable; no operation performed"); }
        }
        } finally {
            // Logging has its own lock/storage and must never affect authorization or the reply.
            AccessLog.record(c,verifiedPackage,activity.operation,activity.outcome);
        }
    }
    private static boolean canNotify(Context c) {
        try { NotificationManager n=c.getSystemService(NotificationManager.class); if(!n.areNotificationsEnabled()) return false;
            if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return false;
            n.createNotificationChannel(new NotificationChannel(CHANNEL,"App access requests",NotificationManager.IMPORTANCE_DEFAULT));
            NotificationChannel ch=n.getNotificationChannel(CHANNEL); return ch!=null && ch.getImportance()!=NotificationManager.IMPORTANCE_NONE;
        } catch(Exception e) { return false; }
    }
    private static boolean notifyRequest(Context c,Identity id,String token) {
        try {
            Intent i=new Intent(c,AccessActivity.class).setAction("com.openclock.REVIEW_ACCESS").setData(android.net.Uri.parse("openclock://access/"+token));
            PendingIntent p=PendingIntent.getActivity(c,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder builder=new Notification.Builder(c,CHANNEL).setSmallIcon(com.openclock.R.drawable.ic_alarm).setContentTitle("App requests alarm access")
                .setContentText(label(c,id.pkg)+" ("+id.pkg+") · Review choices").setContentIntent(p).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE);
            // Listeners can invoke these: choices are only preselected for local review.
            String[] choices={"Allow", "Not allow", "Don't ask again"};
            String[] values={"allow", "declined", "block"};
            for(int j=0;j<choices.length;j++) {
                Intent review=new Intent(c,AccessActivity.class).setAction("com.openclock.REVIEW_ACCESS").setData(android.net.Uri.parse("openclock://access/"+token+"/"+values[j]));
                PendingIntent open=PendingIntent.getActivity(c,0,review,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
                builder.addAction(new Notification.Action.Builder(null,choices[j],open).build());
            }
            Notification n=builder.build();
            c.getSystemService(NotificationManager.class).notify("access:"+id.pkg,27,n);
            return true;
        } catch(Exception ignored) { return false; /* request remains in Apps & access */ }
    }
    static void decide(Context c,String pkg,String token,String choice,java.util.function.BooleanSupplier active) throws Exception {
        synchronized(LOCK) {
            JSONObject s=load(c), r=find(s.getJSONArray("records"),pkg);
            if(r==null || !token.equals(r.getString("token"))) throw new IllegalStateException("This choice is stale; review again");
            if(!choice.equals("allow")&&!choice.equals("block")&&!choice.equals("declined")&&!choice.equals("remove")) throw new IllegalArgumentException();
            android.app.KeyguardManager keyguard=c.getSystemService(android.app.KeyguardManager.class);
            if(!active.getAsBoolean() || keyguard==null || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked()) throw new SecurityException("Review no longer active or device locked");
            if(choice.equals("allow")) {
                Identity id=identify(c,r.getInt("uid")); if(!id.matches(r)) throw new SecurityException("App identity changed");
            }
            // Recheck after potentially slow package lookup, immediately before committing.
            if(!active.getAsBoolean() || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked()) throw new SecurityException("Review no longer active or device locked");
            if(choice.equals("remove")) { JSONArray a=s.getJSONArray("records"); for(int i=0;i<a.length();i++) if(a.getJSONObject(i)==r) { a.remove(i); break; } recentNotices.remove(pkg); }
            else { r.put("state",choice).put("token",UUID.randomUUID().toString()); }
            save(c,s); c.getSystemService(NotificationManager.class).cancel("access:"+pkg,27);
        }
    }
    private AccessPolicy() {}
}
