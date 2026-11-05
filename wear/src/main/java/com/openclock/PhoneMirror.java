package com.openclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Advisory phone sessions; local dismissals only touch an exactly linked watch fallback. */
public final class PhoneMirror {
    static final String CHANNEL = "phone_mirror_v1";
    static final int SERVICE_ID = 31901, ALERT_ID = 31902;
    private static final String PREFS = "phone-mirror-private-v1", KEY = "tokens";
    private static final long MAX_SESSION = 10 * 60_000L, RETAIN = 24 * 60 * 60_000L;
    private static final int MAX_TOKENS = 4096;
    private static final Object LOCK = new Object();
    private static final java.util.Map<String, Long> elapsedDeadlines = new java.util.HashMap<>();
    private static final Set<String> suppressedInProcess = new HashSet<>();

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    private static JSONArray records(Context c) throws Exception {
        String s = prefs(c).getString(KEY, "[]");
        if (s.length() > 2_000_000) throw new IllegalStateException("Mirror state too large");
        JSONArray a = new JSONArray(s);
        if (a.length() > MAX_TOKENS) throw new IllegalStateException("Mirror state too large");
        return a;
    }
    private static boolean save(Context c, JSONArray records) {
        return prefs(c).edit().putString(KEY, records.toString()).commit();
    }
    private static JSONObject record(JSONArray records, String token) {
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r != null && token.equals(r.optString("token"))) return r;
        }
        return null;
    }
    private static long integer(JSONObject a, String key) throws Exception {
        Object value = a.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException();
        return new java.math.BigDecimal(value.toString()).longValueExact();
    }
    private static List<JSONObject> validate(JSONObject snapshot) throws Exception {
        if (snapshot == null || !(snapshot.opt("active") instanceof JSONArray)) throw new IllegalArgumentException();
        JSONArray active = snapshot.getJSONArray("active");
        if (active.length() > 500) throw new IllegalArgumentException();
        List<JSONObject> result = new ArrayList<>();
        Set<String> tokens = new HashSet<>(); Set<Long> ids = new HashSet<>();
        for (int i = 0; i < active.length(); i++) {
            JSONObject a = active.getJSONObject(i);
            long id = integer(a, "id"), started = integer(a, "startedAt"), end = integer(a, "expiresAt");
            long hour = integer(a, "hour"), minute = integer(a, "minute");
            Object tokenObject = a.get("token");
            if (!(tokenObject instanceof String)) throw new IllegalArgumentException();
            String token = (String) tokenObject;
            if (!UUID.fromString(token).toString().equals(token) || !tokens.add(token) || !ids.add(id)
                    || id < 1 || started < 1 || end <= started || end - started > MAX_SESSION + 5000
                    || hour < 0 || hour > 23 || minute < 0 || minute > 59
                    || !(a.get("label") instanceof String) || a.getString("label").length() > 200
                    || !(a.get("sound") instanceof Boolean) || !(a.get("vibrate") instanceof Boolean)
                    || !(a.get("canSnooze") instanceof Boolean)) throw new IllegalArgumentException();
            result.add(new JSONObject(a.toString()));
        }
        return result;
    }
    private static String identity(JSONObject a) { return a.optLong("id") + ":" + a.optString("token"); }
    private static String tag(JSONObject a) { return "phone-mirror:" + identity(a); }

    /** Called only after PhoneSync has committed and accepted its authoritative cache. */
    public static boolean onSnapshot(Context context, JSONObject snapshot) {
        final Context c = context.getApplicationContext();
        final String incoming = snapshot == null ? "" : snapshot.toString();
        return accept(c, incoming);
    }
    private static boolean accept(Context c, String incoming) {
        synchronized (LOCK) {
            try {
                JSONObject proposed = new JSONObject(incoming), current = PhoneSync.cached(c);
                // Ignore delayed callbacks; connected is advisory and may change independently.
                if (current == null || !proposed.optString("epoch").equals(current.optString("epoch"))
                        || proposed.optLong("revision", -1) != current.optLong("revision", -1)
                        || !String.valueOf(proposed.opt("active")).equals(String.valueOf(current.opt("active")))) return false;
                List<JSONObject> active = validate(proposed);
                Set<String> dismissed = PhoneSync.mirrorDismissalsInOutbox(c, current);
                Set<String> mirrorDismissed = AlarmStore.mirrorDismissalKeys(c);
                JSONArray old = records(c), next = new JSONArray(); long now = System.currentTimeMillis();
                Set<String> present = new HashSet<>();
                for (JSONObject a : active) present.add(identity(a));
                for (int i = 0; i < old.length(); i++) {
                    JSONObject r = old.getJSONObject(i);
                    if (!present.contains(identity(r))) r.put("closed", true);
                    // Keep tombstones beyond expiry to avoid stale replays on clock changes.
                    if (r.optLong("expiresAt", Long.MAX_VALUE) >= now - RETAIN) next.put(r);
                    else cancel(c, r);
                }
                for (JSONObject a : active) {
                    long end = a.getLong("expiresAt"), started = a.getLong("startedAt");
                    if (end <= now || started > now + 60_000) continue;
                    JSONObject r = record(next, a.getString("token"));
                    if (r == null) {
                        if (next.length() >= MAX_TOKENS) continue; // fail closed; never evict a live tombstone
                        r = new JSONObject().put("id", a.getLong("id")).put("token", a.getString("token"))
                                .put("expiresAt", end).put("closed", false);
                        next.put(r);
                    } else if (r.optLong("id") != a.getLong("id")) {
                        r.put("closed", true);
                    } else {
                        r.put("expiresAt", Math.min(r.getLong("expiresAt"), end));
                    }
                    if (mirrorDismissed.contains(identity(a)) ||
                            dismissed.contains(a.getLong("id") + ":" + a.optString("occurrenceId", ""))) r.put("closed", true);
                }
                if (!save(c, next)) return false; // never alert without durable replay protection
                for (int i = 0; i < next.length(); i++) {
                    JSONObject r = next.getJSONObject(i);
                    if (r.optBoolean("closed") || r.optLong("expiresAt") <= now) cancel(c, r);
                }
                List<JSONObject> live = liveLocked(c, current, next);
                if (notificationsAllowed(c)) {
                    List<JSONObject> unposted = new ArrayList<>();
                    for (JSONObject a : live) {
                        JSONObject r = record(next, a.getString("token"));
                        if (r != null && !r.optBoolean("posted")) {
                            r.put("posted", true); unposted.add(a);
                        }
                    }
                    // Reserve alert delivery before posting; process death cannot replay it.
                    if (!unposted.isEmpty()) {
                        if (!save(c, next)) return false;
                        for (JSONObject a : unposted) {
                            post(c, a, remaining(c, a));
                            AlarmPresentation.mirror(c, a);
                        }
                    }
                }
                if (!live.isEmpty() && notificationsAllowed(c)) start(c);
                else c.stopService(new Intent(c, PhoneMirrorService.class));
                c.sendBroadcast(new Intent("com.openclock.PHONE_MIRROR_CHANGED").setPackage(c.getPackageName()));
                return true;
            } catch (Exception ignored) {
                // A malformed/incomplete snapshot cannot authorize a session or terminate one.
                return false;
            }
        }
    }
    static List<JSONObject> live(Context c) {
        synchronized (LOCK) {
            try { return liveLocked(c, PhoneSync.cached(c), records(c)); }
            catch (Exception e) { return new ArrayList<>(); }
        }
    }
    private static List<JSONObject> liveLocked(Context c, JSONObject snapshot, JSONArray saved) throws Exception {
        List<JSONObject> live = new ArrayList<>();
        Set<String> dismissed = AlarmStore.mirrorDismissalKeys(c);
        Set<String> consumed = PhoneSync.mirrorDismissalsInOutbox(c, snapshot);
        for (JSONObject a : validate(snapshot)) {
            JSONObject r = record(saved, a.getString("token"));
            if (r == null || r.optBoolean("closed") || dismissed.contains(identity(a)) ||
                    consumed.contains(a.optLong("id") + ":" + a.optString("occurrenceId", "")) || suppressedInProcess.contains(identity(a))
                    || r.optLong("id") != a.getLong("id")) continue;
            long end = Math.min(r.getLong("expiresAt"), a.getLong("expiresAt"));
            a.put("expiresAt", end);
            if (remaining(c, a) > 0) live.add(a);
        }
        return live;
    }
    static JSONObject current(Context c, long id, String token) {
        if (token == null) return null;
        for (JSONObject a : live(c)) if (a.optLong("id") == id && token.equals(a.optString("token"))) return a;
        return null;
    }
    static long remaining(Context c, JSONObject a) {
        long wall = a.optLong("expiresAt") - System.currentTimeMillis();
        String key = identity(a);
        synchronized (LOCK) {
            // An elapsed deadline bounds a live process even if the wall clock moves backwards.
            Long deadline = elapsedDeadlines.get(key);
            long elapsed = SystemClock.elapsedRealtime();
            long limited = Math.max(0, Math.min(MAX_SESSION, wall));
            if (deadline == null) { deadline = elapsed + limited; elapsedDeadlines.put(key, deadline); }
            return Math.max(0, Math.min(limited, deadline - elapsed));
        }
    }
    // A watch dismissal is a local tombstone, not a phone acknowledgement.
    // Only a live, validated mirror token can be suppressed.
    private static JSONObject silenceLocked(Context c, JSONObject identity) throws Exception {
        long id=identity.getLong("id"); String token=identity.getString("token");
        JSONArray state = records(c);
        boolean found=false;
        for (JSONObject a : liveLocked(c, PhoneSync.cached(c), state)) {
            if (a.optLong("id") == id && token.equals(a.optString("token"))) { found=true; break; }
        }
        if (!found) return null;
        JSONObject result;
        try { result=AlarmStore.dismissMirror(c, identity); }
        catch (Exception failure) {
            android.util.Log.w("PhoneMirror", "Could not journal mirror dismissal", failure);
            result=new JSONObject().put("queued",false);
        }
        // Suppress even on storage failure, with an explicit warning. A successful
        // store commit is the authoritative tombstone if this second save fails.
        suppressedInProcess.add(id + ":" + token);
        JSONObject r = record(state, token);
        r.put("closed", true);
        if(!save(c, state)) android.util.Log.w("PhoneMirror", "Could not save mirror preferences");
        cancel(c, r);
        return result;
    }
    // A fallback can be dismissed after the cache commit but before onSnapshot
    // creates its mirror record. Reserve a tombstone before that callback runs.
    private static boolean silenceLinkedLocked(Context c, long id, String token) throws Exception {
        JSONArray state = records(c);
        JSONObject found = null;
        for (JSONObject a : validate(PhoneSync.cached(c))) {
            if (a.optLong("id") == id && token.equals(a.optString("token"))) { found = a; break; }
        }
        long now = System.currentTimeMillis();
        if (found == null || found.optLong("expiresAt") <= now || found.optLong("startedAt") > now + 60_000) return false;
        JSONObject r = record(state, token);
        if (r != null && r.optLong("id") != id) return false;
        suppressedInProcess.add(id + ":" + token);
        if (r == null) {
            if (state.length() >= MAX_TOKENS) return true; // process-local fail-closed
            r = new JSONObject().put("id", id).put("token", token).put("expiresAt", found.getLong("expiresAt"));
            state.put(r);
        }
        r.put("closed", true);
        save(c, state);
        cancel(c, r);
        return true;
    }
    private static void localChanged(Context c) {
        try { c.startService(new Intent(c, PhoneMirrorService.class)); } catch (RuntimeException ignored) { }
        c.sendBroadcast(new Intent("com.openclock.PHONE_MIRROR_CHANGED").setPackage(c.getPackageName()));
    }
    /** Hook after an explicit fallback dismissal committed, and after releasing AlarmStore.class.
     * The argument is the validated active row captured under that lock before finish(). */
    static void localDismissed(Context context, JSONObject fallback) {
        if (fallback == null || !fallback.optBoolean("_phoneFallback")) return;
        JSONObject session = fallback.optJSONObject("_active");
        if (session == null) return;
        Context c = context.getApplicationContext(); boolean silenced = false;
        synchronized (LOCK) {
            try {
                synchronized (PhoneSync.class) {
                    long phoneId = fallback.optLong("_phoneId");
                    String token = PhoneSync.linkedMirrorToken(c, fallback.optString("_phoneNode"),
                            fallback.optString("_phoneEpoch"), phoneId, session.optString("occurrenceId"));
                    if (token != null) silenced = silenceLinkedLocked(c, phoneId, token);
                }
            } catch (Exception ignored) { }
        }
        if (silenced) localChanged(c);
    }
    /** User dismissal of a live mirror: silence locally, then try the phone separately. */
    static boolean dismiss(Context context, long id, String token) {
        return dismiss(context, id, token, false);
    }
    static boolean dismissFromShake(Context context, long id, String token) {
        return dismiss(context, id, token, true);
    }
    private static boolean dismiss(Context context, long id, String token, boolean requireNoLocal) {
        if (id <= 0 || token == null) return false;
        Context c = context.getApplicationContext();
        PhoneDismissalJob.beforeCommit(c);
        JSONObject identity, result;
        synchronized (LOCK) {
            try {
                synchronized (PhoneSync.class) {
                    identity = PhoneSync.mirrorIdentity(c, id, token);
                    if (identity == null) return false;
                    if (requireNoLocal) {
                        // Preserve shake's local-first admission without taking
                        // the store lock before mirror/sync locks.
                        synchronized (AlarmStore.class) {
                            if (AlarmStore.active(c).length() != 0) return false;
                            JSONObject first = null;
                            for (JSONObject candidate : liveLocked(c, PhoneSync.cached(c), records(c)))
                                if (first == null || candidate.optLong("id") < first.optLong("id")) first = candidate;
                            if (first == null || first.optLong("id") != id
                                    || !token.equals(first.optString("token"))) return false;
                            result=silenceLocked(c, identity);
                        }
                    } else result=silenceLocked(c, identity);
                    if (result==null) return false;
                }
            } catch (Exception invalid) { return false; }
        }
        if (result.optBoolean("fallbackChanged")) {
            try { AlarmStore.finishMirrorFallbackEffects(c, result); }
            catch (Exception failure) { android.util.Log.w("PhoneMirror", "Fallback cleanup deferred", failure); }
            try { AlarmStore.refreshService(c); } catch (RuntimeException ignored) { }
            WearSync.afterCommit(c);
            WearSync.afterPhoneFallbackOccurrence(c);
        }
        PhoneDismissalJob.schedule(c);
        if (!result.optBoolean("queued") && !result.optBoolean("duplicate")) {
            android.util.Log.w("PhoneMirror", "Could not queue phone dismissal");
            try { AlarmNotifications.warning(c, "Mirror silenced; phone dismissal could not be queued."); }
            catch (RuntimeException ignored) { }
        }
        localChanged(c);
        return true;
    }
    static void acknowledged(Context c, long id, String token) {
        synchronized (LOCK) {
            suppressedInProcess.add(id + ":" + token);
            try {
                JSONArray state = records(c); JSONObject r = record(state, token);
                if (r != null && r.optLong("id") == id) {
                    r.put("closed", true);
                    save(c, state); // Memory still suppresses this token if persistence fails.
                    cancel(c, r);
                }
            } catch (Exception ignored) { }
        }
        try { c.startService(new Intent(c, PhoneMirrorService.class)); } catch (RuntimeException ignored) { }
        c.sendBroadcast(new Intent("com.openclock.PHONE_MIRROR_CHANGED").setPackage(c.getPackageName()));
        PhoneSync.requestSnapshot(c);
    }
    static boolean start(Context c) {
        try { c.startForegroundService(new Intent(c, PhoneMirrorService.class)); return true; }
        catch (RuntimeException blocked) { fallback(c); return false; }
    }
    // Android may reject a background foreground-service start. A separate
    // channel supplies one bounded platform alert instead of silent failure.
    static void fallback(Context c) {
        synchronized (LOCK) {
            try {
                if (!notificationsAllowed(c)) return;
                JSONArray state = records(c);
                List<JSONObject> alerts = new ArrayList<>();
                for (JSONObject a : liveLocked(c, PhoneSync.cached(c), state)) {
                    JSONObject r = record(state, a.getString("token"));
                    if (r != null && !r.optBoolean("fallbackPosted") &&
                            (a.optBoolean("sound") || a.optBoolean("vibrate"))) {
                        r.put("fallbackPosted", true); alerts.add(a);
                    }
                }
                if (!alerts.isEmpty() && save(c, state))
                    for (JSONObject a : alerts) postFallback(c, a);
            } catch (Exception ignored) { }
        }
    }
    private static void postFallback(Context c, JSONObject a) {
        long remaining = remaining(c, a);
        if (remaining <= 0 || !notificationsAllowed(c)) return;
        try {
            boolean sound = a.optBoolean("sound"), vibration = a.optBoolean("vibrate");
            String channel = "phone_mirror_fallback_" + (sound ? "sound" : "silent") + (vibration ? "_vibrate" : "_still");
            NotificationChannel ch = new NotificationChannel(channel, "Phone alarm fallback", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("One alert when continuous phone-alarm playback cannot start in the background.");
            ch.setSound(sound ? android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI : null,
                    new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ch.enableVibration(vibration);
            if (vibration) ch.setVibrationPattern(new long[]{0, 600, 400, 600});
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            // Only notification channels can request priority-mode bypass; direct
            // MediaPlayer/Vibrator output remains subject to Android's DND policy.
            if (nm.isNotificationPolicyAccessGranted()) ch.setBypassDnd(true);
            nm.createNotificationChannel(ch);
            NotificationChannel actual = nm.getNotificationChannel(channel);
            // User-edited channels may enable an output disabled on this occurrence.
            // The regular silent control notification already exists; skip this
            // fallback rather than use the now-incompatible channel.
            if (actual == null || (!sound && actual.getSound() != null) || (!vibration && actual.shouldVibrate())) return;
            PendingIntent open = open(c, a);
            String label = a.optString("label").trim(); if (label.isEmpty()) label = "Phone alarm";
            Notification.Builder b = new Notification.Builder(c, channel).setSmallIcon(R.drawable.ic_alarm)
                    .setContentTitle(label).setContentText("Phone alarm · Tap for controls")
                    .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOnlyAlertOnce(true).setContentIntent(open).setTimeoutAfter(remaining)
                    .setDeleteIntent(notificationAction(c, a, "fallback_delete"))
                    .addAction(new Notification.Action.Builder(null, "Dismiss", notificationAction(c, a, "dismiss")).build());
            nm.notify(tag(a) + ":fallback", ALERT_ID, b.build());
        } catch (RuntimeException ignored) { }
    }
    static void channels(Context c) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Phone alarms", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Phone alarm controls; sound and vibration follow the phone alarm and Do Not Disturb.");
        ch.setSound(null, null); ch.enableVibration(false); ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.isNotificationPolicyAccessGranted()) ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }
    static boolean notificationsAllowed(Context c) {
        try {
            channels(c);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL);
            return nm.areNotificationsEnabled() && channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        } catch (RuntimeException denied) { return false; }
    }
    static Intent openIntent(Context c, JSONObject a) {
        return new Intent(c, PhoneMirrorActivity.class)
                .setData(Uri.parse("openclock://phone-ring/" + a.optLong("id") + "/" + a.optString("token")))
                .putExtra("id", a.optLong("id")).putExtra("token", a.optString("token"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }
    static PendingIntent open(Context c, JSONObject a) {
        return PendingIntent.getActivity(c, 0, openIntent(c, a), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static PendingIntent notificationAction(Context c, JSONObject a, String source) {
        String action = "dismiss".equals(source) ? "com.openclock.MIRROR_DISMISS"
                : "fallback_delete".equals(source) ? "com.openclock.MIRROR_FALLBACK_DELETE"
                : "com.openclock.MIRROR_DELETE";
        Intent intent = new Intent(c, MirrorNotificationReceiver.class).setAction(action)
                .setData(Uri.parse("openclock://mirror-action/" + a.optLong("id") + "/" + a.optString("token") + "/" + source))
                .putExtra("id", a.optLong("id")).putExtra("token", a.optString("token"));
        return PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static void post(Context c, JSONObject a, long remaining) {
        if (remaining <= 0) return;
        try {
            channels(c); PendingIntent open = open(c, a);
            String label = a.optString("label").trim(); if (label.isEmpty()) label = "Phone alarm";
            Notification.Builder b = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_alarm)
                    .setContentTitle(label).setContentText("Phone alarm · Tap for controls")
                    .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).setTimeoutAfter(remaining)
                    .setDeleteIntent(notificationAction(c, a, "delete"))
                    .addAction(new Notification.Action.Builder(null, "Dismiss", notificationAction(c, a, "dismiss")).build());
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.notify(tag(a), ALERT_ID, b.build());
        } catch (RuntimeException ignored) { /* notification permission/channel may be blocked */ }
    }
    static void cancel(Context c, JSONObject a) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.cancel(tag(a), ALERT_ID); nm.cancel(tag(a) + ":fallback", ALERT_ID);
        }
        catch (RuntimeException ignored) { }
    }
    static Notification summary(Context c, List<JSONObject> live) {
        channels(c);
        Notification.Builder b = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle("Phone alarms")
                .setContentText("Phone alarm ringing · Tap for controls")
                .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true);
        if (!live.isEmpty()) b.setContentIntent(open(c, live.get(0)));
        return b.build();
    }
    private PhoneMirror() { }
}
