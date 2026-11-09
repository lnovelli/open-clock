# Architecture and development

The current source is the authority; contracts and version-specific behavior must be rechecked when editing. See [README](../../README.md) for user-facing behavior and [BACKEND](../../BACKEND.md) for the shared engine's detailed invariants.

## Ownership map

- `app/` contains the phone UI, `AlarmStore`, `AlarmTimes`, receiver, service, notification/presentation logic, third-party access service and phone side of Wear sync. `ClockApplication` supplies lifecycle hooks. Alarm state is serialized and stored in device-protected, backup-excluded storage; external access decisions are separate credential-protected no-backup state.
- `wear/` owns watch UI, phone mirror, local phone-fallback sync, dismissal outbox, complication, gesture and repair code. `wear/build.gradle` copies a selected set of phone engine sources/resources at build time; it is not a separate fork. The compiled watch engine still uses separate watch storage and AlarmManager. Watch-local alarms do not publish back as phone alarms.
- Wear Data Layer is a separate companion protocol, not the third-party Binder access grant. Mirror events, the advisory phone snapshot and independently registered fallback alarms are different mechanisms. See [wear-sync.md](wear-sync.md).
- `AccessService`, `AccessPolicy`, `AccessActivity`, `IntentApi` and `ExternalAlarmConfirmation` implement the phone's third-party boundary. The class name `IntentApi` is not evidence that the retired broadcast transport is available. See [external-integration.md](external-integration.md).

## Build and verification

Use the JDK, SDK and wrapper versions documented in [README Build](../../README.md#build); verify `app/build.gradle`, `wear/build.gradle` and both manifests when requirements change. For changes to shared engine code build both modules, for example:

```sh
./gradlew --no-daemon :app:assembleDebug :wear:assembleDebug
```

Offline builds require cached dependencies. Inspect the merged manifest and compiled resources when relevant. APK handoff requires checking package, version, debuggability and signing certificate with Android build tools, including signer continuity against the intended installed/baseline APK; both companions need the same package and signer for the Data Layer. Avoid accessing keystore secrets. Compilation and host fakes do not test physical alarms, security consent, OEM behavior or updates. See the focused test matrices in [alarm-engine.md](alarm-engine.md), [wear-sync.md](wear-sync.md) and [external-integration.md](external-integration.md).

## Release/update contract

Only publish or deploy with explicit authorization. Inspect current source, branch, version codes, updater implementation and live stable release first. The current manual updater uses latest GitHub release assets named `open-clock-phone-v<versionCode>.apk` and `open-clock-watch-v<versionCode>.apk`; each offered role must be present in the latest release. This is not a manifest-based updater. Compare package/version/signer and hashes, preserve companion signing compatibility, and verify exact published metadata and full asset bytes after publication. A successful download or installer launch is not an installed update. Do not infer runtime behavior from release/build checks; never embed release credentials in the app.
