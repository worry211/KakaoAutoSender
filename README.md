# 카톡매크로 (KakaoAutoSender) 2.0

KakaoAutoSender is a local Android automation app that reuses KakaoTalk's Android notification reply action (`RemoteInput`) to send configured messages to confirmed conversations.

## Privacy and routing policy
- HTTPS licensing uses Cloudflare Worker + D1, with a private Discord seller panel.
- Kakao sending and all room/message/photo configuration run locally on the phone.
- The licensing API receives only installation identity, app version and authentication.
- Requires Android notification-listener access.
- KakaoTalk notifications must remain enabled so reply sessions can be captured. Sound, vibration and pop-ups may be disabled separately.
- Sends are **fail-closed**: if the app cannot confirm the exact saved reply session, it does not guess another room.
- Newly added rooms use an internal routing alias that is separate from the visible Kakao room name. The internal alias is never shown in the normal UI.

## v1.1.0 final reliability pass
- Keeps the v1.0 room-first dashboard and multi-room configuration model.
- **One room per alarm dispatch** instead of firing a whole due batch in one receiver run.
- When several rooms are due together, the next room is scheduled **2-5 seconds later**. This avoids back-to-back Kakao reply actions and also keeps Android `BroadcastReceiver` work short.
- Interval schedules keep the configured minute value as the base and add a **stable 3-10 second per-cycle offset**. Example: a 15-minute room runs at roughly `15 minutes + 3-10 seconds` each cycle.
- The interval offset is deterministic for that room/cycle, so repeated scheduler scans do not keep adding more delay.
- Fixed daily times remain fixed; if several rooms share the same fixed time, cross-room 2-5 second spacing still applies.
- Failed sends retry up to three times with a short randomized retry gap, then fail closed and move on without rerouting to another room.
- Pressing global stop prevents the next room in a staggered batch from being scheduled.
- Alarm scheduling clamps past timestamps to a safe near-future time and still uses exact-alarm support when Android grants it.
- Timing policy has dedicated regression tests in addition to existing schedule/title tests, Android Lint and APK build gates.

## Core features
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

## Normal setup
1. Install and open the APK.
2. Paste the seller's one-time KM activation key, activate, then grant notification access.
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

The commercial workflow builds `app-release.apk` using the seller's stable private
signing key. Debug and ephemeral smoke builds are development fixtures.

## Commercial licensing

Seller: `/license create duration:30d memo:customer-name` → receive a KM key once
and a permanent LIC ID → send the key to the buyer. Buyer: paste KM key → activate.
There is no manual device-code exchange, tier system or buyer issuance function.
One key can bind one installation, even when devices race. Android Keystore P-256
proof prevents authenticating a copied session token on a different phone.

Server expiry/suspension/revocation/delete/min-version lock automation and cancel
schedules while preserving room/message/photo settings. Every background dispatch
validates before touching Kakao. Temporary outages have bounded monotonic offline
grace (default at most 10 minutes); authoritative invalid responses never get grace.
Legacy offline KAS1 licenses are removed from v2 and require seller migration.

* [Exact deployment and signing instructions](docs/COMMERCIAL_DEPLOYMENT.md)
* [Architecture, API, states, recovery and limitations](docs/COMMERCIAL_ARCHITECTURE.md)
* [Pre-change baseline audit](docs/BASELINE_AUDIT.md)
* [Security, UX, reliability, privacy and regression review](docs/COMMERCIAL_REVIEW.md)

## Standalone VoiceRoom products

Android VoiceRoom Manager v0.5.1 remains a separate, previously device-proven app. Windows VoiceRoom Manager v0.4.0 RC9 has a unified workflow, dark dashboard, dedicated icon-only VoiceRoom verification, automatic microphone/speaker mute, configuration backup, recovery and 154 regression tests with focus-preserving automatic checks. Actual create/PIP and automatic ON-to-OFF audio transitions were observed on the target PC; Controlled automatic regeneration with the default menu, active/audio readback and durable start time was observed on October 7; speaker requests and real 48h operation remain unverified.

- [Android VoiceRoom](voiceroom/README.md)
- [Windows RC7 setup and verified limits](desktop/VoiceRoomManager.Windows/README.md)
- [Windows audit](docs/VOICEROOM_WINDOWS_RC_AUDIT.md)
- [Minimum real-PC checklist](docs/VOICEROOM_WINDOWS_RC_CHECKLIST.md)



