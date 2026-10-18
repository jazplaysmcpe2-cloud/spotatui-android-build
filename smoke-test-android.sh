#!/usr/bin/env bash
set -Eeuo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must be set by GitHub Actions}"
APK="${GITHUB_WORKSPACE}/spotatui-android-release.apk"
PACKAGE="com.largemodgames.spotatui"
COMPONENT="${PACKAGE}/.MainActivity"

collect_diagnostics() {
  local status=$?
  trap - ERR
  echo "Smoke-test command failed (exit ${status}); collecting emulator diagnostics." >&2
  adb logcat -d -v threadtime > "${GITHUB_WORKSPACE}/spotatui-logcat-round-failure.txt" 2>&1 || true
  adb shell dumpsys activity activities > "${GITHUB_WORKSPACE}/spotatui-activity-round-failure.txt" 2>&1 || true
  adb shell dumpsys package "${PACKAGE}" > "${GITHUB_WORKSPACE}/spotatui-package-round-failure.txt" 2>&1 || true
  adb devices -l > "${GITHUB_WORKSPACE}/spotatui-adb-devices-failure.txt" 2>&1 || true
  exit "$status"
}
trap collect_diagnostics ERR

if [[ ! -s "$APK" ]]; then
  echo "ERROR: Expected release APK is missing or empty: $APK" >&2
  ls -lah "$GITHUB_WORKSPACE" >&2 || true
  exit 1
fi
if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb is not available on PATH." >&2
  exit 1
fi

printf 'APK: %s\n' "$APK"
printf 'APK size: %s bytes\n' "$(wc -c < "$APK" | tr -d ' ')"
adb wait-for-device
adb devices -l
adb install -r "$APK"

for round in 1 2; do
  echo "Cold-start test ${round}/2"
  adb shell am force-stop "$PACKAGE"
  adb shell pm clear "$PACKAGE"
  adb logcat -c

  resolution_output="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PACKAGE" 2>&1 || true)"
  printf 'Launcher intent resolution: %s\n' "$resolution_output"

  launch_method="launcher intent"
  if ! start_output="$(adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$PACKAGE" 2>&1)"; then
    echo "WARNING: Package-only launcher intent was not resolved; retrying exported MainActivity explicitly." >&2
    printf '%s\n' "$start_output" >&2
    launch_method="explicit MainActivity fallback"
    if ! start_output="$(adb shell am start -W -n "$COMPONENT" 2>&1)"; then
      echo "ERROR: Both launcher-intent and explicit MainActivity launch failed in round ${round}." >&2
      printf '%s\n' "$start_output" >&2
      adb logcat -d -v threadtime > "${GITHUB_WORKSPACE}/spotatui-logcat-round-${round}.txt" 2>&1 || true
      adb shell dumpsys package "$PACKAGE" > "${GITHUB_WORKSPACE}/spotatui-package-round-${round}.txt" 2>&1 || true
      exit 1
    fi
  fi

  printf 'Launch method: %s\n' "$launch_method"
  printf '%s\n' "$start_output"
  if ! grep -Fq 'Status: ok' <<< "$start_output"; then
    echo "ERROR: Android did not report Status: ok in round ${round}." >&2
    printf 'Launcher resolution: %s\n' "$resolution_output" >&2
    adb logcat -d -v threadtime > "${GITHUB_WORKSPACE}/spotatui-logcat-round-${round}.txt" 2>&1 || true
    adb shell dumpsys package "$PACKAGE" > "${GITHUB_WORKSPACE}/spotatui-package-round-${round}.txt" 2>&1 || true
    exit 1
  fi

  ready=0
  for attempt in $(seq 1 60); do
    sleep 2
    activity_state="$(adb shell dumpsys activity activities 2>/dev/null || true)"
    if adb shell pidof "$PACKAGE" >/dev/null 2>&1 && \
      grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' <<< "$activity_state" | \
        grep -F "$PACKAGE" >/dev/null; then
      ready=1
      break
    fi
  done

  if [[ "$ready" -eq 1 ]]; then sleep 8; fi
  LOGCAT="${GITHUB_WORKSPACE}/spotatui-logcat-round-${round}.txt"
  ACTIVITY="${GITHUB_WORKSPACE}/spotatui-activity-round-${round}.txt"
  UI_XML="${GITHUB_WORKSPACE}/spotatui-ui-round-${round}.xml"
  SCREENSHOT="${GITHUB_WORKSPACE}/spotatui-screen-round-${round}.png"
  adb logcat -d -v threadtime > "$LOGCAT"
  adb shell dumpsys activity activities > "$ACTIVITY" 2>&1 || true
  adb shell dumpsys package "$PACKAGE" > "${GITHUB_WORKSPACE}/spotatui-package-round-${round}.txt" 2>&1 || true
  timeout 20s adb shell uiautomator dump /sdcard/spotatui-window.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/spotatui-window.xml > "$UI_XML" 2>/dev/null || true
  adb exec-out screencap -p > "$SCREENSHOT" 2>/dev/null || true

  if [[ "$ready" -ne 1 ]]; then
    echo "ERROR: App did not reach a resumed foreground activity within 120 seconds in round ${round}." >&2
    echo '--- Activity state ---' >&2
    grep -E 'ResumedActivity|topResumedActivity|mResumedActivity|com\.largemodgames\.spotatui' "$ACTIVITY" | tail -n 80 >&2 || true
    echo '--- Package state ---' >&2
    grep -E 'MainActivity|enabled=|enabledComponents|disabledComponents|intent' "${GITHUB_WORKSPACE}/spotatui-package-round-${round}.txt" | tail -n 120 >&2 || true
    echo '--- Recent logcat ---' >&2
    tail -n 300 "$LOGCAT" >&2 || true
    exit 1
  fi

  if grep -A 30 -B 3 -E 'FATAL EXCEPTION|UnsatisfiedLinkError|dlopen failed' "$LOGCAT" | grep -F "$PACKAGE" >/dev/null; then
    echo "ERROR: Fatal Java/native startup error found in Spotatui round ${round}." >&2
    tail -n 300 "$LOGCAT" >&2
    exit 1
  fi
  if ! adb shell pidof "$PACKAGE" >/dev/null 2>&1; then
    echo "ERROR: Spotatui process exited after launch in cold-start round ${round}." >&2
    tail -n 300 "$LOGCAT" >&2
    exit 1
  fi
  activity_state="$(adb shell dumpsys activity activities 2>/dev/null || true)"
  if ! grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' <<< "$activity_state" | grep -F "$PACKAGE" >/dev/null; then
    echo "ERROR: Spotatui no longer owns the resumed activity after startup in round ${round}." >&2
    tail -n 300 "$ACTIVITY" >&2 || true
    tail -n 300 "$LOGCAT" >&2
    exit 1
  fi
  echo "Cold-start test ${round} passed using ${launch_method}."
done

echo 'Both Spotatui cold-start smoke tests passed.'
