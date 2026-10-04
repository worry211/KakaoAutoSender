# KakaoAutoSender

KakaoAutoSender contains two local automation paths:

- Android notification-reply based scheduled message sending.
- Experimental Windows + Android-emulator **VoiceRoom Manager** for multi-room VoiceRoom lifecycle management.

## VoiceRoom Manager v0.3

The desktop module under `desktop/` is GUI-first and does not require Kakao chat commands.

- multiple Open Chat rooms with independent lifecycle state
- 48-hour baseline and real-UI verification before reopening
- Windows monitor may turn off while automation keeps system sleep blocked
- Android audio mute and keep-awake handling
- ADB device auto-discovery with explicit selection when several devices are connected
- environment preflight for ADB, KakaoTalk package and UIAutomator
- **safe recognition check** that verifies the room and VoiceRoom menu without pressing the create button
- separate **actual reopen test** with an explicit warning before a VoiceRoom may be created
- serialized multi-room work, retry backoff, logs and XML/PNG diagnostics

See `desktop/README.md` for the current setup flow.

## Android message sender

The Android app reuses KakaoTalk notification reply actions (`RemoteInput`) for confirmed conversations. It is fail-closed: if the exact saved reply session cannot be confirmed, it does not guess another room. Existing v1.1 reliability behavior, per-room schedules, exact-alarm support and reboot recovery remain unchanged.

## Platform limitations

KakaoTalk does not expose a supported public API for arbitrary Open Chat posting or VoiceRoom lifecycle automation. KakaoTalk/Android UI changes can invalidate desktop UI automation, so live selector validation on the exact emulator/KakaoTalk version remains a release gate. The project does not attempt to bypass platform rate limits, moderation or anti-abuse systems.

## CI

GitHub Actions runs desktop Python syntax/unit tests, Android unit tests, Android Lint and builds the debug APK.
