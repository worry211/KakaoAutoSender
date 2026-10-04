# VoiceRoom Manager — Windows + Android emulator

GUI-first VoiceRoom lifecycle manager for KakaoAutoSender. No Kakao chat command is required.

## Current v0.3 flow

1. Run `start_voiceroom_manager.bat`.
2. Open **환경 설정** and use **자동 찾기** to select the ADB-connected emulator/device. If exactly one ready device exists, the manager can auto-select it.
3. Run **환경 점검**. This verifies ADB connectivity, KakaoTalk installation and UIAutomator access.
4. Add one or more Open Chat rooms.
5. Select each room and run **안전 인식 점검**. This opens the room and the +/more menu, verifies that the VoiceRoom menu is visible, saves diagnostic XML/PNG, and deliberately does not press the create button.
6. Only after safe inspection passes, use **실제 재개설 테스트** once. This test may actually create a VoiceRoom when none is active and therefore requires an explicit confirmation in the GUI.
7. Start **전체 시작** for unattended multi-room lifecycle management.

## Runtime behavior

- Windows stays awake while automation is active; the physical monitor may still turn off.
- The dedicated Android environment is kept awake and its audio streams are muted when configured.
- Each room persists its own `started_at`, next check, failures and last error.
- A manager-created room is checked near `48h - precheck_seconds`; the real UI state, not only the timer, decides whether reopening is attempted.
- A room whose existing start time is unknown is periodically rechecked until a replacement is created and a reliable baseline is known.
- Multiple due rooms are serialized.
- Failures back off from one minute up to one hour and save UI XML + screenshots.
- If several ADB devices are connected, the GUI requires an explicit device selection instead of guessing.

## Runtime files

- `voiceroom_config.json` — local GUI configuration
- `voiceroom_state.json` — per-room lifecycle state
- `logs/voiceroom.jsonl` — append-only runtime events
- `diagnostics/*.xml` + `diagnostics/*.png` — selector/failure snapshots

## Important limitation

KakaoTalk does not expose a supported public VoiceRoom lifecycle automation API. Exact UI labels and hierarchy must therefore be validated once against the actual KakaoTalk/emulator version before unattended use. The manager fails closed when required controls cannot be confirmed and does not implement anti-detection, moderation bypass or ranking-system reverse engineering.
