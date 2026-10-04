# VoiceRoom Manager Windows

Native Windows manager for KakaoTalk Open Chat VoiceRoom maintenance.

Current Windows build: **v0.3.2**.

The Windows app is a separate product from KakaoAutoSender and the Android VoiceRoom Manager. It uses the account already signed into the official KakaoTalk Windows client and never asks for or stores Kakao credentials.

## v0.3.2 operating model

The normal user flow is intentionally short:

1. register a room name and its `https://open.kakao.com/o/...` link
2. keep that room management ON
3. press **전체 시작**
4. the manager automatically bootstraps entry, VoiceRoom verification/creation, audio protection and the 48-hour schedule

`안전 점검` and `실제 점검` remain optional diagnostics. They are no longer required gates before management can start.

Unverified rooms are processed immediately when `전체 시작` is pressed. Initial bootstrap is not deferred merely because the user has just interacted with the PC. Failed bootstrap attempts remain managed and retry with progressive backoff instead of silently stopping.

## Verified OpenChat entry state machine

KakaoTalk 26.x can expose very little usable UI Automation data, and different parts of the OpenChat flow may live in browser content, the titled Kakao main HWND, child HWNDs, or custom-rendered surfaces.

The entry engine therefore uses one continuous verified state machine:

1. validate and launch the registered OpenChat link
2. accept direct Kakao handoff when Windows/browser protocol handling supports it
3. detect the public `open.kakao.com` landing page
4. prefer exact semantic `그룹 오픈채팅 참여하기` / `오픈채팅 참여하기` actions
5. when browser accessibility is unavailable, detect only the large white rounded-outline OpenChat CTA surrounded by Kakao OpenChat blue and click its verified center
6. wait for Kakao to foreground and paint the OpenChat preview
7. prefer semantic `참여 중인 오픈채팅방`
8. when Kakao custom-renders that action, detect only a unique large Kakao-yellow lower CTA inside Kakao-owned visible surfaces
9. require post-click evidence: exact room window, real composer, or a stable verified Kakao surface transition with the CTA disappearing
10. issue a short-lived, room-title-scoped verified-entry token
11. reuse that verified custom-rendered room session throughout the same bootstrap operation instead of falling back to fragile room search simply because no `RICHEDIT` composer is exposed
12. use Win32 room search only as the final verified fallback

Both browser and Kakao visual fallbacks are intentionally narrow. Ambiguous candidates are never clicked, and a click alone is never considered success.

## Unified Kakao surface discovery

`KakaoSurfaceLocator` is the single Win32 source of truth for:

- KakaoTalk top-level and child HWNDs
- class/title/visibility/geometry
- exact independent chat windows
- `RICHEDIT*` composers when they exist
- narrow untitled OpenChat preview panes
- previews custom-rendered directly inside the main Kakao surface
- privacy-safe surface diagnostics

The engine does not assume the titled top-level window owns all visible Kakao pixels.

## VoiceRoom automation

After a verified room session is available, the manager:

- recognizes an already-active VoiceRoom first
- otherwise opens the VoiceRoom flow
- fills the VoiceRoom name
- invokes create
- requires strong active proof twice before recording success
- establishes a new 48-hour baseline only after a proven creation
- does not invent a new start time for an already-active room whose start time is unknown
- prechecks around 47h55m and polls the real Kakao UI near expiry
- recreates only after the real runtime state shows the VoiceRoom is gone
- serializes multi-room foreground work through one single-flight queue

Kakao runtime state remains the source of truth. The timer only decides when to inspect.

## Adaptive UI calibration

Automatic semantic/Win32/verified visual detection is always preferred. For controls Kakao completely hides from automation, the app includes optional per-control calibration rather than requiring a new executable for each UI variation.

Supported calibration targets:

1. VoiceRoom menu/button
2. VoiceRoom name input
3. create button
4. active VoiceRoom leave/exit control used as active proof
5. unmuted microphone icon
6. unmuted speaker icon
7. speaker-request reject action

Calibration stores a Kakao-surface-relative position plus host geometry, a local pixel signature and Kakao version metadata. Replay is refused when the signature/geometry no longer matches.

Calibration data is stored under `%LOCALAPPDATA%\VoiceRoomManagerWindows\calibration.json`.

## Audio and speaker-request protection

- microphone and speaker protection prefer exact semantic controls
- calibrated visual fallback is used only while its signature still matches
- incoming speaker requests are never auto-accepted or promoted
- explicit request context + explicit reject action is preferred
- a calibrated reject target is optional for custom-rendered request UI
- runtime counters track rejects, request-reception disable actions and audio repairs

## Long-running Windows behavior

- monitor may turn off
- while management is ON, system sleep is prevented but display sleep is allowed
- Windows lock/logoff is never bypassed; automation waits safely
- verified-room routine checks may defer briefly while the user is actively working, but first bootstrap does not
- KakaoTalk is relaunched when needed if the official client is closed
- room state is stored under `%LOCALAPPDATA%\VoiceRoomManagerWindows\state.json`
- optional per-user startup uses `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`
- OpenChat/VoiceRoom foreground operations are serialized
- failed initial bootstrap retries at short progressive intervals before backing off further

## Product UI

The WPF dashboard includes:

- room + OpenChat-link registration
- duplicate-link protection
- per-room management ON/OFF
- one-click `전체 시작` bootstrap
- optional safe probe/live check diagnostics
- Windows-login autostart
- human-readable bootstrap/active/retry states and remaining time
- real active / mic / speaker indicators
- latest diagnostics and runtime counters
- adaptive `UI 캘리브레이션`

## Safety boundaries

The project intentionally does **not** implement:

- Kakao credential/session-token collection
- CAPTCHA or security-check bypass
- PIN/pattern/biometric or Windows lock-screen bypass
- account/session-limit bypass
- sanctions/ban evasion
- anti-detection/fingerprint spoofing
- ambiguous blind clicking

Uncertain surfaces fail closed.

## Runtime-only release gates

CI verifies code quality and publishability, but these facts still require real Kakao runtime proof on the target environment:

1. end-to-end PC VoiceRoom create/PIP on the installed KakaoTalk build
2. exact current VoiceRoom menu/create surface behavior when fully custom-rendered
3. mic/speaker state labels or calibrated equivalents
4. real incoming speaker-request reject UI from another account/device
5. a real 47h55m -> 48h expiry/recreation cycle
6. one-account multi-room concurrency behavior enforced by Kakao

These are runtime validation gates, not reasons to bypass Kakao/Windows security boundaries.

## Build

```powershell
dotnet publish VoiceRoomManager.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o out
```

GitHub Actions workflow: `VoiceRoom Windows`.
