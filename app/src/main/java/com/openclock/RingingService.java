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
    private boolean vibrating, prepared, hasFocus;
    private String vibratingPattern="";
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
                if(a.optBoolean("sound")&&(sound==null||a.getLong("id")<sound.getLong("id"))) sound=a;
                if(a.optBoolean("vibrate")&&(vibration==null||a.getLong("id")<vibration.getLong("id"))) vibration=a;
            }
            for(Long id:new HashSet<>(shown.keySet())) if(!current.contains(id)) { nm.cancel("alarm:"+id,11); shown.remove(id); }
            if(active.length()==0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return; }
            nm.notify(AlarmNotifications.SERVICE_ID,AlarmNotifications.summary(this,active.length()));
            if(!wake.isHeld()) wake.acquire(AlarmStore.RING_TIMEOUT+10_000);
            String token=sound==null?"":sound.getJSONObject("_active").getString("token");
            if(!token.equals(playing)) { stopAudio(); playing=token; nextAudioRetry=0; }
            long elapsed=SystemClock.elapsedRealtime();
            if(player!=null&&!prepared&&elapsed>=prepareDeadline) stopAudio();
            if(sound!=null&&player==null&&elapsed>=nextAudioRetry) { nextAudioRetry=elapsed+5000; play(); }
            else if(sound!=null&&!hasFocus&&focus!=null&&elapsed>=nextFocusRetry) requestFocus();
            // Vibration honors the user's Do Not Disturb policy; never request policy bypass.
            boolean dndAllows=nm.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL||nm.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALARMS;
            boolean vibrate=vibration!=null&&dndAllows;
            String pattern=vibrate?vibration.optString("vibrationPattern","basic"):"";
            if(vibrate&&(!vibrating||!pattern.equals(vibratingPattern))&&vibrator!=null&&vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(vibrationWaveform(pattern),0),new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                vibrating=true; vibratingPattern=pattern;
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
        nextFocusRetry=SystemClock.elapsedRealtime()+5000;
        try { hasFocus=audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED; }
        catch(RuntimeException denied) { hasFocus=false; return; }
        try { if(hasFocus&&player!=null&&prepared) player.start(); }
        catch(RuntimeException unavailable) { stopAudio(); }
    }
    private void play() {
        try {
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            final long generation=++audioGeneration;
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(change->{
                    if(generation!=audioGeneration) return;
                    hasFocus=change==AudioManager.AUDIOFOCUS_GAIN;
                    try { if(player!=null&&prepared) { if(hasFocus) player.start(); else player.pause(); } }
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
                try { if(hasFocus) mp.start(); } catch(RuntimeException unavailable) { stopAudio(); }
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
        handler.removeCallbacksAndMessages(null); unregisterReceiver(refresh); stopAudio();
        if(vibrator!=null) vibrator.cancel(); if(wake!=null&&wake.isHeld()) wake.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
