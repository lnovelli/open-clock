package com.openclock;

import android.content.*;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent) {
        long id=intent.getLongExtra("id",-1); String token=intent.getStringExtra("token");
        if("com.openclock.RECOVER".equals(intent.getAction())) { AlarmStore.recoverPending(c); return; }
        if(token==null) return;
        try {
            String action=intent.getAction();
            if("com.openclock.FIRE".equals(action)) {
                if(!AlarmStore.fire(c,id,token)) return;
                AlarmPresentation.local(c,id);
                AlarmStore.recoverRinging(c);
            } else if("com.openclock.DISMISS".equals(action)||"com.openclock.SNOOZE".equals(action)) {
                // Reject stale notification actions before either mutating the store or
                // cancelling the per-alarm notification for a newer occurrence.
                org.json.JSONObject dismissed;
                synchronized(AlarmStore.class) {
                    dismissed=AlarmStore.active(c,id,token);
                    if(dismissed==null) return;
                    boolean snooze="com.openclock.SNOOZE".equals(action);
                    AlarmStore.finish(c,id,token,snooze);
                    if(snooze) dismissed=null;
                    c.getSystemService(android.app.NotificationManager.class).cancel("alarm:"+id,11);
                }
                WearSync.afterLocalDismissal(c,dismissed);
                AlarmStore.refreshService(c);
            }
        } catch(Exception e) { AlarmNotifications.warning(c,AlarmStore.message(e)); }
    }
}
