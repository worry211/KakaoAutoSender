# Commercial release review

## Status and validation scope

Implementation is based on exact main baseline
`c0500485085b5ddc1f333deaf516b88ea1aa1dbd`, isolated on
`feat/commercial-license-platform-v2`. No VoiceRoom/desktop branch or files changed.
This is a reviewable implementation, not a claim of an already deployed commercial
service. Cloudflare account/D1 UUID/secrets, Discord application setup, stable
production Android signing and physical device acceptance remain operator work.

## Security review

Reviewed public API authorization, installation signature canonicalization,
one-time redemption, session rotation/recovery, replay handling, authoritative
expiry/revocation, Discord signature/allowlist, confirmations and transaction/audit
coupling. Two-device redemption and refresh races have real D1 integration tests.
Audit insertion failure rolls back issuance. Consumed keys remain consumed after
reset; tombstones preserve history. No reusable plaintext token/key lives in D1.

Found and fixed during review: raw key references in pending confirmation payloads
(resolved to LIC IDs), unsigned GET version query (query is now signed), possible
normalization/fallback for unknown photo MIME, global-stop race at final reply
acceptance, copied-token authentication, repeated success counter increments,
unsafe placeholder release configuration, and obsolete offline bypass.

Reviewed release APK path: stable private signing required, R8/resource shrinking,
no offline license verifier, no debug admin menu, encrypted local credentials,
backup/device-transfer disabled. The temporary local/CI signing fixture is never
a commercial deliverable. APK signature cannot prevent an attacker patching local
automation; non-exportable identity and authoritative server transitions protect
the actual service. Rooted-device/OS compromise remains outside this trust boundary.

Rate/replay/receipt tables expire with hourly cleanup. Replay is prevented within
the accepted signed timestamp window. HTTPS/TLS checks use platform defaults;
cleartext and redirecting authenticated requests are blocked. No fragile pinning.
Signing proof checks timestamps against server time, and recovery requires the
bound private key. A used key is not an authentication token.

Dependency audit initially found vulnerable development transitive packages.
Updated Vitest/Wrangler and locked patched undici/sharp overrides, then reran
integration tests and npm audit: **0 vulnerabilities**. These are build/test tools;
Worker production code has no third-party runtime dependency.

## Secret scan

Inventoried all reachable branches/history before code changes. Baseline automated
scan: **137 distinct historical file blobs, 0 findings**. Current tree is included
in the final committed-history scan/CI. Scanner detects private-key headers,
keystore files, non-example environment files, common GitHub/Discord/Cloudflare
credential patterns and literal peppers/passwords. Four scanner self-tests pass.
Reports identify object/path/category only; they do not print a matched secret.
The former EC verification key was PUBLIC, not a credential.

No real credential requiring rotation was identified. This is a heuristic scan of
reachable Git objects, not proof about unreachable/deleted remote objects, past
CI artifacts, outside seller files or previously copied credentials. If a real
credential is discovered elsewhere, rotate it; removing a file is insufficient.

## UX review

Buyer: install → paste KM key → activate → grant notification access → connect
actual room → configure payload/schedule → exact-destination test → Start.
License screen has paste, activate, retry, reason and support ID. Seller operates
create/info/search/list/extend/suspend/resume/revoke/reset/replace/delete/note/stats
and system controls through private ephemeral commands. Dangerous commands require
an actor-bound single-use confirmation expiring after 120 seconds. Lists paginate.
Raw keys appear once; loss of an unused key uses rotation. No device-code exchange.

Dashboard displays license/near-expiry warning, automation, room count, next send
and notification/exact-alarm checklist. Photo preview/filename stays local. Error
wording identifies suspension/expiry/revocation/deletion/update/device mismatch.
Latest-version notification is advisory. Renewal and maintenance recovery return
access, then require the customer to explicitly restart automation.

The secure 24-symbol KM key is longer than the illustrative 12-symbol format;
copy/paste avoids typing. Discord command IDs/version settings are admin UI only.
Normal diagnostics share contains no room names, chat, photos or token material.

## Reliability review

Preserved existing shortcut/alias recovery, exact-room routing, one-room alarm
dispatch, per-room schedules/counts/limits, stable 3–10s jitter, 2–5s room spacing,
retry behavior and boot/update repair. Next schedule and execution ID are committed
before sending. Duplicate reservation/success tests verify counter idempotence.
A persisted dispatch fence covers repeated alarms/process restarts.

All scheduled invocations validate before Kakao calls. Foreground checks and every
manual/test/start path use the same service. Final PendingIntent has a shared stop/
lockout boundary. Lockout cancels the single existing alarm and closes normal UI;
there are no independent jobs to leave running. Settings remain unchanged.
Network request budget is bounded; outage grace is anchored to elapsedRealtime and
boot count, capped at 600s and known server expiry. Reboot needs fresh validation.
Authoritative invalid/maintenance states cannot revive cached grace. A 10s online
dispatch lease still applies when optional outage grace is configured as zero.

Physical Kakao acceptance is not a remote delivery receipt. Reservation may lose
one send if the process dies after reserving; it prevents immediate duplicate retry
after acceptance. Android/OEM Doze/alarm restrictions can delay delivery; the app
does not bypass moderation, CAPTCHA, platform rate limits or hidden protocols.

## Privacy review

Reviewed every backend request body and Android network call. They contain only
key/public installation identity, app version and authentication proof. No Kakao
room, message, notification, participant or photo data is uploaded. Strict schemas
reject unknown chat fields. Logs have fixed endpoints, request IDs, result categories
and latency without credentials/body. Android detailed notification data remains
local and is excluded from the normal diagnostic exporter. Business memos remain
private seller data in D1/export, so keep exports encrypted and outside Git.

## Regression review

Existing schedule/title/timing/MIME tests retained. Added Robolectric tests exercise
unlicensed manual/test/background sends, alarm cancellation, configuration/photo/
routing preservation, legacy bypass rejection, real text/photo target checks,
photo-only profile scheduling, data RemoteInput construction, duplicate reservation/
counter behavior, normal diagnostic privacy and foreground redirects for all
critical invalid states. DER-to-P1363 conversion verifies randomized P-256 signatures
with the JVM verifier; Android Keystore non-exportability itself still requires a
physical device check. No fallback conversation or accessibility automation added.

## Reproducible checks

Backend: `npm ci`, `npm run typecheck`, `npm run lint`, `npm test`, `npm audit`.
Integration suite uses the actual bundled Worker and Miniflare/D1 migration,
not an in-memory imitation of license state. `wrangler d1 migrations apply DB
--local` succeeds; `wrangler deploy --dry-run` bundles the Worker with DB binding.

Android with JDK17/Gradle8.9/SDK35: `:app:testDebugUnitTest :app:lintDebug
:app:assembleDebug :app:validateReleaseConfiguration :app:assembleRelease
:app:lintRelease`. Release smoke uses an ephemeral test keystore and example HTTPS
origin. `apksigner verify --verbose --print-certs` verifies that smoke APK.
Required production secrets are deliberately not replaced with fixture values.
Negative release validation without signing configuration must fail.

Final exact counts/CI URLs/commit/PR are recorded in the delivery report and PR.
Physical device matrix, live Discord interaction roundtrip, actual Cloudflare
deployment, customer-key activation and stable customer APK upgrade are unverified
until the seller completes [deployment](COMMERCIAL_DEPLOYMENT.md). No commercial
production APK is claimed before those credentials and checks exist.
