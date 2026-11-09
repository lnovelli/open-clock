# UI and interaction development

Read the current phone and Wear activities, `AlarmPresentation`, resources and [README](../../README.md) before changing interactions; source and product behavior may evolve. A build or host fixture does not establish physical layout, key routing, gesture delivery or toast visibility.

## Phone

- Follow the system light/dark theme for the list and access screens; inspect both resource variants and programmatic colors. Keep the intentional dark-violet ringing presentation readable, including system-bar icons. Preserve editor drafts/baselines, active occurrence and selected snooze across recreation.
- Keep alarm rows compact and readable with accessible toggle targets; a group heading/chevron needs a generous touch target and state-specific accessibility label. Preserve list scroll across switches, refreshes, editor return and recreation. If a `ScrollView` must be rebuilt, restore its offset after measurement and reject stale callbacks or intermediate zero offsets during overlapping refreshes.
- New phone/watch drafts use the current hour and minute, sound off and vibration on; preserve existing and explicitly selected values. The current minute can resolve to tomorrow. Saving an existing edit enables/schedules it subject to access; the list switch remains the disable path. Show committed pending snoozes and scheduling errors without implying registration or delivery.
- Phone group enable confirmation summarizes the earliest committed trigger rather than emitting a toast for each alarm. UI and external confirmation paths have different admission rules; see [alarm-engine.md](alarm-engine.md) and [external-integration.md](external-integration.md).

## Wear

- Preserve phone/watch ownership throughout create/edit/navigation. Back moves one step within a multi-step flow and exits from the list; dialogs and keyboard get their own Back first. Handle activity swipe-to-dismiss consistently with page Back while ignoring stale callbacks; system Home/power remains system-controlled. Keep drafts across Back and recreation.
- Preserve the watch time-then-customize creation flow, visible Save, editable label/date/sound/vibration, row tap-to-edit, long-press actions and the separate enable switch. A month/day choice resolves to a concrete next future date, including leap-day handling; reject a selected DST-gap occurrence rather than silently moving it to another year. Phone-owned changes need an acknowledgement and appropriate matching snapshot; never promise a phone change from an offline watch-only alarm.
- Round-screen ringing content needs readable time/label and accessible distinct dismiss/snooze controls with meaningful offline, mute and unconfirmed state. Measure round-safe child content at its final size during measurement rather than relying on placeholder layout sizes. Preserve exact tokens, snooze eligibility, requested Back/notification-clear dismissal and phone acknowledgements.
- Preserve swipe-delete direction: either horizontal direction on phone, physical right-to-left on watch, with the other watch direction reserved for navigation. Keep the existing confirmation and phone mutation protocol; exclude switch-originated touches, preserve vertical scroll and accessibility, cancel child press only when a drag wins, and abort on multitouch or focus loss.
- Identical phone snapshots should not needlessly rebuild the list. Compare display-relevant state, including freshness and pending/confirmation transitions, without treating publication time alone as content. On tap, atomically resolve a fresh revision and verify that the alarm still matches the displayed row.

See [wear-sync.md](wear-sync.md) for snapshot races, offline fallback, dismissal scope and the complication. Run focused tests on real production formatters, navigation/gesture boundaries and state preservation; report separately any explicitly authorized phone/watch UI tests.
