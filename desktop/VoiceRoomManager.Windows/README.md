# VoiceRoom Manager Windows

Native Windows manager for KakaoTalk Open Chat VoiceRoom maintenance.

Current Windows build: **v0.2.0**.

The Windows app is a separate product from both KakaoAutoSender and the Android VoiceRoom Manager. It uses the user's account already signed into the official KakaoTalk Windows client and never asks for or stores Kakao credentials.

## v0.2.0 architecture

v0.2.0 replaces the earlier one-selector-at-a-time calibration approach with one verified room-session pipeline:

1. validate and open the registered `https://open.kakao.com/o/...` link
2. allow a direct Kakao handoff when Windows/browser protocol handling supports it
3. if the browser landing page appears, invoke only the exact OpenChat join action
4. treat Kakao's OpenChat cover/profile surface as a normal intermediate screen
5. enter the already-joined room through `참여 중인 오픈채팅방`
6. require a real chat proof: exact Kakao chat-window title or visible `RICHEDIT*` composer
7. issue a short-lived verified-entry token for that operation
8. reuse the same verified room session for safe probe / live check / VoiceRoom creation instead of searching for the room again
9. use Win32 room search only as the final verified fallback

This removes the old failure mode where link entry succeeded and the next stage immediately re-ran a fragile room search.

## Unified Kakao surface discovery

Current KakaoTalk Windows builds can render the narrow chat list, right-side OpenChat preview and actual chat/VoiceRoom surfaces in different HWNDs while exposing little or no useful UI Automation tree.

`KakaoSurfaceLocator` is the single Win32 source of truth for:

- all KakaoTalk top-level and child HWNDs
- class/title/visibility/geometry
- exact independent chat windows
- actual `RICHEDIT*` chat composers
- likely OpenChat preview surfaces, including narrow untitled panes
- privacy-safe surface diagnostics on failure

The app no longer assumes that the top-level window titled `카카오톡` owns all visible Kakao pixels.

## OpenChat preview entry

`KakaoOpenChatPreviewBridge` prefers semantic UI Automation for exact actions such as `참여 중인 오픈채팅방`.

When Kakao custom-renders the preview and exposes no actionable UIA descendants, the app may use a tightly scoped visual fallback:

- scan only Kakao-owned visible preview surfaces
- search only the lower part of plausible preview panes
- require a unique, large Kakao-yellow CTA candidate
- reject ambiguous candidates
- never accept the click itself as success
- require an exact chat title or real chat composer after the click

This is a verified visual fallback, not a blind absolute-coordinate macro.

## Adaptive UI calibration

For Kakao controls that remain completely hidden from UIA/Win32, v0.2.0 includes an optional in-app **UI 캘리브레이션** system.

Automatic semantic/Win32 detection is always preferred. Only a missing stage needs calibration.

Supported calibration targets:

1. VoiceRoom menu/button
2. VoiceRoom name input
3. create button
4. active VoiceRoom leave/exit control used as active proof
5. unmuted microphone icon
6. unmuted speaker icon
7. speaker-request reject action

The user chooses a target, the manager hides for six seconds, and the user only hovers the mouse over the correct Kakao UI. The program stores:

- position relative to the owning Kakao top-level surface
- host window class/title kind
- host size/aspect information
- a local 9-point pixel signature
- KakaoTalk file version metadata

Before replay, the host geometry and visual signature must still match. If they do not, the click is refused and recalibration is requested. Moving or resizing the Kakao window is therefore not treated like an absolute-coordinate macro, while changed UI fails closed.

Calibration data is stored locally under `%LOCALAPPDATA%\VoiceRoomManagerWindows\calibration.json`.

## VoiceRoom automation

After a verified room session is available, the engine:

- recognizes an already-active VoiceRoom first
- otherwise opens the VoiceRoom flow
- fills the VoiceRoom name
- invokes create
- requires strong active proof twice before recording success
- establishes a new 48-hour baseline only after a proven creation
- does not invent a fresh start time for an already-existing room whose start time is unknown
- prechecks around 47h55m and polls the real Kakao UI around expiry
- serializes multi-room foreground work through one single-flight queue

Kakao UI remains the final source of truth; the timer only decides when to inspect.

## Audio and speaker-request protection

- mic/speaker protection prefers exact semantic controls
- calibrated visual fallback is used only when a stored signature still matches
- speaker requests are never auto-accepted or promoted
- explicit request-context + explicit reject action is preferred
- a calibrated reject target is optional for custom-rendered request UI
- runtime counters track reject, request-reception disable and audio-repair actions

## Long-running Windows behavior

- monitor may turn off
- while management is ON, system sleep is prevented but display sleep is allowed
- Windows lock/logoff is never bypassed; automation waits safely
- scheduled foreground work defers while the user has recently interacted with the PC
- KakaoTalk is relaunched when needed if the official client is closed
- state is stored under `%LOCALAPPDATA%\VoiceRoomManagerWindows\state.json`
- optional per-user startup uses `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`
- OpenChat/VoiceRoom operations are serialized so two managed rooms cannot drive the UI at the same time

## Product UI

The WPF dashboard includes:

- room + OpenChat-link registration
- duplicate-link protection
- per-room management ON/OFF
- safe probe and live check
- start/stop all
- Windows-login autostart
- human-readable room state and remaining time
- real active / mic / speaker indicators
- latest diagnostics and runtime counters
- adaptive `UI 캘리브레이션` controls

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

The software now contains the adaptive mechanisms needed to finish target-PC calibration without shipping a new code patch for each hidden control. The remaining facts can only be proven against real Kakao runtime behavior:

1. end-to-end VoiceRoom create/PIP on the installed KakaoTalk Windows build
2. actual mic/speaker state labels or one-time calibrated equivalents
3. real incoming speaker-request reject UI from another account/device
4. a real 47h55m -> 48h expiry/recreation cycle
5. one-account multi-room concurrency behavior enforced by Kakao

These are runtime validation gates, not reasons to bypass Kakao/Windows security boundaries.

## Build

```powershell
dotnet publish VoiceRoomManager.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o out
```

GitHub Actions workflow: `VoiceRoom Windows`.
