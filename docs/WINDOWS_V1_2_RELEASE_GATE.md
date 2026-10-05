# KakaoMacro PC v1.2 — Release Gate

Current combined head before this document-only commit: `e873be924959b84c1747330c41186c4c3f703dd0`.

## Automated verification

Windows workflow run `37272185980`: **PASS**

- restore
- Release build
- self-contained `win-x64` publish
- artifact upload

Android/backend workflow run `37272185998`: **PASS**

- backend secret scan
- TypeScript typecheck
- formatting/lint
- backend tests
- `npm audit --audit-level=moderate`
- Android unit tests
- Android lint/build
- minified/R8 release fixture
- artifact upload

The v1.2 hardening gate also compiled the Windows Release build with warnings treated as errors before its temporary one-shot workflow was removed.

## Windows artifact identity

GitHub artifact: `KakaoMacro-PC-win-x64-preview`

- Artifact ID: `11328499608`
- CI artifact ZIP SHA-256: `b256eaa2db9d83af8e513c870b5296ca076c9a129801121d673df8a6ab19c44d`
- Extracted `KakaoMacro.Windows.exe` SHA-256: `bbca697c27786330aa8260165b5b828dbbc3e263f3826056ca653eae1ad1ba10`

The binary is self-contained x64. It is not Authenticode-signed yet, so Windows SmartScreen can warn during preview testing.

## Physical QA required before field-verifying Windows

1. Launch the EXE on Windows 10/11.
2. Activate or recover a PC entitlement.
3. Open a KakaoTalk chat as a separate window, click the input area and pair with `Ctrl+Shift+F8`.
4. Confirm one-shot text send reaches only that paired room.
5. Verify Korean, English and line breaks.
6. Pair at least two separate chat windows and verify profiles remain separate.
7. Restart KakaoTalk and verify stale bindings fail closed until re-paired.
8. Rename/change the paired room title and verify the old binding is blocked instead of guessed.
9. Verify interval scheduling, fixed daily times and daily limits.
10. Verify selected-room multi-send remains serialized and targets the correct room each time.
11. Verify scheduled sends wait while the user is actively using keyboard/mouse.
12. Verify global stop fences stale schedules and no send resumes after app restart until explicit start.
13. Verify tray show/start/stop/exit controls.
14. Verify bulk edit, first-selected copy, latest bulk undo, search and state filters.

Photo automation remains deliberately locked in the Windows preview. Do not add a silent text-only fallback for profiles that require a photo.

## Release decision

Keep PR #25 draft until the physical checks above pass. Automated CI is green, but KakaoTalk desktop focus/window behavior is platform/UI dependent and cannot be honestly called field-verified from CI alone.
