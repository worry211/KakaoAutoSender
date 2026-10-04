# VoiceRoom Manager — PC/Android emulator module

This module is the PC-side VoiceRoom automation path for KakaoAutoSender.

## Goal

- Windows stays on; the physical monitor may be off.
- Android emulator (or an ADB-connected dedicated Android device) stays running.
- Multiple Open Chat rooms are managed independently.
- The manager checks near the 48-hour lifetime, confirms the actual UI state, and opens a new VoiceRoom only after the prior one is no longer active.
- Audio on the dedicated Android environment is kept muted.
- Failures are fail-closed and logged with a UI XML + screenshot snapshot.

It does **not** implement anti-detection, ban evasion, or a claimed Kakao ranking algorithm.

## Requirements

1. Windows with Python 3.10+.
2. An Android emulator or dedicated Android device with KakaoTalk already logged in.
3. ADB available. Set `adb_path` in the JSON if `adb.exe` is not on PATH.
4. Keep the emulator itself running. Turning the PC monitor off is fine; sleeping/hibernating the PC is not.
5. For best routing reliability, keep managed rooms visible in the KakaoTalk chat list or provide each room's `room_url`.

## First run

1. Copy `config.example.json` to `voiceroom_config.json`.
2. Add one or more rooms. Each room needs a stable `id` and its exact visible `title`.
3. Optional but recommended: put the room's Open Chat URL in `room_url`.
4. Test ADB: `adb devices`
5. Capture a diagnostic snapshot while KakaoTalk is open: `python -m voiceroom_manager.cli --config voiceroom_config.json snapshot --prefix kakao`
6. Probe one room: `python -m voiceroom_manager.cli --config voiceroom_config.json probe --room room-a`
7. After selectors are verified, run `run_voiceroom_manager.bat`.

## Scheduler behavior

- A room that this manager successfully creates gets an exact local `started_at`.
- Its next check is scheduled for `48h - precheck_seconds`.
- In the final window, the manager checks the real UI rather than assuming the timer is exact.
- If a room was already active before the manager knew its start time, it checks at `unknown_active_probe_seconds` until that session ends; after the manager creates the replacement it has an exact baseline.
- Multiple due rooms are processed serially.
- Failures back off from 1 minute to 1 hour and never jump to another room as a substitute target.

## Runtime files

- `voiceroom_state.json` — per-room lifecycle state
- `logs/voiceroom.jsonl` — append-only log
- `diagnostics/*.xml` and `diagnostics/*.png` — failure/manual snapshots

Do not commit runtime files if they contain private room names or links.
