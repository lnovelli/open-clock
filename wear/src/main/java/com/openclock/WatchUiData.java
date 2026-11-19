package com.openclock;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Detached presentation inputs. Only loaded on IO; never acquire engine/sync locks in rendering. */
final class WatchUiData {
    JSONObject phone = new JSONObject(), creation, mutation;
    JSONArray alarms = new JSONArray(), active = new JSONArray(), fallback = new JSONArray(), snoozes = new JSONArray();
    List<JSONObject> mirrors = new ArrayList<>();
    WatchSchedule.View schedule = new WatchSchedule.View();
    final java.util.Map<Long, List<JSONObject>> grouped = new java.util.HashMap<>();
    final List<JSONObject> other = new ArrayList<>();
    String fallbackProblem = "", accessProblem, nextKey, key = "loading";
    boolean available;
    static WatchUiData read(Context c) throws Exception { return read(c, false); }
    static WatchUiData read(Context c, boolean finishing) throws Exception {
        WatchUiData d = new WatchUiData();
        synchronized (PhoneSync.class) {
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
        }
        d.alarms = AlarmStore.alarms(c); d.active = AlarmStore.active(c);
        d.fallback = AlarmStore.phoneFallbackAlarms(c); d.snoozes = AlarmStore.watchSnoozeRequests(c);
        try { PhoneMirror.channels(c); } catch (RuntimeException unavailable) { /* Settings UI reports the channel state. */ }
        d.mirrors = PhoneMirror.live(c); d.schedule = WatchSchedule.read(c, d.phone);
        JSONArray groups = d.phone.optJSONArray("groups");
        if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group != null && group.optLong("id", -1) > 0) d.grouped.put(group.optLong("id"), new ArrayList<>());
        }
        for (int i = 0; i < d.schedule.phone.length(); i++) {
            JSONObject row = d.schedule.phone.getJSONObject(i);
            List<JSONObject> group = row.isNull("groupId") ? null : d.grouped.get(row.optLong("groupId", -1));
            if (group == null) d.other.add(row); else group.add(row);
        }
        d.fallbackProblem = PhoneFallback.problem(c); d.accessProblem = AlarmStore.accessProblem(c);
        ComplicationPolicy.Selection selection = new ComplicationPolicy.Selection();
        long now = System.currentTimeMillis();
        JSONObject local = AlarmStore.wearSnapshot(c);
        selection.rows("watch", local.getJSONArray("alarms"), local.getJSONArray("active"), now);
        WatchSchedule.select(selection, d.schedule, now); d.nextKey = selection.firstKey();
        JSONObject display = new JSONObject(d.phone.toString());
        long updated = display.optLong("updatedAt"), age = now - updated;
        display.remove("updatedAt"); display.remove("revision");
        String fingerprint = new JSONObject().put("phone", display).put("watch", d.alarms).put("active", d.active)
            .put("fallback", d.fallback).put("snoozes", d.snoozes).put("mirrors", new JSONArray(d.mirrors))
            .put("creation", d.creation).put("mutation", d.mutation).put("createObserved", observed(d.creation, d.phone))
            .put("mutationObserved", observed(d.mutation, d.phone)).put("access", d.accessProblem)
            .put("problem", d.fallbackProblem).put("fresh", updated > 0 && age >= 0 && age <= 300_000).toString();
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder(64);
        for (byte value : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        d.key = key.toString();
        d.available = true;
        return d;
    }
    private static boolean observed(JSONObject receipt, JSONObject phone) {
        return receipt != null && !receipt.optString("epoch").isEmpty() && receipt.optString("epoch").equals(phone.optString("epoch"))
            && receipt.optLong("revision", -1) >= 0 && phone.optLong("revision", -1) >= receipt.optLong("revision");
    }
}
