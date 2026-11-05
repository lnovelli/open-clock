package com.openclock;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Migration and repair use the already accepted credential-protected cache.
 * Duplicate alerts remain possible. A linked explicit dismissal is sent through
 * the durable outbox; missing identity or an unknown outcome is not confirmation.
 * Alarm generations and recovery live in AlarmStore's device-protected journal. */
final class PhoneFallback {
    private static SharedPreferences status(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("phone-fallback-status-v1", Context.MODE_PRIVATE);
    }
    static boolean reconcile(Context c, String node, JSONObject snapshot) {
      synchronized(AlarmStore.class) {
        try {
            AlarmStore.syncPhoneFallback(c,node,snapshot);
            String problem=AlarmStore.phoneFallbackProblem(c);
            if(!status(c).edit().putBoolean("initialized",true).putBoolean("journalProblem",true)
                    .putString("problem",problem==null?"":problem).commit()) return false;
            if(problem!=null) AlarmNotifications.warning(c,"Phone fallback: "+problem);
            else AlarmNotifications.clearPhoneFallbackWarning(c,false);
            return problem==null;
        } catch(Exception e) {
            String problem="Phone fallback not synchronized: "+AlarmStore.message(e);
            status(c).edit().putBoolean("journalProblem",false).putString("problem",problem).commit();
            AlarmNotifications.warning(c,problem);
            return false;
        }
      }
    }
    static String problem(Context c) {
        String access=AlarmStore.phoneFallbackProblem(c);
        if(access!=null) return access;
        String saved=status(c).getString("problem","");
        // A journal-backed problem can be repaired by the device-protected
        // recovery alarm without another credential-protected snapshot read.
        if(!saved.isEmpty()&&!status(c).getBoolean("journalProblem",false)) return saved;
        if(!status(c).getBoolean("initialized",false)) return "No phone snapshot scheduled on watch yet";
        return "";
    }
    private PhoneFallback() { }
}
