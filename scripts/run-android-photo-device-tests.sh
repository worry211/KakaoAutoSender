#!/usr/bin/env bash
set -euo pipefail

# The emulator action executes each configured line in a separate shell. Keep
# the test and cleanup in one shell so failed UI checks still export their images.
pull_screenshots() {
  adb pull /sdcard/Pictures/KakaoMacroUiChecks photo-ui-screenshots || true
}
trap pull_screenshots EXIT
gradle --no-daemon :app:connectedDebugAndroidTest :spake2:connectedDebugAndroidTest
pull_screenshots
trap - EXIT
test "$(find photo-ui-screenshots -type f -name '*.png' | wc -l)" -eq 8
python3 scripts/panel-device-check.py app/build/outputs/apk/debug/app-debug.apk
