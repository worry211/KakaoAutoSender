# KakaoMacro PC — Windows v1.4.0 Release Gate

Status: **COMMERCIAL SALE CANDIDATE / PR REMAINS DRAFT UNTIL FIELD SMOKE**

Product-code head used for the sale artifact: `4cf41968fe94c62a455d2476d6998a1a14a7a975`

## Automated gate

Windows workflow run `#101` / run id `37331092989`: **PASS**

- win-x64 restore
- Release build with warnings as errors
- self-contained single-file publish
- file version assertion for `1.4.0`
- sale artifact contains the EXE only; no PDB/debug symbols

Artifact: `KakaoMacro-PC-v1.4.0-sale`

- Artifact ID: `11354372898`
- Artifact ZIP SHA-256: `910743d33a8520d93c7875aa78a38e2f8327c7d7125c1a95705196ae0ac085e4`
- Extracted EXE SHA-256: `406928b2bd278f92607aeca76484f45c8f2a832a13219f68f7b9cac170b6df97`

The executable is self-contained Windows x64. It is **not Authenticode-signed**, so Windows SmartScreen may warn.

## v1.4 commercial hardening

- 15-second background Kakao binding health checks
- exact HWND / focused child / PID / process-start / title / class validation remains fail-closed
- stale binding, Kakao restart, title mismatch, or focus-identity mismatch automatically stops an active room
- selected/all manual connection-health checks
- X-button close-to-tray option
- every real exit path confirms when rooms are running
- explicit start required after every app launch
- settings persistence uses atomic replacement
- `settings.json.bak` keeps a last-known-good recovery generation
- corrupt or truncated primary settings recover automatically from backup
- corrupt files are preserved as timestamped diagnostic copies
- settings recovery events are written to `settings-recovery.log`

## Productivity retained

- commercial dark desktop UI
- multi-select and bulk message/schedule/daily-limit/enabled editing
- latest-bulk undo
- selected start/stop/enable/disable/validate/delete
- selected one-shot send with confirmation for large batches
- room search, filters, and status/next-send/name sorting
- debounced valid auto-save, Ctrl+S, Ctrl+Enter
- scheduler work kept off the WPF UI thread

## Safety invariants

- never route by room title alone
- do not guess when target identity is ambiguous
- VoiceRoom compatibility may restore focus only after the exact bound target validates
- Unicode/Korean text uses SendInput without clipboard leakage
- recent-user-input guard remains active
- global stop fence blocks stale dispatch
- photo configured => fail closed; never silently send text only
- room names, messages, and local settings remain local

## Required physical Windows smoke before merge

1. Launch on Windows 10/11 x64 and recover the existing PC entitlement.
2. Pair a KakaoTalk room and send one normal Korean text message.
3. Keep VoiceRoom open and send one text message to the exact paired room.
4. Start a room, restart/close KakaoTalk, and confirm the stale binding is detected and the room auto-stops.
5. Verify X -> tray, tray restore, tray stop, and real-exit confirmation while a room is running.
6. Change one room setting and verify auto-save survives app restart.
7. Multi-select several rooms, apply a bulk change, and verify undo leaves restored rooms stopped.
8. Verify global stop prevents any stale scheduled send.
9. Optional recovery drill: back up `%LOCALAPPDATA%\KakaoMacro\Windows`, corrupt `settings.json`, relaunch, and confirm recovery from `settings.json.bak` with no automatic sending.

## Merge decision

Keep PR #25 Draft until the smoke above passes. Once it passes, merge without changing the tested Windows product code. Do not market the Windows client as Kakao-official, undetectable, or impossible to block.
