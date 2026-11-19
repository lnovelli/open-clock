package com.openclock;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
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
        } catch (AlarmV2.Failure e) {
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
        if(op.startsWith("alarms.v2.")) return AlarmV2.dispatch(context,r,ExternalAlarmConfirmation::afterSave);
        throw new ApiException("UNKNOWN_OPERATION", "Unsupported op");
    }

    private static String string(JSONObject value, String key, int max, boolean empty) throws ApiException {
        Object item = value.opt(key);
        if (!(item instanceof String)) throw bad(key + " must be a string");
        String text = (String) item;
        if (text.length() > max || (!empty && text.trim().isEmpty())) throw bad(key + " has invalid length");
        return text;
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
