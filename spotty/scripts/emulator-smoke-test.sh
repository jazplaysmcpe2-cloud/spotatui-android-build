#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
APK="$GITHUB_WORKSPACE/release-output/spotty-android-release.apk"
ERROR_LOG="$GITHUB_WORKSPACE/release-output/emulator-errors.txt"
SCREENSHOT="$GITHUB_WORKSPACE/release-output/emulator-smoke-test.png"

test -s "$APK"
echo "[smoke] Installing APK"
timeout 90s adb install "$APK"
echo "[smoke] Clearing runtime logs"
timeout 20s adb logcat -c
echo "[smoke] Launching Spotty"
timeout 20s adb shell am force-stop com.spotty.music
timeout 30s adb shell monkey -p com.spotty.music 1
sleep 5

echo "[smoke] Checking process and crash logs"
PID="$(timeout 20s adb shell pidof com.spotty.music | tr -d '\r' || true)"
timeout 20s adb logcat -d -s AndroidRuntime:E > "$ERROR_LOG"
echo "[smoke] Capturing emulator screenshot"
timeout 20s adb shell screencap -p /sdcard/spotty-smoke-test.png
timeout 30s adb pull /sdcard/spotty-smoke-test.png "$SCREENSHOT"

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

timeout 20s adb shell pm list packages | grep -Fx "package:com.spotty.music"
test -s "$SCREENSHOT"
echo "Spotty installed, launched without a captured AndroidRuntime crash, and produced an emulator screenshot."
