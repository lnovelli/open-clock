package com.openclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Detached presentation inputs. Only loaded on IO; never acquire engine/sync locks in rendering. */
final class WatchUiData {
    JSONObject phone = new JSONObject(), creation, mutation;
    JSONArray alarms = new JSONArray(), active = new JSONArray(), fallback = new JSONArray(), snoozes = new JSONArray();
    List<JSONObject> orderedAlarms = new ArrayList<>();
    List<JSONObject> mirrors = new ArrayList<>();
    WatchSchedule.View schedule = new WatchSchedule.View();
    final java.util.Map<Long, List<JSONObject>> grouped = new java.util.HashMap<>();
    final List<JSONObject> other = new ArrayList<>();
    String fallbackProblem = "", accessProblem, nextKey, key = "loading";
    boolean available;
    static WatchUiData read(Context c) throws Exception { return read(c, false); }
    static WatchUiData read(Context c, boolean finishing) throws Exception {
        WatchUiData d = new WatchUiData();
        AlarmStore.WatchUiSnapshot local;
        boolean accepted;
        java.util.Set<JSONObject> owners = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        long phoneWait = SystemClock.elapsedRealtime();
        synchronized (PhoneSync.class) {
          WatchUiTiming.phoneLock(SystemClock.elapsedRealtime() - phoneWait);
          long phoneStart = SystemClock.elapsedRealtime();
          long watchTime = 0;
          try {
            d.phone = new JSONObject(PhoneSync.cached(c).toString());
            d.creation = PhoneSync.pendingCreate(c); d.mutation = PhoneSync.pendingMutation(c);
            JSONObject m = d.mutation;
            if ((finishing || !WatchUiWork.inFlight()) && m != null && "committed".equals(m.optString("status"))) {
                String epoch = d.phone.optString("epoch");
                boolean seen = !epoch.isEmpty() && epoch.equals(m.optString("epoch"))
                    && m.optLong("revision", -1) >= 0 && d.phone.optLong("revision", -1) >= m.optLong("revision");
                boolean replaced = !epoch.isEmpty() && !epoch.equals(m.optString("epoch"));
                if (seen || replaced) { PhoneSync.forgetMutation(c, m.optString("requestId")); d.mutation = PhoneSync.pendingMutation(c); }
            }
            // PhoneSync -> AlarmStore is the existing sync lock order. Capture
            // both generations before another phone publication can replace either.
            long watchWait = SystemClock.elapsedRealtime();
            try { synchronized (AlarmStore.class) {
                WatchUiTiming.watchLock(SystemClock.elapsedRealtime() - watchWait);
                long watchStart = SystemClock.elapsedRealtime();
                try {
                    local = AlarmStore.watchUiSnapshot(c);
                    d.fallbackProblem = fallbackProblem(c, local.fallbackProblem);
                } finally { WatchUiTiming.watchRead(SystemClock.elapsedRealtime() - watchStart); }
            } } finally { watchTime = SystemClock.elapsedRealtime() - watchWait; }
            // Recovery may have committed a newer phone generation without
            // updating the credential cache. Never join it to stale metadata.
            accepted = local.accepted(d.phone) && PhoneSync.fallbackOwner(c, local.phoneNode, local.phoneEpoch);
            for (int i = 0; i < local.fallback.length(); i++) {
                JSONObject row = local.fallback.getJSONObject(i);
                if (accepted && PhoneSync.fallbackOwner(c, row.optString("phoneNode"), row.optString("phoneEpoch"))) owners.add(row);
            }
          // Exclude the nested watch monitor wait/read so the phases do not overlap.
          } finally { WatchUiTiming.phoneRead(Math.max(0, SystemClock.elapsedRealtime() - phoneStart - watchTime)); }
        }
        d.alarms = local.alarms; d.active = local.active;
        d.orderedAlarms = AlarmListOrder.byTime(d.alarms);
        d.fallback = local.fallback; d.snoozes = local.snoozes;
        try { PhoneMirror.channels(c); } catch (RuntimeException unavailable) { /* Settings UI reports the channel state. */ }
        d.mirrors = PhoneMirror.live(c);
        d.schedule = WatchSchedule.read(c, d.phone, d.fallback, d.snoozes, local.fallbackProblem, owners);
        if (!accepted) d.schedule.uncertain = true;
        JSONArray groups = d.phone.optJSONArray("groups");
        if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group != null && group.optLong("id", -1) > 0) d.grouped.put(group.optLong("id"), new ArrayList<>());
        }
        for (JSONObject row : AlarmListOrder.byTime(d.schedule.phone)) {
            List<JSONObject> group = row.isNull("groupId") ? null : d.grouped.get(row.optLong("groupId", -1));
            if (group == null) d.other.add(row); else group.add(row);
        }
        d.accessProblem = local.accessProblem;
        ComplicationPolicy.Selection selection = new ComplicationPolicy.Selection();
        long now = System.currentTimeMillis();
        selection.rows("watch", local.selectionAlarms, local.selectionActive, now);
        WatchSchedule.select(selection, d.schedule, now); d.nextKey = selection.firstKey();
        JSONObject display = new JSONObject(d.phone.toString());
        long updated = display.optLong("updatedAt"), age = now - updated;
        display.remove("updatedAt"); display.remove("revision");
        String fingerprint = new JSONObject().put("phone", display).put("watch", d.alarms).put("active", d.active)
            .put("fallback", d.fallback).put("snoozes", d.snoozes).put("mirrors", new JSONArray(d.mirrors))
            .put("creation", d.creation).put("mutation", d.mutation).put("createObserved", observed(d.creation, d.phone))
            .put("mutationObserved", observed(d.mutation, d.phone)).put("joinAccepted", accepted).put("access", d.accessProblem)
            .put("problem", d.fallbackProblem).put("fresh", updated > 0 && age >= 0 && age <= 300_000).toString();
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder(64);
        for (byte value : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        d.key = key.toString();
        d.available = true;
        return d;
    }
    // Same status rules as PhoneFallback.problem, using the already-read journal.
    // Reconciliation writes these preferences while holding AlarmStore.class.
    private static String fallbackProblem(Context c, String journalProblem) {
        if (journalProblem != null) return journalProblem;
        SharedPreferences status = c.createDeviceProtectedStorageContext().getSharedPreferences("phone-fallback-status-v1", Context.MODE_PRIVATE);
        String saved = status.getString("problem", "");
        if (!saved.isEmpty() && !status.getBoolean("journalProblem", false)) return saved;
        if (!status.getBoolean("initialized", false)) return "No phone snapshot scheduled on watch yet";
        return "";
    }
    private static boolean observed(JSONObject receipt, JSONObject phone) {
        return receipt != null && !receipt.optString("epoch").isEmpty() && receipt.optString("epoch").equals(phone.optString("epoch"))
            && receipt.optLong("revision", -1) >= 0 && phone.optLong("revision", -1) >= receipt.optLong("revision");
    }
}
