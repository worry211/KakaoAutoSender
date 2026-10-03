# KakaoAutoSender

Android local automation app for sending a configured message to a detected KakaoTalk conversation by reusing the notification reply action (RemoteInput).

## Safety / scope
- No remote control server.
- No INTERNET permission.
- Works locally on the device.
- Requires Android notification-listener access.
- KakaoTalk notifications must remain enabled so a reply session can be captured.

## Current MVP
- Detect KakaoTalk conversations from notifications.
- Select a detected conversation.
- Send one manual test message.
- Schedule recurring sends with a minimum 30-minute interval.
- Per-day send limit (max 24).
- Re-register schedule after reboot.

## Build
GitHub Actions builds a debug APK on every push to `main`.

Artifact name: `KakaoAutoSender-debug-apk`
