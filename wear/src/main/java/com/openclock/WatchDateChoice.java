package com.openclock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;

/** Calendar decisions for the watch editor; phone dates remain phone-owned. */
final class WatchDateChoice {
    static LocalDate resolve(int month, int day, int hour, int minute, long now, ZoneId zone) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        for (int year = today.getYear(); year <= today.getYear() + 8 && year <= 9999; year++) {
            LocalDate date;
            try { date = LocalDate.of(year, month, day); }
            catch (java.time.DateTimeException invalid) { continue; }
            if (date.isBefore(today)) continue;
            if (zone.getRules().getValidOffsets(date.atTime(hour, minute)).isEmpty())
                throw new IllegalArgumentException("That time doesn't exist on this date due to daylight saving time");
            try {
                AlarmTimes.next(hour, minute, date.toString(), Collections.emptySet(), now, zone);
                return date;
            } catch (IllegalArgumentException alreadyPassed) { /* Today's time has passed. */ }
        }
        throw new IllegalArgumentException("No future valid date");
    }

    static boolean needsConfirmation(LocalDate resolved, String chosen, long now, ZoneId zone) {
        return resolved.getYear() > Instant.ofEpochMilli(now).atZone(zone).getYear()
                || !resolved.toString().equals(chosen);
    }

    /** Advisory only: this is the watch's clock and zone, not the phone's. */
    static boolean appearsPast(String value, int hour, int minute, long now, ZoneId zone) {
        try {
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return false;
            LocalDate date = AlarmTimes.parseDate(value);
            java.time.LocalDateTime local = date.atTime(hour, minute);
            java.util.List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
            if (offsets.isEmpty()) return false; // A gap is not evidence that the date passed.
            for (ZoneOffset offset : offsets) if (local.toInstant(offset).toEpochMilli() > now) return false;
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    private WatchDateChoice() { }
}
