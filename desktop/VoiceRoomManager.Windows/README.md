# VoiceRoom Manager Windows

Native Windows companion for KakaoTalk Open Chat VoiceRoom management.

## Why native Windows

KakaoTalk for Windows supports creating VoiceRooms in Open Chat. This manager controls the official Windows KakaoTalk client through Windows UI Automation instead of running an Android emulator or using coordinate-only macros.

## Operation model

- Kakao credentials are never collected or stored.
- Uses the KakaoTalk Windows account already signed in on the PC.
- Monitor may turn off; the Windows session must remain unlocked for UI Automation.
- While automatic management is ON, the app requests `SYSTEM_REQUIRED` only: Windows system sleep is prevented, but the display is still allowed to turn off.
- If Windows is locked, automation fails closed and waits; it does not bypass the lock screen.
- Automatic scheduled work waits if the user has interacted with the PC in the last 30 seconds, then retries later instead of stealing the foreground.
- If KakaoTalk is closed when a scheduled check is due, the manager attempts to reopen the official client.
- Room state is stored under `%LOCALAPPDATA%\VoiceRoomManagerWindows\state.json`.
- Optional per-user startup uses `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`.

## Automation model

- Enumerates all top-level UI Automation windows belonging to KakaoTalk, so the main chat window, create dialog and VoiceRoom/PIP surfaces can be evaluated together.
- Room matching supports a title followed by participant count.
- Safe probe verifies room/VoiceRoom controls without creating a room.
- Live check enters the room, opens VoiceRoom controls, fills the name, clicks create, and requires strong VoiceRoom-only active evidence twice.
- An existing VoiceRoom with unknown start time does **not** get a fake fresh 48-hour baseline; it is rechecked every 10 minutes until the next proven recreation establishes a timestamp.
- A newly created VoiceRoom gets a 48-hour baseline, a precheck around 47h55m, then one-minute UI polling around expiry. Kakao UI remains the source of truth.
- Multi-room work is serialized through a single-flight semaphore.

## Audio and speaker-request safety

- UI Automation names/control patterns first; no blind coordinate clicking.
- VoiceRoom creation is considered successful only after strong active proof.
- Speaker requests are never auto-accepted/promoted.
- Incoming requests are auto-rejected only when an explicit request context and explicit `거절/거부` action can be associated safely.
- If Kakao exposes an explicit action label such as `스피커 요청 끄기/받지 않기/차단하기`, the manager may turn request reception off; plain state text is never clicked.
- Mic/speaker protection uses exact semantic controls such as `마이크 끄기/켜기`, `스피커 끄기/켜기` when exposed.
- Ambiguous UI fails closed and reports diagnostics.
- Runtime counters track request rejects, request-reception disable actions and real audio repairs.

## Product UI

- Dark standalone WPF dashboard.
- Room add/remove, per-room management ON/OFF, safe probe, live check, start/stop all.
- Status table for active verification, remaining time, mic/speaker protection and diagnostics.
- Windows-login auto-start option.
- Runtime protection counters and latest operational status.

## Current live gates

The project builds as a self-contained x64 EXE. The Windows client automation still requires one target-PC KakaoTalk calibration pass before a production-release claim because Kakao's exposed UI Automation labels can differ by client build/layout.

Pending runtime gates:
1. room-list selector on the user's installed KakaoTalk Windows build
2. VoiceRoom create dialog selector and strong active proof on that build
3. mic/speaker accessibility action labels
4. real speaker-request deny UI from another account/device
5. full 48-hour expiration/recreation cycle and multiple-room concurrency behavior

## Build

```powershell
dotnet publish VoiceRoomManager.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o out
```

GitHub Actions workflow: `VoiceRoom Windows`.
