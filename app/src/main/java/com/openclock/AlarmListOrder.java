package com.openclock;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Presentation order only; never changes persisted alarm or scheduling order. */
final class AlarmListOrder {
    static List<JSONObject> byTime(JSONArray alarms) {
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < alarms.length(); i++) {
            JSONObject row = alarms.optJSONObject(i);
            if (row != null) rows.add(row);
        }
        // Stable sort preserves the original order for alarms at the same time.
        Collections.sort(rows, (a, b) -> {
            int hour = Integer.compare(a.optInt("hour"), b.optInt("hour"));
            return hour != 0 ? hour : Integer.compare(a.optInt("minute"), b.optInt("minute"));
        });
        return rows;
    }

    private AlarmListOrder() {}
}
