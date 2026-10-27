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
                AlarmStore.recoverRinging(c);
            } else if("com.openclock.DISMISS".equals(action)||"com.openclock.SNOOZE".equals(action)) {
                AlarmStore.finish(c,id,token,"com.openclock.SNOOZE".equals(action));
                c.getSystemService(android.app.NotificationManager.class).cancel("alarm:"+id,11);
                AlarmStore.refreshService(c);
            }
        } catch(Exception e) { AlarmNotifications.warning(c,AlarmStore.message(e)); }
    }
}
