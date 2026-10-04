# Security hardening v2.1.2

This release raises the cost of patching/re-signing the commercial APK and narrows the Discord seller surface.

## APK controls

- Commercial runtime is pinned to package `com.local.kakaoautosender` and the stable release signing-certificate SHA-256 fingerprint.
- Release builds marked debuggable or running with an attached debugger fail closed.
- The launcher enters through `IntegrityGateActivity`; a re-signed APK never reaches the license UI through the normal manifest path.
- `DeliveryGate` repeats the integrity check immediately before the Kakao `PendingIntent` is allowed to send.
- `InstallIdentity` refuses to generate/use installation signing keys or decrypt session credentials when runtime integrity fails.
- The application lifecycle rechecks integrity and cancels scheduled sends on failure.
- Existing server-side one-time activation, device-bound non-exportable Android Keystore key, signed requests, nonce replay protection, short access tokens, refresh rotation, heartbeat, suspend/revoke/delete, min-version and kill-switch remain in place.

## Discord seller controls

- Discord request signature verification remains mandatory.
- Application ID must match the configured Discord application.
- Non-PING interactions are accepted only from seller guild `1550530984783646835`.
- DM-style `user.id` fallback is rejected; a guild `member.user.id` is required.
- The actor must still be in `ADMIN_DISCORD_IDS`.
- Dangerous operations retain actor-bound, single-use, two-minute confirmations.
- Command registration is guild-only and clears stale global command copies when the registration script is run.

## Residual risk

No sideloaded Android APK can be made mathematically impossible to patch because the attacker controls the client device. These controls are defense-in-depth and are intended to make casual sharing and ordinary APK patching materially harder.

A stronger future tier is remote hardware-backed Android Key Attestation validation (including `AttestationApplicationId`) or distributing through Google Play and requiring Play Integrity `PLAY_RECOGNIZED`. Play Integrity cannot simply be made mandatory for the current direct-APK sales flow because sideloaded installs can receive an `UNLICENSED` verdict.

## Release gate

Do not raise the production minimum version to 23 until the signed v2.1.2 APK passes physical-device KakaoTalk QA. After QA, deploy the backend hardening, re-register Discord commands with the dedicated guild ID, then set the minimum version to 23.
