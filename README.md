# Open Clock

Android application/package: **`com.openclock`** (Android 8.0/API 26 or newer).

An alarm-focused Java Android app with a local alarm list, dated one-shots, weekly repeats, labels, sound/vibration, configurable snoozing, and groups. Its light and dark appearance follows the Android system theme. The app UI and authenticated JSON Binder API share the same persistence and scheduling backend. This is not a stopwatch, timer, world clock, or network service.

## Quick start

1. Download the phone and watch APKs from [GitHub Releases](https://github.com/lnovelli/open-clock/releases/latest), or build the debug APKs below. Install the phone APK on Android 8.0+ (for example, `adb install -r app/build/outputs/apk/debug/app-debug.apk`) and open **Open Clock**. Install the watch APK separately on Wear OS. The APKs are debug-signed; updates must use the same signing certificate as the installed app.
2. Open **⋮ → Alarm permissions**. Review precise-alarm access, notifications, and full-screen alarm access where Android requires them. Under **Do Not Disturb**, open Android's settings and allow alarms in every mode you use. Check the ringing notification channel, alarm volume, and device battery restrictions too. On the watch, use **Do Not Disturb** in the alarm list and review its separate settings.
3. Tap **+** (or **Add alarm**), choose a 24-hour time, optionally choose a date or repeating weekdays, set the label/sound/vibration/snooze options, and **Save**. New alarms start at the current hour and minute, with sound off and vibration on; turn sound on if wanted. With neither a date nor weekdays, the alarm rings once at the next occurrence. The current minute has already begun, so leaving it unchanged can schedule the alarm for the next day. Tap an alarm to edit; saving an edit enables and schedules it, including on the watch, provided the required access is available. Use the list switch to disable it, or long-press for edit/delete actions.
4. First verify a near-future alarm while unlocked and locked, including snooze and dismiss. Keep a separate trusted alarm for important commitments until you have verified this build on your phone.

### Do Not Disturb and screen orientation

Phone alarms, watch alarms, and mirrored phone alarms request sound and vibration as alarms, including while priority Do Not Disturb is active; each alarm's sound and vibration switches are still respected. In Android's Do Not Disturb settings on **each device**, allow alarms in every mode you use. Open Clock does not disable Do Not Disturb or change alarm volume. Both apps can optionally request DND access so their alarm notification channels can request priority-mode bypass; this does not bypass policy for continuous audio or vibration. Depending on Android version, mode and device, total silence, blocked alarms, muted alarm volume, notification settings or manufacturer restrictions can still prevent alerts. Test on both devices under the intended sleep mode, and keep a separate trusted alarm until verified.

For phone lock-screen controls, allow Open Clock's full-screen alarm access under **⋮ → Alarm permissions** and keep the ringing channel at High importance. In DND, allow alarms and visual interruptions, and check the channels' DND override where available. Wear OS does not support full-screen notification intents or that permission screen. When a new watch or mirrored phone alarm arrives, the watch app requests a short, at-most-five-second screen wake and makes a best-effort attempt to open its alarm activity. Neither wakes repeatedly, changes the lock state or bypasses the keyguard. Wear OS may suppress screen wake or silently block background activity launches, so it may be necessary to tap the alarm notification instead. Android, DND and the device manufacturer ultimately control presentation. The watch's phone-alarm mirror also depends on connectivity and the watch's own notification permissions. Neither the automatic display nor alarm delivery is verified on a device.

The phone and watch activities request portrait orientation rather than rotating with the accelerometer. Android and device manufacturers may override this on some devices.

### Check for updates

Use **⋮ → Check for updates** on the phone or **Check for updates** near the bottom of the watch alarm list. A manual check reads the latest stable public GitHub release; a 404 may mean there is no release or the repository is inaccessible. There are no automatic checks. The release must contain an APK named `open-clock-phone-v<versionCode>.apk` for the phone and/or `open-clock-watch-v<versionCode>.apk` for the watch, where the decimal version code exactly matches the corresponding APK. Both can be in the same release; the app considers only its own device's asset. GitHub's latest release must contain each role that should be offered an update. Increase the installed app's version code for each subsequent release.

If a newer APK is available, the app asks before downloading, then checks the package ID, version code and current signing certificate against the installed app before invoking Android's installer. During download, the app shows the bytes received and, when the server supplies a size, the percentage. On the watch, it requests that the screen stay on while the download and verification dialog is visible; cancelling or leaving the app interrupts the check. The user must allow installation from Open Clock if required and confirm the system installer; the app does not silently install updates. A different signing certificate or package cannot update the installed app. Wear OS may not support the install permission screen or installer, in which case installation must be performed separately. Runtime behavior has not been tested on devices.

### Dismiss a ringing alarm

On the phone, drag the × from the centre to the surrounding ring and release. A tap does not dismiss the alarm. On the watch, swipe **Dismiss** left or **Snooze** right, starting on the side of each control nearest the centre. While the ringing screen is active, Back also dismisses the displayed occurrence. Notification Dismiss actions and mirror notification-clear callbacks also attempt dismissal. The system may not deliver a particular gesture or notification callback. Accessibility activation remains available for each control.

**Any explicit watch alarm dismissal requests dismissal of every Open Clock alarm currently ringing on the paired phone**, including alarms unrelated to the watch alarm. This applies to watch-local alarms, phone fallbacks, mirrors, notification actions and double shake. Watch silence is not phone confirmation. The watch attempts the request at most four times (initial attempt plus three retries), aiming for two seconds between attempts, and stops on a valid acknowledgement. Discovery failure or disconnection counts as an attempt; a request expires after 20 seconds. A delayed first delivery can dismiss phone alarms that began after the watch gesture but are active when the phone receives the request. Android may delay or prevent background execution, and clock skew can reject requests. Check the phone directly; snooze and automatic expiry do not send this request. The phone retains up to 4,096 non-evicted dismissal receipts to prevent replay even after a clock correction; once full, it refuses further remote dismiss-all requests (local dismissals still work).

While a watch alarm is actively ringing, a local **double shake** can also attempt dismissal, including when its screen is not focused. This is an experimental heuristic, tailored to the user's watch and movement, not Samsung's classifier. It requests the gyroscope at 100 Hz (10,000 µs); Android may deliver slower, irregularly or not at all. It detects two distinct excursions of the X-axis gyro to **−700 degrees/second or lower**. They are distinct only when a sample rises above that threshold between them; remaining below it counts as one excursion. The second crossing is accepted immediately if it occurs within 500 ms of the deepest sampled point of the first excursion. No accelerometer, stillness, additional axes, direction alternation or minimum time between peaks is required. After a detection, a one-second cooldown prevents immediate repeated alarm dismissals, including across target changes. Invalid or out-of-order gyro samples discard a partial pair. Sampling can miss an excursion, and a second crossing within 500 ms does not prove that the second excursion's eventual deepest point was within 500 ms. Noise crossing back and forth around the threshold can count as separate excursions and accidentally dismiss an alarm. Compact logcat diagnostics under `OpenClockShake` report recognition, timeout, sensor readiness and aggregate invalid-sample counts; no raw sensor values, alarm labels, IDs or tokens are logged. Behavior and accidental-dismissal risk have not been validated on the watch. Use a backup alarm for important commitments and normal controls when necessary.

On the watch alarm list, scroll to **Test double shake** to open a foreground-only diagnostic using the same sensor and gesture pipeline. The test requests that the screen stay on while visible; normal screen timeout resumes when it is no longer visible. Wait for **Ready**, then try a double shake; it shows the first qualifying burst, successful detections and a count. The screen also shows peak absolute gyro speed for X/Y/Z independently, the peak combined speed, and the signed X/Y/Z components at that combined peak (all in degrees/second). Each independent axis peak may occur at a different time. Valid gyro samples contribute even if the gesture is not recognized or the detector is waiting to arm; tap **Reset** to start a new measurement, then shake. Scroll if needed to reach the buttons. The values are kept only in the test activity's memory, not logged or saved; readings stop when the test screen is paused or closed. The test itself does not dismiss an alarm, but a real ringing service still listens independently, so testing while an alarm rings can dismiss it.

Only one watch occurrence is targeted per gesture: the lowest-ID active watch-local occurrence takes priority; otherwise the lowest-ID mirrored phone occurrence is selected. Selection is independent of which ringing screen is visible, and the exact occurrence is rechecked before action. The resulting phone request can dismiss all currently ringing phone alarms. Gesture detection is available only while the corresponding existing ringing foreground service is running and the gyroscope registers successfully; if it is unavailable or registration fails, normal ringing and controls remain available. If background service start fails, a fallback notification alone does not provide gesture detection. Listeners stop when no eligible session remains or the services stop, and use the services' existing bounded wake locks rather than a permanent one. The OS may throttle or stop sensor delivery, including off-screen, and no device behavior is guaranteed.

### Snooze a ringing alarm

Tap the centre of the snooze bar to snooze using the displayed duration. Minus and plus adjust it for that occurrence: one-minute steps up to five minutes, then five-minute steps (5 → 10 and 10 → 5), within 1–1440 minutes. This selection does not change the alarm's saved snooze interval; a notification's snooze action still uses the saved interval. Snooze remains unavailable if disabled or the alarm has reached its limit. A pending snooze is highlighted in the alarm list; a phone alarm's snooze also appears on the watch after synchronization.

### Mute a ringing alarm

While the alarm screen is in the foreground and receives key events, press **Volume down** to mute the sound of **all occurrences ringing at that moment**, without dismissing or snoozing them. A visible status and toast confirm the mute. Vibration settings and the device's alarm volume are unchanged. The mute survives service/process recovery for those occurrences; later alarms and fresh snoozed occurrences follow their normal sound settings. Holding the key does not mute alarms that start later; press it again to mute those.

This is an alarm-screen shortcut, **not a global hardware-key hook**: it cannot intercept Volume down when Android routes the key elsewhere (for example, another app, a notification-only alarm, or a screen-off/lock-screen state where the alarm Activity has no key focus). Open the alarm screen first. Lock-screen delivery and physical-key behavior still require verification on the target device.

### Group controls

Use **⋮ → Create group** or **+ Create group** beneath the list. In the group editor, name the group and select alarms; selecting moves an alarm from its previous group, and deselecting a current member ungroups it. You can also choose **Group** in an alarm's editor. Tap the group heading or chevron to collapse/expand it. Each group's **⋮** menu offers **Edit group & alarms**, **Enable all alarms**, **Disable all alarms**, and **Delete group**. Deleting a group keeps its alarms. Group-wide enabling/disabling and membership edits save individual changes, not one transaction: on failure, review the list because some changes may already be saved.

## Wear OS companion

The watch app has its own **Watch alarms**, stored and scheduled on the watch, and a separate **Phone alarms** view synchronized from Open Clock on the paired Android phone. A watch-only alarm does not create an alarm on the phone. Phone alarms and groups remain owned by the phone; controls on the watch require a connection and confirmation from the phone. A disconnected or out-of-date phone view is labeled as such. Sync uses the Wear OS Data Layer, which requires Google Play services and the same app package and signing certificate on both devices; it can use Google's encrypted cloud relay when Bluetooth is unavailable. The matching companion has its own phone-alarm control path; it does not use or change the phone's third-party app access decisions. An unconfirmed request has an unknown outcome: refresh the phone state before trying again.

The watch also schedules independent fallback copies from the last successfully synchronized phone snapshot. Open the watch app while connected, check for fallback warnings, and test a near-future alarm with the phone disconnected. Phone edits made while disconnected may not reach the watch; the fallback can therefore use stale information. A watch dismissal uses the generic, bounded phone request described above, even without a shared occurrence identity. A phone dismissal can close a corresponding watch fallback when its identity is verified. A phone may keep ringing when delivery is delayed or unavailable; snooze remains independent. Mirror and fallback alerts can both appear. At most 350 phone fallback alarms are admitted; scheduling or permission failures are reported in the watch app. The complication still reflects the advisory phone snapshot rather than watch-local fallback snoozes. Actual offline delivery, reboot recovery, sound, vibration and installer behavior have not been verified on a device; keep a trusted backup alarm.

### Next-alarm watch-face complication

Choose **Open Clock → Next alarm** in your watch face's complication editor. Placement, including outer/edge positions, is determined by the watch face; the source offers SHORT_TEXT and LONG_TEXT but cannot make an incompatible slot support either type. Tap it to open the watch app and scroll to the earliest known enabled alarm, whether it belongs to the watch or phone; if none is known, the list opens normally. The app preserves any in-progress editor beneath the new list. It compares committed watch and phone next triggers and pending snoozes, not a separately calculated schedule, and selects up to the earliest three distinct alarms. Within 24 hours it shows the time alone; further out it adds a short date. Alarms are separated by a purple dot. To avoid faces that omit the optional title hiding later alarms, short text puts all candidates in its primary field; this deliberately exceeds the platform's seven-character short-text guidance and may be truncated by the face. For a single distant alarm, short text puts the date in the primary field and the time in the title. Depending on the face, the title or later alarms may be omitted, text may be truncated, and dot color may be ignored. Accessibility announces all displayed candidates. A long-text empty state says **No alarms**; short text splits this into **No** and the title **alarms**.

The phone snapshot is advisory, not a live guarantee. `?` before a time or **Check** means the alarm list is unverified (for example phone disconnected, snapshot old/missing, clock skew, an unconfirmed change, scheduling access/error, ringing, or queued delivery). Shown times in that state are only known candidates; another alarm may be earlier. **No alarms** requires an error-free local state and a successfully read snapshot from the reachable phone, published less than five minutes ago. Confirmation expires no later than five minutes after the read; it is not the watch app's separate 30-second connection indicator. Before first unlock or without a paired phone, the phone state is unknown, not empty. On timeline-capable faces, confidence expires automatically, known candidates become tentative, and past times are removed until the source falls back to **Check**. Older faces that ignore timelines show **Check**. Watch faces may not honour timelines or refresh promptly; check both apps for important alarms.

After a watch alarm change or an accepted phone snapshot, the watch requests a complication update promptly, coalescing closely spaced notifications and skipping unchanged state. A persisted recovery job and a 15-minute system refresh provide best-effort recovery. Repeated changes can produce more frequent update requests than Android's recommended five-minute average. The phone change must first reach the watch, and Wear OS or the watch face can delay or ignore a request, especially in ambient mode; immediate refresh is not guaranteed. Time changes and reboot can also briefly leave a cached timeline on the face until a refresh. The source does not poll the phone, start an always-on service, or publish watch alarms to the phone.

Phone create/edit/delete uncertainty is tracked until its receipt and matching snapshot are observed or the user explicitly clears it. For watch toggles and mirrored finish actions, the updated phone supplies an acknowledged revision; the complication waits for a matching snapshot. After a timeout, an older phone without this revision, or overlapping unresolved commands, the display remains conservative until a matching phone snapshot is published after the bounded command window; a later refresh may be needed. Unreadable state or a change of phone identity can leave it uncertain. Check the phone directly. Watch-face rendering, refresh timing, tap handling, scheduling and alarms have not been verified on a device for this feature.

To add a watch-only alarm, choose **Add on watch**; to create an alarm synchronized with the phone, choose **Add phone alarm**. Both use the time-then-customize flow for label, optional month/day, sound and vibration. Creating, editing or enabling an alarm shows its committed next trigger when available; phone alarms require both confirmation and a sufficiently recent phone snapshot. If the trigger is unavailable, the watch warns instead of claiming it is set. The phone-owned alarm requires the updated phone app and a reachable phone; the watch never substitutes a local alarm. Month/day resolves on the watch to the next future valid date and is stored as a one-time alarm with a concrete year, shown on the watch; it does not repeat annually. The phone validates that date in its own local time zone. A nonexistent daylight-saving time is rejected. Without a date it rings once at its next occurrence.

Phone creation is confirmed by a durable receipt; the list is updated from the phone snapshot. A create is not retried automatically. If the result is unconfirmed, use **Check phone result** and inspect the phone before clearing the unconfirmed request: creating another could duplicate the alarm. New phone creates are blocked until the uncertainty is resolved or explicitly cleared. Phone-side receipt history has a bounded capacity and is not evicted, so eventually further watch-originated creates can be refused; ordinary phone-side edits do not use that receipt history. On the watch, Back and swipe-to-dismiss step from customization to time to the alarm list; at the list they leave the app. A system Home/power button can still return directly to the watch face.

On the watch alarm list, tap an alarm to edit its time, label, date, sound or vibration; touch and hold for actions including delete. Other fields, including weekday repeat, group and snooze settings, are preserved unless the chosen date explicitly replaces a repeating schedule. Changing a schedule or deleting an alarm can stop an active ring or snooze. Phone edits and deletions require the updated phone app and use a revision-checked phone-side commit. If the result is unconfirmed, use **Check phone change** and inspect the phone before clearing it; it may already have been applied. A confirmed receipt does not guarantee that the phone list snapshot has arrived yet. The bounded, non-evicted mutation receipts can eventually prevent further watch-originated phone edits or deletions; use the phone app in that case. Identical phone snapshots do not rebuild the list. The ringing screen shows the alarm time, its label and Dismiss/Snooze controls; snooze can be unavailable when its configured limit is reached.

Phone-alarm mirroring and remote dismiss/snooze depend on connectivity and Android allowing notifications, full-screen display and background work. After the watch's first unlock following boot, it schedules a phone refresh; background Data Layer listeners and reconnect events do not require the watch UI to remain open. This is not a permanent connection, and force-stop, power restrictions, disconnection, clock skew, process death or delayed synchronization can prevent or delay mirroring. When Android blocks continuous background playback, the watch attempts a one-shot notification alert using separately adjustable fallback channels; its sound and vibration remain subject to notification settings and Do Not Disturb. Notification, full-screen and foreground-service restrictions can still make it silent or prevent the ringing screen. Verify notification and exact-alarm access on the watch; a disabled notification or denied exact-alarm access can prevent alerts. A build or successful installation does not verify alarm behavior, sound, vibration, reboot recovery or watch/phone synchronization.


## Build

Use JDK 17+ (the sources target Java 17), Android SDK platform **34**, and build-tools **34.0.0**, with accepted Android SDK licenses. The app compiles against and targets API 34. Set `ANDROID_HOME` to your SDK directory or create an untracked `local.properties` containing `sdk.dir=/absolute/path/to/android-sdk`. The included wrapper selects Gradle **8.10.2**; the Android Gradle plugin is **8.5.2**.

```sh
# From the repository root; the first build needs network access for uncached dependencies.
./gradlew --no-daemon assembleDebug

# Build both the phone and Wear OS debug APKs:
./gradlew --offline --no-daemon :app:assembleDebug :wear:assembleDebug
```

The phone APK is `app/build/outputs/apk/debug/app-debug.apk`; the watch APK is `wear/build/outputs/apk/debug/wear-debug.apk`. Both must be signed with the same certificate for synchronization. Android Studio can also open this repository using the same SDK/JDK configuration. No emulator or device is needed to compile; a successful build does not verify alarm delivery.

## Early-build alarm safety

**This is an early build, not a validated replacement for a critical alarm.** Actual ringing, reboot recovery, lock-screen presentation, permission changes and manufacturer battery policies require testing on the target device. A saved/enabled alarm or successful API reply is not proof of timely, audible delivery. Review any displayed scheduling error. Force-stop, revoked permissions, silent sound/vibration settings, muted channels, Do Not Disturb and OS restrictions can prevent alerts. See [Android permissions and delivery limitations](#android-permissions-and-delivery-limitations) below. There is no custom sound picker, multi-request transaction, or API permission-grant operation. Approved apps have full read/write access, so grant only to apps you trust.

## External access and security

**Version 1.3 breaks the old API:** the exported `com.openclock.IntentApi` receiver and `com.openclock.API` ordered-broadcast transport no longer exist. Old clients and ADB broadcast examples will not work. There is no migration of prior access: every app starts unapproved.

An app's first request is denied before parsing or dispatch. If notifications are available, Open Clock posts an access request with Allow, Not allow and Don't ask again actions. Each notification action opens a token-validated review for that specific app with the selected choice shown; it does not apply the choice. Tap an action on the unlocked review screen to apply it directly, without a confirmation dialog. Stale or replaced notification tokens are rejected. The rejected request is discarded, never queued or replayed: after approval the client must issue a new request. **⋮ → Apps & access** also shows pending, allowed, declined and blocked apps, and allows decisions or removal/reset. Not allow denies for now and later requests can notify again; Don't ask again suppresses further prompts until changed or removed in the UI. Removing an entry resets it to unknown. These decisions are full access to *all* alarm/group operations, not per-operation permissions.

When an approved app creates an enabled alarm, enables one, or changes the time, date or repeat days of an enabled alarm, Open Clock requests a text toast with the committed next trigger. It warns instead when that trigger or required access is unavailable. The toast is best effort: Android or device policy may suppress or rate-limit background toasts, and it can be missed while the screen is off. A toast is not proof of alarm delivery. Disabled alarms, unrelated edits and failed requests do not produce this confirmation.

Authorization is checked on every Binder transaction using Android's calling UID, exactly one installed package, current single signing certificate, and installation time. Shared UIDs, multiple current signers, missing or ambiguous identity, locked credential storage, and storage failures are denied. A UID, signer or installation change invalidates the old decision. The access list is stored separately from alarm data in no-backup credential-protected storage. Operations and decisions are serialized: a revocation waits for an already-running operation and applies to subsequent transactions. An operation already admitted before revocation may complete. If an access write or readback fails, all access is denied for the rest of that process; after restart the last durable decision may remain, so a failed revocation must be retried and checked in the UI. Package visibility requires `QUERY_ALL_PACKAGES` so shared UIDs cannot appear singular; distribution through stores may impose policy restrictions on that permission.

Notifications require Open Clock notifications enabled, the **App access requests** channel enabled, and on Android 13+ its notification permission. If unavailable, requests are still denied and appear in Apps & access; no grant depends on notification delivery. Notifications are coalesced per app identity, normally at most one per hour, without a global minute throttle. A backward clock change after process restart can reset that app's limit; while the process runs a monotonic clock enforces it. The OS may still delay, suppress, or rate-limit notifications; there is no deferred retry, so use the access list if one is missed. At most 100 records are retained; remove old entries if the list fills. App labels are untrusted presentation text; check the package name. App floods can still fill the bounded list or compete for service admission. Android, trusted system services, accessibility services, root, compromised OS/app processes and user-approved automation are outside this authorization boundary. No UI can protect against a malicious accessibility service acting as the user; overlays are restricted/filtered where supported. Notification listeners can invoke notification actions, but those only open review and cannot silently grant access. There is no comprehensive access audit log.

**⋮ → Apps & access** also shows a best-effort, local activity history of up to 100 admitted requests, newest first. It records the verified package (or an unverified-app marker), time, a fixed operation name and a coarse outcome. Requests that have not been approved are recorded only as a generic request, without parsing their JSON; unknown or malformed operations are generic too. Alarm labels, group names, request/response data and error details are never recorded. The history is separate from permissions and alarms, in credential-protected no-backup storage, and is not transmitted by Open Clock. Old events are discarded as new ones arrive. It does not record rejected Binder envelopes or service-busy requests, may lose events on process/storage failure, and is not a security audit. A detected log failure is shown in the screen for the rest of the process; a restart can leave an incomplete history without an error. Log failures do not change access decisions or operation replies; a failed outcome does not guarantee a mutation was rolled back.

## External API transport

Bind explicitly to package `com.openclock`, service class `com.openclock.AccessService`, using `Context.bindService` and `BIND_AUTO_CREATE`. Clients targeting Android 11+ should declare `<queries><package android:name="com.openclock" /></queries>` in their manifest. There is no broadcast, intent request extra, caller credential, or callback receiver. Verify the installed Open Clock signer on the client if it needs protection from a substituted provider.

The returned `IBinder` implements a synchronous raw Binder protocol:

- Descriptor: `com.openclock.IAccessApi` (also returned by `INTERFACE_TRANSACTION`).
- Transaction code: `IBinder.FIRST_CALL_TRANSACTION` (`1`); one-way calls are not supported.
- Request parcel: `writeInterfaceToken("com.openclock.IAccessApi")`, then `writeString(json)`, with no trailing fields. Parcel size is limited to 65,536 bytes; JSON is at most 16,384 UTF-16 characters.
- Reply parcel: `readException()` followed by `readString()` containing the JSON result. `ok` and `error.code` indicate success or failure. Execute off the UI thread, handle binding death/disconnection and `RemoteException`, and recycle both parcels. A disconnected/ambiguous mutation must be reconciled by listing state, not blindly replayed.
- Authorization, validation and operation run synchronously. Separate calls have no cross-sender FIFO or multi-request transaction. A successful authorization does not guarantee alarm delivery.

Requests are at most 16,384 UTF-16 characters and eight nested JSON levels. Only strict JSON is accepted: unique keys; lowercase `true`/`false`/`null`; valid JSON string escapes with no unescaped U+0000–U+001F control characters; integer literals within signed 64-bit range; no comments, trailing content, decimal/exponent numbers, string-to-number or string-to-boolean coercion. Unknown operation fields, unknown alarm/group fields, wrong types, invalid ranges, and missing required fields are errors. Keys and operation names are case-sensitive. Null is accepted only for `date` and `groupId`.

### Operations

All rows below show complete request shapes. Replace illustrative IDs with IDs returned by this installation. Creates must omit IDs; updates require an existing ID. An update merges only the supplied fields with the stored object; omitted fields are preserved. The complete merged alarm is validated before saving. For example, changing a repeating alarm to a dated one requires setting `days: []` as well as `date`.

| Operation | Request | Success JSON |
| --- | --- | --- |
| List alarms | `{"op":"alarms.list"}` | `{"ok":true,"alarms":[...]}` |
| Create alarm | `{"op":"alarms.create","alarm":{"hour":7,"minute":30}}` | `{"ok":true,"alarm":{...}}` |
| Trigger a new alarm ASAP | `{"op":"alarms.triggerNow","alarm":{"label":"Automation alert","sound":true}}` | `{"ok":true,"alarm":{...}}` |
| Update alarm | `{"op":"alarms.update","alarm":{"id":123,"enabled":false}}` | `{"ok":true,"alarm":{...}}` |
| Delete alarm | `{"op":"alarms.delete","id":123}` | `{"ok":true,"id":123}` |
| List groups | `{"op":"groups.list"}` | `{"ok":true,"groups":[...]}` |
| Create group | `{"op":"groups.create","group":{"name":"Work"}}` | `{"ok":true,"group":{...}}` |
| Update group | `{"op":"groups.update","group":{"id":456,"name":"Weekdays"}}` | `{"ok":true,"group":{...}}` |
| Delete group | `{"op":"groups.delete","id":456}` | `{"ok":true,"id":456}` |
| Add/move alarm to group | `{"op":"groups.addAlarm","groupId":456,"alarmId":123}` | `{"ok":true,"alarm":{...}}` |
| Remove alarm from group | `{"op":"groups.removeAlarm","groupId":456,"alarmId":123}` | `{"ok":true,"alarm":{...}}` |

An alarm belongs to at most one group. Adding it to a group replaces its previous membership; adding the same membership again is safe. Removing a membership that is already absent is a successful no-op and never removes membership in a different group. Both named objects must still exist. You may also change membership with an alarm update's `groupId` field, or clear it with JSON `null`.

`alarms.triggerNow` creates a new, dated one-shot alarm due immediately through the normal Android alarm path; it does not trigger or change an existing alarm. `alarm` is optional and may contain only `label`, `sound`, `vibrate`, `vibrationPattern`, `snoozeEnabled`, `snoozeMinutes`, `snoozeCount`, and `groupId`, with the same validation and defaults as a normal create. For example, `{"op":"alarms.triggerNow"}` defaults to vibration on and sound off. The returned `hour` and `minute` are display fields, not the scheduled precision: `nextTriggerAt` is the intended immediate occurrence, with a minimum one-second Android registration delay. Actual dispatch can be delayed by Android. The alarm remains in the list and becomes disabled after dismissal or expiry; repeated requests create separate alarms and eventually consume storage capacity. Snooze and watch synchronization use the normal alarm mechanisms. A successful response means it was registered and saved, not that it rang or was heard. An unacknowledged request may have succeeded; inspect the list before considering a retry.

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
| `sound` | Boolean; enable/disable the system default alarm sound | `false` |
| `vibrate` | Boolean | `true` |
| `vibrationPattern` | String: `basic`, `pulse` or `heartbeat` | `"basic"` |
| `snoozeEnabled` | Boolean | `true` |
| `snoozeMinutes` | Integer `1..1440` | `5` |
| `snoozeCount` | Integer `0..1000`; `0` means unlimited snoozes | `3` |
| `groupId` | Existing positive group ID or JSON `null` | `null` |

Returned alarms also include backend-owned, read-only `nextTriggerAt` (epoch milliseconds, `0` when no scheduled timestamp is recorded), `snoozedUntil` (epoch milliseconds, `0` when no snooze is pending), and `schedulingError` (string or JSON `null`). A pending snooze can remain visible when its registration has failed; check `schedulingError` and permissions. These are diagnostic fields, not accepted request fields; construct update patches instead of sending a complete returned alarm back unchanged. The backend currently limits storage to 500 alarms (at most 450 enabled) and 100 groups; exceeding these limits produces `INVALID_ARGUMENT`.

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
| `ACCESS_DENIED` | Not approved; review Apps & access, then explicitly retry |
| `ACCESS_BLOCKED` | User chose Don't ask again |
| `IDENTITY_UNAVAILABLE` | Caller has ambiguous, shared, unsigned or unresolvable identity |
| `ACCESS_UNAVAILABLE` | Access storage or user unlock is unavailable; no operation was dispatched |
| `ACCESS_BUSY` | Another Binder request is admitted; this request was not dispatched. Retry later |
| `UNKNOWN_OPERATION` | `op` is a string but not a supported operation |
| `INVALID_ARGUMENT` | Malformed/missing JSON, invalid shape/type/range, unknown field, missing required value, incompatible recurrence, or backend validation rejection |
| `NOT_FOUND` | Referenced alarm/group ID does not exist |
| `PERMISSION_DENIED` | Android raised a `SecurityException` |
| `STORAGE_ERROR` | Storage raised an `IOException`, or a store read wrapped an I/O/JSON-decoding failure in `IllegalStateException`; no empty-store or pretend-success fallback |
| `INTERNAL_ERROR` | Another operation failure; read back state before retrying |
| `RESULT_TOO_LARGE` | Serialized result exceeds 200,000 UTF-16 characters; there is no pagination |

Permission/storage/internal errors do not promise rollback if saving completed before scheduling failed. A successful save means the backend accepted the operation, **not** that Android has granted all permissions or guarantees an audible alert. Large list responses can fail with `RESULT_TOO_LARGE`; result size is bounded to reduce Binder transaction risk.

## Android Java client

Run the transaction on a worker thread after `onServiceConnected`. Do not automatically replay a mutation on disconnect. `bindService` returning true means a bind was requested, not that access was granted.

```java
// Manifest: <queries><package android:name="com.openclock" /></queries>
Intent intent = new Intent().setComponent(
    new ComponentName("com.openclock", "com.openclock.AccessService"));
ServiceConnection connection = new ServiceConnection() {
    @Override public void onServiceConnected(ComponentName name, IBinder service) {
        executor.execute(() -> {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.openclock.IAccessApi");
                data.writeString("{\"op\":\"alarms.list\"}");
                if (!service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                    throw new RemoteException("Transaction not supported");
                reply.readException();
                String json = reply.readString();
                // Parse JSON: check ok and error.code. ACCESS_DENIED requires
                // user approval followed by an explicit new request.
            } catch (RemoteException | RuntimeException e) {
                // Unacknowledged: reconcile state before retrying a mutation.
            } finally { reply.recycle(); data.recycle(); }
        });
    }
    @Override public void onServiceDisconnected(ComponentName name) {
        // Binder died; do not replay an uncertain mutation.
    }
};
boolean requested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
// Unbind when no longer needed; also handle null binding/binding death as appropriate.
```

Imports: `android.content.ComponentName`, `Context`, `Intent`, `ServiceConnection`; `android.os.IBinder`, `Parcel`, `RemoteException`; plus an application-owned worker `executor`. Handle bind failures and lifecycle. For other operations, serialize the JSON from the table above. A raw `adb shell am broadcast` cannot call this Binder service.

## Android permissions and delivery limitations

Calling this IPC requires user approval in Open Clock. **Open Clock itself** needs the relevant platform access: exact-alarm special access where required, notification runtime permission on Android 13+, and applicable full-screen alarm access on newer Android versions. Review these in the app/system settings before relying on alarms. Device volume, Do Not Disturb, battery policies, OS restrictions, force-stop state, and sound URI access can prevent timely or audible delivery. The IPC does not grant permissions, open permission prompts, disable Do Not Disturb, bypass force-stop, or certify permission readiness; there is no permission-status or permission-grant operation.

The custom API does **not** implement Android's standard `AlarmClock.ACTION_SET_ALARM`, `ACTION_SET_TIMER`, `ACTION_SHOW_ALARMS`, or related `AlarmClock` extras. Do not infer support from a manifest filter; only the Binder transport and JSON operations documented here are implemented.
