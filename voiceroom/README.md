# Kakao VoiceRoom Manager — standalone Android app

`보이스룸 매니저`는 KakaoAutoSender와 완전히 별개로 설치되는 Android 앱이다.

- module: `:voiceroom`
- application ID: `com.local.kakaovoiceroom`
- app label: `보이스룸 매니저`
- current version: `0.5.1` / versionCode `13`
- debug CI artifact: `KakaoVoiceRoomManager-debug-apk`
- production workflow: `VoiceRoom signed release APK`

KakaoAutoSender의 launcher/UI/preferences/notification listener/application ID와 공유하지 않는다. 두 앱은 같은 휴대폰에 동시에 설치할 수 있다.

## Account model

이 앱은 Kakao ID, 비밀번호, 세션 토큰, 쿠키를 입력받거나 저장하지 않는다. 같은 휴대폰의 공식 KakaoTalk 앱에 이미 로그인된 본인 계정을 사용한다.

## Verified target-phone flow

실제 Samsung 기기에서 다음 경로가 확인됐다.

1. 방 이름 + `https://open.kakao.com/...` 링크 등록
2. `안전 점검`: 정확한 방 -> 하단 `+` -> VoiceRoom 생성 화면까지 인식
3. `실제 점검`: 보룸 이름 입력 -> 실제 `만들기` 클릭
4. 일반 채팅/메뉴 문구가 아닌 VoiceRoom 전용 강한 활성 증거 2회 확인
5. 실제 Kakao PIP VoiceRoom 카드가 생성되고 `1명 참여 중` 및 마이크/스피커/퇴장 컨트롤이 노출됨
6. 앱 카드에 `보룸 활성 · 47시간59분`, `안전 ✓`, `실제 활성 ✓`가 표시되고 새 48시간 기준이 저장됨
7. v0.4.2 오디오 가드가 실제 PIP의 마이크와 스피커를 모두 음소거 상태로 만들고 앱에서 `마이크 ✓ · 스피커 ✓`를 확인함

v0.4.0에서 실제 보룸 없이 `실제 ✓`가 찍힌 오탐은 v0.4.1에서 제거됐다. v0.4.2에서 실제 생성/오디오 보호까지 target phone에서 확인됐고, v0.5.0은 장시간 런타임 요청 보호와 운영 가시성을 추가했다. v0.5.1은 Samsung 화면 OFF/AOD 판정과 secure-lock 대기/재개 흐름을 보강한다.

## Audio protection

활성 VoiceRoom을 확인한 뒤 앱은 카카오 내부 마이크/스피커 컨트롤을 fail-safe 방식으로 점검한다.

- 명시적인 `마이크 끄기/켜기`, `스피커 끄기/켜기`, 음소거 관련 accessibility action label을 우선 사용
- semantic label이 없으면 실제 활성 PIP의 명확한 퇴장 컨트롤을 기준으로 같은 행의 마이크/스피커 후보를 구조적으로 찾는다
- 구조 fallback은 `checkable/checked` 또는 `selected`처럼 현재 ON 상태를 확인할 수 있을 때만 자동 토글한다
- 의미를 확정할 수 없는 아이콘은 맹목적으로 누르지 않는다
- 방별 `micMuted`, `speakerMuted`, `audioCheckedAt` 증거를 저장한다
- VoiceRoom 활성 성공 자체와 오디오 보호 검증은 UI에서 별개로 표시한다
- 자동관리 중 Kakao가 명확한 mic/speaker action label을 다시 노출하면 runtime guard가 재음소거한다
- mic와 speaker 의미가 한 컨테이너에 동시에 섞인 broad control은 runtime guard가 클릭하지 않는다

전역 휴대폰 미디어 볼륨은 자동화 트랜잭션 동안만 임시 0으로 만든다. 복구 시 현재 볼륨이 아직 0일 때만 이전 값을 복원하며, 사용자가 작업 도중 직접 다른 볼륨으로 바꿨다면 그 값을 덮어쓰지 않는다. 48시간 동안 휴대폰 전체를 강제 무음으로 두는 방식은 사용하지 않는다.

## Speaker-request protection

카카오 VoiceRoom의 스피커 요청은 runtime event guard가 처리한다. 앱이 48시간 점검을 수행하지 않는 평상시에도 접근성 서비스가 Kakao 창/오버레이/관련 이벤트를 관찰한다.

- 관리가 ON이고 `실제 활성 ✓`인 방만 요청 보호 대상이다
- 카카오가 `스피커 요청 끄기`, `스피커 요청 받지 않기`, `...차단하기`처럼 행동 의미가 명확한 request-control label을 제공할 때만 자동으로 요청 받기를 끈다
- `스피커 요청 차단`처럼 단순 상태 문구는 절대 클릭 근거로 쓰지 않는다
- 들어온 요청은 explicit speaker-request context와 같은 작은 UI subtree 안에 explicit `거절/거부` action이 함께 있을 때만 자동 거절한다
- `수락`, `승인`, standalone `스피커로 참여`, `스피커로 전환/승격` 계열 action은 자동 클릭 금지다
- `스피커로 참여 요청 거절` 같은 reject 문구는 `스피커로 참여`와 부분 문자열이 겹쳐도 올바르게 reject로 유지한다
- generic `취소`, profile name, 좌표/아이콘 추측으로 요청을 처리하지 않는다
- 현재 Kakao interactive windows에서 관리 VoiceRoom identity가 확인돼야 요청 popup을 해당 방에 연결한다
- 여러 관리 보룸 중 어느 방 요청인지 확정되지 않으면 자동 클릭하지 않고 ambiguous 진단을 남긴다
- request notification text만 감지되고 안전한 reject action이나 room identity가 없을 때도 자동 조작하지 않는다
- 런타임 운영 지표로 요청거절 횟수, request-toggle 차단 횟수, passive audio re-protection 횟수를 표시한다

## v0.5.1 screen-off / lock behavior

- 화면 OFF 여부는 `PowerManager.isInteractive()` 하나만 사용하지 않고 실제 기본 디스플레이 상태(`OFF`, `DOZE`, `DOZE_SUSPEND`, `ON`)와 함께 판정한다.
- Samsung AOD/DOZE 또는 wake transition을 실제 사용자 사용으로 오인해 5분 미루는 문제를 줄인다.
- 자동 작업이 시작될 때 `interactive/display/locked/secure` 진단을 저장해 실기 오판을 추적할 수 있다.
- 화면이 OFF라도 기기가 논리적으로 unlocked/trusted 상태라면 자동 점검을 계속할 수 있다.
- 지문/패턴/PIN 인증이 실제로 필요한 secure-lock 상태는 우회하지 않는다. 해당 방은 `WAITING_UNLOCK`으로 두고 안전하게 대기한다.
- 사용자가 정상적인 지문/패턴/PIN으로 잠금 해제하면 `ACTION_USER_PRESENT`를 받아 대기 중 방을 수초 내 재예약한다. 5분 주기를 끝까지 기다릴 필요가 없다.
- lock-screen bypass, biometric/PIN injection, security-check bypass는 구현하지 않는다.

## Product/UX behavior

- `보룸 활성`과 `관리 ON/OFF`를 분리해서 표시한다.
- `관리 OFF`는 현재 VoiceRoom을 종료하지 않고 48시간 자동 재점검/재개설 및 runtime request protection만 끈다.
- 방 카드에 `실제 활성 ✓`, 마이크 보호, 스피커 보호, 스피커 요청 보호 상태를 별도 표시한다.
- 오디오 보호가 아직 확인되지 않은 활성 방은 `오디오 확인` 버튼으로 기존 VoiceRoom을 재점검할 수 있다.
- `전체 시작`은 실제 활성 검증을 통과한 관리 ON 방만 스케줄링하고 runtime request guard도 함께 활성화한다.
- `20초 화면 OFF 자동점검 테스트`는 자동관리 ON 상태에서 실제 AlarmManager/WakeActivity 경로를 빠르게 검증하기 위한 운영 테스트다.
- footer에 runtime 보호 누적 지표와 최근 동작 상태를 표시한다.

## Reliability behavior

- strict Kakao package gate
- room-title + participant-count suffix support (`게임방 386`, `1 1`)
- official HTTPS `open.kakao.com` link validation
- bottom-left composer `+` geometry fallback
- staged state machine: room -> composer -> VoiceRoom -> create form -> name -> submit -> strong active proof -> audio guard
- 실제 `만들기` 클릭 전 새 VoiceRoom 성공 처리 금지
- strong active proof 2회 연속 확인
- Compose/custom input fallback using focus, `ACTION_SET_TEXT`, bounds, max-length, parent/child nodes
- request decisions are semantic + room-scoped and fail closed
- interactive Kakao windows are scanned so PIP/overlay controls can be handled without coordinate macros
- per-stage timeout + whole-job watchdog
- privacy-safe boolean diagnostics and aggregate runtime counters
- progressive unattended retry: 1m -> 3m -> 10m -> 30m -> 1h
- ~47h55m precheck; actual Kakao UI remains source of truth
- crash/stale-pending/app-update recovery
- device reboot never trusts the old 47h55m timer: verified managed rooms are staggered into a real health check starting ~15 seconds after boot
- AUTO work defers 5 minutes only when the default display is genuinely ON/interactive
- secure-lock waits for normal unlock and resumes immediately after `USER_PRESENT`
- scheduled jobs remain single-flight
- BootReceiver accepts only the fixed system recovery action allowlist; WakeActivity remains non-exported

## Build and release

PR CI validates backend plus KakaoAutoSender and VoiceRoom Manager unit tests, lint, debug APKs, and minified release smoke builds. Production VoiceRoom distribution uses `.github/workflows/voiceroom-release.yml` with protected signing secrets, signature verification, and SHA-256 generation.

## Remaining live-device gates

1. v0.5.x speaker-request runtime guard against a real request from another account/device
2. v0.5.1 20-second screen-off test on the target Samsung: OFF/DOZE false-busy fix + secure-lock WAITING_UNLOCK + normal-unlock resume
3. full 47h55m -> 48h expiry -> recreation cycle
4. one-account/device multi-VoiceRoom concurrency behavior

These runtime-only gates remain pending until they pass on the target phone. The app does not bypass lock screens, security checks, CAPTCHAs, account/session limits, moderation controls, or sanctions.
