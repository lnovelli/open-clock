package com.openclock;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

/** Wall-clock schedules follow the current device zone, not the zone at creation.
 * DST gaps: reject explicit dates, skip that day for rolling/weekly schedules.
 * DST overlap: first occurrence still in the future (either offset).
 */
final class AlarmTimes {
    static LocalDate parseDate(String value) {
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Date must be yyyy-MM-dd");
        LocalDate date=LocalDate.parse(value, DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT));
        if(date.getYear()<1) throw new IllegalArgumentException("Date year must be between 0001 and 9999");
        return date;
    }
    static long next(int hour, int minute, String date, Set<Integer> days, long now, ZoneId zone) {
        LocalDate start = date == null ? Instant.ofEpochMilli(now).atZone(zone).toLocalDate() : parseDate(date);
        for (int i = 0; i < (date == null ? 370 : 1); i++) {
            LocalDate day = start.plusDays(i);
            int calendarDay = day.getDayOfWeek().getValue() % 7 + 1;
            if (!days.isEmpty() && !days.contains(calendarDay)) continue;
            LocalDateTime local = day.atTime(hour, minute);
            List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
            if (offsets.isEmpty() && date != null) throw new IllegalArgumentException("That local time does not exist due to daylight saving time");
            long best = Long.MAX_VALUE;
            for (ZoneOffset offset : offsets) {
                long candidate = local.toInstant(offset).toEpochMilli();
                if (candidate > now && candidate < best) best = candidate;
            }
            if (best != Long.MAX_VALUE) return best;
        }
        throw new IllegalArgumentException("Alarm date and time must be in the future");
    }
}
