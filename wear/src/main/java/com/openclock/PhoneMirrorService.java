package com.openclock;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Best-effort, time-bounded phone mirror; independent of watch alarm sessions. */
public final class PhoneMirrorService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::reconcile;
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private Vibrator vibrator;
    private PowerManager.WakeLock wake;
    private boolean focused, prepared, vibrating;
    private String soundToken = "";
    private long soundId = -1, generation, retryAt, prepareAt, nextVibration, nextFocus;
    private final List<JSONObject> displayed = new ArrayList<>();

    @Override public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        vibrator = getSystemService(Vibrator.class);
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenClock:phone-mirror");
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        List<JSONObject> live = PhoneMirror.live(this);
        try { startForeground(PhoneMirror.SERVICE_ID, PhoneMirror.summary(this, live)); }
        catch (RuntimeException denied) { PhoneMirror.fallback(this); stopSelf(); return START_NOT_STICKY; }
        reconcile();
        return START_NOT_STICKY;
    }
    private void reconcile() {
        handler.removeCallbacks(tick);
        List<JSONObject> live = PhoneMirror.live(this);
        for (JSONObject prior : displayed) {
            boolean found = false;
            for (JSONObject a : live) if (a.optLong("id") == prior.optLong("id")
                    && a.optString("token").equals(prior.optString("token"))) { found = true; break; }
            if (!found) PhoneMirror.cancel(this, prior);
        }
        displayed.clear(); displayed.addAll(live);
        if (live.isEmpty() || !PhoneMirror.notificationsAllowed(this)) { stopSelf(); return; }
        try {
            getSystemService(NotificationManager.class).notify(PhoneMirror.SERVICE_ID, PhoneMirror.summary(this, live));
            if (!wake.isHeld()) wake.acquire(10 * 60_000L + 5000);
            JSONObject sound = null; boolean wantVibration = false;
            for (JSONObject a : live) {
                if (a.optBoolean("sound") && (sound == null || a.optLong("id") < sound.optLong("id"))) sound = a;
                if (a.optBoolean("vibrate")) wantVibration = true;
            }
            boolean localSound = false, localVibration = false;
            try {
                org.json.JSONArray local = AlarmStore.active(this);
                for (int i = 0; i < local.length(); i++) {
                    JSONObject item = local.getJSONObject(i);
                    localSound |= AlarmStore.requestsSound(item);
                    localVibration |= item.optBoolean("vibrate");
                }
            } catch (Exception unavailable) { localSound = true; localVibration = true; }
            if (localSound) sound = null; // Let the independent local engine own audio focus.
            String token = sound == null ? "" : sound.optString("token");
            if (!token.equals(soundToken)) {
                stopAudio(); soundToken = token; soundId = sound == null ? -1 : sound.optLong("id"); retryAt = 0;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (player != null && !soundAllowed()) stopAudio();
            if (player != null && !prepared && now >= prepareAt) stopAudio();
            if (sound != null && player == null && now >= retryAt) { retryAt = now + 5000; play(); }
            else if (sound != null && player != null && !focused && focus != null && now >= nextFocus) {
                nextFocus = now + 5000;
                focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                if (focused && prepared && soundAllowed()) player.start();
            }
            // Conservative DND: do not vibrate in priority-only mode, whose policy may disallow alarms.
            int filter = getSystemService(NotificationManager.class).getCurrentInterruptionFilter();
            boolean allowVibration = wantVibration && !localVibration && (filter == NotificationManager.INTERRUPTION_FILTER_ALL
                    || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS);
            if (allowVibration && now >= nextVibration && vibrator != null && vibrator.hasVibrator()) {
                long duration = 0;
                for (JSONObject a : live) if (a.optBoolean("vibrate")) duration = Math.max(duration, PhoneMirror.remaining(this, a));
                // A finite pulse cannot become an unbounded vibration after process death.
                if (duration > 0) {
                    vibrator.vibrate(VibrationEffect.createOneShot(Math.min(600, duration), VibrationEffect.DEFAULT_AMPLITUDE),
                            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                    vibrating = true; nextVibration = now + 1000;
                }
            } else if (!allowVibration && vibrating) { cancelVibration(); }
            long delay = 1000;
            for (JSONObject a : live) delay = Math.min(delay, Math.max(1, PhoneMirror.remaining(this, a)));
            handler.postDelayed(tick, delay);
        } catch (RuntimeException e) { PhoneMirror.fallback(this); stopSelf(); }
    }
    private boolean soundAllowed() {
        JSONObject a = PhoneMirror.current(this, soundId, soundToken);
        int filter = getSystemService(NotificationManager.class).getCurrentInterruptionFilter();
        return a != null && a.optBoolean("sound") && (filter == NotificationManager.INTERRUPTION_FILTER_ALL
                || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS);
    }
    private void play() {
        if (!soundAllowed() || audio == null) return;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            long g = ++generation;
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true).setOnAudioFocusChangeListener(change -> {
                        if (g != generation) return;
                        focused = change == AudioManager.AUDIOFOCUS_GAIN;
                        try {
                            if (focused && prepared && soundAllowed() && player != null) player.start();
                            else if (!focused && prepared && player != null) player.pause();
                        } catch (RuntimeException e) { stopAudio(); }
                    }, handler).build();
            focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            player = new MediaPlayer(); player.setAudioAttributes(attrs); player.setLooping(true);
            android.net.Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = Settings.System.DEFAULT_ALARM_ALERT_URI;
            try { player.setDataSource(this, uri); }
            catch (Exception unavailable) {
                player.reset(); player.setAudioAttributes(attrs); player.setLooping(true);
                try (android.content.res.AssetFileDescriptor fd = getResources().openRawResourceFd(R.raw.alarm_sound)) {
                    player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                }
            }
            player.setOnPreparedListener(mp -> {
                if (mp != player) return;
                prepared = true;
                try { if (focused && soundAllowed()) mp.start(); }
                catch (RuntimeException e) { stopAudio(); }
            });
            player.setOnErrorListener((mp, what, extra) -> { if (mp == player) stopAudio(); return true; });
            prepareAt = android.os.SystemClock.elapsedRealtime() + 10_000;
            player.prepareAsync();
        } catch (Exception e) { stopAudio(); }
    }
    private void stopAudio() {
        ++generation; prepared = false; focused = false;
        if (player != null) { player.release(); player = null; }
        if (focus != null && audio != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
    }
    private void cancelVibration() {
        // Vibrator cancellation is app-scoped on some devices: do not cancel a local alarm.
        boolean localVibrating = false;
        try {
            org.json.JSONArray local = AlarmStore.active(this);
            for (int i = 0; i < local.length(); i++) if (local.optJSONObject(i).optBoolean("vibrate")) localVibrating = true;
        } catch (RuntimeException ignored) { localVibrating = true; }
        if (!localVibrating && vibrator != null) vibrator.cancel();
        vibrating = false;
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null); stopAudio();
        if (vibrating) cancelVibration();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
