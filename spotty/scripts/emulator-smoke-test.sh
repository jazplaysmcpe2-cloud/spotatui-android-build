#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
APK="$GITHUB_WORKSPACE/release-output/spotty-android-release.apk"
ERROR_LOG="$GITHUB_WORKSPACE/release-output/emulator-errors.txt"
LOGCAT="$GITHUB_WORKSPACE/release-output/emulator-logcat.txt"
SCREENSHOT="$GITHUB_WORKSPACE/release-output/emulator-smoke-test.png"

test -s "$APK"
echo "[smoke] Verifying emulator boot without foregrounding the home launcher"
BOOTED="$(timeout 20s adb shell getprop sys.boot_completed | tr -d '\r')"
if [ "$BOOTED" != "1" ]; then
  echo "Android emulator has not completed boot."
  exit 1
fi
echo "[smoke] Installing APK"
timeout 90s adb install "$APK"
echo "[smoke] Clearing runtime logs before direct activity launch"
timeout 20s adb logcat -c
echo "[smoke] Launching Spotty activity directly (without monkey/home-launcher handoff)"
timeout 20s adb shell am force-stop com.spotty.music
timeout 30s adb shell am start -W -n com.spotty.music/.MainActivity
sleep 10

echo "[smoke] Checking process, crash logs, and ANRs"
PID="$(timeout 20s adb shell pidof com.spotty.music | tr -d '\r' || true)"
timeout 30s adb logcat -d -b main -b system -b events > "$LOGCAT"
grep -E "FATAL EXCEPTION|ANR in com\\.spotty\\.music|am_anr.*com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$LOGCAT" > "$ERROR_LOG" || true
echo "[smoke] Capturing emulator screenshot"
timeout 20s adb shell screencap -p /sdcard/spotty-smoke-test.png
timeout 30s adb pull /sdcard/spotty-smoke-test.png "$SCREENSHOT"

if [ -z "$PID" ]; then
  echo "Spotty did not remain running after launch."
  cat "$ERROR_LOG"
  exit 1
fi

if grep -Eiq "FATAL EXCEPTION|ANR in com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$LOGCAT"; then
  echo "Spotty crashed or became unresponsive in the emulator."
  cat "$ERROR_LOG"
  exit 1
fi

if grep -Eiq "ANR in com\\.google\\.android\\.apps\\.nexuslauncher|am_anr.*com\\.google\\.android\\.apps\\.nexuslauncher" "$LOGCAT"; then
  echo "The emulator home launcher became unresponsive during the test; treating this as an unstable emulator session."
  grep -Ei "ANR in com\\.google\\.android\\.apps\\.nexuslauncher|am_anr.*com\\.google\\.android\\.apps\\.nexuslauncher" "$LOGCAT" || true
  exit 1
fi

timeout 20s adb shell pm list packages | grep -Fx "package:com.spotty.music"
test -s "$SCREENSHOT"
echo "Spotty installed, launched without a captured AndroidRuntime crash, and produced an emulator screenshot."
