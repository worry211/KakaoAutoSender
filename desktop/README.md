# VoiceRoom Manager — Windows GUI + Android emulator

`desktop/` is the PC-side VoiceRoom manager for KakaoAutoSender.

## What the user does

1. Keep Windows on. The physical monitor may be off.
2. Keep one Android emulator (or a dedicated ADB-connected Android device) running with KakaoTalk logged in.
3. Double-click `start_voiceroom_manager.bat`.
4. Add Open Chat rooms in the GUI.
5. Use **기기 확인** once, then **선택 방 테스트** on one room.
6. After the live KakaoTalk UI is recognized correctly, press **전체 시작**.

No chat command such as `/보룸켜기` is required.

## GUI

The GUI handles normal operation without editing JSON by hand.

- room add / edit / delete
- per-room enable/disable
- current VoiceRoom state
- estimated remaining time
- next verification time
- failure count
- one-room live probe
- ADB device check
- diagnostic screenshot + UI XML capture
- global start / stop

The raw `voiceroom_config.json` still exists for advanced calibration, but the ordinary room list is managed by the GUI.

## Runtime behavior

- Each room has independent persisted state.
- A room successfully created by this manager gets a local `started_at` baseline.
- The normal next check is scheduled for about `48h - precheck_seconds`.
- In the final window the manager checks the actual KakaoTalk UI instead of assuming the 48-hour timer is exact.
- If a VoiceRoom was already running before the manager learned its start time, the manager periodically probes it until that session ends.
- Multiple due rooms are processed serially.
- Failures back off from 1 minute to 1 hour and never reroute to another room.
- The dedicated Android environment is muted when automation starts.
- State survives application/PC restarts through `voiceroom_state.json`.

## Requirements

- Windows
- Python 3.10+
- Android emulator or dedicated Android device
- ADB (`adb.exe`) available on PATH, or set `adb_path`
- KakaoTalk already logged in on that Android environment
- PC sleep/hibernation disabled while unattended automation is expected to run

Turning only the monitor off is fine.

## Important first live test

KakaoTalk does not expose a supported public VoiceRoom automation API, so UI text/resource behavior can change by KakaoTalk/Android/emulator version.

Before unattended use:

1. open the target room normally once,
2. click **진단 캡처**,
3. run **선택 방 테스트**,
4. confirm that the manager identifies the correct room and VoiceRoom state.

If the UI differs, the room's selector overrides can be calibrated in `voiceroom_config.json` without changing the scheduler.

## Headless mode

`run_voiceroom_manager.bat` still starts the same engine without the GUI.

## Files

- `voiceroom_config.json` — local settings and room list
- `voiceroom_state.json` — per-room lifecycle state
- `logs/voiceroom.jsonl` — append-only runtime log
- `diagnostics/*.xml` and `diagnostics/*.png` — manual/failure snapshots

Runtime files are ignored by Git.

## Boundary

This module automates the configured VoiceRoom lifecycle. It does not implement anti-detection, ban evasion, or reverse-engineering of Kakao ranking/moderation systems.
