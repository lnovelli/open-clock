package com.openclock;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** JSON operation engine; it is not an Android component. */
public final class IntentApi {
    private static final int MAX_REQUEST = 16384;
    private static final int MAX_RESULT = 200000;

    static final class Activity {
        String operation=AccessLog.REQUEST;
        String outcome="failed";
    }

    /** Called only after Binder authorization; never exposed as a receiver. */
    static String execute(Context context, String raw, Activity activity) {
        JSONObject response;
        try {
            if (raw == null || raw.length() > MAX_REQUEST) throw bad("Request is null or exceeds 16384 characters");
            JSONObject request = parse(raw);
            activity.operation = AccessLog.operation(request.opt("op"));
            synchronized (AlarmStore.class) { response = dispatch(context, request); }
        } catch (ApiException e) {
            response = error(e.code, e.getMessage());
        } catch (IOException e) {
            response = error("STORAGE_ERROR", "Storage I/O failed; read back state before retrying a mutation");
        } catch (SecurityException e) {
            response = error("PERMISSION_DENIED", "Android denied an operation; review app permissions");
        } catch (IllegalArgumentException e) {
            response = error("INVALID_ARGUMENT", "The store rejected the supplied values");
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof IOException || e.getCause() instanceof org.json.JSONException)
                response = error("STORAGE_ERROR", "Storage is unreadable; read back state before retrying a mutation");
            else response = error("INTERNAL_ERROR", "Operation failed; read back state before retrying a mutation");
        } catch (Exception e) {
            response = error("INTERNAL_ERROR", "Operation failed; read back state before retrying a mutation");
        }
        String json = response.toString();
        if (json.length() > MAX_RESULT) return error("RESULT_TOO_LARGE", "Result exceeds 200000 characters").toString();
        activity.outcome = response.optBoolean("ok", false) ? "success" : "failed";
        return json;
    }

    static String denied(String code, String message) { return error(code, message).toString(); }

    private static JSONObject dispatch(Context context, JSONObject r) throws Exception {
        String op = string(r, "op", 40, false);
        JSONObject out = new JSONObject().put("ok", true);
        switch (op) {
            case "alarms.list":
                fields(r, "op");
                return out.put("alarms", AlarmStore.alarms(context));
            case "groups.list":
                fields(r, "op");
                return out.put("groups", AlarmStore.groups(context));
            case "alarms.triggerNow": {
                fields(r, "op", "alarm");
                JSONObject patch = r.has("alarm") ? object(r, "alarm") : new JSONObject();
                fields(patch, "label", "sound", "vibrate", "vibrationPattern",
                        "snoozeEnabled", "snoozeMinutes", "snoozeCount", "groupId");
                java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
                JSONObject alarm = new JSONObject().put("hour", now.getHour()).put("minute", now.getMinute())
                        .put("date", now.toLocalDate().toString()).put("days", new JSONArray())
                        .put("label", "").put("enabled", true).put("sound", false)
                        .put("vibrate", true).put("vibrationPattern", "basic").put("snoozeEnabled", true)
                        .put("snoozeMinutes", 5).put("snoozeCount", 3).put("groupId", JSONObject.NULL);
                merge(alarm, patch);
                validateAlarm(context, alarm);
                return out.put("alarm", AlarmStore.triggerNow(context, alarm));
            }
            case "alarms.create":
            case "alarms.update": {
                fields(r, "op", "alarm");
                JSONObject patch = object(r, "alarm");
                fields(patch, "id", "hour", "minute", "date", "days", "label", "enabled",
                        "sound", "vibrate", "vibrationPattern", "snoozeEnabled", "snoozeMinutes", "snoozeCount", "groupId");
                boolean update = op.equals("alarms.update");
                JSONObject alarm;
                if (update) {
                    alarm = copy(find(AlarmStore.alarms(context), integer(patch, "id", 1, Long.MAX_VALUE), "alarm"));
                } else {
                    if (patch.has("id")) throw bad("New alarms must omit id");
                    alarm = new JSONObject().put("date", JSONObject.NULL).put("days", new JSONArray())
                            .put("label", "").put("enabled", true).put("sound", false)
                            .put("vibrate", true).put("vibrationPattern", "basic").put("snoozeEnabled", true)
                            .put("snoozeMinutes", 5).put("snoozeCount", 3).put("groupId", JSONObject.NULL);
                }
                merge(alarm, patch);
                validateAlarm(context, alarm);
                return out.put("alarm", AlarmStore.saveAlarm(context, alarm));
            }
            case "alarms.delete": {
                fields(r, "op", "id");
                long id = integer(r, "id", 1, Long.MAX_VALUE);
                find(AlarmStore.alarms(context), id, "alarm");
                AlarmStore.deleteAlarm(context, id);
                return out.put("id", id);
            }
            case "groups.create":
            case "groups.update": {
                fields(r, "op", "group");
                JSONObject patch = object(r, "group");
                fields(patch, "id", "name");
                JSONObject group;
                if (op.equals("groups.update")) {
                    group = copy(find(AlarmStore.groups(context), integer(patch, "id", 1, Long.MAX_VALUE), "group"));
                } else {
                    if (patch.has("id")) throw bad("New groups must omit id");
                    group = new JSONObject();
                }
                merge(group, patch);
                string(group, "name", 100, false);
                return out.put("group", AlarmStore.saveGroup(context, group));
            }
            case "groups.delete": {
                fields(r, "op", "id");
                long id = integer(r, "id", 1, Long.MAX_VALUE);
                find(AlarmStore.groups(context), id, "group");
                AlarmStore.deleteGroup(context, id);
                return out.put("id", id);
            }
            case "groups.addAlarm":
            case "groups.removeAlarm": {
                fields(r, "op", "groupId", "alarmId");
                long groupId = integer(r, "groupId", 1, Long.MAX_VALUE);
                long alarmId = integer(r, "alarmId", 1, Long.MAX_VALUE);
                find(AlarmStore.groups(context), groupId, "group");
                JSONObject alarm = copy(find(AlarmStore.alarms(context), alarmId, "alarm"));
                if (op.equals("groups.addAlarm")) {
                    if (alarm.optLong("groupId", -1) == groupId) return out.put("alarm", alarm);
                    alarm.put("groupId", groupId);
                } else if (alarm.optLong("groupId", -1) == groupId) {
                    alarm.put("groupId", JSONObject.NULL);
                } else {
                    return out.put("alarm", alarm); // Idempotent; never remove a different membership.
                }
                return out.put("alarm", AlarmStore.saveAlarm(context, alarm));
            }
            default:
                throw new ApiException("UNKNOWN_OPERATION", "Unsupported op");
        }
    }

    private static void validateAlarm(Context context, JSONObject a) throws Exception {
        integer(a, "hour", 0, 23);
        integer(a, "minute", 0, 59);
        string(a, "label", 200, true);
        bool(a, "sound");
        bool(a, "enabled");
        bool(a, "vibrate");
        String pattern = string(a, "vibrationPattern", 16, false);
        if (!pattern.equals("basic") && !pattern.equals("pulse") && !pattern.equals("heartbeat"))
            throw bad("vibrationPattern must be basic, pulse or heartbeat");
        bool(a, "snoozeEnabled");
        integer(a, "snoozeMinutes", 1, 1440);
        integer(a, "snoozeCount", 0, 1000);
        Object daysValue = a.opt("days");
        if (!(daysValue instanceof JSONArray)) throw bad("days must be an array");
        JSONArray days = (JSONArray) daysValue;
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < days.length(); i++) {
            long day = number(days.get(i), "days entry", 1, 7);
            if (!seen.add(day)) throw bad("days must not contain duplicates");
        }
        if (!a.isNull("date")) {
            String date = string(a, "date", 10, false);
            if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || date.startsWith("0000")) {
                throw bad("date must be a valid yyyy-MM-dd date");
            }
            try {
                LocalDate.parse(date);
            } catch (DateTimeParseException e) {
                throw bad("date must be a valid yyyy-MM-dd date");
            }
            if (days.length() != 0) throw bad("date and nonempty days are mutually exclusive");
        }
        if (!a.isNull("groupId")) {
            find(AlarmStore.groups(context), integer(a, "groupId", 1, Long.MAX_VALUE), "group");
        }
    }

    private static JSONObject find(JSONArray values, long id, String kind) throws Exception {
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            if (value.getLong("id") == id) return value;
        }
        throw new ApiException("NOT_FOUND", "No such " + kind);
    }

    private static JSONObject copy(JSONObject value) throws Exception {
        return new JSONObject(value.toString());
    }

    private static void merge(JSONObject target, JSONObject patch) throws Exception {
        Iterator<String> keys = patch.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            target.put(key, patch.get(key));
        }
    }

    private static void fields(JSONObject value, String... allowed) throws ApiException {
        Set<String> names = new HashSet<>();
        for (String name : allowed) names.add(name);
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) if (!names.contains(keys.next())) throw bad("Unknown field");
    }

    private static JSONObject object(JSONObject value, String key) throws ApiException {
        Object item = value.opt(key);
        if (!(item instanceof JSONObject)) throw bad(key + " must be an object");
        return (JSONObject) item;
    }

    private static String string(JSONObject value, String key, int max, boolean empty) throws ApiException {
        Object item = value.opt(key);
        if (!(item instanceof String)) throw bad(key + " must be a string");
        String text = (String) item;
        if (text.length() > max || (!empty && text.trim().isEmpty())) throw bad(key + " has invalid length");
        return text;
    }

    private static void bool(JSONObject value, String key) throws ApiException {
        if (!(value.opt(key) instanceof Boolean)) throw bad(key + " must be a boolean");
    }

    private static long integer(JSONObject value, String key, long min, long max) throws ApiException {
        return number(value.opt(key), key, min, max);
    }

    private static long number(Object value, String key, long min, long max) throws ApiException {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw bad(key + " must be an integer");
        long n = ((Number) value).longValue();
        if (n < min || n > max) throw bad(key + " is out of range");
        return n;
    }

    // Neither Android JSONObject nor JsonReader's non-lenient mode fully enforces
    // RFC 8259 lexical rules. Check tokens before JsonReader checks structure;
    // the bounded builder still rejects duplicate keys, fractions and overflow.
    private static JSONObject parse(String raw) throws ApiException {
        checkJsonTokens(raw);
        try (JsonReader reader = new JsonReader(new StringReader(raw))) {
            reader.setLenient(false);
            Object value = read(reader, 0);
            if (!(value instanceof JSONObject) || reader.peek() != JsonToken.END_DOCUMENT) throw bad("Expected one JSON object");
            return (JSONObject) value;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw bad("Invalid JSON; use unique keys and signed 64-bit integer numbers");
        }
    }

    /** Lexical gate only; JsonReader below owns object/array grammar and token ordering. */
    private static void checkJsonTokens(String raw) throws ApiException {
        for (int i = 0; i < raw.length();) {
            char c = raw.charAt(i++);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n'
                    || c == '{' || c == '}' || c == '[' || c == ']'
                    || c == ':' || c == ',') continue;
            if (c == '"') {
                boolean closed = false;
                while (i < raw.length()) {
                    c = raw.charAt(i++);
                    if (c == '"') { closed = true; break; }
                    if (c < 0x20) throw bad("Unescaped control character in JSON string");
                    if (c != '\\') continue;
                    if (i == raw.length()) throw bad("Incomplete JSON escape");
                    c = raw.charAt(i++);
                    if (c == 'u') {
                        for (int hex = 0; hex < 4; hex++) {
                            if (i == raw.length()) throw bad("Incomplete JSON Unicode escape");
                            char digit = raw.charAt(i++);
                            if (!((digit >= '0' && digit <= '9') || (digit >= 'a' && digit <= 'f')
                                    || (digit >= 'A' && digit <= 'F'))) throw bad("Invalid JSON Unicode escape");
                        }
                    } else if (c != '"' && c != '\\' && c != '/' && c != 'b'
                            && c != 'f' && c != 'n' && c != 'r' && c != 't') {
                        throw bad("Invalid JSON escape");
                    }
                }
                if (!closed) throw bad("Unterminated JSON string");
                continue;
            }
            String literal = c == 't' ? "true" : c == 'f' ? "false" : c == 'n' ? "null" : null;
            if (literal != null) {
                int start = i - 1;
                if (!raw.startsWith(literal, start)) throw bad("Invalid JSON literal");
                i = start + literal.length();
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                int start = i - 1;
                while (i < raw.length() && raw.charAt(i) >= '0' && raw.charAt(i) <= '9') i++;
                if (!raw.substring(start, i).matches("-?(0|[1-9][0-9]*)")) throw bad("Numbers must be integer literals");
            } else {
                throw bad("Invalid JSON token");
            }
            if (i < raw.length()) {
                c = raw.charAt(i);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n'
                        && c != ',' && c != ']' && c != '}') throw bad("Invalid JSON token boundary");
            }
        }
    }

    private static Object read(JsonReader reader, int depth) throws Exception {
        if (depth > 8) throw bad("JSON nesting exceeds 8 levels");
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JSONObject object = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw bad("Duplicate JSON key");
                    object.put(key, read(reader, depth + 1));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) array.put(read(reader, depth + 1));
                reader.endArray();
                return array;
            }
            case STRING: return reader.nextString();
            case NUMBER: {
                String number = reader.nextString();
                if (!number.matches("-?(0|[1-9][0-9]*)")) throw bad("Numbers must be integer literals");
                return Long.parseLong(number);
            }
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return JSONObject.NULL;
            default: throw bad("Invalid JSON value");
        }
    }

    private static JSONObject error(String code, String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false).put("error", new JSONObject().put("code", code).put("message", message));
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return result;
    }

    private static ApiException bad(String message) {
        return new ApiException("INVALID_ARGUMENT", message);
    }

    private static final class ApiException extends Exception {
        final String code;
        ApiException(String code, String message) { super(message); this.code = code; }
    }
}
