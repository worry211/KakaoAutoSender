# Baseline audit (before implementation)

Inspected `c0500485085b5ddc1f333deaf516b88ea1aa1dbd`, main, Android 1.2.0.
No AGENTS.md exists in the checkout. Commercial work is isolated on
`feat/commercial-license-platform-v2`; historical desktop/VoiceRoom work is out of scope.

* Architecture: Java, Android SDK 26–35, platform Activities, SharedPreferences,
  NotificationListenerService, RemoteInput and AlarmManager. No networking library.
* LicenseManager accepts offline KAS1 ECDSA documents bound to ANDROID_ID-derived
  device codes and Android wall time. Application lifecycle polls every 15 seconds.
  Receiver and boot restore check only that offline license. Production must replace it.
* MainActivityV4 testRoom calls the text sender directly; RoomEditorActivity and
  scheduled sends use KakaoMessageSender. Main dashboard test therefore ignores photos.
  Start, editor and low-level sender lack their own server gate.
* Exact-room routing verifies a live bound alias and rejects stored shortcut identity
  conflicts. There is no send fallback to latest/other conversation. Preserve these checks.
* Scheduler handles one due room, reserves nextAt before dispatch, retries failed
  PendingIntent calls, and spaces subsequent rooms 2–5 seconds. Interval jitter derives
  from room and base nextAt (3–10 seconds), so recomputation does not accumulate it.
  Persistence currently uses apply(), leaving a process-death durability window.
* BootReceiver handles BOOT_COMPLETED and MY_PACKAGE_REPLACED, repairs times,
  schedules and reconnects the listener. New validation must be asynchronous.
* Media uses SAF persisted URI permissions, no broad storage permission. RemoteInput
  image MIME support is required; configured images must never fall back to text.
  Unknown stored MIME currently makes hasImage false, potentially causing text fallback.
* Unit tests cover timing, schedule parsing and MIME matching. CI runs debug unit
  tests, lint and assembleDebug. No backend, migration or entitlement tests exist.
* Gradle release signing reads KAS_* variables but does not require them. R8 and
  resource shrinking are disabled; CI only produces a debug APK. No Gradle wrapper.
* Current tree and full reachable history filenames were inventoried. Existing
  verification key is a PUBLIC EC key, not a secret. Automated content/history scan
  and its limitations are recorded in COMMERCIAL_REVIEW.md after implementation.

Physical Kakao delivery, notification capture across Android/OEM/Kakao versions,
and stable customer APK upgrade signing require device/release validation.
