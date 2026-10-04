# Kakao VoiceRoom Manager — standalone Android app

`보이스룸 매니저`는 KakaoAutoSender와 완전히 별개로 설치되는 Android 앱이다.

- module: `:voiceroom`
- application ID: `com.local.kakaovoiceroom`
- app label: `보이스룸 매니저`
- current version: `0.4.1` / versionCode `10`
- debug CI artifact: `KakaoVoiceRoomManager-debug-apk`
- production workflow: `VoiceRoom signed release APK`

KakaoAutoSender의 launcher/UI/preferences/notification listener/application ID와 공유하지 않는다. 두 앱은 같은 휴대폰에 동시에 설치할 수 있다.

## Account/security model

이 앱은 Kakao ID, 비밀번호, 세션 토큰, 쿠키를 입력받거나 저장하지 않는다. 같은 휴대폰의 **공식 KakaoTalk 앱에 이미 로그인된 본인 계정**을 Accessibility로 조작한다.

Accessibility 서비스는 `com.kakao.talk` 화면만 조작한다. 앱은 잠금화면, Kakao 보안확인, CAPTCHA, 계정/세션 제한, 제재를 우회하지 않는다.

## v0.4.1 product flow

1. 방 이름 + 권장 `https://open.kakao.com/...` 링크를 등록한다.
2. `안전 점검`으로 정확한 방 → 하단 `+` → VoiceRoom 생성 화면까지 인식되는지 확인한다. 이 단계는 생성 버튼을 누르지 않는다.
3. `실제 점검`은 AlarmManager를 기다리지 않고 선택한 방에서 즉시 실행된다.
4. VoiceRoom이 없으면 방 이름을 최대 30 Unicode code points로 잘라 이름칸에 입력한다.
5. 이름 반영과 활성화된 `만들기` 버튼을 각각 확인한 뒤에만 실제 생성 버튼을 누른다.
6. **성공 판정은 fail-closed**다. 일반 채팅방/메뉴의 `보이스룸`, `스피커`, `리스너` 같은 약한 문구는 성공 증거로 쓰지 않는다.
7. `VOICE_MENU` 또는 실제 생성 버튼 클릭 후 `CREATING_CONFIRMING` 단계에서, 클릭 가능한 `보이스룸 종료/나가기` 같은 강한 활성 증거가 **2회 연속** 보여야 `실제 ✓`를 저장한다.
8. 새로 만든 VoiceRoom이면 강한 활성 증거 확인 시점부터 새 48시간 기준시간을 기록한다.
9. 모든 ON 방이 `실제 ✓`일 때만 `전체 시작`이 허용된다.
10. 이후 자동관리는 검증 완료된 방만 직렬(single-flight) 처리한다.

### v0.4.0 -> v0.4.1 trust migration

v0.4.0 실기에서 실제 VoiceRoom이 생성되지 않았는데 `실제 ✓`가 저장되는 false positive가 확인됐다. 원인은 일반 VoiceRoom 메뉴의 약한 텍스트 조합도 활성 상태로 인정한 것이었다.

v0.4.1 첫 실행 시 verification schema migration이 실행되어:

- v0.4.0에서 저장된 `실제 ✓`를 자동 무효화
- 잘못 저장될 수 있었던 시작시간/다음 점검시간 초기화
- 자동관리 OFF 전환
- 기존 비파괴 `안전 점검` 결과는 유지 가능
- strict v0.4.1 `실제 점검`을 다시 통과해야 자동관리 재개

방 이름이나 링크를 수정해도 안전/실제 검증과 48시간 기준은 자동 무효화된다.

## Reliability behavior

- strict Kakao package gate: delayed callbacks never click a non-Kakao window
- exact room-title matching plus participant-count suffix support (`게임방 386`, `1 1`)
- official HTTPS `open.kakao.com` deep link validation
- bottom-left composer `+` geometry fallback to avoid top-right menu confusion
- staged state machine: room -> composer -> VoiceRoom -> create form -> name -> submit -> strong active proof
- no active success before the actual create-button click on a new room
- two consecutive strong active proofs to reduce transient Accessibility false positives
- Compose/custom input fallback using focus, `ACTION_SET_TEXT`, bounds, max-length, parent/child nodes
- per-stage timeout + 90-second whole-job watchdog
- privacy-safe diagnostics store UI-state booleans, not chat messages
- progressive unattended retry: 1m -> 3m -> 10m -> 30m -> 1h
- ~47h55m precheck; actual Kakao UI remains source of truth
- crash/stale-pending/reboot/app-update recovery
- interrupted manual checks never silently rerun after reboot/update
- AUTO work never steals the foreground while the user is actively using the phone; it defers 5 minutes
- secure device locks are never bypassed
- media volume is muted only during the automation transaction and restored afterward
- AUTO completion returns Home; direct checks return to VoiceRoom Manager

## Build and release

PR CI validates backend plus both standalone Android apps: unit tests, lint, debug APKs, and minified release smoke builds.

Production VoiceRoom distribution uses `.github/workflows/voiceroom-release.yml` with protected signing secrets. The workflow validates config, runs tests/lint, builds a minified release, verifies its signature with `apksigner`, generates SHA-256, and uploads APK + checksum.

Debug signing is for testing only and is not the production update identity.

## Live-device gates before merge/release claim

KakaoTalk exposes no public VoiceRoom automation API, so these still require the exact target phone/runtime:

1. v0.4.1 live `실제 점검`: name injection -> `만들기` -> real VoiceRoom -> 2x strong active proof -> persisted 48h baseline
2. screen-off unattended wake/recreation on target Samsung firmware
3. current Kakao in-room speaker/audio control semantics before automating long-lived per-room silence
4. whether one Kakao account/device can concurrently maintain several active VoiceRooms; surface any platform/session limit instead of bypassing it

Do not market screen-off, silent long-lived operation, or multi-room concurrency as verified until those live gates pass.
