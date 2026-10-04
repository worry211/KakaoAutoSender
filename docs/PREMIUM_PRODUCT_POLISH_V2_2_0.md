# Premium product polish v2.2.0

This release consolidates the strongest buyer-facing UX work into one commercial candidate on top of the v2.1.3 launch hotfix and v2.1.2 security hardening.

## Dashboard

- Premium automation hero with clear LIVE / READY / SETUP state.
- High-signal metrics for connected rooms, enabled rooms and rooms ready to send.
- Professional Korean customer copy instead of raw diagnostic-style status text.
- Cleaner room cards with connection state, schedule, message preview and safer action hierarchy.
- Searchable bottom-sheet style room picker with candidate confidence and reconnect awareness.
- Support and reset actions are visually separated from primary automation controls.

## Room editor

- Rebuilt hierarchy for connection status, message, image, schedule, usage limits and final actions.
- Premium card treatment consistent with the dashboard and license screen.
- Safer image behavior messaging and stronger destructive-action hierarchy.
- Save / one-time test / back actions grouped separately from delete.

## Preserved behavior

No licensing, Discord admin scope, APK signing, anti-tamper, exact-room fail-closed routing, scheduler or photo-delivery semantics are weakened by this release.

## Release gate

Do not raise the production minimum version to versionCode 25 until the signed v2.2.0 APK passes physical-device QA: launch, activation/session restore, room discovery, text test send, schedule/interval, photo fail-closed behavior, suspend/resume, reset-device and revoke.
