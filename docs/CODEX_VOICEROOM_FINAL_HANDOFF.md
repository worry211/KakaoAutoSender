## RC10 추가 감사 — 2026-10-07

RC9가 판매 완료본에 가까운 것처럼 남은 항목만 나열했던 평가는 철회한다. 사용자 운영에서 등록 2개/표시 1개, 전역 OFF/방 관리 ON 모순, 두 번째 방 생성 후 대기가 발견됐다.

- 목록: UI 전용 ObservableCollection으로 등록/삭제 알림을 보낸다. WPF DataGrid 실제 항목 수와 선택 유지 회귀 테스트를 추가했다.
- 상태: 방의 관리 대상 설정과 전역 실행 상태를 구분한다. 전역 OFF 수동 확인은 다음 자동 점검을 예약하지 않는다. 중단 중 보호 결과는 마지막 점검으로 표시한다.
- 실제 Kakao 26.8.1.5315, 방 1 참여 중 방 12 생성 확인에서 `현재 참여하고 있는 보이스룸을 종료하고, 새로 만들어볼까요?`가 관측됐다. 취소했고 방 1은 유지했다. RC9는 이 확인을 놓쳐 생성 제출 대기로 기록했다.
- 새 정책: 다른 전용 보이스룸 창이 있으면 새 방 진입/생성 전에 대기한다. 생성 중 나타난 전환 확인은 정확한 문구 두 번, 취소 버튼, 소멸 결과를 검증한 경우에만 취소한다. 기존 참여를 종료하거나 플랫폼 제한을 우회하지 않는다. 대기는 오류 횟수를 올리지 않는다.
- 여러 방 등록은 같은 클라이언트의 여러 방 동시 참여 보장이 아니다. 기존 방을 자동으로 희생하지 않는다. 실제 동시 운영 요구에는 별도 지원 가능성이 검증돼야 한다.
- 수동 취소된 과거 생성 요청은 창 부재만으로 중복 생성 장벽을 삭제하지 않는다. 사용자가 확인한 종료/미생성 해제와 실제 취소 문구 검증만 허용한다.
- 추가 보호: 런타임 점검 결과도 Stop/Dispose 이후에는 폐기한다. 개별 관리 대상 재활성화는 즉시 재확인을 예약한다.

추가 실기 감사에서 복귀 후 오래된 대기 Stage가 남는 문제를 수정했다. Stop 이후 늦은 runtime 결과 폐기와 대기 해제/유지 회귀 테스트, 실제 전환 확인 모달 OCR fixture를 추가했다. 모달 OCR은 쉼표를 누락하고 볼까요를 몰까요로 읽는 관측 변형을 지원한다. 최초 exact-caption fixture 실패도 감사 이력으로 보존한다. 실제 48시간 만료, 타 계정 요청, 모니터 OFF/잠금/재시작은 여전히 미검증이다.
# Current continuation: Windows RC9 browser entry reliability

Final RC9 quiet/resume verification: other-app foreground 18:37:40 through 18:41:51 crossed the scheduled 18:41:30 check without promotion, new failure/success or epoch reset. Returning to manager recovered real active/both-muted at 18:42:20 in 2438ms, with original 18:36:27 epoch retained. Minimal checklist now separates final RC9 observed runs from the remaining real-device gates.

Final target RC9 EXE (code cb40c32) normal launch/ready at 18:31:24/18:31:25 KST. Closed-chat browser → preview → exact room → existing active/both-muted at 18:31:46, 18279ms. Controlled termination → automatic default-menu regeneration → active/both-muted at 18:33:38. Complete fresh run: both chat and VoiceRoom closed, StartAll once at 18:36:11 → native browser CTA → verified yellow preview → exact chat → automatic default-menu/form submission → new active/mic-muted/speaker-muted repeated proof at 18:36:30, 19062ms, durable StartedAt 18:36:27 UTC+9. No manual creation/entry/audio click during either workflow. These are controlled tests, not elapsed 48h proof. Code CI VoiceRoom Windows #104, Windows client #168, APK/backend #651 success.

Additional target evidence: first RC9 native input was delivered but no preview appeared within 8 seconds, and the retry was then blocked because its own exact OpenChat landing counted as other-app foreground. Pending entry now resumes only on the registered exact official URL in a known OpenChat browser; other tabs/URLs/games still defer. Healthy verified-room browser foreground is not an exception. Handoff observation allows up to 20 seconds within the existing 90-second operation limit, without a fixed success delay. Failed confirmation diagnostics now expose launcher captions; arbitrary launch/security buttons are never accepted.

Final RC8 chat-close/StartAll target test first failed to find preview after semantic browser invocation at 18:14:34; automatic retry eventually recovered exact chat/active/both-muted at 18:19:29. This is delay/retry evidence, not proof that browser InvokePattern alone caused failure. RC9 uses a native click on the fresh exact-name UIA rectangle only after exact official URL, containment, foreground and point ownership checks. Delivery remains transition-pending; actual Kakao room proof is required. Selection/Invoke-only browser execution was removed. Native browser action and complete entry traces are logged. Observed green OpenChat landing joins the existing blue-theme outline recognizer; geometry/unique-match/exact URL guards remain. Unsupported colors and generic solid backgrounds are rejected. 154 local tests pass before packaging; final RC9 CI/binary/actual QA belongs in PR #12 and output manifest. Preserve all unresolved real-device gates.

---
# Previous continuation: Windows RC8 footer reliability

Final RC8 target run: normal launch 18:07:22 / ready 18:07:23 / existing active+both muted 18:07:27. Controlled one-participant termination then unattended retry recovered a transient menu miss: default menu, submit 18:08:42, active+both-muted 18:08:45, 5020ms. Durable new epoch 18:08:42.5814718 KST, no manual creation/audio action. CI for code 687b5e4: VoiceRoom Windows #101, Windows client #165, APK/backend #648 all success. Real elapsed 48h remains pending.

142 local Windows regression tests pass. Actual RC7 controlled termination on October 7 was followed by unattended default-menu creation at 18:03:50 KST and active/both-muted proof at 18:03:53, 5087ms from due workflow entry. Persisted StartedAt matches the delivered submission epoch; CreationUncertain is false after proof. No manual creation menu or audio click was used. This is controlled termination recovery, not elapsed 48-hour expiry proof.

Target QA also exposed an exit-circle component joined to cursor-overlay illumination: the left four circles remained intact. RC8 recovers only the exit control from its strict glyph in the bounded right-hand layout slot, retaining exact room/PID/participant, four-control geometry, typed audio and repeated readback. Missing exit glyph still fails closed. Positive and missing-glyph overlay fixtures run at 100%, 150%, 200%. Android/backend unchanged. Final RC8 launch/CI/hashes and residual gates are recorded in PR #12 and output manifest.

---
# Previous continuation: Windows RC7 cancellation/startup audit

139 local Windows tests pass. Final target testing exposed an audio readback versus foreground-switch race; transient uncertainty now retries read-only under bounded backoff instead of permanently requiring intervention, and rechecks focus before classifying failed native input. Coordinator logs follow its StateStore directory; test stores do not pollute real operational logs. Manual/probe results arriving after Stop/Dispose are marked cancelled and discarded by coordinator and UI. Delivered submit time checkpoints precede cancelled progress callbacks. Startup attempts, loaded window, duplicate instance and startup exceptions are recorded in logs/startup.jsonl. Existing focus-aware wait remains; no background-only backend or unattended focus-stealing mode.

RC6 launch verification resumed successfully on 2026-10-07 at 17:04 KST through the normal File Explorer EXE launch. launch_app's window-opened handler preparation fails; this did not reproduce an EXE/security refusal. Actual outputs RC6 path/version and active/both-muted recovery were observed; duration 2255ms versus previous 31914ms observation, not a controlled benchmark. Legacy unknown start remains unknown. Modified build real 48h regeneration and other-account request gates still need proof.

RC7 final commit CI, package hashes and actual launch/quiet/resume checks belong in PR #12 and output manifest. Preserve Android/backend and existing state. Never fabricate release approval from these tests.

---
# Current continuation: Windows RC6 expiry recovery — 2026-10-07

Actual target logs: 16:17 KST explicit ended VoiceRoom was closed, then lost chat foreground caused a menu intervention. 16:18–16:20 repeated desktop UIA scans delayed work by roughly 15 seconds each and verification exceeded 90 seconds after submission. User subsequently cleared uncertainty; the active room was rediscovered with unknown start. Do not fabricate a recovered timestamp for this legacy case.

RC6: native dedicated active/audio evidence first; exact HWND UIA root instead of desktop-wide enumeration; verified default-name create form skips editable UIA; chat re-established after explicit end; toolbar-first auto menu; menu retry rather than permanent intervention; submission timestamp persisted after delivered confirm input; late verified activity restores that epoch; pending submission re-observed automatically under backoff while duplicate-create barrier remains. Exact ended proof clears prior epoch. 132 tests cover submission timeout/restart/date recovery and safety. Android/backend unchanged.

User requested investigation of fully background operation and explicitly said to leave current behavior if unsupported. Current background UIA/capture did not provide reliable controls. No background click/message backend or new foreground-permitting unattended mode was added. RC5 focus-preserving wait remains: other-app foreground also delays expiry work. Calibration remains optional, default menu is automatic. Final CI/package and actual RC6 existing-room verification are recorded in PR #12 and output manifest; modified-binary real 48h regeneration still needs proof.

---
# Current continuation: Windows RC5 focus-preserving checks

RC5 fixes periodic five-minute focus stealing. Timer/bootstrap/retry/recovery and runtime input defer while another app is foreground, including idle games/videos. Start only from manager/exact room or a Windows desktop idle for 30 seconds. Explicit user actions get a one-shot immediate check. Operation checks yield before subsequent input/activation if the user switches to another app. Deferral preserves failures, success time, expiry and creation uncertainty; UI clearly distinguishes historical protection from pending fresh proof. Read-only background existence cannot claim active/audio success. Other-app foreground delays audio/request/expiry work until an allowed surface is available. Windows lock remains separate safe wait.

Tests: 128 Windows cases including repeated expired-room ticks with no Kakao invocation, preserving uncertainty/backoff, and mid-operation app-switch before creation intent. Android/backend unchanged. Final CI/package and target-PC quiet/resume evidence will be recorded in PR #12 and output manifest.

---
# Current handoff: Windows v0.4.0 RC4 — 2026-10-05

Continue on feat/voiceroom-standalone-android-v1 / Draft PR #12. Current Windows source uses one RoomWorkflow, dedicated exact-room VoiceHost, typed ON/OFF glyphs, repeated participant/audio evidence, explicit ended-window verification, durable uncertain submission, and guarded header menu detection. Do not restore RICHEDIT or whole-chat menu OCR fallbacks. Android/backend source is preserved.

121 Windows tests pass locally. Actual target Kakao creation and ended-window regeneration were observed, then actual Mic ON/Speaker ON → automatic OFF and strong active verification after app restart (12:00 KST). Final default four-glyph menu was recognized without calibration by actual SafeProbe (12:18). Last healthy existing-room checks were about 1.8–2.2 seconds. A runtime control-occlusion failure was exposed and bounded read-only retries added. At 12:27, after moving the test pointer highlight away from the controls, RUNTIME_GUARD automatically restored actual Speaker ON→OFF and retained ACTIVE/both-muted evidence; AudioRepairs increased to 3. Final CI/package evidence belongs in the release manifest and PR.

The 11:58 creation still used the then-existing compatibility menu. Do not claim final no-calibration fresh-create E2E from a detector fixture. Other-account speaker requests, account multi-room limits, physical monitor OFF/lock/Windows-login restart and real 48-hour expiry remain approval gates. See [current audit](VOICEROOM_WINDOWS_RC_AUDIT.md), [minimal checklist](VOICEROOM_WINDOWS_RC_CHECKLIST.md), and Windows README. Earlier RC records below are historical; their automatic audio pending status is superseded by the RC4 observation.

---
# RC3 직접 실기 관찰 — 2026-10-05 10:46 KST

다운로드 RC1 실행을 확인한 후 최신 RC3 EXE를 실행했다. UI의 RC3 버전, 기존 방 복원, 중단 시 점검 표시 없음, 이전 trace 요약을 직접 확인했다. 전체 시작 한 번으로 browser→preview→정확한 방 1→검증 폼 제출→‘보이스룸: 1’ 전용 창이 생성됐다. 창에 1명 참여 중과 오디오/퇴장 아이콘을 확인했다.

프로그램의 강한 자동 활성 검사는 별도 보이스룸 창의 소유 관계/UIA 빈 트리/아이콘만 있는 컨트롤을 읽지 못해 USER_ACTION_REQUIRED가 됐다. CreationUncertain=true가 저장돼 추가 생성이 차단됐다. 마이크와 스피커는 Computer Use로 각각 직접 음소거한 뒤 crossed 아이콘 표시를 확인했다. 이는 수동 보호 증거이며 자동 오디오 보호 성공이 아니다. 보이스룸은 유지했다.

실제 생성 성공은 이번 관찰로 확인됐으나 자동 활성/음소거/요청 거절, recovery와 48h 승인 게이트는 여전히 남는다. 이미지 fixture/CI만으로 그 게이트를 채우지 않는다.

---

# RC3 runtime version correction — 2026-10-05

User screenshot at 10:40 KST was produced by Downloads/VoiceRoomManager-Windows-v0.4.0-rc1-x64, confirmed from the running process path and old OCR diagnostic. It does not establish an RC2 failure. RC3 retains the RC2 automation, summarizes legacy persisted traces, hides obsolete scheduled checks for stopped/disabled rooms and clears pending check time on Stop. 74 Windows regression tests pass locally. Android source is preserved. Actual create/active/audio/48h approval remains pending. Final source/CI/package hashes are in PR #12 and the delivery manifest.

---

# RC2 continuation — 2026-10-05

Windows v0.4.0 RC2 adds contextual preview identity, actionable offline OCR capability diagnosis, verified create-form submission, durable uncertain-submission recovery and a user-confirmed inactive recovery action. Removes obsolete RICHEDIT composer proof and unverified name/create coordinate fallbacks. Only room-menu calibration remains. Android source is unchanged.

Local Windows tests: 72 passed. Target PC evidence: exact independent chat `1` entry and opening its create form were observed; automatic submission, active VoiceRoom and audio protection were NOT proven. Real OCR create heading read `보이스름 만들기`; this alias is accepted only within create-form evidence, never room identity or activity. CI without Korean OCR verifies capability reporting, not Korean recognition success. Final commit CI results are recorded in PR #12 and the output release manifest.

Use [current audit](VOICEROOM_WINDOWS_RC_AUDIT.md), [PC checklist](VOICEROOM_WINDOWS_RC_CHECKLIST.md) and [README](../desktop/VoiceRoomManager.Windows/README.md). Build/fixtures are not real Kakao E2E proof. Do not remove the persisted creation barrier to make retries appear successful. Remain Draft until actual release gates pass.

---

# Current handoff update — 2026-10-05 / Windows v0.4.0 RC1

The historical mission and v0.3.2 baseline below are retained as context. The current implementation removes duplicate preview/search engines and global timed session tokens; uses RoomWorkflow, operation-scoped proof, local Korean OCR, verified audio readback, restart recheck, atomic backup, process-owned power request, dark onboarding/dashboard, diagnostics export and regression fixtures.

Current technical facts and remaining product gates: [RC audit](VOICEROOM_WINDOWS_RC_AUDIT.md), [minimum PC checklist](VOICEROOM_WINDOWS_RC_CHECKLIST.md), [Windows README](../desktop/VoiceRoomManager.Windows/README.md). Android v0.5.1 source is preserved. Windows RC1 is not real-runtime sale-approved. Do not resurrect title-only session tokens, composer-anywhere proof or weak calibration-only activity.

---

# VoiceRoom Manager — Codex Final Completion Handoff

## Mission

Take full ownership of the VoiceRoom Manager project and finish it to a production/sale-ready standard. Do not act as a narrow patching assistant. Act as the product owner, senior Windows/Android engineer, QA lead, UX lead, reliability owner, and release owner.

The creator is explicitly asking you to continue autonomously until the product is genuinely finished. Do not stop after finding one bug, fixing one selector, or making one build pass. Audit the whole product, remove structural weaknesses, implement missing pieces, improve quality, test everything you can, and leave a polished release candidate with clear real-world validation steps for the few things that cannot be proven in CI.

Do not require the creator to discover every missing feature or defect one-by-one. Proactively identify and fix them.

## Repository / current GitHub baseline

- Repository: `worry211/KakaoAutoSender`
- Active branch: `feat/voiceroom-standalone-android-v1`
- Draft PR: `#12`
- Current PR head at handoff: `042509c55b12cf767b0949be02b33601891ca65f`
- PR is open, draft, mergeable.
- PR title at handoff: `feat: VoiceRoom Manager Android v0.5.1 + Windows v0.3.2 one-click automation`
- Base: `main`

Before changing anything, read the actual current branch, PR #12, workflows, README/handoff docs, and every relevant file. Do not assume the PR description means the implementation is correct. Validate the code yourself.

## Product separation

There are three distinct things in the repo and they must remain conceptually separate:

1. `KakaoAutoSender` — existing Kakao messaging automation product.
2. Android VoiceRoom Manager — standalone mobile VoiceRoom automation app.
3. Windows VoiceRoom Manager — standalone Windows VoiceRoom automation app.

Do not merge these into one confusing application. Shared low-level utilities are fine if cleanly abstracted, but user-facing products must remain separate.

## Stable Android baseline

Android VoiceRoom Manager is currently around v0.5.1 and is substantially ahead of Windows in real-device validation.

Real Android evidence already achieved:
- Actual Kakao Voice Room creation succeeded on the creator's phone.
- Actual active Voice Room UI was observed.
- 48-hour start/remaining-time state was recorded.
- Microphone/speaker muting was observed working.
- Speaker-request guard logic exists.
- Screen-off scheduling/recovery was improved.

Do not destabilize or rewrite the working Android app merely to make architecture symmetrical with Windows. Preserve working Android behavior. You may audit and improve it, but only with regression coverage and a clear reason.

Important Android limitation: do not bypass PIN/pattern/biometric lock. If Android is genuinely locked and authentication is required, wait for legitimate unlock. Screen OFF/AOD is not the same thing as an authenticated lock state.

## Windows product goal

Windows VoiceRoom Manager should feel like a polished commercial desktop utility, not an engineering test harness.

The desired normal setup is:

1. User installs/runs the Windows app.
2. User adds one or more Kakao OpenChat rooms.
3. Each room has a display name plus its `https://open.kakao.com/o/...` link.
4. User enables management for desired rooms.
5. User presses ONE primary action: `전체 시작`.
6. The app handles the rest automatically.

Manual `안전 점검`, `실제 점검`, calibration, diagnostics, etc. must be optional troubleshooting tools — never mandatory ceremony in the normal path.

## Required end-to-end behavior

For each enabled room, `전체 시작` must autonomously do the equivalent of:

1. Validate configuration.
2. Ensure KakaoTalk is running; launch/relaunch if needed.
3. Resolve/open the intended OpenChat room robustly.
4. If a browser OpenChat landing page appears, handle it safely.
5. If Kakao shows an OpenChat cover/preview page, enter the already-joined room safely.
6. Verify the intended room strongly enough to avoid operating on a wrong room.
7. Detect whether a Voice Room is already active.
8. If no Voice Room is active, create one.
9. Verify actual Voice Room activation with strong evidence. Never mark active based only on generic menu words.
10. Apply microphone/speaker safety so unattended operation is quiet.
11. If safely identifiable, disable speaker-request intake or auto-reject speaker requests. Never auto-accept/approve/promote a listener.
12. Record real start time when creation is verified.
13. Track expected 48-hour lifecycle.
14. Re-check near expiry, verify actual Kakao state, and recreate only when actually ended.
15. Recover from transient failures automatically with bounded backoff.
16. Recover after KakaoTalk crash/restart.
17. Recover after Windows/app restart where possible.
18. Process multiple rooms serially/single-flight so UI automation cannot collide.

The app must not claim success unless the relevant state is genuinely verified.

## 48-hour lifecycle

Do not implement a blind `48h timer => click create` design.

Desired behavior:
- Creation success => store verified start time and expected expiry.
- Pre-check around 47h55m.
- If still active, schedule a short follow-up.
- If ended, recreate.
- After recreation, verify active state and store new start time.
- If start time of an already-running Voice Room is unknown, represent that honestly and re-check at a safe interval instead of inventing a fresh 48h start.

## Multi-room

Support several configured rooms with a global UI-operation mutex/queue.

Per-room state should be understandable to a normal user, e.g.:
- 준비
- 자동 시작 중
- 보룸 활성
- 47시간 12분 남음
- 재점검 중
- 재시도 대기
- 잠금 해제 대기
- 사용자 조치 필요

Avoid exposing raw internal enum names like `NEW`, `PROBE_OK`, etc. in the UI.

Do not assume one Kakao account can host/participate in unlimited simultaneous Voice Rooms. If actual Kakao account concurrency limits are encountered, surface them honestly as a product limitation/state. Do not bypass platform/session limits.

## Current Windows history — why a full audit is required

Windows work evolved through approximately v0.1.x → v0.3.2 and accumulated multiple overlapping techniques:

- Windows UI Automation (UIA)
- Win32 HWND enumeration
- exact chat-title matching
- browser UIA/accessibility matching
- browser visual fallback
- Kakao preview visual fallback
- color/shape CTA detection
- relative/surface-based coordinates
- manual calibration profiles
- short-lived verified-entry/session tokens
- retry loops

This created a patch-by-patch maze. You must audit all of it, consolidate responsibilities, remove dead/duplicated paths, and make a coherent state machine. Do not keep obsolete fallback engines merely because they exist.

### Important real PC diagnostics

On the creator's actual KakaoTalk Windows environment, UI Automation exposed almost nothing inside the Kakao main window. Earlier diagnostics included roughly:

- `windows=1`
- `buttons=0`
- `edits=0`
- `search=0`
- `title=0`

So the implementation cannot depend solely on UIA controls existing.

Win32 child HWNDs sometimes expose useful classes; custom-rendered surfaces are also used.

### Two repeatedly observed real-world stages

#### Stage A — Browser landing

Opening the room's `open.kakao.com` link can show the public Kakao OpenChat landing page in the browser.

The creator's screenshot visibly showed a centered white-outline CTA:

`그룹 오픈채팅 참여하기`

The Windows automation repeatedly stalled here even though the control was visible.

A browser visual fallback was later added and actual screenshot dimensions/colors were used to tune it. Audit whether it is truly integrated into the single authoritative entry state machine and whether it handles load timing, focus, scaling, DPI, browser chrome, multiple monitors, and stale pages safely.

#### Stage B — Kakao OpenChat preview/cover

After browser handoff, KakaoTalk can show an OpenChat introduction/cover surface rather than the actual chat immediately.

The creator's screenshot visibly showed the large yellow CTA:

`참여 중인 오픈채팅방`

Automation repeatedly stalled here too.

The OpenChat cover can be custom-rendered in the main Kakao window or in another Kakao-owned surface/child HWND. Do not assume the titled main HWND contains every visible region, and do not assume the preview is always a separate HWND either.

Actual screenshot analysis used during previous work estimated roughly:
- Browser white CTA: ~293 x 78 px
- Kakao yellow CTA: ~298 x 36 px

Do not hardcode those absolute dimensions. Use them only as evidence for sensible relative/shape thresholds.

### Proven structural bug that was already discovered

Earlier Windows builds had a major logic defect:
- `전체 시작` required `LiveVerified == true` before it would really manage a room.
- Scheduler filtered out unverified rooms.
- Therefore a newly configured room could sit at `등록 대기` and do nothing.

This was changed around v0.3.0 so Start All should perform bootstrap automatically. Verify that this is now actually true throughout the whole coordinator/state-machine path and that no hidden manual gate remains.

Another proven defect:
- Entry could succeed and issue a short-lived verification token.
- A later Voice Room step then demanded a `RICHEDIT`/UIA chat composer, failed to see it in a custom-rendered Kakao build, discarded the successful session, and fell back to room search again.

The room-session contract was changed so a verified entry can survive into the Voice Room stage. Audit this carefully and replace ad-hoc token logic with a coherent explicit session state if appropriate.

## Prefer architecture over screenshot patching

Do not respond to the current state by adding another one-off selector for one screenshot.

Build or refactor toward a clear deterministic state machine such as:

- CONFIGURED
- ENSURE_KAKAO
- OPEN_LINK
- HANDLE_BROWSER_LANDING
- HANDLE_KAKAO_PREVIEW
- VERIFY_ROOM
- CHECK_VOICE_ROOM
- CREATE_VOICE_ROOM
- VERIFY_VOICE_ACTIVE
- APPLY_AUDIO_GUARD
- ACTIVE
- PRE_EXPIRY_CHECK
- RECREATE_REQUIRED
- RETRY_BACKOFF
- WAITING_UNLOCK
- USER_ACTION_REQUIRED

You may choose better names/structure. The important part is that every stage has:
- entry condition
- success evidence
- timeout
- retry policy
- diagnostic evidence
- safe rollback/abort behavior

A single operation should not silently jump back to an earlier obsolete room-search flow after a later stage has already established a trustworthy room context.

## Browser / deep-link strategy

Investigate whether Windows/Kakao installs a stable, legitimate protocol/deep-link or registered URL handler that can open the OpenChat target more directly than browser pixel automation.

If a stable supported mechanism exists on the target environment, prefer it.

If browser handoff is unavoidable:
- semantic/UIA browser controls when reliable
- narrow validated visual recognition fallback when accessibility is unavailable
- never click generic browser controls such as arbitrary `열기`
- validate OpenChat context before any visual click
- handle slow page paint/foreground delays
- handle DPI scaling and different browser sizes
- prove a state transition after click

Do not attempt browser security bypasses.

## Kakao UI automation strategy

Use layered evidence rather than one brittle selector:

1. Strong semantic/UIA evidence if present.
2. Win32 HWND/class/title evidence.
3. Strict Kakao-owned surface visual recognition.
4. User calibration only as a last-resort compatibility mechanism.

Calibration must not be required for the default happy path if automatic detection can be made robust.

If calibration is retained:
- make it visual and easy, not a cryptic numbered developer menu
- explain exactly what needs calibration
- store relative position + host-window/surface identity + visual signature
- invalidate profiles when the Kakao UI/version/layout materially changes
- never replay a stale calibration blindly

## Voice Room creation / active verification

This is the core product, not just room navigation.

Audit the entire Windows Voice Room automation implementation after room entry.

The strong success standard is:
- detect active Voice Room OR
- deliberately navigate to the Voice Room creation UI
- set/create appropriate room data if Kakao requires it
- invoke create
- observe strong active-state evidence
- require repeated/stable evidence when necessary
- only then set `LiveVerified`, start time, 48h tracking, and `ACTIVE`

Do not use weak text combinations such as merely seeing `보이스룸`, `스피커`, `리스너` as proof that the Voice Room is active.

## Audio / speaker-request behavior

Unattended operation must be quiet and safe.

Desired:
- microphone muted
- speaker/output muted where possible
- do not globally destroy the user's Windows audio settings unnecessarily
- if the user changes system volume manually during an operation, do not blindly restore stale values over their choice
- if Kakao exposes explicit mute-state controls, prefer state-aware toggling
- runtime guard can repair audio state if Kakao changes it

Speaker requests:
- if Kakao exposes an explicit `speaker requests off` option, use it safely
- otherwise auto-reject only a positively identified request/reject control
- never auto-accept or auto-promote a listener
- ambiguous UI => do nothing and report

## Windows session / monitor behavior

Desired unattended setup:
- Windows PC stays powered on.
- Monitor may sleep/OFF.
- Windows user session remains logged in and unlocked.
- App may prevent system sleep while management is active.
- App should not prevent monitor sleep unnecessarily.

If Windows is locked:
- pause safely
- show `잠금 해제 대기`
- resume after legitimate unlock

Never bypass Windows lock, credentials, Windows Hello, Kakao security prompts, CAPTCHA, or account restrictions.

## Reliability / recovery

Implement and audit:
- Kakao process watchdog/relaunch
- app restart persistence
- Windows login auto-start option
- stale state recovery
- atomic/local state persistence
- bounded retry/backoff with jitter if useful
- no retry storms
- single-flight UI automation
- cancellation when user presses Stop
- graceful shutdown
- logs/diagnostics capped by size/retention
- evidence screenshots on failure if appropriate and privacy-conscious
- clear `USER_ACTION_REQUIRED` states for login/security/account checks

Do not keep clicking through unknown dialogs.

## UX / visual quality

The creator explicitly wants high-quality design, not a developer-looking WPF control panel.

Redesign/polish Windows UI to feel like a finished commercial Windows utility while staying lightweight.

Expected qualities:
- clear visual hierarchy
- modern dark UI
- coherent spacing/typography
- strong primary `전체 시작` / `전체 중단`
- obvious per-room state cards/table
- readable remaining time
- clear success/warning/error colors
- no raw enum codes
- no unlabeled checkboxes
- no white-on-white/default WPF styling
- no giant empty areas
- responsive resizing
- sensible minimum window size
- useful empty state
- onboarding for first room
- progress/state timeline during bootstrap
- clear action when user intervention is genuinely required
- copyable detailed diagnostics behind an advanced/details affordance rather than dumping giant errors into a table cell

Default UI should be simple. Advanced diagnostics/calibration should be hidden behind an advanced section.

Do not mimic Kakao branding deceptively or imply official affiliation.

## Product improvements you are authorized to add

You are explicitly authorized to add, remove, redesign, or refactor features when it materially improves reliability, usability, maintainability, or sale-readiness.

Examples of good additions if justified:
- first-run onboarding
- per-room enable/disable
- force check/recreate action
- current automation stage/progress
- health/self-test page
- diagnostics export
- copy diagnostic bundle
- log retention controls
- update-safe schema migrations
- crash recovery
- app single-instance guard
- tray behavior if genuinely useful
- Windows notification only for meaningful intervention-required failures
- safer configuration validation
- duplicate OpenChat-link detection
- per-room last successful verification
- next scheduled check display
- startup health summary

Do not add random feature bloat. Every addition should serve the core unattended Voice Room lifecycle.

## Security / abuse boundaries

Do NOT implement:
- CAPTCHA bypass
- anti-detection/stealth/evasion
- fingerprint/device spoofing
- ban/sanction evasion
- lock-screen bypass
- fake user engagement
- artificial member simulation
- bypassing Kakao account/session limits
- automatic acceptance/promotion of unknown users

This tool should automate the user's own normal Kakao UI workflow, with conservative fail-closed behavior around ambiguous/security-sensitive states.

## Testing standard

Do not equate `build passes` with `product works`.

You must create meaningful automated coverage where possible:
- pure state-machine unit tests
- room lifecycle tests
- retry/backoff tests
- persistence/migration tests
- URL validation tests
- duplicate-room/link tests
- visual-detector tests using fixture images/crops derived from available real screenshots if they can be added safely
- UI-state transition tests
- regression tests for previously proven bugs

Windows CI must at least:
- restore
- build Release
- publish self-contained single-file x64 EXE
- hash artifact
- upload artifact

Preserve Android/backend CI.

If practical, introduce a deterministic simulation/fake Kakao surface harness so the state machine can be tested without a live Kakao process.

## Real-world validation truthfulness

Some things cannot be proven in GitHub Actions. Do not claim they are verified when they are not.

Before calling Windows fully proven, distinguish:
- CI verified
- simulated/fixture verified
- actual Windows/Kakao verified

Actual end-to-end proof eventually needed:
- browser/open-link entry on target PC
- Kakao preview → real chat
- Voice Room creation
- Voice Room active/PIP proof
- mic/speaker guard
- speaker-request behavior with another participant/account when available
- real 48h expiry/recreation
- multi-room/account concurrency behavior

Your implementation should minimize how many manual test interactions the creator must perform. Prefer one comprehensive smoke run that captures detailed stage evidence over asking for screenshots after every click.

## Acceptance criteria for final Windows release candidate

Normal user flow must be:

1. Launch app.
2. Add room name + OpenChat link.
3. Toggle room management ON if not default.
4. Press `전체 시작`.
5. Observe understandable progress.
6. Automation reaches `보룸 활성` or a precise actionable `사용자 조치 필요` state without requiring manual safe/live checks.

The release is NOT acceptable if a correctly configured room simply remains at `등록 대기` or silently does nothing.

The release is NOT acceptable if it marks Voice Room active without strong evidence.

The release is NOT acceptable if one failed room permanently stops all management.

The release is NOT acceptable if the default path requires cryptic manual calibration.

## Final deliverables

Do not stop at an analysis report. Implement the work.

Before finishing:

1. Audit current code and summarize architectural problems privately in your working notes.
2. Refactor/fix them.
3. Add tests.
4. Run all relevant tests/CI locally where available.
5. Update Windows version appropriately.
6. Update README/handoff docs to match reality.
7. Keep PR #12 current, or if you have a strong reason to split the work, create a clearly named follow-up PR and document why.
8. Produce a self-contained Windows x64 EXE artifact.
9. Preserve a ZIP artifact as well.
10. Record SHA-256.
11. Provide a concise final report containing:
   - what was structurally changed
   - what was added/improved proactively
   - tests and CI results
   - artifact/version
   - what is truly verified vs what still requires real Kakao runtime validation
   - the smallest possible real-device/PC validation checklist

## Working style

- Do not ask the creator to decide routine engineering details.
- Do not wait for the creator to point out obvious UX/reliability gaps.
- Do not ship a stream of micro-patches.
- Bundle related fixes into coherent releases.
- Prefer deleting duplicated/brittle code to layering more fallback spaghetti.
- Preserve working functionality.
- Keep the repo buildable throughout the work.
- If an architectural change is clearly needed, make it.
- If current assumptions are wrong, correct them rather than preserving them for compatibility.
- Continue until you have the best production-ready release candidate you can reasonably produce from the repository and available runtime evidence.
