# KakaoAutoSender

Android local automation app for sending a configured message to a KakaoTalk conversation by reusing Android's notification reply action (`RemoteInput`).

## Scope and privacy
- No remote-control server.
- No `INTERNET` permission.
- Runs locally on the phone.
- Requires Android notification-listener access.
- KakaoTalk notifications must remain enabled so a reply session can be captured. Sound, vibration, pop-ups, and lock-screen visibility may be disabled separately.

## v0.4.0
- Explicit **Start** and **Stop immediately** controls.
- Manual one-shot test send before automation is enabled.
- Automatic room-name detection from standard conversation notification fields.
- More defensive parsing of KakaoTalk notification extras and messaging-style message bundles.
- Recent reply-session picker for phones where KakaoTalk does not expose the Open Chat room title directly.
- Manual room pairing with persistent recovery when a safe conversation identifier (`shortcutId` / notification tag) is available.
- Legacy weak bindings are migrated away to reduce the risk of routing to the wrong room.
- Exact room matching for sends; fuzzy room-name matching is intentionally not used.
- Runtime diagnostics showing listener state, parsed notification metadata, recent activity, and binding state.
- Listener rebind / active-notification rescan control.
- Recurring sends with a minimum 30-minute interval and a daily cap of 24.
- Consecutive-failure tracking and clearer recovery messages.
- Re-registers the schedule after reboot and after the app package is updated.
- In-app settings save / schedule refresh while automation is running.

## Normal setup flow
1. Install the APK and open the app.
2. Grant notification-listener access.
3. Receive a new message in the target Open Chat room.
4. If the room appears under auto-detected rooms, select it. Otherwise type the room name and choose the correct item under **recent Kakao notifications**.
5. Run a one-shot test send.
6. Configure interval / daily limit and start automation.
7. Use **Stop immediately** at any time to cancel the pending alarm and disable automation.

## Important limitations
KakaoTalk does not provide a supported public API for arbitrary Open Chat posting. This app therefore depends on the reply action attached to KakaoTalk notifications. A KakaoTalk update, Android update, reboot, cleared notification, or expired `PendingIntent` can invalidate a captured reply session. When that happens, receiving one new message in the target room normally provides a fresh session. Some KakaoTalk/Android combinations do not expose the human-readable room title; the recent-session pairing flow exists for that case.

## Build
GitHub Actions builds a debug APK on every push to `main`.

Artifact name: `KakaoAutoSender-debug-apk`
