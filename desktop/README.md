# KakaoMacro PC — Windows v1.6.5

KakaoMacro PC is the Windows companion to the Android KakaoMacro product. It reuses the commercial entitlement service while using a Windows-only KakaoTalk desktop delivery adapter.

Buyer instructions: [한국어 구매자 안내](../docs/BUYER_GUIDE_KO.md).

## Safety model

This client intentionally does **not** search for a room name and click the first result. Pairing is live-window based:

1. Open a KakaoTalk chat as its own window.
2. Click the chat input area so the intended room owns keyboard focus.
3. Use the hotkey shown in the app (`Ctrl+Shift+F8`, falling back to F9/F10 when occupied), or use the in-app room-pairing action.
4. KakaoMacro records and validates the exact KakaoTalk window/input identity.
5. Every send revalidates the binding. If KakaoTalk restarts, the room window changes, or the input identity cannot be restored, dispatch fails closed and requires re-pairing.

Two separate open-chat windows can therefore remain distinct even when their visible titles are identical. KakaoTalk chat-window sends use the actual input-control UI thread for focus restoration instead of assuming it matches the top-level KakaoTalk window thread.

## v1.6.5 operations and reliability

After KakaoTalk restarts, select the existing profile and use **이 방 다시 연결**. Click the intended live chat input during the countdown, then confirm the displayed profile and actual room title. Existing message/schedule/counts remain; sending stays stopped until explicitly started. Another profile's bound window cannot be selected as a duplicate target.

Profiles with a saved photo remain blocked. **저장된 사진 설정 지우기** explicitly removes that setting after confirmation and keeps sending stopped; the next explicit start uses text only. Monitor/DPI changes re-fit the workspace, and room-action buttons wrap within the available width.

- Windows 10/11 WPF client
- persistent non-exportable P-256 Windows CNG installation identity
- same signed license protocol as Android (`KM1`, ECDSA P-256, nonce/timestamp/body+token hashes)
- activation, same-install recovery, access heartbeat and refresh
- license lease gate before every scheduled dispatch
- commercial dark desktop UI with unified rounded fields, tabs, selection states and restrained brand accents
- multi-room profiles, search, state filters and sorting
- interval schedules and fixed daily times
- quick 1 / 5 / 10 / 30 / 60 minute interval presets
- daily send limits
- extended multi-select and bulk message / schedule / daily-limit / enabled-state editing
- copy first-selected room settings to the rest
- one-level latest bulk-change undo; restored rooms stay stopped until explicitly restarted
- selected start / stop / enable / disable / validate / delete
- selected-room one-shot send with large-batch confirmation
- single-room editor with debounced automatic persistence; no redundant visible save button
- `Ctrl+S` remains an optional explicit flush shortcut; `Ctrl+Enter` performs one-shot send
- system tray show / start / stop / exit
- configurable X-button behavior: hide to tray or exit
- every real exit path confirms when automation is still running
- explicit global start / stop fence
- no automatic sending after app restart
- 15-second background binding-health checks
- running rooms automatically stop if Kakao restart/stale HWND/title/focus identity makes the binding invalid
- explicit whole-list and selected-room connection validation
- Unicode/Korean text delivery using Windows `SendInput` without clipboard text leakage
- user-activity guard for scheduled sends
- debounced search and settings persistence
- settings JSON/disk writes off the WPF UI thread
- `settings.json.bak` last-known-good backup; corrupt primary settings are preserved for diagnostics and automatically restored when possible
- low-overhead scheduler while preserving serialized Kakao dispatch and focus-safety delays
- customer-facing UI does not expose raw HWND identifiers
- local-only room/message configuration; the license backend never receives room names or message bodies

## Deliberately locked

Photo automation is not silently downgraded to text-only. KakaoTalk PC attachment/confirmation UI needs physical compatibility testing before it can be enabled safely. A profile with a configured photo remains blocked until the photo adapter is validated.

The current license database binds one redeem key to one installation public key. Android and Windows simultaneous use therefore needs separate entitlements until an explicit multi-seat/product entitlement model is introduced.

## Local recovery files

Runtime configuration lives under `%LOCALAPPDATA%\KakaoMacro\Windows`.

- `settings.json` — current configuration
- `settings.json.bak` — last-known-good backup
- `settings.corrupt-YYYYMMDD-HHMMSS.json` — preserved damaged primary when automatic recovery is needed
- `settings-recovery.log` — recovery events only; message contents are not uploaded anywhere
- `crashes\` — local crash diagnostics

Automatic recovery never restarts sending. Rooms still require explicit start after the app launches.

## Build

```powershell
dotnet restore .\desktop\KakaoMacro.Windows\KakaoMacro.Windows.csproj
dotnet build .\desktop\KakaoMacro.Windows\KakaoMacro.Windows.csproj -c Release -warnaserror
dotnet publish .\desktop\KakaoMacro.Windows\KakaoMacro.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```

The client is not a Kakao-official integration and does not use a private Kakao network API. KakaoTalk desktop UI changes can still require adapter updates, so physical QA against the current KakaoTalk build remains a release gate. The executable is also not Authenticode-signed yet, so SmartScreen can warn on first launch.
