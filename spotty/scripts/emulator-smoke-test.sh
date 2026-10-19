#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
APK="$GITHUB_WORKSPACE/release-output/spotty-android-release.apk"
SMOKE_APK="$GITHUB_WORKSPACE/release-output/spotty-android-smoke.apk"
ERROR_LOG="$GITHUB_WORKSPACE/release-output/emulator-errors.txt"
LOGCAT="$GITHUB_WORKSPACE/release-output/emulator-logcat.txt"
SCREENSHOT="$GITHUB_WORKSPACE/release-output/emulator-smoke-test.png"
WAV_FIXTURE="$GITHUB_WORKSPACE/release-output/spotty-ci-smoke.wav"
PLAYBACK_LOGCAT="$GITHUB_WORKSPACE/release-output/background-playback-logcat.txt"
SERVICE_LOG="$GITHUB_WORKSPACE/release-output/background-playback-service.txt"
SERVICE_DUMP="$GITHUB_WORKSPACE/release-output/background-playback-service-dump.txt"

test -s "$APK"
test -s "$SMOKE_APK"
echo "[smoke] Verifying emulator boot without foregrounding the home launcher"
BOOTED="$(timeout 20s adb shell getprop sys.boot_completed | tr -d '\r')"
if [ "$BOOTED" != "1" ]; then
  echo "Android emulator has not completed boot."
  exit 1
fi

echo "[smoke] Installing and launching the signed release APK"
timeout 90s adb install "$APK"
timeout 20s adb logcat -c
timeout 20s adb shell am force-stop com.spotty.music
timeout 30s adb shell am start -W -n com.spotty.music/.MainActivity
sleep 10

echo "[smoke] Checking release APK process, crash logs, and ANRs"
PID="$(timeout 20s adb shell pidof com.spotty.music | tr -d '\r' || true)"
timeout 30s adb logcat -d -b main -b system -b events > "$LOGCAT"
grep -E "FATAL EXCEPTION|ANR in com\\.spotty\\.music|am_anr.*com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$LOGCAT" > "$ERROR_LOG" || true
echo "[smoke] Capturing signed release APK screenshot"
timeout 20s adb shell screencap -p /sdcard/spotty-smoke-test.png
timeout 30s adb pull /sdcard/spotty-smoke-test.png "$SCREENSHOT"

if [ -z "$PID" ]; then
  echo "Spotty release APK did not remain running after launch."
  cat "$ERROR_LOG"
  exit 1
fi

if grep -Eiq "FATAL EXCEPTION|ANR in com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$LOGCAT"; then
  echo "Spotty release APK crashed or became unresponsive in the emulator."
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
echo "[smoke] Signed release APK installed/launched and screenshot captured."

# Exercise the actual MediaPlayer + foreground-service path with a generated local WAV,
# not a UI/mock object or an external provider that may be offline during CI.
echo "[smoke] Generating a short local WAV fixture for end-to-end background playback"
python3 - "$WAV_FIXTURE" <<'PY'
import array
import math
import sys
import wave

path = sys.argv[1]
rate = 44100
seconds = 16
frames = rate * seconds
samples = array.array("h", (
    int(1800 * math.sin(2.0 * math.pi * 440.0 * i / rate))
    for i in range(frames)
))
with wave.open(path, "wb") as stream:
    stream.setnchannels(1)
    stream.setsampwidth(2)
    stream.setframerate(rate)
    stream.writeframes(samples.tobytes())
print(f"Generated deterministic test audio: {frames} frames at {rate} Hz")
PY

echo "[smoke] Switching to debug-only test APK to exercise real local-file playback"
timeout 30s adb uninstall com.spotty.music
timeout 90s adb install "$SMOKE_APK"
timeout 30s adb push "$WAV_FIXTURE" /data/local/tmp/spotty-ci-smoke.wav
timeout 20s adb shell run-as com.spotty.music mkdir -p files
timeout 20s adb shell run-as com.spotty.music cp /data/local/tmp/spotty-ci-smoke.wav files/spotty-ci-smoke.wav
timeout 20s adb logcat -c

echo "[smoke] Launching the debug-only internal WAV test hook"
timeout 30s adb shell am start -W -n com.spotty.music/.MainActivity --ez spotty_ci_smoke_playback true
sleep 3
timeout 20s adb logcat -d -s SpottyPlaybackService:I > "$SERVICE_LOG"
if ! grep -Fq "playback-state=playing" "$SERVICE_LOG"; then
  echo "The foreground playback service never reported that the real WAV stream started."
  cat "$SERVICE_LOG"
  timeout 30s adb logcat -d -b main -b system -b events > "$PLAYBACK_LOGCAT"
  exit 1
fi

echo "[smoke] Moving Spotty behind Settings while service playback is active"
timeout 30s adb shell am start -W -a android.settings.SETTINGS
sleep 3
timeout 30s adb shell dumpsys activity services com.spotty.music > "$SERVICE_DUMP"
if ! grep -Eiq 'isForeground=true|foregroundId=7401|foregroundNoti=Notification' "$SERVICE_DUMP"; then
  echo "Spotty playback service did not remain a foreground media service after the Activity was backgrounded."
  cat "$SERVICE_DUMP"
  exit 1
fi
PID="$(timeout 20s adb shell pidof com.spotty.music | tr -d '\r' || true)"
if [ -z "$PID" ]; then
  echo "Spotty process disappeared while its media service should have been playing."
  exit 1
fi
sleep 2
timeout 30s adb logcat -d -b main -b system -b events > "$PLAYBACK_LOGCAT"
if grep -Eiq "FATAL EXCEPTION|ANR in com\\.spotty\\.music|am_anr.*com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$PLAYBACK_LOGCAT"; then
  echo "Spotty crashed or became unresponsive during background playback."
  grep -Ei "FATAL EXCEPTION|ANR in com\\.spotty\\.music|am_anr.*com\\.spotty\\.music|Input dispatching timed out.*com\\.spotty\\.music|Process: com\\.spotty\\.music" "$PLAYBACK_LOGCAT" > "$ERROR_LOG" || true
  cat "$ERROR_LOG"
  exit 1
fi

grep -Fq "playback-state=playing" "$SERVICE_LOG"
echo "Spotty release install/launch and real local-WAV background media-service smoke tests passed."
