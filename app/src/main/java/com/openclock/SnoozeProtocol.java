package com.openclock;

import org.json.JSONObject;

/** Versioned, exact-session snooze. Missing receipts never establish non-commit. */
final class SnoozeProtocol {
    static final String REQUEST="/openclock/v2/snooze", STATUS="/openclock/v2/snooze-status",
        REPLY="/openclock/v2/snooze-reply";
    static final int CAPACITY=128;
    static final long WINDOW=15_000;
    // Status is best effort after bounded history compaction, never proof of non-commit.
    static final long LOOKUP_RETENTION=48*60*60_000L;
    static boolean uuid(String value) {
        return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    static long integer(JSONObject x,String key) throws Exception {
        if(!(x.get(key) instanceof Number)) throw new IllegalArgumentException("Invalid snooze number");
        return new java.math.BigDecimal(x.get(key).toString()).longValueExact();
    }
    static void validate(JSONObject r) throws Exception {
        if(r.length()!=9||!(r.opt("owner") instanceof String)||r.getString("owner").isEmpty()||r.getString("owner").length()>256||integer(r,"version")!=2||!uuid(r.getString("requestId"))||!uuid(r.getString("epoch"))
                ||!uuid(r.getString("token"))||!uuid(r.getString("occurrenceId"))||integer(r,"id")<1
                ||integer(r,"minutes")<1||integer(r,"minutes")>1440||integer(r,"issuedAt")<1)
            throw new IllegalArgumentException("Invalid snooze request");
    }
    static boolean same(JSONObject a,JSONObject b) {
        for(String k:new String[]{"version","owner","requestId","epoch","id","token","occurrenceId","minutes","issuedAt"})
            if(!String.valueOf(a.opt(k)).equals(String.valueOf(b.opt(k)))) return false;
        return true;
    }
    private SnoozeProtocol() { }
}
