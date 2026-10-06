# KakaoMacro PC v1.4 sale baseline

## Product baseline

- single-room settings are auto-saved; there is no customer-facing manual Save step
- bulk changes require explicit per-field opt-in and one Apply action
- exact Kakao window/focus/process identity validation remains fail-closed
- Kakao restart or stale binding is detected by periodic health checks and running rooms stop safely
- global stop fences scheduled dispatch
- photo-configured rooms remain fail-closed until the PC photo path is separately verified
- X can hide to tray; every real exit path warns while rooms are running
- settings stay local under `%LOCALAPPDATA%\KakaoMacro\Windows`
- `settings.json.bak` is maintained automatically; valid temp/backup data is recovered if the primary settings file is damaged
- damaged settings copies are preserved with a timestamp for diagnostics

## v1.4 UX baseline

The desktop workspace is organized around three jobs instead of exposing every action equally:

1. **Selected room** — message + schedule, auto-save, one test-send CTA.
2. **Bulk edit** — choose exactly which fields change, preview through enabled controls, Apply once, Undo available.
3. **Status & history** — connection health, tray behavior, protected local settings, privacy-safe runtime log.

Primary actions use one visual hierarchy. Destructive actions are separated and red. The Kakao yellow is retained as a brand accent instead of being used for every button.

## Automated release gate

- `dotnet restore` win-x64
- Release build with warnings as errors
- self-contained single-file win-x64 publish
- file version must match `1.4.0*`
- GitHub Actions artifact name: `KakaoMacro-PC-v1.4.0-sale`

## Field gate

Before distributing broadly, launch the final EXE once on Windows and confirm:

- license recovery
- existing room/settings migration
- normal text send
- VoiceRoom-open text send
- multi-select and bulk Apply/Undo
- auto-save after editing and restart
- Kakao restart causes exact binding health failure + automatic room stop
- close-to-tray, tray start/stop/show, and real-exit confirmation
- global stop prevents any later scheduled dispatch
- corrupt-settings recovery can restore from `settings.json.bak`

## Known limitation

The EXE is not Authenticode-signed yet. Windows SmartScreen may warn. Do not market it as code-signed until a Windows signing certificate is added.
