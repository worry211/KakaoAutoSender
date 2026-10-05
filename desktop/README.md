# KakaoMacro PC — Windows v1.2 Preview

KakaoMacro PC is the Windows companion to the Android KakaoMacro product. It reuses the existing commercial entitlement service while using a Windows-only KakaoTalk desktop delivery adapter.

## Safety model

This client intentionally does **not** search for a room name and click the first result. That can misdeliver messages when multiple rooms have the same visible title.

Pairing is live-window based:

1. Open a KakaoTalk chat as its own window.
2. Click the chat input area so the intended room owns keyboard focus.
3. Press `Ctrl+Shift+F8`.
4. KakaoMacro records the exact top-level HWND, focused child HWND, process ID/start time, window title and window classes.
5. Every send revalidates those facts. If KakaoTalk restarts, the window closes, the title changes, or focus identity cannot be restored, dispatch fails closed and requires re-pairing.

Two separate open chat windows can therefore remain distinct even if their visible titles are identical. A KakaoTalk restart deliberately invalidates live bindings instead of guessing.

## v1.2 operations

- Windows 10/11 WPF client
- persistent non-exportable P-256 Windows CNG installation identity
- same signed license protocol as Android (`KM1`, ECDSA P-256, nonce/timestamp/body+token hashes)
- activation, same-install recovery, access heartbeat and refresh
- license lease gate before every scheduled dispatch
- multi-room profiles
- interval schedules and fixed daily times
- daily send limits
- explicit global start / stop fence
- global `Ctrl+Shift+F8` safe room pairing
- live-window/process/focus fail-closed validation
- Unicode/Korean text delivery using Windows `SendInput` without clipboard text leakage
- user-activity guard for scheduled sends
- redesigned dashboard + room list + editor + bulk editor + diagnostics
- multi-select start / stop / enable / disable / validate / delete
- selected-room one-shot send with confirmation for large batches
- bulk message/schedule/daily-limit/enabled-state editing
- copy first-selected room settings to the rest
- one-level latest bulk-change undo; restored rooms stay stopped until explicitly restarted
- search plus state filters: all / running / enabled / needs pairing / needs attention
- system tray show / start / stop / exit
- debounced search and settings persistence
- settings JSON/disk writes moved off the WPF UI thread
- low-overhead 1-second scheduler polling while preserving serialized Kakao dispatch and focus-safety delays
- local-only configuration and diagnostics that never send room names or message bodies to the license backend

## Deliberately locked for this preview

Photo automation is not silently downgraded to text-only. KakaoTalk PC attachment/confirmation UI needs physical compatibility testing before it can be enabled safely. A profile with a configured photo must be blocked until the photo adapter is validated.

The current license database binds one redeem key to one installation public key. If one customer needs Android and Windows simultaneously, issue separate entitlements for now. A later multi-seat/product entitlement migration should be explicit rather than weakening the existing one-device security model.

## Build

```powershell
dotnet restore .\desktop\KakaoMacro.Windows\KakaoMacro.Windows.csproj
dotnet build .\desktop\KakaoMacro.Windows\KakaoMacro.Windows.csproj -c Release -warnaserror
dotnet publish .\desktop\KakaoMacro.Windows\KakaoMacro.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
```

## Current verified preview artifact

See `docs/WINDOWS_V1_2_RELEASE_GATE.md` for the exact workflow runs, hashes and physical QA checklist.

Do not treat this client as a Kakao-official integration. It does not reverse engineer Kakao network protocols or use a private Kakao API. KakaoTalk UI changes can still require adapter updates, so physical QA against the current supported Windows KakaoTalk build remains a release gate.
