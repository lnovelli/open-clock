package com.openclock;

import org.json.*;
import java.util.*;

/** Private identity metadata. Never participates in PendingIntent or occurrence identity. */
final class AlarmIdentity {
    static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }
    static JSONObject origin(JSONObject value) throws Exception {
        if(value.length()!=2 || !(value.opt("source") instanceof String) || !(value.opt("reference") instanceof String))
            throw new IllegalArgumentException("Invalid origin");
        String source=value.getString("source"), reference=value.getString("reference");
        if(!source.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || !reference.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid origin");
        return new JSONObject().put("source",source).put("reference",reference);
    }
    static void fresh(JSONObject row) throws Exception {
        row.put("_uuid",UUID.randomUUID().toString()).put("_revision",1);
    }
    static void preserve(JSONObject old,JSONObject row) throws Exception {
        for(String key:new String[]{"_uuid","_revision","_origin","_identityUnknown"}) {
            row.remove(key); if(old.has(key)) row.put(key,old.get(key));
        }
    }
    /** Legacy phone peers do not supply a source UUID. Never invent one for them. */
    static void fallback(JSONObject row,JSONObject source) throws Exception {
        row.remove("_uuid"); row.remove("_origin"); row.put("_revision",1);
        Object id=source.opt("uuid");
        if(id instanceof String && uuid((String)id)) {
            row.put("_uuid",id); row.remove("_identityUnknown");
        } else if(source.has("uuid") && !source.isNull("uuid")) throw new IllegalArgumentException("Invalid source UUID");
        else row.put("_identityUnknown",true);
    }
    static boolean migrate(JSONObject state) throws Exception {
        boolean legacy=!state.has("_identityVersion");
        Object version=state.opt("_identityVersion");
        if(!legacy && (!(version instanceof Integer||version instanceof Long)||state.getLong("_identityVersion")!=2))
            throw new IllegalStateException("Unsupported identity storage");
        JSONArray rows=state.getJSONArray("alarms");
        if(legacy) {
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                if(row.has("_uuid")||row.has("_origin")||row.has("_revision")||row.has("_identityUnknown"))
                    throw new IllegalStateException("Partial identity migration");
                if(row.optBoolean("_phoneFallback")) {
                    JSONObject accepted=state.optJSONObject("_phoneAccepted"), source=null;
                    if(accepted!=null && row.optString("_phoneNode").equals(state.optString("_phoneNode"))
                            && row.optString("_phoneEpoch").equals(state.optString("_phoneEpoch")))
                        source=AlarmStore.find(accepted.getJSONArray("alarms"),row.optLong("_phoneId"));
                    fallback(row,source==null?new JSONObject():source);
                } else fresh(row);
            }
            state.put("_identityVersion",2);
        }
        validate(rows);
        boolean groupsMigrated=migrateGroups(state);
        Object next=state.opt("nextId");
        if(!(next instanceof Integer||next instanceof Long)||state.getLong("nextId")<1) throw new IllegalStateException("Invalid next ID");
        for(String list:new String[]{"alarms","groups"}) {
            JSONArray values=state.getJSONArray(list);
            for(int i=0;i<values.length();i++) if(values.getJSONObject(i).getLong("id")>=state.getLong("nextId"))
                throw new IllegalStateException("Invalid next ID");
        }
        AlarmV2.validateJournal(state);
        return legacy||groupsMigrated;
    }
    /** Upgrade existing groups without changing IDs, names, membership or scheduling. */
    private static boolean migrateGroups(JSONObject state) throws Exception {
        boolean legacy=!state.has("_groupRevisionVersion");
        Object version=state.opt("_groupRevisionVersion");
        if(!legacy&&(!(version instanceof Integer||version instanceof Long)||state.getLong("_groupRevisionVersion")!=1))
            throw new IllegalStateException("Unsupported group revision storage");
        JSONArray groups=state.getJSONArray("groups");
        if(legacy) {
            for(int i=0;i<groups.length();i++) {
                JSONObject group=groups.getJSONObject(i);
                if(group.has("_revision")) throw new IllegalStateException("Partial group revision migration");
                group.put("_revision",1);
            }
            state.put("_groupRevisionVersion",1);
        }
        validateGroups(groups);
        return legacy;
    }
    static void validateGroups(JSONArray groups) throws Exception {
        Set<Long> ids=new HashSet<>();
        for(int i=0;i<groups.length();i++) {
            JSONObject group=groups.getJSONObject(i); Object id=group.opt("id"), revision=group.opt("_revision");
            if(!(id instanceof Integer||id instanceof Long)||group.getLong("id")<1||!ids.add(group.getLong("id"))
                    ||!(revision instanceof Integer||revision instanceof Long)||group.getLong("_revision")<1
                    ||!(group.opt("name") instanceof String)) throw new IllegalStateException("Invalid group identity");
        }
    }
    static void groupRevisions(JSONObject state,JSONObject previous) throws Exception {
        JSONArray groups=state.getJSONArray("groups"), prior=previous.getJSONArray("groups");
        for(int i=0;i<groups.length();i++) {
            JSONObject group=groups.getJSONObject(i), old=AlarmStore.find(prior,group.getLong("id"));
            if(old==null) group.put("_revision",1);
            else {
                long revision=old.getLong("_revision");
                if(!group.getString("name").equals(old.getString("name"))) revision=Math.addExact(revision,1);
                group.put("_revision",revision);
            }
        }
        validateGroups(groups);
    }
    static void validate(JSONArray rows) throws Exception {
        Set<Long> ids=new HashSet<>(); Map<String,Boolean> uuids=new HashMap<>(); Set<String> origins=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i); Object id=row.opt("id"), rev=row.opt("_revision");
            if(!(id instanceof Integer||id instanceof Long)||row.getLong("id")<1||!ids.add(row.getLong("id"))
                    || !(rev instanceof Integer||rev instanceof Long)||row.getLong("_revision")<1)
                throw new IllegalStateException("Invalid stored identity");
            if(row.has("_identityUnknown")&&!(row.opt("_identityUnknown") instanceof Boolean))
                throw new IllegalStateException("Invalid identity marker");
            if(row.optBoolean("_identityUnknown")) {
                if(!row.optBoolean("_phoneFallback")||row.has("_uuid")||row.has("_origin")) throw new IllegalStateException("Invalid unknown identity");
            } else {
                if(!(row.opt("_uuid") instanceof String)||!uuid(row.getString("_uuid"))) throw new IllegalStateException("Invalid UUID");
                String uuid=row.getString("_uuid"); boolean fallback=row.optBoolean("_phoneFallback");
                // Protected old-owner and replacement fallback registrations may
                // coexist for ONE source logical alarm. They are not new alarms.
                if(uuids.containsKey(uuid)&&(!fallback||!uuids.get(uuid))) throw new IllegalStateException("Duplicate local UUID");
                uuids.put(uuid,fallback);
            }
            if(row.has("_origin")) {
                JSONObject origin=origin(row.getJSONObject("_origin"));
                if(!origins.add(key(origin))) throw new IllegalStateException("Duplicate origin");
            }
        }
    }
    static String key(JSONObject origin) throws Exception { return origin.getString("source")+":"+origin.getString("reference"); }
    static String normalized(Object value) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value; List<String> keys=new ArrayList<>(); Iterator<String> it=object.keys();
            while(it.hasNext()) keys.add(it.next()); Collections.sort(keys);
            StringBuilder out=new StringBuilder("{");
            for(String key:keys) { if(out.length()>1) out.append(','); out.append(JSONObject.quote(key)).append(':').append(normalized(object.get(key))); }
            return out.append('}').toString();
        }
        if(value instanceof JSONArray) {
            JSONArray array=(JSONArray)value; StringBuilder out=new StringBuilder("[");
            for(int i=0;i<array.length();i++) { if(i>0) out.append(','); out.append(normalized(array.get(i))); } return out.append(']').toString();
        }
        return value instanceof String?JSONObject.quote((String)value):String.valueOf(value);
    }
    private static String content(JSONObject row) throws Exception {
        JSONObject copy=new JSONObject(row.toString());
        for(String key:new String[]{"_uuid","_revision","_origin","_identityUnknown"}) copy.remove(key);
        return normalized(copy);
    }
    /** Central commit policy also covers old UI/Binder/Wear/group/runtime routes. */
    static void revisions(JSONObject state,JSONObject previous) throws Exception {
        JSONArray rows=state.getJSONArray("alarms"), prior=previous.getJSONArray("alarms");
        boolean groupsChanged=!normalized(state.getJSONArray("groups")).equals(normalized(previous.getJSONArray("groups")));
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i), old=AlarmStore.find(prior,row.getLong("id"));
            if(old==null) {
                if(!row.has("_revision")) { if(row.optBoolean("_phoneFallback")) fallback(row,new JSONObject()); else fresh(row); }
            } else {
                boolean branch=old.optBoolean("_phoneFallback")&&!row.optBoolean("_phoneFallback");
                // Fallback replacement can learn a previously unknown source UUID, never change a known one.
                if(branch) { row.remove("_origin"); row.remove("_identityUnknown"); fresh(row); }
                else if(!old.optBoolean("_identityUnknown") || !row.optBoolean("_phoneFallback")) preserve(old,row);
                else if(!row.has("_revision")) preserve(old,row);
                long revision=old.getLong("_revision");
                if(branch || !content(row).equals(content(old)) || (groupsChanged && !row.isNull("groupId"))) revision=Math.addExact(revision,1);
                row.put("_revision",revision);
            }
        }
        validate(rows);
    }
    private AlarmIdentity() {}
}
