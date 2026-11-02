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
    static final class ShortFields {
        final String text, title;
        final boolean dot;
        ShortFields(String text, String title, boolean dot) { this.text = text; this.title = title; this.dot = dot; }
    }
    private String shortTime(long at, int max) {
        String preferred = time(at);
        if (preferred.length() <= max) return preferred;
        // A 12-hour locale's AM/PM marker may not fit. Explicit 24h remains unambiguous.
        String fallback = compact(format(short24Pattern, at));
        return fallback.length() <= max ? fallback : null;
    }
    ShortFields shortFields(List<Long> times, boolean uncertain, long now) {
        if (times.isEmpty()) return new ShortFields(uncertain ? "Check" : "No", uncertain ? null : "alarms", false);
        long first = times.get(0);
        String marker = uncertain ? "?" : "";
        // Edge faces sometimes ignore the optional title completely. Keep all
        // alarms in the required text even though this exceeds the platform's
        // seven-character short-text guidance; constrained faces may truncate.
        if (times.size() > 1) {
            StringBuilder text = new StringBuilder(marker);
            for (int i = 0; i < Math.min(times.size(), ComplicationPolicy.DISPLAY_LIMIT); i++) {
                if (i > 0) text.append('•');
                text.append(item(times.get(i), now));
            }
            return new ShortFields(text.toString(), null, true);
        }
        if (distant(first, now)) {
            String date = date(first);
            String time = shortTime(first, 7);
            // A face may omit the title. Showing the date in the required field avoids
            // presenting a distant alarm as an apparently imminent time.
            if ((marker + date).length() > 7 || time == null) return new ShortFields("Check", null, false);
            return new ShortFields(marker + date, time, false);
        }
        String time = shortTime(first, 7 - marker.length());
        if (time == null) return new ShortFields("Check", null, false);
        return new ShortFields(marker + time, null, false);
    }
}
