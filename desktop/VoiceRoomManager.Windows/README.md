# VoiceRoom Manager Windows

Native Windows companion for KakaoTalk Open Chat VoiceRoom management.

## Why native Windows

KakaoTalk for Windows officially supports creating VoiceRooms in Open Chat. This manager therefore controls the official Windows KakaoTalk client through Windows UI Automation instead of running an Android emulator or using coordinate-only macros.

## Operation model

- Kakao credentials are never collected or stored.
- Uses the KakaoTalk Windows account already signed in on the PC.
- Monitor may turn off; the Windows session must remain unlocked for UI Automation.
- If Windows is locked, automation fails closed and waits.
- Room state is stored under `%LOCALAPPDATA%\VoiceRoomManagerWindows\state.json`.
- Optional per-user startup uses `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`.

## Safety model

- UI Automation names/control patterns first; no blind coordinate clicking.
- VoiceRoom creation is considered successful only after strong active proof.
- Speaker requests are never auto-accepted/promoted; only explicit reject/deny actions are eligible.
- Mic/speaker protection uses explicit semantic controls when exposed.
- Ambiguous UI fails closed and reports diagnostics.

## Current live gates

The project builds as a self-contained x64 EXE. UI selectors are intentionally conservative and still need one target-PC KakaoTalk calibration pass for the user's current Windows KakaoTalk build before a production-release claim.

## Build

```powershell
dotnet publish VoiceRoomManager.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o out
```

GitHub Actions workflow: `VoiceRoom Windows`.
