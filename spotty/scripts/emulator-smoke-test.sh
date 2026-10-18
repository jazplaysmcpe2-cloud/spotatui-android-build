#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
APK="$GITHUB_WORKSPACE/release-output/spotty-android-release.apk"
ERROR_LOG="$GITHUB_WORKSPACE/release-output/emulator-errors.txt"
SCREENSHOT="$GITHUB_WORKSPACE/release-output/emulator-smoke-test.png"

test -s "$APK"
adb install "$APK"
adb logcat -c
adb shell am force-stop com.spotty.music
adb shell monkey -p com.spotty.music 1
sleep 5

PID="$(adb shell pidof com.spotty.music | tr -d '\r' || true)"
adb logcat -d -s AndroidRuntime:E > "$ERROR_LOG"
adb shell screencap -p /sdcard/spotty-smoke-test.png
adb pull /sdcard/spotty-smoke-test.png "$SCREENSHOT"

if [ -z "$PID" ]; then
  echo "Spotty did not remain running after launch."
  cat "$ERROR_LOG"
  exit 1
fi

if grep -Fq "Process: com.spotty.music" "$ERROR_LOG"; then
  echo "Spotty crashed in the emulator."
  cat "$ERROR_LOG"
  exit 1
fi

adb shell pm list packages | grep -Fx "package:com.spotty.music"
test -s "$SCREENSHOT"
echo "Spotty installed, launched without a captured AndroidRuntime crash, and produced an emulator screenshot."
