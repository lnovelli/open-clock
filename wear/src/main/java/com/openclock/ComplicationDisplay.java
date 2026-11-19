package com.openclock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Formatting policy independent of Android rendering, so the real policy is host-probeable. */
final class ComplicationDisplay {
    final Locale locale;
    final TimeZone zone;
    final String timePattern, datePattern, short24Pattern;

    ComplicationDisplay(Locale locale, TimeZone zone, String timePattern, String datePattern, String short24Pattern) {
        this.locale = locale; this.zone = zone; this.timePattern = timePattern;
        this.datePattern = datePattern; this.short24Pattern = short24Pattern;
    }
    private String format(String pattern, long at) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, locale);
        formatter.setTimeZone(zone);
        return formatter.format(new Date(at));
    }
    private static String compact(String input) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) out.append(c);
        }
        return out.toString();
    }
    String time(long at) { return compact(format(timePattern, at)); }
    String date(long at) { return compact(format(datePattern, at)); }
    boolean distant(long at, long now) { return at - now > ComplicationPolicy.DAY; }
    String item(long at, long now) { return (distant(at, now) ? date(at) + " " : "") + time(at); }
    String description(List<Long> times, boolean uncertain, long now) {
        if (times.isEmpty()) return uncertain ? "Alarm state unknown; open Open Clock" : "No alarms";
        StringBuilder out = new StringBuilder(uncertain ? "Possibly " : "Alarms: ");
        for (long at : times) {
            if (out.length() > (uncertain ? 9 : 8)) out.append(", ");
            // Include the date even for near alarms, for unambiguous accessibility.
            out.append(date(at)).append(' ').append(time(at));
        }
        if (uncertain) out.append("; phone or alarm state unverified");
        return out.toString();
    }
    String items(List<Long> times, long now) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(times.size(), ComplicationPolicy.DISPLAY_LIMIT); i++) {
            if (i > 0) text.append("  •  ");
            text.append(item(times.get(i), now));
        }
        return text.toString();
    }
    private String shortTime(long at, int max) {
        String preferred = time(at);
        if (preferred.length() <= max) return preferred;
        // A 12-hour locale's AM/PM marker may not fit. Explicit 24h remains unambiguous.
        String fallback = compact(format(short24Pattern, at));
        return fallback.length() <= max ? fallback : null;
    }
    String shortText(List<Long> times, boolean uncertain, long now) {
        if (times.isEmpty()) return uncertain ? "Check" : "None";
        long first = times.get(0);
        if (times.size() > 1) return items(times, now);
        String time = shortTime(first, 7);
        if (time == null) return "Check";
        // Keep date and time together when no title is supplied; faces may truncate.
        return distant(first, now) ? date(first) + " " + time : time;
    }
}
