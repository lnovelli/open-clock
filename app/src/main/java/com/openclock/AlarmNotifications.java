package com.openclock;

import android.app.*;
import android.content.*;
import android.net.Uri;

import org.json.JSONObject;

final class AlarmNotifications {
    static final String RING_CHANNEL="ringing_v1", STATUS_CHANNEL="status_v1";
    static final int SERVICE_ID=10;
    static void channels(Context c) {
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        NotificationChannel ring=new NotificationChannel(RING_CHANNEL,"Ringing alarms",NotificationManager.IMPORTANCE_HIGH);
        ring.setDescription("Alarm controls. Sound and vibration follow each alarm's settings; Do Not Disturb still applies.");
        ring.setSound(null,null); ring.enableVibration(false); ring.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // Optional policy access permits a priority-mode exception for the visual
        // notification. Android still controls full-screen presentation and alarm audio.
        if(nm.isNotificationPolicyAccessGranted()) ring.setBypassDnd(true);
        nm.createNotificationChannel(ring);
        NotificationChannel status=new NotificationChannel(STATUS_CHANNEL,"Alarm scheduling problems",NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(status);
    }
    static Intent alertIntent(Context c,JSONObject a) {
        String token=a.optJSONObject("_active").optString("token");
        return new Intent(c,AlarmActivity.class).setData(Uri.parse("openclock://ring/"+a.optLong("id")+"/"+token))
            .putExtra("id",a.optLong("id")).putExtra("token",token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }
    static PendingIntent action(Context c,JSONObject a,String action) {
        String token=a.optJSONObject("_active").optString("token");
        Intent i=new Intent(c,AlarmReceiver.class).setAction(action)
            .setData(Uri.parse("openclock://action/"+a.optLong("id")+"/"+token+"/"+action))
            .putExtra("id",a.optLong("id")).putExtra("token",token);
        return PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static Notification ringing(Context c,JSONObject a) {
        channels(c);
        PendingIntent open=PendingIntent.getActivity(c,0,alertIntent(c,a),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String label=a.optString("label"); if(label.isEmpty()) label="Alarm";
        Notification.Builder b=new Notification.Builder(c,RING_CHANNEL).setSmallIcon(com.openclock.R.drawable.ic_alarm)
            .setContentTitle(label).setContentText(String.format(java.util.Locale.getDefault(),"%02d:%02d · Tap for alarm controls",a.optInt("hour"),a.optInt("minute")))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).setDeleteIntent(action(c,a,"com.openclock.DISMISS"))
            .setTimeoutAfter(Math.max(1,AlarmStore.remainingSessionMillis(a.optJSONObject("_active"))))
            .addAction(new Notification.Action.Builder(null,"Dismiss",action(c,a,"com.openclock.DISMISS")).build());
        if(AlarmStore.canSnooze(a)) b.addAction(new Notification.Action.Builder(null,"Snooze "+a.optInt("snoozeMinutes")+" min",action(c,a,"com.openclock.SNOOZE")).build());
        // Wear OS does not support full-screen notification intents.
        if(AlarmPresentation.fullScreenIntentSupported()) b.setFullScreenIntent(open,true);
        return b.build();
    }
    static Notification summary(Context c,int count) {
        return new Notification.Builder(c,RING_CHANNEL).setSmallIcon(R.drawable.ic_alarm).setContentTitle("Open Clock")
            .setContentText(count+" alarm(s) ringing").setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE))
            .setCategory(Notification.CATEGORY_SERVICE).build();
    }
    static void warning(Context c,String message) {
        try {
            channels(c);
            Notification n=new Notification.Builder(c,STATUS_CHANNEL).setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("Check Open Clock alarms").setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE)).build();
            c.getSystemService(NotificationManager.class).notify("problem",20,n);
        } catch(SecurityException ignored) { /* MainActivity also exposes accessProblem(). */ }
    }
}
