# Open Clock

Android application/package: **`com.openclock`** (Android 8.0/API 26 or newer).

An alarm-focused Java Android app with a local alarm list, dated one-shots, weekly repeats, labels, sound/vibration, configurable snoozing, and groups. The app UI and public JSON intent API share the same persistence and scheduling backend. This is not a stopwatch, timer, world clock, or network service.

## Quick start

1. Build the debug APK below, then install it on an Android 8.0+ phone (for example, `adb install -r app/build/outputs/apk/debug/app-debug.apk`) and open **Open Clock**.
2. Open **⋮ → Alarm permissions**. Review precise-alarm access, notifications, and full-screen alarm access where Android requires them. Also check the ringing notification channel, alarm volume, Do Not Disturb, and device battery restrictions.
3. Tap **+** (or **Add alarm**), choose a 24-hour time, optionally choose a date or repeating weekdays, set the label/sound/vibration/snooze options, and **Save**. With neither a date nor weekdays, the alarm rings once at the next occurrence. Tap an alarm to edit; use its switch to enable/disable, or long-press for edit/delete actions.
4. First verify a near-future alarm while unlocked and locked, including snooze and dismiss. Keep a separate trusted alarm for important commitments until you have verified this build on your phone.

### Group controls

Use **⋮ → Create group** or **+ Create group** beneath the list. In the group editor, name the group and select alarms; selecting moves an alarm from its previous group, and deselecting a current member ungroups it. You can also choose **Group** in an alarm's editor. Tap the group heading or chevron to collapse/expand it. Each group's **⋮** menu offers **Edit group & alarms**, **Enable all alarms**, **Disable all alarms**, and **Delete group**. Deleting a group keeps its alarms. Group-wide enabling/disabling and membership edits save individual changes, not one transaction: on failure, review the list because some changes may already be saved.

## Build

Use JDK 17+ (the sources target Java 17), Android SDK platform **34** and build-tools **34.0.0**, with accepted Android SDK licenses. Set `ANDROID_HOME` to your SDK directory or create an untracked `local.properties` containing `sdk.dir=/absolute/path/to/android-sdk`. The included wrapper selects Gradle **8.10.2**; the Android Gradle plugin is **8.5.2**.

```sh
# From the repository root; the first build needs network access for uncached dependencies.
./gradlew --no-daemon assembleDebug

# When the wrapper, SDK and dependencies are already cached:
./gradlew --offline --no-daemon assembleDebug
```

The debug-signed APK is `app/build/outputs/apk/debug/app-debug.apk`. Android Studio can also open this repository using the same SDK/JDK configuration. No emulator or device is needed to compile; a successful build does not verify alarm delivery.

## Early-build alarm safety

**This is an early build, not a validated replacement for a critical alarm.** Actual ringing, reboot recovery, lock-screen presentation, permission changes and manufacturer battery policies require testing on the target device. A saved/enabled alarm or successful API reply is not proof of timely, audible delivery. Review any displayed scheduling error. Force-stop, revoked permissions, silent sound/vibration settings, muted channels, Do Not Disturb and OS restrictions can prevent alerts. See [Android permissions and delivery limitations](#android-permissions-and-delivery-limitations) below. There is no custom sound picker, multi-request transaction, or API permission-grant operation. The deliberately open IPC described next is an additional safety/privacy trade-off.

## Security: deliberately unrestricted external control

**The exported `com.openclock.IntentApi` broadcast receiver is deliberately unprotected. Any other app on the device can read alarm labels, times and groups, create or change alarms, disable them, delete them, or change group membership without user confirmation.** There is no signature permission, token, caller allowlist, or authentication. Do not store secrets in alarm labels or group names. Install this build only if you accept arbitrary other-app access. This is a local Android IPC interface, not a network service.

Clients should explicitly address `com.openclock/.IntentApi`. Replies are returned to the sender through Android's **ordered-broadcast result**, never an implicit response broadcast. Explicit addressing avoids involving unrelated receivers, but does not restrict who can call this public component.

## External API transport

- Action: **`com.openclock.API`** (case-sensitive).
- Component: **`com.openclock/.IntentApi`**.
- Exactly one intent extra: **`request`**, a string containing one JSON object.
- Use **`Context.sendOrderedBroadcast`**, not `sendBroadcast`. Unordered requests are ignored without changing data.
- The final result receiver gets a fresh result-extras bundle containing **`result`**, a JSON string. The same JSON is also the broadcast's result data (visible in `adb shell am broadcast` output).
- Result code: `Activity.RESULT_OK` (`-1`) for success; `Activity.RESULT_CANCELED` (`0`) for an API error. Do not interpret the shell command's process exit status as API success.
- No separate reply action, callback intent extra, credential, or permission is required.
- The receiver completes synchronously. Reads, validation and mutations are serialized with the store's class monitor. Send dependent operations only after receiving the preceding result; there is no cross-sender FIFO or multi-request transaction guarantee.
- If no JSON result arrives, the request was not acknowledged: check installation, component availability, current Android user/profile and ordered delivery. A broadcast completion alone does not prove that the receiver ran.

Requests are at most 16,384 UTF-16 characters and eight nested JSON levels. Only strict JSON is accepted: unique keys; lowercase `true`/`false`/`null`; valid JSON string escapes with no unescaped U+0000–U+001F control characters; integer literals within signed 64-bit range; no comments, trailing content, decimal/exponent numbers, string-to-number or string-to-boolean coercion. Unknown operation fields, unknown alarm/group fields, wrong types, invalid ranges, missing required fields, and extra intent extras are errors. Keys and operation names are case-sensitive. Null is accepted only for `date` and `groupId`.

### Operations

All rows below show complete request shapes. Replace illustrative IDs with IDs returned by this installation. Creates must omit IDs; updates require an existing ID. An update merges only the supplied fields with the stored object; omitted fields are preserved. The complete merged alarm is validated before saving. For example, changing a repeating alarm to a dated one requires setting `days: []` as well as `date`.

| Operation | Request | Success JSON |
| --- | --- | --- |
| List alarms | `{"op":"alarms.list"}` | `{"ok":true,"alarms":[...]}` |
| Create alarm | `{"op":"alarms.create","alarm":{"hour":7,"minute":30}}` | `{"ok":true,"alarm":{...}}` |
| Update alarm | `{"op":"alarms.update","alarm":{"id":123,"enabled":false}}` | `{"ok":true,"alarm":{...}}` |
| Delete alarm | `{"op":"alarms.delete","id":123}` | `{"ok":true,"id":123}` |
| List groups | `{"op":"groups.list"}` | `{"ok":true,"groups":[...]}` |
| Create group | `{"op":"groups.create","group":{"name":"Work"}}` | `{"ok":true,"group":{...}}` |
| Update group | `{"op":"groups.update","group":{"id":456,"name":"Weekdays"}}` | `{"ok":true,"group":{...}}` |
| Delete group | `{"op":"groups.delete","id":456}` | `{"ok":true,"id":456}` |
| Add/move alarm to group | `{"op":"groups.addAlarm","groupId":456,"alarmId":123}` | `{"ok":true,"alarm":{...}}` |
| Remove alarm from group | `{"op":"groups.removeAlarm","groupId":456,"alarmId":123}` | `{"ok":true,"alarm":{...}}` |

An alarm belongs to at most one group. Adding it to a group replaces its previous membership; adding the same membership again is safe. Removing a membership that is already absent is a successful no-op and never removes membership in a different group. Both named objects must still exist. You may also change membership with an alarm update's `groupId` field, or clear it with JSON `null`.

Deleting a group **ungroups its alarms; it does not delete alarms**. Referencing an unknown alarm or group, including deleting an already-deleted ID, returns `NOT_FOUND`. Updates do not create missing objects. There is no bulk operation or idempotency key. Retrying a create can create duplicates. After missing acknowledgements or storage/internal failures, list current state before retrying a mutation.

### Alarm schema

| Field | Type / API validation | Create default |
| --- | --- | --- |
| `id` | Positive signed 64-bit integer; required for update, forbidden for create | Store-generated |
| `hour` | Integer `0..23`, local 24-hour time | **Required** |
| `minute` | Integer `0..59` | **Required** |
| `date` | Valid ISO calendar date `yyyy-MM-dd` (years `0001..9999`), or JSON `null` | `null` |
| `days` | Array of unique integers: Android `Calendar.SUNDAY=1`, Monday `2`, through Saturday `7` | `[]` |
| `label` | String, at most 200 UTF-16 characters; empty allowed | `""` |
| `enabled` | Boolean | `true` |
| `sound` | Boolean; enable/disable the system default alarm sound | `true` |
| `vibrate` | Boolean | `true` |
| `vibrationPattern` | String: `basic`, `pulse` or `heartbeat` | `"basic"` |
| `snoozeEnabled` | Boolean | `true` |
| `snoozeMinutes` | Integer `1..1440` | `5` |
| `snoozeCount` | Integer `0..1000`; `0` means unlimited snoozes | `3` |
| `groupId` | Existing positive group ID or JSON `null` | `null` |

Returned alarms also include backend-owned, read-only `nextTriggerAt` (epoch milliseconds, `0` when no scheduled timestamp is recorded) and `schedulingError` (string or JSON `null`). These are diagnostic fields, not accepted request fields; construct update patches instead of sending a complete returned alarm back unchanged. The backend currently limits storage to 500 alarms (at most 450 enabled) and 100 groups; exceeding these limits produces `INVALID_ARGUMENT`.

`sound: false` makes the alarm silent; vibration is controlled separately by `vibrate`. There is no custom sound URI field or URI-permission transfer in this API. Labels and group names are trimmed by the backend.

A group is `{"id":456,"name":"Work"}`. `id` has the same ID rules as alarms. `name` is a nonblank string of at most 100 UTF-16 characters (trimmed by the backend). It is required for create and optional for update. Group names are not identifiers; always use the returned ID. Do not assume names or alarm labels are unique. Preserve IDs as 64-bit integers; JavaScript clients must avoid rounding IDs beyond their safe-integer range.

### Dates, weekdays and time zones

- `date: null, days: []`: one-shot at the next occurrence of the local hour/minute, not a daily repeating alarm.
- `date: "2027-01-15", days: []`: one-shot on that local calendar date.
- `date: null, days: [2,3,4,5,6]`: weekly repeating Monday through Friday.
- A non-null `date` and nonempty `days` are mutually exclusive. Explicit JSON null clears a date; omission on update does not clear it.
- Times follow the **device's current time zone**, not UTC or the caller's time zone. There is no timezone/offset/seconds field.
- When enabling/scheduling an alarm, the scheduler rejects an explicit date/time that has no future occurrence or falls in a daylight-saving gap. A disabled alarm can retain a past date, but enabling it requires a future occurrence. Rolling/weekly schedules skip a gap day. During a repeated local time at a DST overlap it selects the first occurrence still in the future. Clock/timezone changes and Android scheduling policy can affect delivery.

### Results and errors

Success envelopes are shown in the operation table. An error is:

```json
{"ok":false,"error":{"code":"INVALID_ARGUMENT","message":"hour is out of range"}}
```

Clients should branch on `ok` and `error.code`, not human-readable messages. Errors do not include raw exception messages or request bodies.

| Code | Meaning |
| --- | --- |
| `INVALID_ACTION` | Ordered intent action was not `com.openclock.API` |
| `UNKNOWN_OPERATION` | `op` is a string but not a supported operation |
| `INVALID_ARGUMENT` | Malformed/missing JSON, invalid shape/type/range, unknown field, missing required value, incompatible recurrence, or backend validation rejection |
| `NOT_FOUND` | Referenced alarm/group ID does not exist |
| `PERMISSION_DENIED` | Android raised a `SecurityException` |
| `STORAGE_ERROR` | Storage raised an `IOException`, or a store read wrapped an I/O/JSON-decoding failure in `IllegalStateException`; no empty-store or pretend-success fallback |
| `INTERNAL_ERROR` | Another operation failure; read back state before retrying |
| `RESULT_TOO_LARGE` | Serialized result exceeds 200,000 UTF-16 characters; there is no pagination |

Permission/storage/internal errors do not promise rollback if saving completed before scheduling failed. A successful save means the backend accepted the operation, **not** that Android has granted all permissions or guarantees an audible alert. Large list responses can fail with `RESULT_TOO_LARGE`; result size is bounded to reduce Binder transaction risk.

## Working ADB examples

These commands use a POSIX host shell. The outer double quotes and escaped JSON double quotes preserve single-quoted JSON on the Android shell. Use the same Android user in which the app is installed. `am broadcast` is ordered by default; **do not use `--async`**. `--include-stopped-packages` permits explicitly targeting an installed app that has not yet been launched; it does not grant scheduling/notification permissions.

```sh
# List alarms; inspect Broadcast completed: result=-1, data="...".
adb shell "am broadcast --user current --include-stopped-packages -a com.openclock.API -n com.openclock/.IntentApi --es request '{\"op\":\"alarms.list\"}'"

# Create a weekday alarm. The returned alarm contains its generated ID.
adb shell "am broadcast --user current --include-stopped-packages -a com.openclock.API -n com.openclock/.IntentApi --es request '{\"op\":\"alarms.create\",\"alarm\":{\"hour\":7,\"minute\":30,\"days\":[2,3,4,5,6],\"label\":\"Work\"}}'"

# Create a group; use the returned group ID in membership requests.
adb shell "am broadcast --user current --include-stopped-packages -a com.openclock.API -n com.openclock/.IntentApi --es request '{\"op\":\"groups.create\",\"group\":{\"name\":\"Work\"}}'"

# Replace 123/456 with actual IDs from the preceding results.
adb shell "am broadcast --user current --include-stopped-packages -a com.openclock.API -n com.openclock/.IntentApi --es request '{\"op\":\"groups.addAlarm\",\"alarmId\":123,\"groupId\":456}'"
adb shell "am broadcast --user current --include-stopped-packages -a com.openclock.API -n com.openclock/.IntentApi --es request '{\"op\":\"alarms.update\",\"alarm\":{\"id\":123,\"enabled\":false}}'"
adb shell "am broadcast --user current --include-stopped-packages -a com.openclock.API -n com.openclock/.IntentApi --es request '{\"op\":\"groups.removeAlarm\",\"alarmId\":123,\"groupId\":456}'"
adb shell "am broadcast --user current --include-stopped-packages -a com.openclock.API -n com.openclock/.IntentApi --es request '{\"op\":\"alarms.delete\",\"id\":123}'"
adb shell "am broadcast --user current --include-stopped-packages -a com.openclock.API -n com.openclock/.IntentApi --es request '{\"op\":\"groups.delete\",\"id\":456}'"
```

Listing is read-only. Creating an enabled alarm schedules it; these are real operations, not a dry run. The IDs and output values above are illustrative, not expected fixed IDs.

## Android Java client

Call this from a method with a live `Context context`. This uses a final result receiver supplied directly to Android; no client receiver registration or exported response receiver is necessary.

```java
JSONObject request = new JSONObject().put("op", "alarms.list");
Intent intent = new Intent("com.openclock.API")
        .setComponent(new ComponentName("com.openclock", "com.openclock.IntentApi"))
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        .putExtra("request", request.toString());

context.sendOrderedBroadcast(
        intent,
        null, // No receiver permission: this API is deliberately unrestricted.
        new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent ignored) {
                Bundle extras = getResultExtras(false);
                String json = extras == null ? null : extras.getString("result");
                if (json == null) {
                    // No API acknowledgement; do not blindly retry a create.
                    return;
                }
                try {
                    JSONObject result = new JSONObject(json);
                    if (getResultCode() == Activity.RESULT_OK && result.getBoolean("ok")) {
                        JSONArray alarms = result.getJSONArray("alarms");
                        // Consume the returned snapshot here; keep IDs as long.
                    } else {
                        JSONObject error = result.getJSONObject("error");
                        String code = error.getString("code");
                        // Handle/display the API error, without assuming rollback.
                    }
                } catch (JSONException malformedReply) {
                    // Treat malformed replies as an unacknowledged operation.
                }
            }
        },
        new Handler(Looper.getMainLooper()),
        Activity.RESULT_CANCELED,
        null,
        null);
```

Imports: `android.app.Activity`, `android.content.BroadcastReceiver`, `android.content.ComponentName`, `android.content.Context`, `android.content.Intent`, `android.os.Bundle`, `android.os.Handler`, `android.os.Looper`, and `org.json.JSONArray`, `JSONException`, `JSONObject`. The enclosing request construction must handle/declare `JSONException`. For other operations, construct the corresponding JSON and consume its `alarm`, `group`, or `id` result instead of `alarms`. Implement application-level timeout handling without automatically replaying mutations.

## Android permissions and delivery limitations

Calling this IPC requires no caller permission. **Open Clock itself** needs the relevant platform access: exact-alarm special access where required, notification runtime permission on Android 13+, and applicable full-screen alarm access on newer Android versions. Review these in the app/system settings before relying on alarms. Device volume, Do Not Disturb, battery policies, OS restrictions, force-stop state, and sound URI access can prevent timely or audible delivery. The IPC does not grant permissions, open permission prompts, disable Do Not Disturb, bypass force-stop, or certify permission readiness; there is no permission-status or permission-grant operation.

The custom API does **not** implement Android's standard `AlarmClock.ACTION_SET_ALARM`, `ACTION_SET_TIMER`, `ACTION_SHOW_ALARMS`, or related `AlarmClock` extras. Do not send standard alarm intents to this receiver or infer support from a manifest filter; only the action and JSON operations documented here are implemented.
