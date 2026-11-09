# Working on Open Clock

- Inspect the working tree, existing documentation and current source before changing behavior; preserve unrelated work. This repository contains separate phone and Wear APKs; the watch compiles selected phone engine sources, so check both affected paths.
- Treat alarms as safety-sensitive: preserve committed occurrences, user settings, permissions, identity and recovery. A save, build, notification, acknowledgement or recorded next trigger is not proof of timely, audible delivery. Do not retry an uncertain mutation blindly.
- Keep user-visible and API documentation aligned with changes. Do not add credentials, private endpoints, personal paths or local deployment instructions to this public repository.
- Use focused checks against production code and report exactly what ran; device behavior requires separately authorized testing. Follow the build instructions in [README](README.md#build); inspect current manifests and Gradle versions rather than assuming old values.

## Task references

- Module ownership, build and releases: [architecture and development](docs/development/architecture.md).
- Scheduling, persistence, DST, alerting and verification: [alarm engine](docs/development/alarm-engine.md) and [BACKEND.md](BACKEND.md).
- Phone/Wear synchronization, offline fallback, dismissal and complication: [Wear synchronization](docs/development/wear-sync.md).
- UI, navigation, state restoration and gestures: [UI interactions](docs/development/ui-interactions.md).
- Binder security, confirmation and Hermes integration: [external integration](docs/development/external-integration.md) and the [README API contract](README.md#external-access-and-security).
- User-facing behavior, limitations and update assets: [README.md](README.md).
