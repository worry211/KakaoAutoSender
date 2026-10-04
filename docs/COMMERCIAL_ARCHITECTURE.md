# Commercial architecture and contract

## Components and trust boundaries

Android Java app → HTTPS Cloudflare Worker → D1. Discord signed Interactions
→ the same Worker → shared admin transition service. No Gateway daemon, paid
auth service, Redis, billing service or buyer license issuance endpoint exists.
Source is public; security relies on server secrets, atomic DB transitions and
installation key possession. A patched/rooted APK can alter its local controls;
obfuscation does not make a locally executed sender unbreakable.

`LicenseManager` is Android's single entitlement/lockout service. `EntitlementPolicy`
is pure monotonic-clock policy; `InstallIdentity` owns non-exportable EC P-256 and
AES-GCM Keystore keys. `DeliveryGate` serializes final reply acceptance with stop
and lockout. Existing shortcut routing, room storage and timing remain local.

Worker `license.ts` owns buyer transitions; `admin.ts` owns administrative
transitions; `discord.ts` validates signatures/actor IDs, confirmations and receipts.
D1 queries go to the primary without opting into read-replica Sessions, so
authoritative checks do not use eventually consistent replicas.

## D1 state model

License IDs are permanent random `LIC-UUID` references. Stored status is UNUSED,
ACTIVE, SUSPENDED, REVOKED or DELETED; EXPIRED is derived from ACTIVE and the SERVER
clock. Permanent duration/expiry is NULL. Other effective API states are NOT_FOUND,
INVALID, DEVICE_MISMATCH, UPDATE_REQUIRED and MAINTENANCE.

`licenses` contains duration, creation/activation/expiry timestamps, seller ID,
installation public key/fingerprint, last seen, customer/admin memos, suspension/
revocation/delete timestamps and actors, reset count/time, generation, revision,
updated time and mutation correlation ID. `redeem_keys` stores HMAC-SHA256 hashes,
consumed/retired timestamps and unique claim IDs. Its partial unique index permits
only one unconsumed, unretired key per license. Old consumed rows remain forever.
`sessions` stores only peppered token hashes and expiry/generation/revocation fields.
`audit_events` rejects UPDATE/DELETE via triggers. Supporting tables store bounded
configuration, nonce hashes, hashed rate signals, interaction IDs and ID-only
confirmation payloads. Audit reasons/memos are business data; never paste secrets there.

First activation uses a conditional key UPDATE and unique claim ID. License
binding, session insert and ACTIVATE audit insert are in the SAME D1 batch. A
losing device changes zero rows and receives ALREADY_USED. No client flag decides
redemption. Batch failure rolls back all changes. Admin mutations use optimistic
revision comparison; dependent writes/audit use the winning mutation correlation ID.

Reset increments generation, revokes old sessions, unbinds installation identity,
sets UNUSED and issues a NEW key in one batch. Original consumed key stays consumed.
Reactivation preserves existing activated_at/expiry. Replacing an UNUSED key retires
its predecessor. Extending revoked/deleted/suspended licenses is rejected; use
resume on SUSPENDED or explicitly issue a new license on REVOKED/DELETED.

## API

| Method/path | Strict fields | Authentication |
| --- | --- | --- |
| POST /api/v1/activate | key, public_key, app_version | installation signature |
| POST /api/v1/session/refresh | app_version | refresh token + installation signature |
| POST /api/v1/session/recover | public_key, app_version | bound installation signature |
| POST /api/v1/heartbeat | app_version | access token + installation signature |
| GET /api/v1/entitlement?app_version=20 | signed query; empty body | access token + installation signature |
| POST /api/v1/deactivate-session | app_version | access token + installation signature |
| GET /api/v1/client-config | none | public, grants NO entitlement |
| POST /discord/interactions | Discord interaction | Ed25519 + allowed seller ID |

`app_version` is Android versionCode (20), not the version name. Activation keys
are normalized to uppercase without whitespace. `public_key` is base64 SPKI P-256.
Access and refresh values are random 256-bit opaque strings (base64url, 43 symbols).
Access expires after 5 minutes; refresh after 30 days and rotates both credentials
on use. A conditional refresh hash UPDATE permits one winner in a refresh race.
Renewal/resume uses bound identity recovery without reusing the consumed KM key.
Recovery also handles an activation/refresh response lost after DB commit. It
invalidates earlier sessions and requires the original Keystore private key.
It does not allow a new device to recover a different device's license.

All buyer authenticated requests use these headers:

* Authorization: Bearer opaque-token (absent for activation/recovery)
* X-Install-Time: integer server-aligned epoch seconds
* X-Install-Nonce: random 32-character UUID-derived hex
* X-Install-Signature: base64 64-byte IEEE P1363 SHA256/ECDSA signature

Canonical signed UTF-8 text has seven lines, with no final newline:

```text
KM1
METHOD
/path?exact-query-if-any
timestamp
nonce
lowercase-hex-SHA256(exact-body-bytes)
lowercase-hex-SHA256(token-or-empty-string)
```

Timestamp tolerance is 120 seconds. A unique public-key/nonce hash prevents replay;
entries remain longer than the timestamp window. Android converts Keystore's DER
signature to P1363. HTTPS server_time synchronizes signing time; neither Android
wall-clock changes nor the public config endpoint grant entitlement. An invalid
proof time may be retried once after TLS-authenticated clock synchronization.
Copied tokens cannot authenticate with another installation key.

Successful heartbeat includes effective state, server_time, expires_at, license_id,
maintenance, kill_switch, min/latest version, HTTPS download URL, optional message,
heartbeat_seconds, grace_seconds and lease_seconds. Tokens appear only on issuance/
rotation/recovery. Error responses contain state, server_time and X-Request-Id;
no redeem hash, customer chat or secret is returned. Bodies are limited to 16KiB,
unknown JSON properties are rejected. Authentication/schema failures use 4xx;
temporary storage/runtime failures use 503. App timeouts, 429 and 5xx are temporary.

Activation IP buckets allow 15/minute; general API 600/minute; recovery 30/minute;
per-credential calls 60/minute. These rolling minute-number buckets are hashed with
the token pepper, expire automatically, and do not permanently ban shared networks.
Hourly cleanup removes expired replay/rate/interaction/confirmation records.
No CORS wildcard, buyer create endpoint, insecure TLS override or cleartext exists.

## Central Android lockout

Cold start/foreground resumes, foreground heartbeat (default 60s, bounds 30–300),
global start, manual/test sends and every alarm validate with the service. Receiver
validation happens before notification rebind/refresh or any Kakao action. The
final text and image PendingIntent both check a usable lease again. Scheduled
dispatch also checks the global-active flag at that final boundary.

EXPIRED/SUSPENDED/REVOKED/DELETED/NOT_FOUND/INVALID/DEVICE_MISMATCH/UPDATE_REQUIRED
invalidate the local lease, clear access credentials, set automation false, cancel
the single outstanding alarm and close normal Activities. When foreground exists,
the license screen clears the task; when there is no UI, the next resume routes
there. No intentional process crash or impossible background UI redirect claim.
Encrypted refresh/Keystore identity is retained for explicit renewal/resume/recovery;
it cannot bypass the authoritative server state. Settings and SAF photos survive.

Maintenance and kill switch use MAINTENANCE, stop sends and preserve license state.
Recovery validation after maintenance is allowed; automation is not restarted
silently. Below minimum version is a hard lock. Latest version is advisory.

Temporary failures may use the last ACTIVE lease on the SAME boot: default maximum
600s since successful server validation, limited further by known server expiry.
The age uses elapsedRealtime and Settings.Global.BOOT_COUNT, never local wall time.
Reboot requires fresh online validation. Zero configured outage grace still permits
the 10s online dispatch lease needed to finish an already validated operation.
Failures never extend either lease or grace. A prior explicit invalid/maintenance
response cannot use old grace. After grace ends automation stops; retry validation
and explicit Start are required. This means an unreachable server's revocation
cannot be observed until contact returns or grace expires (at most 10 minutes).

Alarm network operations share a 4.5s request budget with 1.5s individual timeouts,
followed by existing short listener waits/retries. One room per dispatch remains;
there is no sleep over an entire room batch. Android/OEM restrictions can still
delay or terminate background work; physical acceptance testing is required.

## Scheduler and photos

The original 1-minute minimum, daily times/counts, unlimited DAILY COUNT mode,
stable 3–10 second jitter and 2–5 second inter-room spacing remain. Durable
reservation with execution UUID precedes dispatch; stale duplicate reservations
are rejected and duplicate success cannot double-increment counters. A persisted
dispatch fence prevents duplicate alarms from immediately dispatching a second room.
RemoteInput acceptance is not a Kakao delivery receipt: exactly-once external
delivery cannot be proved if Kakao accepted a request then the process died.
Reservation favors avoiding duplicate sends and may skip one attempt in that case.

Dashboard and editor tests now use the SAME photo-aware sender as scheduled sends.
Text-only, photo-only and text+photo profiles are supported when the notification
RemoteInput actually accepts the image MIME. Unknown/wildcard stored MIME, invalid
SAF URI, unsupported data input, missing session or identity conflicts fail closed.
No text fallback for an explicitly configured photo and no Accessibility workaround.
SAF requires no broad storage access. Thumbnail decode is sampled locally.

## Release/migration

Legacy KAS1 verifier/public verification key/device-code UI are removed, not gated
by a hidden flag. Old license preference fields are removed on startup; room/media
preferences remain. Sellers issue new commercial KM keys to migrating customers.
Old APKs already installed cannot be remotely controlled by the new service; the
seller must migrate buyers to the stably signed v2 APK. An incompatible historical
debug signature requires uninstall/reinstall, which loses Android app data; don't
promise automatic settings migration across that operation.

R8/resource shrinking enabled; Android endpoint injected once by BuildConfig.
Release validation rejects missing signing material or the .invalid placeholder.
Both old and modern backup/transfer mechanisms are disabled to prevent copying
sessions to another device. No production test/admin bypass, release keystore,
Discord token, database credential or pepper exists in source/APK.

Normal support exporter includes version, license/support ID, state, server check
time and coarse error category. It excludes all room names/chat/photos/tokens.
Existing detailed notification diagnostics remain local implementation data and
are not included in the normal support share. Worker logs contain fixed endpoint,
request ID, result category and latency, never request bodies or bearer credentials.
