# KakaoAutoSender

KakaoAutoSender is a local Android automation app that reuses KakaoTalk's Android notification reply action (`RemoteInput`) to send configured messages to confirmed conversations.

## Privacy and routing policy
- No remote-control server.
- No `INTERNET` permission.
- Runs locally on the phone.
- Requires Android notification-listener access.
- KakaoTalk notifications must remain enabled so reply sessions can be captured. Sound, vibration and pop-ups may be disabled separately.
- Sends are **fail-closed**: if the app cannot confirm the exact saved reply session, it does not guess another room.
- Newly added rooms use an internal routing alias that is separate from the visible Kakao room name. The internal alias is never shown in the normal UI.

## v1.0.0
- Room-first dashboard: the normal UI and room chooser show the actual Kakao room name only.
- Multiple rooms with independent messages and schedules.
- Per-room pause/resume and one-shot test send.
- Global start and immediate stop.
- Repeating schedules from **1 minute** upward.
- Fixed daily schedules such as `09:00, 13:30, 20:00`.
- 1/5/10/30/60-minute quick presets.
- Per-room daily limit or unlimited mode.
- Next-send time, connection state, send count and failures visible on each room card.
- Android exact-alarm support when the device grants it, with safe inexact fallback.
- Automatic schedule restore after reboot/app update.
- Android conversation-shortcut (`shortcutId`) based recovery when KakaoTalk exposes a stable conversation identity.
- Legacy v0.8/v0.9 profiles are migrated without discarding message/schedule settings.
- Unit tests + Android Lint + APK build run in CI.

## Normal setup
1. Install and open the APK.
2. Grant notification access.
3. Receive a new message in the Open Chat room you want to add.
4. Tap **새 방 추가** and select the Kakao room name.
5. Set the message and either an interval or daily send times.
6. Use **지금 1회 전송** to verify the exact target.
7. Enable the room and tap **전체 시작** on the dashboard.
8. Use **전체 중단** at any time to cancel pending automation.

## Important platform limitations
KakaoTalk does not expose a supported public API for arbitrary Open Chat posting. This app therefore depends on reply actions attached to KakaoTalk notifications. KakaoTalk/Android updates, reboot, process death, cleared/expired reply actions, OEM battery policy, or notification changes can temporarily invalidate a session. Receiving a new message in that room normally provides a fresh reply session, and a saved Android conversation shortcut can restore the correct binding automatically when available.

A 1-minute schedule is supported by the app, but Android may defer alarms while the phone is idle if exact-alarm access is unavailable or the OS applies battery restrictions. Fixed-time mode is most precise when exact-alarm access is granted.

Very frequent automated posting can also be limited by KakaoTalk or an Open Chat room's anti-spam policy. The app does not attempt to bypass platform rate limits or moderation.

## Build
GitHub Actions runs unit tests, Android Lint, and creates a debug APK on pushes/PRs.

Artifact: `KakaoAutoSender-debug-apk`
