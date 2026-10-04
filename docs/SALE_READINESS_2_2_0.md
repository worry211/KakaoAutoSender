# KakaoMacro v2.2.0 sale release checkpoint

Date: 2026-10-05 KST

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

`latest_version=20` is stale release metadata, not an entitlement/activation blocker. Do not raise `min_version` to 27 before real-device QA. Once a stable HTTPS customer download URL is chosen, update metadata with the Discord admin command `/system latest-version version:27 url:<https-url>`. Raise `/system min-version version:27` only after physical QA confirms upgrading customers are safe.

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

## Physical sale gate still required

The automated release is sale-ready from code/build/signing/server-health perspectives, but these platform-dependent checks require one physical Android/KakaoTalk pass before calling the release fully field-verified:

1. Install/upgrade the signed `2.2.0` APK on the target phone.
2. Activate using a fresh seller-issued key and confirm the main screen opens without a persistent server-connection error.
3. Confirm app restart and network reconnect recover the same license without asking for the redeem key again.
4. Create/connect two Kakao rooms that have the same visible title and verify they do not collapse into one route when Android provides distinct stable shortcut identities.
5. Verify a renamed room with the same stable identity does not become a duplicate macro.
6. Verify ambiguous sessions with no stable identity are blocked rather than guessed.
7. Send text-only once to a verified room.
8. Send photo-only and text+photo where Kakao exposes compatible RemoteInput data support.
9. Delete/move a previously selected photo and verify the app reports a photo-read failure without sending text-only.
10. Start automation, stop it, restart it, and verify the global stop fence prevents an already scheduled alarm from sending.
11. Reboot the phone and verify the license is revalidated online before schedules resume.
12. Confirm notification-listener reconnect and room session recovery behavior after reboot/app update.

Until that pass finishes, keep production `min_version` at 20. The signed APK itself is the correct v2.2.0 commercial release candidate.
