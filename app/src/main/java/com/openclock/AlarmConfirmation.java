package com.openclock;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formats the committed next trigger, not a separately calculated schedule. */
final class AlarmConfirmation {
    private static final long MINUTE = 60_000L, DAY = 24 * 60 * MINUTE;

    static String message(long triggerAt, long now, ZoneId zone, Locale locale) {
        if (triggerAt <= now) return null;
        long delay = triggerAt - now;
        if (delay < DAY) {
            // Round up partial minutes so an imminent alarm never appears overdue.
            long minutes = (delay + MINUTE - 1) / MINUTE;
            long hours = minutes / 60, remainder = minutes % 60;
            String duration = hours == 0 ? quantity(remainder, "minute")
                    : remainder == 0 ? quantity(hours, "hour")
                    : quantity(hours, "hour") + " and " + quantity(remainder, "minute");
            return "Alarm set for " + duration + " from now";
        }
        String date = DateTimeFormatter.ofPattern("HH:mm 'on' EEEE, MMMM d", locale)
                .format(Instant.ofEpochMilli(triggerAt).atZone(zone));
        return "Alarm set for " + date;
    }

    private static String quantity(long value, String unit) {
        return value + " " + unit + (value == 1 ? "" : "s");
    }
    private AlarmConfirmation() { }
}
