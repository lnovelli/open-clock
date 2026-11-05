package com.openclock;

import android.content.Context;
import android.os.UserManager;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Best-effort local history; never used to make an authorization decision. */
final class AccessLog {
    private static final Object LOCK = new Object();
    private static final int LIMIT = 100;
    private static boolean unavailable;
    static final String REQUEST = "request";

    static String operation(Object value) {
        if (!(value instanceof String)) return REQUEST;
        switch ((String) value) {
            case "alarms.list": case "alarms.create": case "alarms.update": case "alarms.delete": case "alarms.triggerNow":
            case "groups.list": case "groups.create": case "groups.update": case "groups.delete":
            case "groups.addAlarm": case "groups.removeAlarm": return (String) value;
            default: return REQUEST;
        }
    }
    private static boolean validOutcome(String value) {
        return value.equals("success") || value.equals("failed") || value.equals("denied")
                || value.equals("blocked") || value.equals("unavailable");
    }
    private static AtomicFile file(Context c) {
        Context app=c.getApplicationContext();
        UserManager users=app.getSystemService(UserManager.class);
        if (users==null || !users.isUserUnlocked() || app.isDeviceProtectedStorage())
            throw new IllegalStateException();
        return new AtomicFile(new File(app.getNoBackupFilesDir(),"access-log-v1.json"));
    }
    private static JSONArray loadLocked(Context c) throws Exception {
        AtomicFile f=file(c);
        if (!f.getBaseFile().exists() && !new File(f.getBaseFile()+".bak").exists()) return new JSONArray();
        byte[] bytes=f.readFully();
        if (bytes.length>100_000) throw new IllegalStateException();
        JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        JSONArray events=root.getJSONArray("events");
        if (root.getInt("version")!=1 || events.length()>LIMIT) throw new IllegalStateException();
        for (int i=0;i<events.length();i++) {
            JSONObject event=events.getJSONObject(i);
            String pkg=event.getString("package"), op=event.getString("operation"), outcome=event.getString("outcome");
            if (pkg.length()>255 || event.getLong("time")<0 || !operation(op).equals(op) || !validOutcome(outcome))
                throw new IllegalStateException();
        }
        return events;
    }
    /** A failure is visible for this process and cannot alter the API reply. */
    static void record(Context c, String pkg, String op, String outcome) {
        synchronized (LOCK) {
            try {
                if (unavailable) return;
                JSONArray old=loadLocked(c), events=new JSONArray();
                for (int i=Math.max(0,old.length()-(LIMIT-1));i<old.length();i++) events.put(old.getJSONObject(i));
                events.put(new JSONObject().put("package",pkg==null?"":pkg).put("time",System.currentTimeMillis())
                        .put("operation",operation(op)).put("outcome",validOutcome(outcome)?outcome:"unavailable"));
                byte[] bytes=new JSONObject().put("version",1).put("events",events).toString().getBytes(StandardCharsets.UTF_8);
                AtomicFile f=file(c); FileOutputStream out=null;
                try {
                    out=f.startWrite(); out.write(bytes); out.flush(); out.getFD().sync(); f.finishWrite(out); out=null;
                    if (!java.util.Arrays.equals(bytes,f.readFully())) throw new java.io.IOException();
                } catch (Exception e) { if (out!=null) f.failWrite(out); throw e; }
            } catch (Exception e) { unavailable=true; }
        }
    }
    /** Caller must handle failure; no partial or fabricated history is returned. */
    static JSONArray read(Context c) throws Exception {
        synchronized (LOCK) {
            if (unavailable) throw new IllegalStateException();
            try { return loadLocked(c); }
            catch (Exception e) { unavailable=true; throw e; }
        }
    }
    private AccessLog() {}
}
