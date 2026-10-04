# Kakao VoiceRoom Manager — standalone Android app

`보이스룸 매니저`는 KakaoAutoSender와 완전히 별개로 설치되는 Android 앱이다.

- module: `:voiceroom`
- application ID: `com.local.kakaovoiceroom`
- app label: `보이스룸 매니저`
- current version: `0.4.2` / versionCode `11`
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
5. 실제 Kakao PIP VoiceRoom 카드가 생성되고 참여자 수/마이크/스피커/퇴장 컨트롤이 노출됨
6. `실제 활성 ✓` 및 새 48시간 기준 저장

v0.4.0에서 실제 보룸 없이 `실제 ✓`가 찍힌 오탐은 v0.4.1에서 제거됐다. v0.4.2는 이 성공 기준을 유지하면서 오디오 보호와 운영 UX를 추가한다.

## v0.4.2 audio protection

활성 VoiceRoom을 확인한 뒤 앱은 카카오 내부 마이크/스피커 컨트롤을 fail-safe 방식으로 점검한다.

- 명시적인 `마이크 끄기/켜기`, `스피커 끄기/켜기`, 음소거 관련 accessibility action label을 우선 사용
- semantic label이 없으면 실제 활성 PIP의 명확한 퇴장 컨트롤을 기준으로 같은 행의 마이크/스피커 후보를 구조적으로 찾는다
- 구조 fallback은 `checkable/checked` 또는 `selected`처럼 현재 ON 상태를 확인할 수 있을 때만 자동 토글한다
- 의미를 확정할 수 없는 아이콘은 맹목적으로 누르지 않는다
- 방별 `micMuted`, `speakerMuted`, `audioCheckedAt` 증거를 저장한다
- VoiceRoom 활성 성공 자체와 오디오 보호 검증은 UI에서 별개로 표시한다

전역 휴대폰 미디어 볼륨은 자동화 트랜잭션 동안만 임시 0으로 만들고 성공/실패/복구 시 원래 값으로 돌린다. 48시간 동안 휴대폰 전체를 강제 무음으로 두는 방식은 사용하지 않는다.

## Product/UX behavior

- `보룸 활성`과 `관리 ON/OFF`를 분리해서 표시한다.
- `관리 OFF`는 현재 VoiceRoom을 종료하지 않고 48시간 자동 재점검/재개설만 끈다.
- 방 카드에 `실제 활성 ✓`, 마이크 보호, 스피커 보호를 별도 표시한다.
- 오디오 보호가 아직 확인되지 않은 활성 방은 `오디오 확인` 버튼으로 기존 VoiceRoom을 재점검할 수 있다.
- `전체 시작`은 실제 활성 검증을 통과한 관리 ON 방만 스케줄링한다.
- `20초 화면 OFF 자동점검 테스트`는 자동관리 ON 상태에서 실제 AlarmManager/WakeActivity 경로를 빠르게 검증하기 위한 운영 테스트다.

## Reliability behavior

- strict Kakao package gate
- room-title + participant-count suffix support (`게임방 386`, `1 1`)
- official HTTPS `open.kakao.com` link validation
- bottom-left composer `+` geometry fallback
- staged state machine: room -> composer -> VoiceRoom -> create form -> name -> submit -> strong active proof -> audio guard
- 실제 `만들기` 클릭 전 새 VoiceRoom 성공 처리 금지
- strong active proof 2회 연속 확인
- Compose/custom input fallback using focus, `ACTION_SET_TEXT`, bounds, max-length, parent/child nodes
- per-stage timeout + whole-job watchdog
- privacy-safe boolean diagnostics
- progressive unattended retry: 1m -> 3m -> 10m -> 30m -> 1h
- ~47h55m precheck; actual Kakao UI remains source of truth
- crash/stale-pending/reboot/app-update recovery
- AUTO work defers 5 minutes while the user is actively using the phone
- scheduled jobs remain single-flight

## Build and release

PR CI validates backend plus KakaoAutoSender and VoiceRoom Manager unit tests, lint, debug APKs, and minified release smoke builds. Production VoiceRoom distribution uses `.github/workflows/voiceroom-release.yml` with protected signing secrets, signature verification, and SHA-256 generation.

## Remaining live-device gates

1. v0.4.2 audio guard: actual PIP mic/speaker accessibility state and automatic mute result on the target Samsung phone
2. 20-second screen-off unattended wake/check path on the target Samsung firmware
3. full 47h55m -> 48h expiry -> recreation cycle
4. one-account/device multi-VoiceRoom concurrency behavior

Runtime-only gates remain pending until they pass on the target phone. The app does not bypass lock screens, security checks, CAPTCHAs, account/session limits, moderation controls, or sanctions.
