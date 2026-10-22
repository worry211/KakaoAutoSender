#!/usr/bin/env bash
set -euo pipefail

# The emulator action executes each configured line in a separate shell. Keep
# the test and cleanup in one shell so failed UI checks still export their images.
trap 'adb pull /sdcard/Android/data/com.local.kakaoautosender/files/photo-ui photo-ui-screenshots || true' EXIT
gradle --no-daemon :app:connectedDebugAndroidTest :spake2:connectedDebugAndroidTest
python3 scripts/panel-device-check.py app/build/outputs/apk/debug/app-debug.apk
