# Kakao VoiceRoom Manager (standalone Android app)

This module is a separate installable Android application from KakaoAutoSender.

- module: `:voiceroom`
- application ID: `com.local.kakaovoiceroom`
- app label: `보이스룸 매니저`
- APK artifact: `KakaoVoiceRoomManager-debug-apk`

It does not share the KakaoAutoSender launcher, UI, preferences, notification-listener workflow, or application ID. Both apps can be installed side-by-side.

## Account model

The app never asks for a Kakao ID, password, session token, or cookie. It operates only with the account already logged into the official KakaoTalk app on the same phone.

## Current v0.3.1 scope

- multiple Open Chat room configs with per-room ON/OFF
- manager start/stop and persisted lifecycle state
- separate non-destructive `안전 점검` path: verifies the target room, opens the bottom composer `+`, enters the VoiceRoom UI, and stops before pressing a create button
- explicit warning before `실제 점검`, because it may create a VoiceRoom when none is active
- strict package gate: delayed Accessibility callbacks never inspect or click a non-Kakao window
- step de-duplication and one-shot follow-up scheduling so a room/link is not repeatedly clicked
- Kakao title normalization for Accessibility labels that merge the room title with a participant count (for example `게임방 386`, or `1 1` for a room named `1`)
- stored `open.kakao.com` links are used as an additional room-entry proof while title matching remains strict for chat-list fallback
- bottom-left composer action is selected by screen region so the top-right room menu is not mistaken for the VoiceRoom entry path
- stage-gated flow: target room -> bottom `+` -> VoiceRoom -> create -> confirm -> active verification
- stage-level timeout diagnostics instead of one generic failure
- privacy-preserving diagnostics store booleans such as title/input/open-chat/add/voice/create/active, not chat message text
- progressive retry backoff for unattended failures: 1m -> 3m -> 10m -> 30m -> 1h
- 48-hour scheduling with a 5-minute pre-expiry check window and real-UI polling near expiry
- newly created VoiceRooms always reset their own start baseline
- pending-job watchdog and stale-job recovery so a killed/no-event run cannot leave the manager stuck forever
- reboot/update recovery of an interrupted task
- routine scheduled checks do not steal the foreground while the user is actively using the phone; they defer 5 minutes until the phone is idle, while direct NEW/`실제 점검` actions still run immediately
- scheduled screen wake attempt; secure lock screens are retried, never bypassed
- temporary global media-volume mute during unattended automatic work, with the prior volume restored on success/failure/stop/recovery
- Android 15 dashboard system-bar overlap opt-out for the current target SDK
- exact-alarm and battery/accessibility setup shortcuts
- unit tests for title matching, retry policy, unknown-start, pre-expiry, final-window and overdue-active scheduling behavior

## Important validation gate

KakaoTalk does not provide a public VoiceRoom automation API. Actual labels and accessibility nodes can change by KakaoTalk version/device. Before unattended use, validate one target room on the exact phone and KakaoTalk build with `안전 점검`, then run one explicitly confirmed live check.

The media mute is intentionally global and temporary rather than an unsupported per-app routing trick. Whether Kakao continues playing VoiceRoom audio after creation, the exact screen-off wake behavior, one-account multi-room concurrency, and any additional confirmation/security screen still require live-device validation before silent unattended operation is declared ready. The app does not bypass lock screens, Kakao security checks, account/session limits, or moderation controls.
