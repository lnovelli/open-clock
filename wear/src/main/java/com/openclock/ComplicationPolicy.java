package com.openclock;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure selection and timeline rules, shared with the host probes. */
final class ComplicationPolicy {
    static final long FRESH_MS = 5 * 60_000L;
    static final int DISPLAY_LIMIT = 3;

    static final class Selection {
        // Bound timeline size. IDs belong to a source, not a global ID namespace.
        private final java.util.Map<String, Long> candidates = new java.util.HashMap<>();
        boolean uncertain;
        void rows(String source, JSONArray alarms, JSONArray active, long now) throws Exception {
            if (alarms == null || active == null) throw new IllegalStateException();
            if (active.length() > 0) uncertain = true;
            for (int i = 0; i < alarms.length(); i++) {
                JSONObject row = alarms.getJSONObject(i);
                if (!(row.get("enabled") instanceof Boolean)) throw new IllegalStateException();
                if (!row.getBoolean("enabled")) continue;
                if (!(row.get("id") instanceof Number) || !(row.get("nextTriggerAt") instanceof Number)
                        || !(row.get("snoozedUntil") instanceof Number) || !row.has("schedulingError"))
                    throw new IllegalStateException();
                long id = row.getLong("id");
                boolean ringing = false;
                for (int j = 0; j < active.length(); j++)
                    if (active.getJSONObject(j).getLong("id") == id) ringing = true;
                if (!row.isNull("schedulingError")) uncertain = true;
                long next = row.getLong("nextTriggerAt"), snooze = row.getLong("snoozedUntil");
                long candidate = Long.MAX_VALUE;
                if (next > now) candidate = next;
                else if (next > 0) uncertain = true;
                if (snooze > now) candidate = Math.min(candidate, snooze);
                else if (snooze > 0) uncertain = true;
                if (next <= 0 && snooze <= 0 && !ringing) uncertain = true;
                if (candidate != Long.MAX_VALUE) {
                    String key = source + ":" + id;
                    candidates.merge(key, candidate, Math::min);
                }
            }
        }
        java.util.List<Long> times() {
            java.util.ArrayList<Long> out = new java.util.ArrayList<>(candidates.values());
            java.util.Collections.sort(out);
            return out.size() > 8 ? new java.util.ArrayList<>(out.subList(0, 8)) : out;
        }
        String firstKey() {
            String best = null; long earliest = Long.MAX_VALUE;
            for (java.util.Map.Entry<String, Long> entry : candidates.entrySet()) {
                long at = entry.getValue();
                if (at < earliest || (at == earliest && (best == null || entry.getKey().compareTo(best) < 0))) {
                    earliest = at; best = entry.getKey();
                }
            }
            return best;
        }
    }

    // Phone timestamps bound the claim even after a successful local read; skew fails closed.
    static long certainFor(long now, long updated, long confirmedFor, boolean unresolved,
                           String epoch, long revision) {
        if (unresolved || epoch == null || epoch.isEmpty() || revision < 0 || updated <= 0
                || updated > now || now - updated >= FRESH_MS || confirmedFor <= 0) return 0;
        return Math.min(confirmedFor, FRESH_MS - (now - updated));
    }

    static final long DAY = 24 * 60 * 60_000L;
    static final class Segment {
        final long start, end;
        final java.util.List<Long> times;
        final boolean uncertain;
        Segment(long start, long end, java.util.List<Long> times, boolean uncertain) {
            this.start = start; this.end = end; this.times = times; this.uncertain = uncertain;
        }
    }
    // The AndroidX timeline transport serializes whole epoch seconds. Round
    // expiry down and the 24h transition up so rounding never overstates certainty.
    private static long floorSecond(long value) { return Math.floorDiv(value, 1000) * 1000; }
    private static long ceilSecond(long value) { return floorSecond(value - 1) + 1000; }
    static java.util.List<Segment> timeline(Selection selection, long now, long certainUntil) {
        java.util.List<Long> candidates = selection.times();
        long confidenceEnd = floorSecond(certainUntil);
        java.util.TreeSet<Long> points = new java.util.TreeSet<>();
        points.add(now);
        if (!selection.uncertain && confidenceEnd > now) points.add(confidenceEnd);
        for (long at : candidates) {
            long expiry = floorSecond(at);
            if (expiry > now) points.add(expiry);
            // At exactly 24h the time alone is sufficient.
            long threshold = ceilSecond(at - DAY);
            if (threshold > now && threshold < expiry) points.add(threshold);
        }
        java.util.ArrayList<Long> boundaries = new java.util.ArrayList<>(points);
        java.util.ArrayList<Segment> segments = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < boundaries.size(); i++) {
            long start = boundaries.get(i), end = boundaries.get(i + 1);
            boolean uncertain = selection.uncertain || start >= confidenceEnd
                    || (!candidates.isEmpty() && start >= floorSecond(candidates.get(0)));
            java.util.ArrayList<Long> remaining = new java.util.ArrayList<>(DISPLAY_LIMIT);
            for (long at : candidates) if (floorSecond(at) > start && remaining.size() < DISPLAY_LIMIT) remaining.add(at);
            // Empty and uncertain uses the permanent conservative fallback.
            if (!remaining.isEmpty() || !uncertain) segments.add(new Segment(start, end, remaining, uncertain));
        }
        return segments;
    }
    static boolean commandCovered(JSONObject barrier, String owner, JSONObject snapshot) {
        if (owner.isEmpty() || !owner.equals(barrier.optString("node"))
                || !snapshot.optString("epoch").equals(barrier.optString("epoch"))) return false;
        if (!barrier.optBoolean("overlap") && barrier.optBoolean("ack")
                && snapshot.optLong("revision",-1)>=barrier.optLong("committedRevision",Long.MAX_VALUE)) return true;
        // The phone rejects these commands after 15 s. A later phone-authored
        // snapshot safely covers even an unknown outcome after the bounded
        // transport window; an older snapshot cannot resolve uncertainty.
        long after = barrier.optLong("safeAfter", Long.MAX_VALUE);
        return after > 0 && snapshot.optLong("updatedAt", 0) > after;
    }

    private ComplicationPolicy() { }
}
