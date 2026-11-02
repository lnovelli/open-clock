package com.openclock;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.provider.Settings;
import org.json.*;
import java.util.*;

/** A bounded foreground lifecycle, supporting simultaneous independent alarm sessions. */
public final class RingingService extends Service {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wake;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private String playing="";
    private long playingId=-1;
    private boolean vibrating, prepared, hasFocus;
    private String vibratingPattern="";
    private long nextVibrationRetry;
    private int vibrationFilter=-1;
    private long nextFocusRetry, nextAudioRetry, audioGeneration, prepareDeadline;
    private final Map<Long,String> shown=new HashMap<>();
    private final Runnable tick=new Runnable() { public void run() { reconcile(); } };
    private final BroadcastReceiver refresh=new BroadcastReceiver() { public void onReceive(Context c,Intent i) { reconcile(); } };
    @Override public void onCreate() {
        super.onCreate(); AlarmNotifications.channels(this);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(refresh,new IntentFilter("com.openclock.REFRESH"),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(refresh,new IntentFilter("com.openclock.REFRESH"));
        audio=getSystemService(AudioManager.class); vibrator=getSystemService(Vibrator.class);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OpenClock:ring");
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        startForeground(AlarmNotifications.SERVICE_ID,AlarmNotifications.summary(this,AlarmStore.active(this).length()));
        reconcile(); return START_STICKY;
    }
    private void reconcile() {
        handler.removeCallbacks(tick);
        try {
            AlarmStore.expireSessions(this);
            JSONArray active=AlarmStore.active(this); long now=System.currentTimeMillis();
            for(int i=0;i<active.length();i++) {
                JSONObject a=active.getJSONObject(i), session=a.getJSONObject("_active");
                if(!AlarmStore.validSession(this,session,now))
                    AlarmStore.finish(this,a.getLong("id"),session.getString("token"),false);
            }
            active=AlarmStore.active(this); Set<Long> current=new HashSet<>();
            JSONObject sound=null, vibration=null;
            NotificationManager nm=getSystemService(NotificationManager.class);
            for(int i=0;i<active.length();i++) {
                JSONObject a=active.getJSONObject(i); current.add(a.getLong("id"));
                String session=a.toString();
                if(!session.equals(shown.get(a.getLong("id")))) {
                    nm.notify("alarm:"+a.getLong("id"),11,AlarmNotifications.ringing(this,a)); shown.put(a.getLong("id"),session);
                }
                // Deterministic independent owners: lowest ID requesting each output.
                if(AlarmStore.requestsSound(a)&&(sound==null||a.getLong("id")<sound.getLong("id"))) sound=a;
                if(a.optBoolean("vibrate")&&(vibration==null||a.getLong("id")<vibration.getLong("id"))) vibration=a;
            }
            for(Long id:new HashSet<>(shown.keySet())) if(!current.contains(id)) { nm.cancel("alarm:"+id,11); shown.remove(id); }
            if(active.length()==0) { WatchShakeBridge.local(this,false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return; }
            nm.notify(AlarmNotifications.SERVICE_ID,AlarmNotifications.summary(this,active.length()));
            if(!wake.isHeld()) wake.acquire(AlarmStore.RING_TIMEOUT+10_000);
            WatchShakeBridge.local(this,true);
            String token=sound==null?"":sound.getJSONObject("_active").getString("token");
            if(!token.equals(playing)) { stopAudio(); playing=token; playingId=sound==null?-1:sound.getLong("id"); nextAudioRetry=0; }
            long elapsed=SystemClock.elapsedRealtime();
            if(player!=null&&!prepared&&elapsed>=prepareDeadline) stopAudio();
            if(sound!=null&&player==null&&elapsed>=nextAudioRetry) { nextAudioRetry=elapsed+5000; play(); }
            else if(sound!=null&&!hasFocus&&focus!=null&&elapsed>=nextFocusRetry) requestFocus();
            // Ask Android as an alarm, including in priority mode: its policy may allow
            // alarms there. We do not override DND or the individual alarm's choice.
            int filter=nm.getCurrentInterruptionFilter();
            boolean vibrate=vibration!=null;
            String pattern=vibrate?vibration.optString("vibrationPattern","basic"):"";
            // Android may discard/cancel a vibration while DND blocks alarms. Retry
            // periodically in DND so a policy change in the same mode can take effect.
            if(vibrate&&(!vibrating||!pattern.equals(vibratingPattern)||filter!=vibrationFilter||
                    (filter!=NotificationManager.INTERRUPTION_FILTER_ALL&&elapsed>=nextVibrationRetry))&&vibrator!=null&&vibrator.hasVibrator()) {
                VibrationEffect effect=VibrationEffect.createWaveform(vibrationWaveform(pattern),0);
                if(Build.VERSION.SDK_INT>=33) vibrator.vibrate(effect,new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build());
                else vibrator.vibrate(effect,new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                vibrating=true; vibratingPattern=pattern; vibrationFilter=filter; nextVibrationRetry=elapsed+10_000;
            } else if(!vibrate&&vibrating) { vibrator.cancel(); vibrating=false; vibratingPattern=""; }
            handler.postDelayed(tick,1000);
        } catch(Exception e) { AlarmNotifications.warning(this,AlarmStore.message(e)); stopSelf(); }
    }
    static long[] vibrationWaveform(String pattern) {
        if("pulse".equals(pattern)) return new long[]{0,150,150,150,550,0};
        if("heartbeat".equals(pattern)) return new long[]{0,100,120,250,900,0};
        return new long[]{0,600,400,0};
    }
    private void requestFocus() {
        if(!currentSoundAllowed()) { stopAudio(); return; }
        nextFocusRetry=SystemClock.elapsedRealtime()+5000;
        try { hasFocus=audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
        catch(RuntimeException denied) { hasFocus=false; return; }
        try { startPreparedAudio(); }
        catch(RuntimeException unavailable) { stopAudio(); }
    }
    private boolean currentSoundAllowed() {
        JSONObject a=AlarmStore.active(this,playingId,playing);
        return a!=null&&AlarmStore.requestsSound(a);
    }
    private void startPreparedAudio() {
        // Re-read durable state at every async start boundary. A queued focus/prepared
        // callback must not revive muted, dismissed, expired or replaced occurrences.
        if(!currentSoundAllowed()) { stopAudio(); return; }
        if(hasFocus&&player!=null&&prepared) player.start();
    }
    private void play() {
        try {
            if(!currentSoundAllowed()) { stopAudio(); return; }
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            final long generation=++audioGeneration;
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(change->{
                    if(generation!=audioGeneration) return;
                    hasFocus=change==AudioManager.AUDIOFOCUS_GAIN;
                    try { if(hasFocus) startPreparedAudio(); else if(player!=null&&prepared) player.pause(); }
                    catch(RuntimeException unavailable) { stopAudio(); }
                },handler).build();
            requestFocus();
            player=new MediaPlayer(); player.setAudioAttributes(attrs); player.setLooping(true);
            android.net.Uri uri=RingtoneManager.getActualDefaultRingtoneUri(this,RingtoneManager.TYPE_ALARM);
            if(uri==null) uri=Settings.System.DEFAULT_ALARM_ALERT_URI;
            try { player.setDataSource(this,uri); }
            catch(Exception unavailable) {
                player.reset(); player.setAudioAttributes(attrs); player.setLooping(true);
                try(android.content.res.AssetFileDescriptor fd=getResources().openRawResourceFd(R.raw.alarm_sound)) {
                    player.setDataSource(fd.getFileDescriptor(),fd.getStartOffset(),fd.getLength());
                }
            }
            player.setOnPreparedListener(mp->{
                if(mp!=player) return;
                prepared=true;
                try { startPreparedAudio(); } catch(RuntimeException unavailable) { stopAudio(); }
            });
            player.setOnErrorListener((mp,what,extra)->{ if(mp!=player) return true; stopAudio(); AlarmNotifications.warning(this,"Alarm sound is unavailable. Use the visible alarm controls."); return true; });
            prepareDeadline=SystemClock.elapsedRealtime()+10_000;
            player.prepareAsync();
        } catch(Exception e) { stopAudio(); AlarmNotifications.warning(this,"Alarm sound is unavailable. Use the visible alarm controls."); }
    }
    private void stopAudio() {
        ++audioGeneration; prepared=false; hasFocus=false;
        if(player!=null) { player.release(); player=null; }
        if(focus!=null&&audio!=null) { audio.abandonAudioFocusRequest(focus); focus=null; }
    }
    @Override public void onDestroy() {
        WatchShakeBridge.local(this,false);
        handler.removeCallbacksAndMessages(null); unregisterReceiver(refresh); stopAudio();
        if(vibrator!=null) vibrator.cancel(); if(wake!=null&&wake.isHeld()) wake.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
