# Windows v0.4.0 RC11 candidate notes

RC11 is a targeted follow-up to RC10 based on the operator's actual Kakao 26.8.1.5315 screenshots and RC5 diagnostic `footerControls=0` after successful VoiceRoom creation.

## What changed

- Dedicated VoiceRoom footer capture is widened from the old ~65px lower strip to a DPI-scaled lower band (`31%` of window width, clamped `96..280px`). The observed 380x640 VoiceRoom places the 42px control row roughly 55-80px above the bottom edge, so the previous crop could cut off the upper half of the circles even though the room was visibly active.
- `VoiceControlVision` now returns the hand/speaker-request control only after the same five-control row and exact hand glyph have already passed the existing visual proof.
- New `SpeakerRequestGuard` implements the operator-observed Kakao flow:
  1. vision-proved hand control,
  2. exact `스피커 요청 리스트`,
  3. exact `스피커 요청 비활성화`,
  4. exact warning `모든 스피커 요청이 초기화돼요.` + `진행할까요?`,
  5. unique `확인`/`취소`,
  6. explicit opposite action such as `스피커 요청 활성화` for disabled-state readback.
- Unknown captions, duplicate confirmation controls, missing state readback, wrong foreground, or wrong process fail closed. No generic request text or arbitrary coordinate is treated as permission to toggle.
- The visual request-policy check runs on bootstrap/health-check protection, not every 5-second runtime pass, avoiding needless request-panel churn while restoring the policy after startup and scheduled health checks.

## Automated coverage added

- Current 380/420/630/840px VoiceRoom footer capture heights.
- Existing real muted footer must expose a vision-proved hand control between speaker and exit.
- Exact disable/enable caption allow-lists and ambiguous-text negatives.
- Actual observed request-list text contract.
- Actual observed confirmation text contract, including missing/duplicate confirmation negatives.

## Still requires real-PC validation

This change is not a claim that the new UI path is device-proven. On the target Windows/Kakao account, verify:

1. Fresh create -> dedicated VoiceRoom -> ACTIVE + microphone muted + speaker muted with no `footerControls=0`.
2. Hand control -> request list -> disable -> exact confirmation -> disabled-state readback -> request panel closes.
3. Relaunch manager while VoiceRoom remains active -> no duplicate create; scheduled health check preserves the disabled request policy.
4. A second account's actual speaker request cannot be accepted/promoted while the policy is disabled.
5. Existing RC10 long-run gates (actual gameplay foreground, monitor off/lock/reboot, real elapsed 48h) remain separate.
