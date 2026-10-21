# 2.3.16 (47): phone-only paired physical-panel-OFF photo tour preview

## Scope

The user explicitly allowed initial phone-only Developer Options / Wireless Debugging / pairing setup. This version adds that opt-in path without a PC, USB cable, root or another helper app. Android 11+ and a working Wi-Fi wireless-debugging environment are required. It is separate from ordinary APK permissions and may require reconnecting after reboot, Wi-Fi changes or Android revoking the pairing.

The physical panel is requested OFF while Android remains logically awake and unlocked, using the shell display-power APIs also used by scrcpy. This is **not** sending through an Android secure lock or automatically unlocking a phone. Pressing the power button, locking, losing the control lease or failing a route check stops the tour. No black overlay or zero-brightness trick is used. API responses do not prove actual hardware panel power or photo delivery; those require device verification. This is an experimental feature, not a production support claim.

## User flow

1. Main → 사진 여러 방 전송 → 화면 끄기 최초 설정.
2. Open Developer Options and enable Wireless Debugging. First pairing: open the pairing-code dialog, use split screen to enter its **pairing port** and six-digit code into this app. Only the port is entered, never a remote host.
3. After pairing, use the **connection port** from Wireless Debugging's main page. It differs from the pairing port. The client connects only to `127.0.0.1` on this phone.
4. Run the photo-free five-second panel test. Confirm that the physical panel actually goes dark and returns. A hardware check is not inferred from the UI acknowledgement.
5. Return to the tour, select/prep rooms and set the photo and schedule as in 2.3.15. Select the explicit panel-OFF checkbox for that run and start once/repeat. This opt-in is not restored after restart.
6. The tour starts panel control, checks its live response, then retains the existing verified-recipient, empty-editor, learned-preview, quota and single-use dispatch gates before every photo. The phone UI is used internally; the PC mouse is not used.

The basic screen-on mode retains its previous behavior. Its screen-off pause/resume is not described as off-screen sending. The older single-room screen-off-attempt toggle is independent and remains unverified.

## Architecture and recovery

`LocalAdb` stores a 2048-bit RSA pairing key/certificate under app-private no-backup storage. Pairing codes are ephemeral, excluded from view state/autofill and never logged or sent to the license server. Only local numeric ports are accepted. No user-entered shell commands or remote ADB hosts exist.

`PanelOffManager` launches the kept `PanelShell` entry point from the installed APK via the authorized local ADB shell. It performs no pairing automatically after process restart. All socket/command IO is off the main thread. Per-run nonce and monotonic acknowledgement expiry block old or missing responses. A panel-OFF command acknowledgement is distinct from actual photo receipt. License checks renew proactively during long waits; expired authorization stops control.

`PanelShell` rejects any UID other than Android shell (2000), supports Android 11+ and never calls wake-up/unlock APIs. Android 15+ uses DisplayManagerGlobal.requestDisplayPower(0, false); earlier supported versions use SurfaceControl display power mode. Android 14 enumerates through DisplayControl where required. Pre-15 multiple physical panels are refused in this preview. OEM API incompatibility fails closed.

Control uses a seven-second heartbeat deadman and an independent detached `setsid app_process` recovery process with a pipe lease. The recovery process is armed before panel OFF and restores on EOF/timeout, including control-process death. If a recovery process cannot be started, the panel is not turned off. Stop requests restoration, closes the stream, invalidates the nonce and enforces a nine-second cooldown before another run can take over. OS-wide killing of both shell processes or OEM power API failures cannot be guaranteed recoverable in software; the hardware power button remains available.

The independent guardian is for display restoration only; it cannot send photos, access room content or start a tour. No chat, image, pairing code or server credential is passed to it. Existing notification identity, UI binding and uncertain-send no-replay rules remain in force.

## Validation and remaining device work

Host unit tests cover lease expiry, stale tokens, cancelled runs, numeric port validation, shell quoting, no-backup key reuse and matching self-signed certificates, missing panel leases stopping before any photo claim, and physical-power-button stop behavior.

Android device CI is added for APIs 30, 34 and 35. It runs actual JNI SPAKE2 agreement/corruption/compatibility tests, AES-GCM pairing authentication and wrong-password rejection, repeated destruction after invalid native input, and the packaged Android Conscrypt TLS1.3 exporter. It also invokes the actual shell UID display helper and exercises stop, disconnected input and heartbeat expiry while checking logical wakefulness. An emulator cannot confirm a real hardware panel or Kakao recipient receipt.

Physical hardware panel OFF, phone-only pairing UI, OEM behavior, multiple-room Kakao photo arrival and long-duration battery behavior must still be verified on a real phone before selling this capability as supported. No real Kakao messages are sent by automated tests. Actual check results accompany the delivered APK; merely adding CI is not a passing result.

Sources: https://developer.android.com/tools/adb ; https://github.com/Genymobile/scrcpy/blob/master/doc/device.md ; https://github.com/Genymobile/scrcpy/tree/master/server/src/main/java/com/genymobile/scrcpy/wrappers . Library provenance and relinking/source-distribution instructions are in third_party/PROVENANCE.md.
