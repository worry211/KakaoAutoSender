# KakaoMacro PC — Windows v1.3.1 Release Gate

Status: **FIELD CANDIDATE / PR REMAINS DRAFT**

Windows v1.3.1 is the current PC baseline for field smoke. It preserves fail-closed KakaoTalk routing and the VoiceRoom-compatible input-focus fix while improving operator usability and removing developer-facing presentation from the normal UI.

## Verified automated gates

Clean product-code head before documentation-only commits: `76559132f97830b33812c2d4883d86b05ec6b8a9`

Windows workflow run `37294001184`: **PASS**

- restore
- Release WPF build
- self-contained `win-x64` publish
- artifact upload

Android/backend workflow run `37294001171`: **PASS**

- backend secret-scan self-test and repository scan
- install / typecheck / lint / tests / npm audit
- Android unit tests
- Android lint/debug build
- minified/R8 release fixture
- artifact upload

## Windows artifact identity

GitHub artifact: `KakaoMacro-PC-win-x64-preview`

- Artifact ID: `11337063940`
- CI artifact ZIP SHA-256: `ff74acedec5f91643f495ad44a06f5704d1ed82299919b534727906a15b9df2c`
- Extracted EXE SHA-256: `a4856229daf54a0b90eb452cd06625d944872569c841511ffa3bf0ca6a950704`

The binary is self-contained x64. It is not Authenticode-signed yet, so Windows SmartScreen can warn during field testing.

## v1.3.1 usability changes

- custom dark ComboBox and dropdown styling instead of bright system-theme controls
- room filters for all / running / paused / enabled / needs pairing / needs attention
- sort by status priority / next send / room name
- quick interval presets: 1 / 5 / 10 / 30 / 60 minutes
- same interval presets in the multi-room bulk editor
- single-room Save / one-shot-send actions stay fixed outside the scrolling editor body
- raw HWND identifiers removed from customer-facing room status
- internal scheduler timing details removed from customer-facing status copy
- previous multi-select, bulk edit, latest-bulk undo, tray controls and low-overhead persistence are retained

## Safety invariants retained

- no room-name-only guessing
- exact KakaoTalk window/input identity must validate before send
- KakaoTalk restart, replaced window, title change or input-identity mismatch fails closed
- VoiceRoom-open text send restores focus using the actual input-control UI thread
- user-activity guard remains active
- global stop fence blocks stale scheduled dispatch
- app restart never resumes sending without an explicit start
- room names and message content remain local
- photo automation stays locked/fail-closed until the KakaoTalk PC attachment path is physically verified

## Required physical smoke before merge

1. Launch v1.3.1 on Windows 10/11 x64.
2. Recover the existing PC entitlement.
3. Pair a normal KakaoTalk chat and send one text message.
4. Keep VoiceRoom open and send one text message to the paired chat.
5. Verify the dark filter/sort dropdowns display and open correctly.
6. Verify running / paused / connection / attention filters.
7. Verify status / next-send / name sorting.
8. Verify interval preset buttons update the interval field.
9. Multi-select several rooms and apply a safe bulk change.
10. Verify selected stop and global stop prevent stale sends.
11. Hide to tray and restore.
12. Restart KakaoTalk and confirm stale bindings are blocked rather than guessed.

## Release decision

Keep PR #25 Draft until the physical checks above pass. Do not weaken routing checks or photo fail-closed behavior for convenience. Do not market the Windows client as Kakao-official or as impossible to detect or block.
