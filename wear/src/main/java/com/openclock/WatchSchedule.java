package com.openclock;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Join accepted phone metadata to committed watch registrations, not requested times. */
final class WatchSchedule {
    static final class View {
        final JSONArray phone=new JSONArray(), retained=new JSONArray();
        boolean uncertain;
    }
    static View read(Context c,JSONObject snapshot) throws Exception {
        View view=new View(); JSONArray fallback=AlarmStore.phoneFallbackAlarms(c);
        JSONArray source=snapshot==null?null:snapshot.optJSONArray("alarms"); Set<Long> used=new HashSet<>();
        if(source==null) { source=new JSONArray(); view.uncertain=true; }
        for(int i=0;i<source.length();i++) {
            JSONObject row=new JSONObject(source.getJSONObject(i).toString()), match=null;
            for(int j=0;j<fallback.length();j++) {
                JSONObject candidate=fallback.getJSONObject(j);
                if(candidate.optLong("phoneId")==row.optLong("id")&&candidate.optString("phoneEpoch").equals(snapshot.optString("epoch"))
                    &&PhoneSync.fallbackOwner(c,candidate.optString("phoneNode"),candidate.optString("phoneEpoch"))) {
                    if(match!=null) { view.uncertain=true; match=null; break; } match=candidate;
                }
            }
            if(match!=null) {
                used.add(match.getLong("id"));
                row.put("nextTriggerAt",match.getLong("nextTriggerAt")).put("snoozedUntil",match.getLong("snoozedUntil"))
                    .put("schedulingError",match.get("schedulingError")).put("uncertain",match.optBoolean("uncertain"))
                    .put("fallbackRinging",match.optBoolean("ringing"));
                if(!row.optBoolean("enabled")&&(match.optLong("nextTriggerAt")>0||match.optBoolean("ringing"))) {
                    row.put("uncertain",true).put("fallbackNotice","Phone disabled; retained due/ringing fallback is unresolved");
                    // Phone editing controls retain authoritative enabled state;
                    // the separate retained candidate represents only watch delivery.
                    view.retained.put(new JSONObject(match.toString()).put("uncertain",true));
                } else row.put("fallbackNotice","Watch fallback: "+(match.optBoolean("ringing")?"ringing":match.optBoolean("uncertain")?"uncertain":"committed"));
            } else {
                row.put("nextTriggerAt",0).put("snoozedUntil",0);
                if(row.optBoolean("enabled")) row.put("schedulingError","Watch fallback not committed; phone state is advisory");
            }
            if(row.optBoolean("uncertain")||!row.isNull("schedulingError")) view.uncertain=true;
            view.phone.put(row);
        }
        for(int i=0;i<fallback.length();i++) {
            JSONObject row=fallback.getJSONObject(i);
            if(!used.contains(row.getLong("id"))) { row.put("uncertain",true); view.retained.put(row); view.uncertain=true; }
        }
        if(AlarmStore.phoneFallbackProblem(c)!=null) view.uncertain=true;
        JSONArray pending=AlarmStore.watchSnoozeRequests(c);
        for(int i=0;i<pending.length();i++) {
            JSONObject r=pending.getJSONObject(i);
            if("pending".equals(r.optString("state"))||("branched".equals(r.optString("state"))&&!r.has("receipt"))) view.uncertain=true;
        }
        return view;
    }
    static void select(ComplicationPolicy.Selection selection,View view,long now) throws Exception {
        selection.uncertain|=view.uncertain;
        selection.rows("phone",view.phone,new JSONArray(),now);
        selection.rows("fallback",view.retained,new JSONArray(),now);
    }
    private WatchSchedule() { }
}
