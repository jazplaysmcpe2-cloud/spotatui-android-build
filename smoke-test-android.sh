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

tap_text_center() {
  local ui_xml="$1"
  local target_text="$2"
  local coords

  coords="$(python3 - "$ui_xml" "$target_text" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, target = sys.argv[1], sys.argv[2]
root = ET.parse(path).getroot()
for node in root.iter("node"):
    if (node.attrib.get("text") or "").strip() != target:
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if not match:
        continue
    left, top, right, bottom = map(int, match.groups())
    print((left + right) // 2, (top + bottom) // 2)
    raise SystemExit(0)
raise SystemExit(1)
PY
)" || return 1

  local x y
  read -r x y <<< "$coords"
  adb shell input tap "$x" "$y"
}

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

  # A clean install first asks whether to join the optional global song counter.
  # Answer No (privacy-preserving default) so the smoke test reaches Spotify auth instead
  # of incorrectly requiring auth controls on the preceding consent question.
  if grep -Fq 'Global Song Counter' "$UI_XML" && grep -Fq 'Would you like to participate?' "$UI_XML"; then
    if ! tap_text_center "$UI_XML" "No"; then
      echo "ERROR: Found the initial Global Song Counter prompt but could not locate its No button." >&2
      cat "$UI_XML" >&2 || true
      exit 1
    fi
    echo "Selected No for the optional Global Song Counter prompt on fresh install."
    for attempt in $(seq 1 20); do
      sleep 2
      timeout 20s adb shell uiautomator dump /sdcard/spotatui-window.xml >/dev/null 2>&1 || true
      adb shell cat /sdcard/spotatui-window.xml > "$UI_XML" 2>/dev/null || true
      if grep -Fq 'Open Spotify sign-in in browser' "$UI_XML" && grep -Fq 'Spotify sign-in code' "$UI_XML"; then
        break
      fi
    done
    adb exec-out screencap -p > "$SCREENSHOT" 2>/dev/null || true
  fi

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

  if grep -Fqi 'The connection to spotatui was refused or closed' "$UI_XML"; then
    echo "ERROR: Spotatui opened but its core connection failed in cold-start round ${round}; foreground launch alone is not a successful smoke test." >&2
    echo '--- UI hierarchy ---' >&2
    cat "$UI_XML" >&2 || true
    echo '--- Spotatui/core logcat clues ---' >&2
    grep -Ei 'spotatui|spotatuicore|UnsatisfiedLinkError|dlopen failed|FATAL EXCEPTION|connection refused|websocket|panicked|error' "$LOGCAT" | tail -n 300 >&2 || true
    exit 1
  fi

  if grep -Eqi 'text="(Connecting…|Starting spotatui…|Spotatui could not reach its local core)' "$UI_XML"; then
    echo "ERROR: Spotatui remained in an unresolved startup/connection state in cold-start round ${round}." >&2
    cat "$UI_XML" >&2 || true
    grep -Ei 'spotatui|ReactNativeJS|GUI:|WebSocket|preflight|FATAL EXCEPTION|error|warn' "$LOGCAT" | tail -n 300 >&2 || true
    exit 1
  fi

  if ! grep -Eqi 'text="(Yes|No|Continue|Home|Search|Your Library|Liked Songs|SPOTIFY|Log in|Login)' "$UI_XML"; then
    echo "ERROR: Spotatui is foregrounded but neither a known healthy onboarding nor home-screen marker was found in cold-start round ${round}." >&2
    echo "--- UI hierarchy ---" >&2
    cat "$UI_XML" >&2 || true
    echo "--- Recent application diagnostics ---" >&2
    grep -Ei 'spotatui|ReactNativeJS|GUI:|WebSocket|preflight|FATAL EXCEPTION|error|warn' "$LOGCAT" | tail -n 300 >&2 || true
    exit 1
  fi

  # pm clear above guarantees a fresh, logged-out app state for each round.
  # The phone must expose a direct Android-friendly action for Spotify's device pairing flow.
  if ! grep -Fq 'Open Spotify sign-in in browser' "$UI_XML"; then
    echo "ERROR: Fresh install did not expose the Spotify sign-in action in cold-start round ${round}." >&2
    cat "$UI_XML" >&2 || true
    grep -Ei 'spotatui|ReactNativeJS|GUI:|WebSocket|FATAL EXCEPTION|error|warn' "$LOGCAT" | tail -n 300 >&2 || true
    exit 1
  fi

  if ! grep -Fq 'Spotify sign-in code' "$UI_XML"; then
    echo "ERROR: Fresh install did not expose a selectable Spotify pairing code in cold-start round ${round}." >&2
    cat "$UI_XML" >&2 || true
    grep -Ei 'spotatui|ReactNativeJS|GUI:|WebSocket|FATAL EXCEPTION|error|warn' "$LOGCAT" | tail -n 300 >&2 || true
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
  echo "Cold-start test ${round} passed using ${launch_method}; a known healthy onboarding/home UI was detected."
done

echo 'Both Spotatui cold-start smoke tests passed with known healthy UI markers and no core-connection error.'
