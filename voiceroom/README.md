# Kakao VoiceRoom Manager — standalone Android app

`보이스룸 매니저`는 KakaoAutoSender와 완전히 별개로 설치되는 Android 앱이다.

- module: `:voiceroom`
- application ID: `com.local.kakaovoiceroom`
- app label: `보이스룸 매니저`
- current version: `0.4.0` / versionCode `9`
- debug CI artifact: `KakaoVoiceRoomManager-debug-apk`
- production workflow: `VoiceRoom signed release APK`

KakaoAutoSender의 launcher/UI/preferences/notification listener/application ID와 공유하지 않는다. 두 앱은 같은 휴대폰에 동시에 설치할 수 있다.

## Account/security model

이 앱은 Kakao ID, 비밀번호, 세션 토큰, 쿠키를 입력받거나 저장하지 않는다. 같은 휴대폰의 **공식 KakaoTalk 앱에 이미 로그인된 본인 계정**을 Accessibility로 조작한다.

Accessibility 서비스는 manifest/config에서 `com.kakao.talk` 화면으로 제한된다. 앱은 잠금화면, Kakao 보안확인, CAPTCHA, 계정/세션 제한, 제재를 우회하지 않는다.

## v0.4.0 product flow

1. 방 이름 + 권장 `https://open.kakao.com/...` 링크를 등록한다.
2. `안전 점검`으로 정확한 방 → 하단 `+` → VoiceRoom 생성 화면까지 인식되는지 확인한다. 이 단계는 생성 버튼을 누르지 않는다.
3. `실제 점검`을 누르면 AlarmManager를 기다리지 않고 **즉시** 해당 방만 live check 한다.
4. VoiceRoom이 없으면 방 이름을 최대 30 Unicode code points로 안전하게 잘라 자동 입력하고, 활성화된 `만들기`를 누른 뒤 실제 활성 화면까지 확인한다.
5. 성공 시 방 카드에 `안전 ✓ / 실제 ✓`를 저장하고 새로 만든 VoiceRoom이면 48시간 기준시간을 기록한다.
6. 모든 ON 방이 `실제 ✓`일 때만 `전체 시작`이 허용된다.
7. 이후 자동관리는 검증 완료된 방만 직렬(single-flight) 처리한다.

방 이름이나 링크를 수정하면 해당 방의 안전/실제 검증과 48시간 기준은 자동 무효화된다.

## Reliability behavior

- strict Kakao package gate: delayed callbacks never click a non-Kakao window
- exact room-title matching plus participant-count suffix support (`게임방 386`, `1 1`)
- open-chat deep link as an additional identity proof; official HTTPS `open.kakao.com` host only
- bottom-left composer `+` geometry fallback to avoid top-right menu confusion
- staged state machine: room -> composer -> VoiceRoom -> create form -> name -> submit -> active verification
- Compose/custom input fallback using focus, `ACTION_SET_TEXT`, bounds, max-length, parent/child nodes
- per-stage timeout + 90-second whole-job watchdog
- privacy-safe diagnostics store UI-state booleans, not chat messages
- progressive unattended retry: 1m -> 3m -> 10m -> 30m -> 1h
- 48h schedule hint with precheck at ~47h55m; actual Kakao UI remains source of truth
- crash/stale-pending/reboot/app-update recovery
- interrupted manual checks never silently rerun after reboot/update
- AUTO work never steals the foreground while the user is actively using the phone; it defers 5 minutes
- scheduled wake is attempted only when needed; secure device locks are never bypassed
- media volume is muted only during the automation transaction and restored on success/failure/stop/stale recovery
- AUTO completion returns to Home and may turn the display off only where Android allows it without bypassing a secure lock
- direct safe/live checks return to VoiceRoom Manager

## Build and release

Normal PR CI runs:

- backend checks
- KakaoAutoSender unit/lint/debug build
- VoiceRoom Manager unit/lint/debug build
- ephemeral signed/minified release smoke for both Android apps

Production VoiceRoom distribution uses `.github/workflows/voiceroom-release.yml` and the protected production signing secrets already used by Android release infrastructure:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow validates configuration, runs tests/lint, builds minified release, verifies the APK signature with `apksigner`, generates SHA-256, and uploads the APK + checksum. Debug APK signing is for testing only and must not be treated as the production update identity.

## Live-device gates before merge/release claim

KakaoTalk exposes no public VoiceRoom automation API, so these cannot be truthfully proven by CI alone:

1. target phone live `실제 점검`: name injection -> `만들기` -> active detection -> persisted 48h baseline
2. target Samsung firmware screen-off unattended wake/recreation
3. exact current Kakao in-room speaker/audio control semantics before automating long-lived per-room silence; do not keep the whole phone's media volume at zero for 48h as a substitute
4. whether one Kakao account/device can concurrently maintain several active VoiceRooms; if Kakao enforces a session limit, surface it instead of bypassing it

Do not merge/market screen-off, silent long-lived operation, or multi-room concurrency as verified until those target-device gates pass.
