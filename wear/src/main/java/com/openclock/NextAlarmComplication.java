package com.openclock;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.UserManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import androidx.wear.watchface.complications.data.*;
import androidx.wear.watchface.complications.datasource.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Read committed state only; never start phone discovery from a provider request. */
public final class NextAlarmComplication extends ComplicationDataSourceService {
    private static final ExecutorService READS = Executors.newSingleThreadExecutor();
    private static final int DOT_COLOR = 0xffb995ff;

    @Override public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        ComplicationType type = request.getComplicationType();
        if (type != ComplicationType.SHORT_TEXT && type != ComplicationType.LONG_TEXT) {
            try { listener.onComplicationData(null); } catch (android.os.RemoteException ignored) { }
            return;
        }
        READS.execute(() -> {
            long now = System.currentTimeMillis();
            ComplicationPolicy.Selection result = new ComplicationPolicy.Selection(); long certainUntil = 0;
            try {
                JSONObject local = AlarmStore.wearSnapshot(this);
                result.rows("watch", local.getJSONArray("alarms"), local.getJSONArray("active"), now);
            } catch (Exception e) { result.uncertain = true; }
            try {
                UserManager users = getSystemService(UserManager.class);
                JSONObject phone = users != null && users.isUserUnlocked() ? PhoneSync.complicationSnapshot(this) : null;
                if (phone == null) throw new IllegalStateException();
                long remaining = ComplicationPolicy.certainFor(now, phone.getLong("updatedAt"),
                        phone.getLong("connectedForMillis"), phone.optBoolean("complicationUnresolved", true),
                        phone.optString("epoch"), phone.optLong("revision", -1));
                if (remaining == 0) result.uncertain = true;
                else certainUntil = now + remaining;
                result.rows("phone", phone.getJSONArray("alarms"), phone.getJSONArray("active"), now);
            } catch (Exception e) { result.uncertain = true; }
            ComplicationDisplay display = display();
            // Old watch faces display only the default: always mark unverified state.
            ComplicationData fallback = data(type, Collections.emptyList(), true, now, display);
            ArrayList<TimelineEntry> entries = new ArrayList<>();
            for (ComplicationPolicy.Segment segment : ComplicationPolicy.timeline(result, now, certainUntil)) {
                entries.add(new TimelineEntry(new TimeInterval(Instant.ofEpochMilli(segment.start), Instant.ofEpochMilli(segment.end)),
                        data(type, segment.times, segment.uncertain, segment.start, display)));
            }
            try { listener.onComplicationDataTimeline(new ComplicationDataTimeline(fallback, entries)); }
            catch (android.os.RemoteException ignored) { }
        });
    }

    private PendingIntent open() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_OPEN_COMPLICATION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private ComplicationDisplay display() {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        boolean use24 = android.text.format.DateFormat.is24HourFormat(this);
        return new ComplicationDisplay(locale, TimeZone.getDefault(),
                android.text.format.DateFormat.getBestDateTimePattern(locale, use24 ? "Hm" : "hm"),
                android.text.format.DateFormat.getBestDateTimePattern(locale, "Md"),
                android.text.format.DateFormat.getBestDateTimePattern(locale, "Hm"));
    }
    private static ComplicationText plain(CharSequence text) {
        return new PlainComplicationText.Builder(text).build();
    }
    private static CharSequence coloredDot(String text) {
        SpannableString styled = new SpannableString(text);
        for (int index = text.indexOf('•'); index >= 0; index = text.indexOf('•', index + 1))
            styled.setSpan(new ForegroundColorSpan(DOT_COLOR), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }
    private ComplicationData data(ComplicationType type, List<Long> times, boolean uncertain, long now, ComplicationDisplay display) {
        ComplicationText description = plain(display.description(times, uncertain, now));
        if (type == ComplicationType.LONG_TEXT) {
            String text;
            if (times.isEmpty()) text = uncertain ? "Check" : "No alarms";
            else {
                StringBuilder items = new StringBuilder(uncertain ? "?" : "");
                for (int i = 0; i < Math.min(times.size(), ComplicationPolicy.DISPLAY_LIMIT); i++) {
                    if (i > 0) items.append(" • ");
                    items.append(display.item(times.get(i), now));
                }
                text = items.toString();
            }
            return new LongTextComplicationData.Builder(plain(coloredDot(text)), description).setTapAction(open()).build();
        }
        ComplicationDisplay.ShortFields fields = display.shortFields(times, uncertain, now);
        ShortTextComplicationData.Builder shortData = new ShortTextComplicationData.Builder(
                plain(fields.dot ? coloredDot(fields.text) : fields.text), description).setTapAction(open());
        if (fields.title != null) shortData.setTitle(plain(fields.title));
        return shortData.build();
    }
    @Override public ComplicationData getPreviewData(ComplicationType type) {
        if (type != ComplicationType.SHORT_TEXT && type != ComplicationType.LONG_TEXT) return null;
        long now = System.currentTimeMillis();
        return data(type, java.util.Arrays.asList(now + 3_600_000, now + 7_200_000, now + 10_800_000), false, now, display());
    }
}
