RC10 추가 전경 시험: Roblox 로그인 대기 화면을 전경에 유지한 23:37–23:42 동안 23:41:39 예정 점검을 넘겨도 전경 전환/새 실패/새 성공/수명 변경이 없었다. 실제 게임 플레이나 로그인 자동화 시험이 아니다. 매니저 복귀 후 23:42:28 실제 활성/두 음소거 재검증(1.572초), Stage도 활성·보호 확인으로 복구, 동일 생성 epoch 유지. 방 12는 추가 생성/기존 방 종료 없이 대기.

## RC10 최종 코드 검증 — 2026-10-07

시험 EXE 코드 `126299854851420cad71eef49e2fa7be08ce8c74`. 로컬 174/174 통과, 실패/skip 0. VoiceRoom Windows #110(37637842937), Windows client #174(37637842796), APK/backend #660(37637842841) CI success. 실제 두 방 상태, 전역 OFF 단발 보호 확인(23:35:40, 1.929초), 프로그램 ON 재시작 후 보호 확인(23:36:39, 2.097초), 다른 방 사전 대기(23:37:02, 87ms)를 확인했다. 시간은 단일 관찰값이다. 원래 생성 epoch 18:36:27 유지, 방 12 생성/기존 방 종료 없음. 점검 완료 팝업을 제거하고 결과를 화면에 남긴다.

RC9 전체 신규 생성 실기는 과거 코드의 근거다. RC10에서 새 생성 E2E/실제 48시간 만료까지 재시험했다고 주장하지 않는다. 다중 방 등록/안전 대기는 같은 클라이언트의 동시 참여 지원 완료가 아니다. 실제 스피커 요청, 물리 모니터 OFF/잠금/Windows 재시작, Kakao 재시작/fault injection도 남았다. 최초 모달 fixture OCR 실패와 수정, 사용자 운영에서 추가 발견한 오류를 아래 감사 이력에 남긴다. 판매 완료본으로 승인하지 않는다.
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

First RC9 native-delivery run still missed preview within 8 seconds; retry then stalled behind its own landing under the generic foreground gate. Pending entry now recognizes only its exact registered official OpenChat URL as a continuation surface; healthy-room browsing/other tabs/games remain deferred. The read-only handoff budget is 20 seconds within the 90-second operation deadline, not a fixed delay before success. Failed browser-confirmation captions are retained for diagnosis; no arbitrary launch/security button is allowed.

RC8 actual closed-chat StartAll failed to see preview at 18:14:34; automatic retry recovered exact chat/active/both-muted at 18:19:29. RC9 sends a native click to the fresh unique exact-name browser action, verifying official URL, rectangle containment, foreground and point ownership. The old Invoke/Selection-only browser executor is removed. Delivered input remains pending until real Kakao room proof. Browser action and entry traces are logged. The observed green landing theme is recognized with the existing strict single-outline geometry; solid backgrounds and unsupported colors are rejected. 154 local tests cover geometry, theme variants and existing workflow barriers. Final RC9 CI/target proof/hashes belong in PR #12 and manifest. Android/backend unchanged; elapsed 48h, other-account requests, account multi-room limits and physical monitor/lock/login gates remain.

---
# Previous continuation: Windows RC8 footer reliability

Final RC8 target run: normal launch 18:07:22 / ready 18:07:23 / existing active+both muted 18:07:27. Controlled one-participant termination then unattended retry recovered a transient menu miss: default menu, submit 18:08:42, active+both-muted 18:08:45, 5020ms. Durable new epoch 18:08:42.5814718 KST, no manual creation/audio action. CI for code 687b5e4: VoiceRoom Windows #101, Windows client #165, APK/backend #648 all success. Real elapsed 48h remains pending.

142 local tests pass. On October 7, controlled termination of the one-participant room was followed by the RC7 unattended default-menu workflow: submit 18:03:50 KST, active/mic-muted/speaker-muted repeated proof 18:03:53, 5087ms operation. The durable epoch matches submission; no manual creation menu or audio click. This proves controlled recovery, not real elapsed 48-hour expiration.

Exit-circle recognition occasionally failed when cursor-overlay illumination connected its background to adjacent pixels while four left circles remained intact. RC8 recovers only the actual exit glyph within the strict right-hand layout slot; it still requires exact title/PID, positive participants, left-control geometry and typed audio readback. Overlay and missing-exit-glyph regression cases cover 100%, 150%, 200%. A missing audio control is never inferred. Final RC8 binary QA/CI/hash evidence is in PR #12 and output manifest. Other-account requests, account multi-room limits, physical monitor OFF/lock/login restart and elapsed 48h remain unverified.

---
# Previous continuation: Windows RC7 cancellation/startup audit

139 local Windows tests pass. Final target testing exposed an audio readback versus foreground-switch race; transient uncertainty now retries read-only under bounded backoff instead of permanently requiring intervention, and rechecks focus before classifying failed native input. Coordinator logs follow its StateStore directory so fixture/simulation state stores cannot pollute real operational logs. Manual/probe results arriving after Stop/Dispose are marked cancelled and discarded by coordinator and UI. Delivered submit time checkpoints precede cancelled progress callbacks. Startup attempts, loaded window, duplicate instance and startup exceptions are recorded in logs/startup.jsonl. Existing focus-aware wait remains; no background-only backend or unattended focus-stealing mode.

RC6 launch verification resumed successfully on 2026-10-07 at 17:04 KST through the normal File Explorer EXE launch. launch_app's window-opened handler preparation fails; this did not reproduce an EXE/security refusal. Actual outputs RC6 path/version and active/both-muted recovery were observed; duration 2255ms versus previous 31914ms observation, not a controlled benchmark. Legacy unknown start remains unknown. Modified build real 48h regeneration and other-account request gates still need proof.

RC7 final commit CI, package hashes and actual launch/quiet/resume checks belong in PR #12 and output manifest. Preserve Android/backend and existing state. Never fabricate release approval from these tests.

---
# Current continuation: Windows RC6 expiry recovery — 2026-10-07

Actual target logs: 16:17 KST explicit ended VoiceRoom was closed, then lost chat foreground caused a menu intervention. 16:18–16:20 repeated desktop UIA scans delayed work by roughly 15 seconds each and verification exceeded 90 seconds after submission. User subsequently cleared uncertainty; the active room was rediscovered with unknown start. Do not fabricate a recovered timestamp for this legacy case.

RC6: native dedicated active/audio evidence first; exact HWND UIA root instead of desktop-wide enumeration; verified default-name create form skips editable UIA; chat re-established after explicit end; toolbar-first auto menu; menu retry rather than permanent intervention; submission timestamp persisted after delivered confirm input; late verified activity restores that epoch; pending submission re-observed automatically under backoff while duplicate-create barrier remains. Exact ended proof clears prior epoch. 132 tests cover submission timeout/restart/date recovery and safety. Android/backend unchanged.

User requested investigation of fully background operation and explicitly said to leave current behavior if unsupported. Current background UIA/capture did not provide reliable controls. No background click/message backend or new foreground-permitting unattended mode was added. RC5 focus-preserving wait remains: other-app foreground also delays expiry work. Calibration remains optional, default menu is automatic. Final CI/package and actual RC6 existing-room verification are recorded in PR #12 and output manifest; modified-binary real 48h regeneration still needs proof.

---
# RC5: periodic focus stealing fixed

The five-minute health check previously called the complete foreground bootstrap. RC5 gates automatic due checks, retries, runtime work and startup recovery before workflow entry. Other-app foreground defers without a click/activation, failure increment, fresh success or timer/creation-barrier reset. Explicit StartAll/RecheckAll/manual actions remain immediate one-shot work. Automatic operation checks also yield before subsequent input/activation when foreground changes away from controlled surfaces. The manager/exact target room or an unattended desktop (30 seconds idle) permits resume; game/video idle does not. Pending verification is presented explicitly, and audio/request/expiry actions are delayed instead of claiming background proof. 128 tests pass locally. Actual-PC/CI/package evidence is in PR #12 and delivery manifest.

---
# Windows RC4 감사 및 검증 — 2026-10-05

현재 기준은 RC4이며 아래 RC3/RC2 기록은 역사적 관찰입니다. RC3의 자동 활성·음소거 미검증 상태는 RC4의 실제 전용 창 검증으로 개선했습니다. Android/backend 소스는 이번 변경에서 유지했습니다.

## 구조적 변경

RoomWorkflow가 유일한 bootstrap 경로이고 작업별 exact room/PID/창 증거를 사용합니다. 채팅과 별도 전용 VoiceHost를 분리하여 UIA 빈 트리나 소유 관계 없는 PIP 때문에 정확한 방 성공이 취소되지 않습니다. 전용 창 제목+같은 PID+참여 인원+다섯 컨트롤을 함께 읽고 두 번 확인합니다. ON/OFF 실측 glyph와 상태를 검증한 경우만 토글하며 반대 상태를 재확인합니다. 빈 glyph, 누락 이웃, 잘못된 row/geometry는 거절합니다. 전환 애니메이션에는 최대 네 번 읽기만 재시도하고 같은 입력을 반복하지 않습니다.

명시적 종료의 세 문구를 두 번 읽고 창 닫힘까지 확인한 경우에만 생성 불확실성을 해제합니다. 창 소멸/인식 실패는 종료로 취급하지 않습니다. 실제 생성 전에 불확실성을 저장하고 재시작 시 오래된 활성/음소거 증거를 폐기합니다. 최종 메뉴는 exact chat header의 네 glyph를 함께 확인하며 채팅 텍스트 OCR을 메뉴 입력에 사용하던 경로를 제거했습니다.

기능: 전체 재점검, 설정만 백업/복원(최대 100방, 공식 URL, 중복/크기/schema 검증), 손상된 primary/backup 안전 대기, 작업 소요 시간과 최신 버전 표시, 다크 ComboBox, 선택 방 유지. OCR 엔진 재사용/직렬화, 중복 native/UIA 읽기와 대기 축소. 요청은 scoped 명시적 UIA action과 결과 재확인만 허용합니다. icon-only 요청 처리를 실기로 증명하지 않았습니다.

## 실제 증거와 경계

대상 PC / Kakao 26.8.1.5315 / 방 1 / 100% 배율:

- 10:46 RC3 전체 시작에서 browser → preview → 정확한 방 → 실제 전용 창 생성.
- 11:27 RC4 개발 중 이미 종료된 전용 창의 명시적 안내 확인/닫기 → 실제 자동 재생성.
- 11:58 새 생성에는 당시 호환성 메뉴 지점을 사용함. 최종 no-calibration 신규 생성 전체 run은 미검증.
- 12:00 재시작 후 실제 Mic ON·Speaker ON → 둘 다 자동 OFF → 활성/음소거 반복 확인. 수동 마이크 음소거 없이 이 전환을 관찰함.
- 12:05 기존 보호된 방 점검 약 2.2초, 12:18 약 1.8초. 전체 신규 생성 latency 보장은 아님.
- 12:18 안전 진단 로그 `four-glyph header recognized · no calibration`: 최종 기본 메뉴 실제 인식 확인.
- 12:20 runtime 스피커 전환 시험에서 실제 OFF 전환 후 전환 직후 컨트롤 가림으로 final readback 실패. 실패를 보호 완료로 숨기지 않았으며 bounded read-only 안정화 재확인과 intervention stage를 보강함. 12:27 포인터 강조가 컨트롤을 가리지 않도록 옮긴 재시험에서 RUNTIME_GUARD가 실제 Speaker ON→OFF를 처리하고 ACTIVE/두 음소거를 유지했으며 AudioRepairs가 3으로 증가함. 가림 때문에 footer가 누락된 상황은 보호 완료로 표시하지 않음.

자동 테스트 121개 전부 통과: 기존 정책/simulation 외 실측 footer/header/ended fixture, ON/OFF, 빈 glyph negative, 누락 메뉴 이웃, 100/150/200% 배율, OCR offset 동시성, 설정 import, malformed backup, 사라진 UI와 명시적 종료 분리. 녹화/프로필/채팅 본문을 fixture로 배포하지 않으며 generic glyph와 redacted header만 포함합니다.

남은 승인: 최종 no-calibration 신규 생성 종합 run, 다른 계정 요청, 다중 방/account 한도, 실제 monitor OFF/Windows 잠금·로그인 재시작, 48시간 만료. [최소 체크리스트](VOICEROOM_WINDOWS_RC_CHECKLIST.md)를 사용합니다. 정확한 제목/OCR/control을 확정할 수 없는 버전은 fail closed. native UIA provider 자체의 장시간 blocking은 cooperative 시간 제한의 한계입니다. 보안/플랫폼 제한 우회는 없습니다.

---

## 이전 RC 기록 (현재 검증 범위가 아님)
# RC3 직접 실기 관찰 — 2026-10-05 10:46 KST

다운로드 RC1 실행을 확인한 후 최신 RC3 EXE를 실행했다. UI의 RC3 버전, 기존 방 복원, 중단 시 점검 표시 없음, 이전 trace 요약을 직접 확인했다. 전체 시작 한 번으로 browser→preview→정확한 방 1→검증 폼 제출→‘보이스룸: 1’ 전용 창이 생성됐다. 창에 1명 참여 중과 오디오/퇴장 아이콘을 확인했다.

프로그램의 강한 자동 활성 검사는 별도 보이스룸 창의 소유 관계/UIA 빈 트리/아이콘만 있는 컨트롤을 읽지 못해 USER_ACTION_REQUIRED가 됐다. CreationUncertain=true가 저장돼 추가 생성이 차단됐다. 마이크와 스피커는 Computer Use로 각각 직접 음소거한 뒤 crossed 아이콘 표시를 확인했다. 이는 수동 보호 증거이며 자동 오디오 보호 성공이 아니다. 보이스룸은 유지했다.

실제 생성 성공은 이번 관찰로 확인됐으나 자동 활성/음소거/요청 거절, recovery와 48h 승인 게이트는 여전히 남는다. 이미지 fixture/CI만으로 그 게이트를 채우지 않는다.

---

# Windows RC3  실행 버전 감사 — 2026-10-05

10:40 KST 오류 화면의 실행 프로세스 경로는 Downloads/VoiceRoomManager-Windows-v0.4.0-rc1-x64였다. 기존 제목 OCR 실패 문자열과 calibration 3개도 RC1 실행과 일치한다. 최신 RC2 자동화 경로의 실패로 판정하지 않는다.

RC3: 중단/관리 OFF 방에는 다음 점검을 표시하지 않고 Stop 시 예약 시각을 폐기한다. RC1에서 저장된 긴 trace도 원인별 요약으로 표시한다. 자동화는 RC2 구조를 유지하며 Windows 테스트 74개를 통과했다. 최종 CI/해시/실기 한계는 PR와 배포 manifest에 기록한다.

---

# Windows v0.4.0 RC2 추가 감사 — 2026-10-05

RC1 이후 실제 대상 PC에서 `1` 방으로 정확히 진입하고 보이스룸 생성 폼을 여는 것을 관찰했다. 생성 버튼 제출 이후 활성/오디오 보호는 증명되지 않았다. 이 보고서는 그 경계를 유지한다. Android 소스는 변경하지 않았다.

## 구조 개선
- Preview 이름은 참여자/개설일에 붙은 제목 영역에서 exact match한다. 아바타의 글자나 참여자 수를 방 이름으로 쓰지 않는다. 큰 화면 OCR에서 빠지는 짧은 숫자는 같은 문맥을 포함한 영역으로 다시 인식한다.
- 한국어 OCR 설치 상태와 엔진 HRESULT를 분리한다. 미설치/엔진 오류/정확한 이름 불일치의 조치 안내를 표시하며 raw trace는 진단에 보관한다.
- 생성 폼을 먼저 확인하고 재사용한다. 폼이 열려 있는데 메뉴를 다시 누르는 경로를 제거했다. 제목·확인·글자수/기본 안내/단일 editable 증거가 있어야 한다. UIA 입력칸 전체가 폼 안에 있어야 하고 writable ValuePattern 입력값을 읽어 일치해야 한다.
- 검증되지 않은 이름/생성 좌표와 best-edit guess를 삭제했다. 호환성 캘리브레이션은 방 메뉴에만 남았다. 기존 calibration 데이터의 obsolete target은 실행하지 않는다. Kakao 버전 변경 시 예전 지점을 폐기한다.
- 생성 요청 전 상태 파일에 CreationUncertain을 atomic 저장한다. 확인 실패/중단/재시작 이후 중복 생성을 차단한다. 강한 활성 확인 또는 사용자의 실제 종료 확인으로만 해제한다. 시작시각을 추측하지 않는다.
- 실제 폼/preview의 개인정보 제거 fixture, 문맥 identity·UIA 폼 경계·불확실 생성/상태 저장/재시작 회귀 검사를 추가했다.

## 이번 RC 검증
Windows Release 테스트 72 PASS, 0 FAIL, 0 SKIP. 로컬 한국어 OCR fixture 인식은 PASS. CI에서 한국어 OCR이 없는 환경은 명확한 설치 필요 안내를 검사하며 실제 한국어 인식 PASS로 간주하지 않는다. 게시된 빌드/CI 결과와 SHA-256은 PR #12 및 배포 manifest에서 확인한다. 기존 RC1의 Android/backend 로컬 검증은 아래에 기록되어 있고 이번 커밋 CI로 다시 검사한다.

## 아직 남은 실기 게이트
최종 폼 입력/제출 → 실제 active/PIP → 마이크/스피커 readback, 실제 스피커 요청 차단/거절, monitor OFF, 잠금/해제, Kakao/앱/Windows 재시작, 다중 방 및 계정 한도, 48h 실제 만료/재생성. 기존 실제 chat/form 진입 확인만으로 이 항목을 통과 처리하지 않는다. 현재 배포는 unsigned RC이며 판매 승인 완료가 아니다.

컴퓨터 제어 도구는 브라우저 URL을 판별하지 못해 안전 검사를 통과하지 못했다. 해당 도구 제한을 우회하지 않고 코드/fixture/패키지 검증을 계속했다. 최종 자동 생성 실기는 별도로 수행해야 한다.

---

# Windows v0.4.0 RC1 감사 기록 — 2026-10-05

기준: `feat/voiceroom-standalone-android-v1`, PR #12 head `cfff7d2f6bd5731876a3f68f0a05c6a7d72ad6cf`. 최초 handoff 전체, PR 본문/댓글(댓글 없음), Windows 모든 소스/XAML, 네 workflow, root/Android/Windows README, Android 수명/저장/접근성/요청·오디오/복구 핵심 정책을 읽고 감사했다. 이전 head의 Windows/APK CI는 success였다. 새 변경은 별도 검사한다.

## 근본 결함과 변경
| 발견 | 변경 및 근거 |
|---|---|
| link launcher + entry + coordinator에서 preview/브라우저 흐름 반복 | URL launcher를 transport로 축소, RoomWorkflow가 단일 bootstrap 경로 소유 |
| 유사한 노란 버튼 detector 두 개와 호출 없는 UIA navigator | 중복 preview fallback 및 죽은 navigator 제거; CtaDetector 공유 |
| 최종 단계에서 다시 Win32 방 검색 | 검색 fallback 제거. 정확한 진입 증거 없으면 실패하며, 이미 증명한 세션은 RICHEDIT를 다시 요구하지 않음 |
| 시간 토큰이 title만으로 전역 공유되고 Save 때 삭제 | 작업 범위 room ID/URL/HWND/PID/session으로 대체. 다음 작업에는 폐기 |
| 어디든 있는 composer 또는 CTA 소멸만으로 방 진입 인정 | preview 제목 + 실제 채팅 header/정확한 독립 title 확인. 화면 변경만으로는 실패 |
| 다른 방/PIP의 활성 증거가 현재 방에 적용 가능 | 검증된 방 HWND 및 소유 modal에만 UIA/OCR scope 적용. 불명확한 독립 PIP는 성공으로 처리하지 않음 |
| icon calibration 하나로 active 인정 | 강한 semantic/OCR 증거 요구, stable 재관찰 유지 |
| 음소거 클릭만으로 true | fresh action state readback. 불명확하면 USER_ACTION_REQUIRED; 전역 시스템 볼륨 불변 |
| 루트까지 올라가 generic 거절 및 calibration 좌표로 요청 처리 | 작은 request context 컨테이너의 explicit reject만 허용, pixel 요청 거절 제거 |
| 47h55m까지 프로세스 복구가 지연 가능 | 최대 5분 health check + 47h55m/만료 주변 1분 확인; 실제 상태가 source of truth |
| 앱 재시작 때 live 상태 그대로 신뢰 | Live/Mic/Speaker 증거 폐기, enabled managed rooms 즉시 실제 점검 |
| Stop/닫기 후 sleep-loop 클릭 지속 | 모든 입력 직전 작업 cancellation/잠금 체크, cancelable wait, 90초 cooperative watchdog |
| x64 INPUT union 32byte MOUSEINPUT 공간 누락 | 중앙 NativeInput x64 레이아웃으로 대체 |
| thread별 SetThreadExecutionState ON/OFF가 다른 스레드에서 실행 | process handle PowerCreateRequest SystemRequired; display sleep 허용 |
| 상태 손상 시 무조건 빈 상태 | atomic replace + 마지막 backup + corrupt 원본 보존, 복구 후 management OFF |
| 기본 UI에서 numbered calibration·긴 오류·개발자 진단 | inline 이름/링크 onboarding, dark dashboard, 단계/다음 점검/최근 성공, 선택 방 조치, 접힌 advanced/export |

UIA 없는 client를 위해 Windows 한국어 로컬 OCR을 추가했다. OCR label/name readback은 자동화 근거이며 임의 아이콘을 이름에 따라 추측해서 클릭하지 않는다. 브라우저 주소가 등록 URL과 일치하지 않으면 visual CTA를 호출하지 않는다. 권한/보안/로그인/플랫폼 한도는 우회하지 않는다.

## 자동 검증
- Windows unit + workflow simulation + 실제 screenshot fixture tests: 53 PASS (초기 테스트 float 1tick 비교를 분 단위 입력으로 수정).
- 실제 browser landing 및 redacted preview CTA fixture: 100%, 150%, 200% 검출. 이 테스트는 실제 handoff나 생성 성공의 증거가 아니다.
- WPF dashboard를 직접 render하고 화면 검토. 첫 render에서 Button template이 DataGridRow에도 적용되는 오류를 발견·제거. 1180px와 최소 840px 렌더를 검사한다.
- Windows Release build: warning/error 0. self-contained win-x64 single-file publish + ZIP + SHA-256를 재현 가능한 PowerShell script로 생성.
- 두 Android 모듈 testDebugUnitTest/lintDebug/assembleDebug PASS. Android 소스/리소스/build 설정 변경 없음.
- backend typecheck/lint/59 tests/audit(취약점 0), secret scanner/self-test PASS. CRLF checkout 때문에 생긴 로컬 prettier 경고는 작업 파일 line ending만 정규화; backend 커밋 변경 없음.
- Android 두 앱 minified release smoke/Release Lint도 로컬 PASS. 새 head GitHub CI 결과는 PR/checks에서 확인. 로컬/CI와 실기를 혼동하지 않는다.

## 남은 제품 승인 한계
Windows 실제 Kakao 보이스룸 생성/PIP 및 icon-only audio/요청 UI, 모니터 OFF, 잠금 도중 cancellation, Kakao/Windows 재시작, 계정 다중 보룸 한도, 실제 48h cycle은 이 세션에서 검증되지 않았다. 사용자 제공 화면은 landing/preview 증거이며 보이스룸 active fixture가 아니다. 소유 관계 없는 PIP, Korean OCR 미설치, UIA address 미노출은 conservative 실패가 날 수 있다.

90초 제한은 adapter 호출 사이에서 검사하는 cooperative 제한이다. native UIA provider가 한 호출에서 멈추는 경우 강제 중단은 보장하지 않는다. 자동화를 worker process로 격리하는 개선 여지가 남는다. 이 RC를 판매 승인 완료로 포장하지 않는다. unsigned EXE이며 코드서명/installer/licensing를 Windows에 새로 만들지 않았다.

[한 번의 종합 smoke + 장시간 체크](VOICEROOM_WINDOWS_RC_CHECKLIST.md)를 사용한다. CI는 real Kakao 계정에 접속하지 않는다.
