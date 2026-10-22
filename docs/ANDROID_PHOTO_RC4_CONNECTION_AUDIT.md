# Android photo RC4: connection flow repair

Candidate: 2.3.20-photo-rc4 (51). This is a test candidate, not a verified commercial final release.

## Fixed paths

- Capture the verified Kakao activity opening action immediately after notification binding and automatic recovery. Opening a chat may dismiss its notification before the photo settings page ever runs. The opening action can survive dismissal within this process/listener session; the dismissed notification reply action remains invalid.
- Accept a photo-only room candidate when a Kakao notification has a conversation title, stable conversation identity and a Kakao-created activity opening action, even if it has no text reply action. Reject summaries, unidentified notifications and foreign opening actions. Never advertise text reply readiness for an opening-only route.
- Do not request a listener rebind on every main-screen refresh while the listener is connected. A real listener disconnect still clears runtime routes and stops a running tour.
- Parse notification-access component names, accepting both short and full component forms and rejecting substring matches.
- Reconnect a saved room by its existing identity before considering names. Do not attach a different stable identity to the same saved room merely because the title or notification key is reused. Invalidate an existing route when its runtime notification changes identity or becomes unusable. Ignore older notification callbacks.
- Allow first preparation when the requested chat is already open; manual room confirmation is still required. For scheduled transmission, an unchanged current chat is accepted only with an already matching saved binding and identity.
- Poll for the input connection after navigation even when Android does not emit another IME view-start callback. Polls belong to a navigation generation, cease on cancellation or phase change, and remain bounded by the existing opening deadline. Wait for a visible Kakao input connection before arming a tour send.
- Expose photo scheduling directly in each room editor. Carry that room into the photo menu with saved media, text and schedule preserved. An unsupported notification-photo test directs the user to photo preparation instead of repeatedly attempting the unsupported reply path. Display text reply and photo opening capabilities separately.

## Validation

NotificationConnectionFlowTest starts from notification callbacks, candidate listing and manual binding, rather than inserting only preverified targets. It covers immediate notification dismissal, opening-only notifications, summaries, foreign actions, missing identity, reused notification keys, reconnection and permission component forms. Lifecycle regressions cover already-open preparation, absent IME callbacks and cancellation of stale polls. Room editor navigation is checked on the host; five setup/editor screens are covered by emulator instrumentation on API 30, 34 and 35.

The Android emulator has no real Kakao account. Tests do not prove Kakao's actual notification format, recipient receipt, OEM physical panel behavior, or operation with a secure lock. Those remain physical-device checks. Missing stable conversation identity is surfaced as an explicit unsupported connection rather than silently using a room title as identity.

## Installation and first connection

Install as an update; retain existing app data. Check the installed version is 2.3.20-photo-rc4. Enable notification access, enable the target Kakao room's notifications, leave the chat screen and receive a fresh message so its notification remains in the notification shade. Use Main → New room connection. Saved rooms use Recheck connection; identical titles are resolved by saved identity. Set a photo, enter Photo schedule/repeat, complete keyboard/accessibility permissions, prepare the selected rooms, then perform one controlled test before enabling repetition. Physical panel OFF additionally requires phone-only wireless pairing and connection. Do not reset all stored room data merely to obtain a new notification route.
