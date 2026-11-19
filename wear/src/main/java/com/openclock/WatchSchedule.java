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
        return join(c,snapshot,AlarmStore.phoneFallbackAlarms(c),null,null,null);
    }
    // Owners were checked under the same PhoneSync monitor as the phone and
    // store reads; never revalidate this detached generation against a newer cache.
    static View read(Context c,JSONObject snapshot,JSONArray fallback,JSONArray pending,
                     String problem,Set<JSONObject> owners) throws Exception {
        return join(c,snapshot,fallback,pending,problem,owners);
    }
    private static View join(Context c,JSONObject snapshot,JSONArray fallback,JSONArray pending,
                             String problem,Set<JSONObject> owners) throws Exception {
        View view=new View();
        JSONArray source=snapshot==null?null:snapshot.optJSONArray("alarms"); Set<Long> used=new HashSet<>();
        if(source==null) { source=new JSONArray(); view.uncertain=true; }
        for(int i=0;i<source.length();i++) {
            JSONObject row=new JSONObject(source.getJSONObject(i).toString()), match=null;
            for(int j=0;j<fallback.length();j++) {
                JSONObject candidate=fallback.getJSONObject(j);
                if(candidate.optLong("phoneId")==row.optLong("id")&&candidate.optString("phoneEpoch").equals(snapshot.optString("epoch"))
                    &&(owners==null?PhoneSync.fallbackOwner(c,candidate.optString("phoneNode"),candidate.optString("phoneEpoch")):owners.contains(candidate))) {
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
                } else if (match.optBoolean("uncertain")) row.put("fallbackNotice","Watch fallback: uncertain");
            } else {
                row.put("nextTriggerAt",0).put("snoozedUntil",0);
                if(row.optBoolean("enabled")) row.put("schedulingError","Watch fallback not committed; phone state is advisory");
            }
            if(row.optBoolean("uncertain")||!row.isNull("schedulingError")) view.uncertain=true;
            view.phone.put(row);
        }
        for(int i=0;i<fallback.length();i++) {
            JSONObject row=fallback.getJSONObject(i);
            if(!used.contains(row.getLong("id"))) {
                view.retained.put((owners==null?row:new JSONObject(row.toString())).put("uncertain",true)); view.uncertain=true;
            }
        }
        if((owners==null?AlarmStore.phoneFallbackProblem(c):problem)!=null) view.uncertain=true;
        if(owners==null) pending=AlarmStore.watchSnoozeRequests(c);
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
