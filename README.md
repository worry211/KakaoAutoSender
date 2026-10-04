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

## v1.1.0 final reliability pass
- Keeps the v1.0 room-first dashboard and multi-room configuration model.
- **One room per alarm dispatch** instead of firing a whole due batch in one receiver run.
- When several rooms are due together, the next room is scheduled **2-5 seconds later**.
- Interval schedules keep the configured minute value as the base and add a **stable 3-10 second per-cycle offset**.
- Failed sends retry up to three times with a short randomized retry gap, then fail closed.

## Core features
- Room-first dashboard and multiple rooms with independent messages/schedules.
- Per-room pause/resume and one-shot test send.
- Global start/stop, 1-minute+ intervals, fixed daily times, daily limit or unlimited mode.
- Exact-alarm support, reboot recovery and confirmed conversation routing.

## PC VoiceRoom Manager (experimental v0.2)
`desktop/` contains a Windows + ADB manager for the separate VoiceRoom lifecycle use case.

- GUI-first operation; no Kakao chat commands are required.
- Add/edit/delete multiple Open Chat rooms from the program window.
- Shows per-room running state, remaining estimate, next verification and failures.
- Designed for a dedicated Android emulator/device while the PC monitor is off.
- Mutes the dedicated Android audio streams.
- Near the 48-hour lifetime, verifies the real KakaoTalk UI state before reopening.
- Serializes multiple due rooms and persists lifecycle/retry state.
- Saves screenshot + UI XML diagnostics on failures or manual capture.
- Provides one-room live probe and ADB device check before unattended start.
- Uses room-specific UI selectors so KakaoTalk wording changes can be calibrated without rewriting the scheduler.

Start with `desktop/start_voiceroom_manager.bat`. See `desktop/README.md` for the live calibration flow. The desktop module is intentionally fail-closed: live KakaoTalk UI selectors must be verified on the target emulator before unattended use.

## Important platform limitations
KakaoTalk does not expose a supported public API for arbitrary Open Chat posting or VoiceRoom lifecycle automation. KakaoTalk/Android UI changes can invalidate automation, so unattended operation should only be enabled after a live selector probe succeeds on the exact emulator/KakaoTalk version. The project does not attempt to bypass platform rate limits or moderation.

## Build
GitHub Actions runs desktop VoiceRoom unit tests, Android unit tests, Android Lint, and creates a debug APK on pushes/PRs.

Artifact: `KakaoAutoSender-debug-apk`
