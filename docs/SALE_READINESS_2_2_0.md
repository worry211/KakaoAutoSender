# KakaoMacro v2.2.0 sale release checkpoint

Date: 2026-10-05 KST

## Final decision

- **SALE APPROVED**
- Release: `KakaoMacro v2.2.0`
- Android versionCode: `27`
- Signed commercial APK is the approved customer distribution binary.
- Automated code/build/signing/backend/server-health gates are complete and passing.
- Physical Android/KakaoTalk field QA has not been independently observed by ChatGPT; the owner explicitly chose to proceed with sale with that residual platform-dependent risk accepted.
- Production `min_version` remains `20` so existing customers are not force-blocked before broad field verification.
- Production `latest_version` should be changed to `27` only after a stable HTTPS customer download URL is selected.

## Release identity

- Product: KakaoMacro / 카톡매크로
- Version: `2.2.0`
- Android versionCode: `27`
- Sale-source main commit: `eb70456678f3b2283189de7e2e8920c7287d2408`
- v2.2.0 final hardening was merged through PR #24 after the earlier v2.2.0 commercial product pass.
- The one-time build commit `3b5af70c6f4406d1af395e3c030438d7323f59bb` differs from the sale-source main commit only by the temporary workflow push trigger used to start the commercial build. No app/backend source file differs.

## Production commercial build

GitHub Actions run: `37214082365` (`Commercial signed APK`)
Artifact ID: `11307473283`
Artifact name: `commercial-app-release`

All release gates passed:

- repository secret scan: PASS, 525 blobs checked, 0 findings
- backend TypeScript typecheck: PASS
- backend formatting/lint: PASS
- backend tests: PASS, 59 tests
- npm audit moderate+: PASS, 0 vulnerabilities
- Android debug unit tests: PASS
- Android debug lint: PASS
- release configuration validation: PASS
- R8/minified release build: PASS
- release lint: PASS
- APK signature verification: PASS
- artifact upload: PASS
- temporary signing material cleanup: PASS

## APK integrity

- APK SHA-256: `1c3ee28f88e2d795f375034719a0e4a3f8937fd17e8d64f799bfdeef0e3248e3`
- GitHub artifact ZIP SHA-256: `db1e59cec3c3d49674348950095cb1768628005950bf3458ca89561e3cda27e7`
- APK signing: APK Signature Scheme v2 verified
- Signers: 1
- Release certificate SHA-256: `7fbfc166fa8a8cc397e17d13ca912f52c37776f3a5f6267261c626ced88ef2d5`
- Certificate DN: `CN=KakaoAutoSender, OU=Release, O=Owner, L=Seoul, ST=Seoul, C=KR`
- RSA key size: 3072 bits
- The certificate SHA-256 exactly matches `BuildConfig.EXPECTED_RELEASE_CERT_SHA256`.

## Production license service

Compiled production API origin:

`https://kakaomacro-license.ei3921163.workers.dev`

Read-only production check against `/api/v1/client-config` returned HTTP 200 with:

- `state=CONFIG`
- `maintenance=false`
- `kill_switch=false`
- `min_version=20`
- `latest_version=20`
- `heartbeat_seconds=60`
- `grace_seconds=600`
- `lease_seconds=10`

The server is live and v2.2.0/versionCode 27 is not blocked by the current minimum-version policy.

`latest_version=20` is stale update metadata, not an entitlement or activation blocker. Once a stable HTTPS customer download URL is selected, update it with `/system latest-version version:27 url:<https-url>`. Do not raise `/system min-version version:27` until there is enough real-device evidence that existing customers can safely upgrade.

## Important v2.2.0 hardening

### License reliability

- bounded production network deadline/timeouts
- recover installation session after a lost one-time activation response
- retry activation only after recovery confirms the claim did not commit
- recover `ALREADY_USED` after ambiguous activation when the same installation already owns the license
- distinguish activation network failure from stale `NOT_FOUND`
- preserve the last authoritative ACTIVE state/session through transient network failure while still stopping dispatch when the online lease is no longer usable

### Kakao room routing

- canonical Unicode/whitespace title normalization
- stable Kakao shortcut identity when available
- same visible title with different stable identities stays distinct
- same stable identity seen under a changed title consolidates safely
- runtime notification token no longer includes post time
- ambiguous same-title sessions without a trustworthy identity remain fail-closed
- PendingIntent equality is intentionally not trusted as an authoritative room identity because that could merge separate Kakao rooms and cause misdelivery

### Photo delivery

- configured photo capability is checked before file access
- stored SAF URI must still be readable immediately before send
- deleted/moved image or lost grant fails closed
- unsupported image reply capability fails closed
- no silent text-only fallback when a photo was configured

## Residual field risk after sale approval

The following behaviors depend on Android/KakaoTalk runtime behavior and remain important post-sale smoke checks:

1. First activation on a fresh installation.
2. License recovery after app restart and Wi-Fi/mobile-data changes.
3. Two Kakao rooms with the same visible title when Android exposes distinct shortcut identities.
4. Renamed room consolidation when the stable identity remains the same.
5. Ambiguous same-title sessions staying blocked instead of guessed.
6. Text-only, photo-only and text+photo delivery where Kakao exposes compatible RemoteInput support.
7. Deleted/moved selected photo failing without silent text-only fallback.
8. Global stop fence blocking already scheduled alarms.
9. Reboot requiring online license revalidation before schedules resume.
10. Notification-listener reconnect and room-session recovery after reboot/app update.

Any confirmed wrong-room delivery, duplicate automation for one physical room, persistent activation failure on a healthy network, or post-stop send is a release-blocking defect and should trigger immediate sale pause plus a hotfix.
