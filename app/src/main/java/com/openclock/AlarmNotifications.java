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
        return ringing(c,a,RING_CHANNEL,true);
    }
    private static Notification ringing(Context c,JSONObject a,String channel,boolean onlyOnce) {
        PendingIntent open=PendingIntent.getActivity(c,0,alertIntent(c,a),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String label=a.optString("label"); if(label.isEmpty()) label="Alarm";
        String origin=a.optBoolean("_phoneFallback")?" · Phone fallback, watch only":"";
        Notification.Builder b=new Notification.Builder(c,channel).setSmallIcon(com.openclock.R.drawable.ic_alarm)
            .setContentTitle(label).setContentText(String.format(java.util.Locale.getDefault(),"%02d:%02d%s · Tap for alarm controls",a.optInt("hour"),a.optInt("minute"),origin))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(onlyOnce).setContentIntent(open).setDeleteIntent(action(c,a,"com.openclock.DISMISS"))
            .setTimeoutAfter(Math.max(1,AlarmStore.remainingSessionMillis(a.optJSONObject("_active"))))
            .addAction(new Notification.Action.Builder(null,"Dismiss",action(c,a,"com.openclock.DISMISS")).build());
        if(AlarmStore.canSnooze(a)) b.addAction(new Notification.Action.Builder(null,"Snooze "+a.optInt("snoozeMinutes")+" min",action(c,a,"com.openclock.SNOOZE")).build());
        // Wear OS does not support full-screen notification intents.
        if(AlarmPresentation.fullScreenIntentSupported()) b.setFullScreenIntent(open,true);
        return b.build();
    }
    /** One platform-managed alert if Android refuses the continuous ringing service. */
    static void audibleFallback(Context c,JSONObject alarm) {
        synchronized(AlarmStore.class) {
            JSONObject session=alarm.optJSONObject("_active");
            if(session==null) return;
            JSONObject a=AlarmStore.active(c,alarm.optLong("id"),session.optString("token"));
            if(a==null||AlarmStore.remainingSessionMillis(a.optJSONObject("_active"))<=0) return;
            try {
                NotificationManager nm=c.getSystemService(NotificationManager.class);
                boolean sound=AlarmStore.requestsSound(a), vibrate=a.optBoolean("vibrate");
                String pattern=a.optString("vibrationPattern","basic");
                if(!"pulse".equals(pattern)&&!"heartbeat".equals(pattern)) pattern="basic";
                String channel=fallbackChannel(nm,sound,vibrate,pattern);
                NotificationChannel actual=nm.getNotificationChannel(channel);
                // Channels are user-controlled and immutable after creation. Never
                // play an output disabled for this occurrence, even if a channel
                // has subsequently been edited in Android settings.
                if(!compatible(actual,sound,vibrate)) {
                    channel=fallbackChannel(nm,false,false,"basic");
                    actual=nm.getNotificationChannel(channel);
                    if(!compatible(actual,sound,vibrate)) {
                        android.util.Log.w("OpenClock","No compatible alarm fallback notification channel");
                        return;
                    }
                }
                String tag="alarm:"+a.optLong("id");
                boolean onlyOnce=true;
                // A previously shown control-only notification must alert on the
                // transition to a fallback channel; retries on that channel must not.
                for(android.service.notification.StatusBarNotification shown:nm.getActiveNotifications()) {
                    if(tag.equals(shown.getTag())&&shown.getId()==11) {
                        onlyOnce=channel.equals(shown.getNotification().getChannelId());
                        break;
                    }
                }
                nm.notify(tag,11,ringing(c,a,channel,onlyOnce));
            } catch(RuntimeException denied) {
                android.util.Log.w("OpenClock","Unable to post alarm fallback notification",denied);
            }
        }
    }
    private static boolean compatible(NotificationChannel ch,boolean sound,boolean vibrate) {
        return ch!=null&&(sound||ch.getSound()==null)&&(vibrate||!ch.shouldVibrate());
    }
    private static String fallbackChannel(NotificationManager nm,boolean sound,boolean vibrate,String pattern) {
        String id="local_fallback_v1_"+(sound?"sound":"silent")+"_"+(vibrate?pattern:"still");
        String outputs=sound?(vibrate?"sound + "+pattern+" vibration":"sound"):(vibrate?pattern+" vibration":"silent");
        NotificationChannel ch=new NotificationChannel(id,"Alarm fallback: "+outputs,NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("One alert when continuous alarm ringing cannot start. Android controls sound, vibration and Do Not Disturb.");
        android.media.AudioAttributes attrs=new android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ALARM).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        ch.setSound(sound?android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI:null,attrs);
        ch.enableVibration(vibrate);
        if(vibrate) ch.setVibrationPattern(RingingService.vibrationWaveform(pattern));
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // Do not request a DND bypass: Android's alarm policy remains authoritative.
        nm.createNotificationChannel(ch);
        return id;
    }
    static Notification summary(Context c,int count) {
        return new Notification.Builder(c,RING_CHANNEL).setSmallIcon(R.drawable.ic_alarm).setContentTitle("Open Clock")
            .setContentText(count+" alarm(s) ringing").setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE))
            .setCategory(Notification.CATEGORY_SERVICE).build();
    }
    static synchronized void warning(Context c,String message) {
        try {
            channels(c);
            Notification n=new Notification.Builder(c,STATUS_CHANNEL).setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("Check Open Clock alarms").setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE)).build();
            c.getSystemService(NotificationManager.class).notify("problem",20,n);
        } catch(SecurityException ignored) { /* MainActivity also exposes accessProblem(). */ }
    }
    /** Clear only our fallback warning, never a different scheduling or ringing warning. */
    static synchronized void clearPhoneFallbackWarning(Context c,boolean journalOnly) {
        try {
            NotificationManager nm=c.getSystemService(NotificationManager.class);
            for(android.service.notification.StatusBarNotification shown:nm.getActiveNotifications()) {
                if(!"problem".equals(shown.getTag())||shown.getId()!=20) continue;
                CharSequence text=shown.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
                if(text==null) continue;
                String message=text.toString();
                if(message.startsWith("Phone fallback: ")||
                        (!journalOnly&&message.startsWith("Phone fallback not synchronized: ")))
                    nm.cancel("problem",20);
            }
        } catch(RuntimeException ignored) { /* The warning is advisory; do not block alarm recovery. */ }
    }
}
